package me.rerere.tts.controller

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AudioPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null
    private var volumeBeforeDuck: Float = 1f
    private var duckAnimationJob: Job? = null

    // ===== Far-end PCM 回调（WebRTC AEC3 参考信号） =====
    // 回调时机：TTS 调用 play() 时，在交给 ExoPlayer 播放之前立刻发出。
    // 为什么不挂 ExoPlayer AudioProcessor 钩子？
    //   AEC3 对 far-end 时序要求宽松（最多容忍 ~100ms 延迟），
    //   直接在 play() 入口发 PCM 更简单，且避免依赖 ExoPlayer 内部实现细节。
    fun interface OnFarPcmListener {
        /**
         * @param pcm      原始 PCM 字节（16-bit 小端）
         * @param sampleRateHz 采样率（可能是 24k / 48k / 16k 等，上层需要 SRC 到 16k 再喂 WebRTC）
         * @param channels 通道数（TTS 通常是 1，单声道）
         */
        fun onPcmReady(pcm: ByteArray, sampleRateHz: Int, channels: Int)
    }

    @Volatile
    private var farPcmListener: OnFarPcmListener? = null

    fun setOnFarPcmListener(listener: OnFarPcmListener?) {
        farPcmListener = listener
    }

    // 所有 ExoPlayer 操作必须在其创建线程（主线程）执行.
    // AudioPlayer 可能被 service 层在 IO 线程调用, 因此统一切换到主线程.
    fun pause() {
        scope.launch { player.pause() }
    }

    fun resume() {
        scope.launch { player.play() }
    }

    fun stop() {
        scope.launch { player.stop() }
    }

    fun clear() {
        scope.launch { player.clearMediaItems() }
    }

    fun release() {
        scope.launch { player.release() }
    }

    fun seekBy(ms: Long) {
        scope.launch { player.seekTo(player.currentPosition + ms) }
    }

    fun setSpeed(speed: Float) {
        scope.launch {
            player.playbackParameters = PlaybackParameters(speed)
            _playbackState.update { it.copy(speed = speed) }
        }
    }

    /** 直接设置音量 (0f ~ 1f) */
    fun setVolume(volume: Float) {
        scope.launch {
            player.volume = volume.coerceIn(0f, 1f)
        }
    }

    /**
     * 两阶段打断：Duck（压低音量）。
     * - PDF 推荐 240ms 内降到 0.2 左右，表示"我听到你要说话了"；
     * - 若 520ms 内未确认 interrupt，会调用 [restoreVolume] 回弹。
     */
    fun duckVolume(targetVolume: Float = 0.2f, durationMs: Long = 240L) {
        scope.launch {
            duckAnimationJob?.cancel()
            volumeBeforeDuck = player.volume
            val start = player.volume
            val end = targetVolume.coerceIn(0f, 1f)
            val steps = (durationMs / 16L).coerceAtLeast(1L)
            var step = 0
            duckAnimationJob = launch {
                while (step < steps) {
                    val progress = step.toFloat() / steps.toFloat()
                    val eased = 1f - (1f - progress) * (1f - progress) // easeOutQuad
                    player.volume = start + (end - start) * eased
                    delay(16L)
                    step++
                }
                player.volume = end
            }
        }
    }

    /** 从 duck 状态恢复音量（打断取消或抢话放弃时） */
    fun restoreVolume(durationMs: Long = 160L) {
        scope.launch {
            duckAnimationJob?.cancel()
            val start = player.volume
            val end = volumeBeforeDuck.coerceIn(0f, 1f)
            val steps = (durationMs / 16L).coerceAtLeast(1L)
            var step = 0
            duckAnimationJob = launch {
                while (step < steps) {
                    val progress = step.toFloat() / steps.toFloat()
                    val eased = 1f - (1f - progress) * (1f - progress)
                    player.volume = start + (end - start) * eased
                    delay(16L)
                    step++
                }
                player.volume = end
            }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun play(response: TTSResponse) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Unit> { cont ->
            // ====== WebRTC AEC far-end 参考：PCM 模式下先把原始音频回调给上层 ======
            // 注意：即使是非 PCM 格式（比如 MP3/OPUS），也可以在这里解码后回调，
            // 但目前所有 TTS Provider 在通话模式下都用 PCM 流式，所以只处理 PCM 就够了。
            if (response.format == AudioFormat.PCM) {
                val listener = farPcmListener
                val sampleRate = response.sampleRate ?: 24000
                val channels = 1 // TTS 输出固定单声道
                // 立刻回调原始 PCM 字节 + 采样率，不阻塞播放（上层用协程/非阻塞消费）
                runCatching {
                    listener?.onPcmReady(response.audioData, sampleRate, channels)
                }.onFailure {
                    android.util.Log.w("AudioPlayer", "Far-end PCM callback failed", it)
                }
            }

            val bytes = if (response.format == AudioFormat.PCM) {
                pcmToWav(response.audioData, response.sampleRate ?: 24000)
            } else response.audioData

            val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()

        _playbackState.update {
            it.copy(
                status = PlaybackStatus.Buffering,
                positionMs = 0L,
                durationMs = (response.duration?.times(1000))?.toLong() ?: it.durationMs
            )
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        stopPositionUpdates()
                    }
                    Player.STATE_READY -> {
                        val isPlaying = player.isPlaying
                        val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                        _playbackState.update {
                            it.copy(
                                status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                durationMs = duration,
                                positionMs = player.currentPosition
                            )
                        }
                        if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                    }
                    Player.STATE_ENDED -> {
                        stopPositionUpdates()
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                positionMs = player.duration.coerceAtLeast(it.positionMs),
                                durationMs = if (player.duration > 0) player.duration else it.durationMs
                            )
                        }
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    Player.STATE_IDLE -> {
                        stopPositionUpdates()
                        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this)
                stopPositionUpdates()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
                if (cont.isActive) cont.resumeWithException(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                _playbackState.update { it.copy(status = status) }
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }
        }
        player.addListener(listener)
        cont.invokeOnCancellation {
            // 取消时可能不在主线程, 用 scope.launch 切换; stopPositionUpdates 仅操作 Job, 线程安全
            scope.launch {
                player.removeListener(listener)
                player.stop()
            }
            stopPositionUpdates()
        }
        }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                _playbackState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}

