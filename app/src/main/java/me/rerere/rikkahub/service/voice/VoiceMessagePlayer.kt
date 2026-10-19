package me.rerere.rikkahub.service.voice

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单条语音消息的播放状态。
 *
 * @param key 语音消息唯一标识（一般用 MessagePart 的 url）
 * @param isPlaying 是否正在播放
 * @param positionMs 当前播放位置
 * @param durationMs 总时长
 * @param speed 当前播放倍速（持久化在播放器层，不随 Composable 销毁丢失）
 */
data class VoiceMessagePlayback(
    val key: String,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f
)

/**
 * 语音消息播放器。单实例管理当前播放的语音条，同一时刻只播一条；
 * 切换到另一条会停掉前一条。供消息列表里所有语音条 Composable 订阅。
 *
 * 线程模型：所有 ExoPlayer 操作切到主线程（ExoPlayer 要求）。
 */
class VoiceMessagePlayer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val player = ExoPlayer.Builder(context).build()

    /** 当前播放的语音 key（url）；null 表示未在播放。 */
    private val _currentKey = MutableStateFlow<String?>(null)
    val currentKey: StateFlow<String?> = _currentKey.asStateFlow()

    /** 当前播放状态（仅当 currentKey != null 时有意义）。 */
    private val _playback = MutableStateFlow<VoiceMessagePlayback?>(null)
    val playback: StateFlow<VoiceMessagePlayback?> = _playback.asStateFlow()

    /**
     * 当前播放倍速（持久化，不随播放状态变化或 Composable 销毁丢失）。
     * 切换录音 / 重播时沿用该倍速。
     */
    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private var positionJob: Job? = null
    private var listener: Player.Listener? = null

    /**
     * 播放指定 uri 的语音。如果 key 与当前正在播放的一致，则切换为暂停/恢复。
     * 如果是新的 key，会停止当前播放并开始新的。
     */
    fun playOrToggle(key: String, uri: Uri) {
        scope.launch {
            if (_currentKey.value == key) {
                // 同一条：toggle
                if (player.isPlaying) {
                    player.pause()
                } else if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.play()
                } else {
                    player.play()
                }
                return@launch
            }
            // 切换到新 key
            startNew(key, uri)
        }
    }

    /**
     * 拖动到指定进度（仅当 [key] 与当前播放 key 一致时生效）。
     */
    fun seekTo(key: String, positionMs: Long) {
        scope.launch {
            if (_currentKey.value != key) return@launch
            val duration = if (player.duration > 0) player.duration else 0L
            val target = positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
            player.seekTo(target)
            _playback.update { it?.copy(positionMs = target) }
        }
    }

    /**
     * 设置播放倍速。
     *
     * - 始终持久化到 [_speed]，确保 UI 滑动后重建仍能显示当前倍速；
     * - 仅当 [key] 与当前播放 key 一致时才应用到 ExoPlayer；
     * - 已结束（STATE_ENDED）的记录再次播放时沿用当前倍速。
     */
    fun setSpeed(key: String, speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        _speed.value = clamped
        scope.launch {
            if (_currentKey.value == key) {
                player.setPlaybackSpeed(clamped)
            }
            _playback.update { it?.copy(speed = clamped) }
        }
    }

    /**
     * 停止当前播放（不影响其它语音条状态）。
     */
    fun stop() {
        scope.launch {
            detachListener()
            positionJob?.cancel()
            positionJob = null
            player.stop()
            player.clearMediaItems()
            _currentKey.value = null
            _playback.value = null
        }
    }

    /**
     * 释放资源（单例无需调用，仅用于测试）。
     */
    fun release() {
        scope.launch {
            stop()
            player.release()
        }
    }

    private suspend fun startNew(key: String, uri: Uri) {
        detachListener()
        positionJob?.cancel()
        positionJob = null
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(uri))
        // 沿用持久化的倍速
        player.setPlaybackSpeed(_speed.value)
        player.prepare()
        player.playWhenReady = true
        _currentKey.value = key
        _playback.value = VoiceMessagePlayback(key = key, isPlaying = true, speed = _speed.value)
        attachListener(key)
        startPositionUpdates(key)
    }

    private fun attachListener(key: String) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        val duration = if (player.duration > 0) player.duration else 0L
                        _playback.update { it?.copy(isPlaying = player.isPlaying, durationMs = duration) }
                    }
                    Player.STATE_ENDED -> {
                        // 播放结束，回到 idle 态但保留 key（点击会重播）
                        positionJob?.cancel()
                        positionJob = null
                        _playback.update { it?.copy(isPlaying = false, positionMs = it.durationMs) }
                    }
                    Player.STATE_IDLE, Player.STATE_BUFFERING -> {
                        _playback.update { it?.copy(isPlaying = player.isPlaying) }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playback.update { it?.copy(isPlaying = isPlaying) }
                if (isPlaying) startPositionUpdates(key) else positionJob?.cancel().also { positionJob = null }
            }
        }
        listener = l
        player.addListener(l)
    }

    private fun detachListener() {
        listener?.let { player.removeListener(it) }
        listener = null
    }

    private fun startPositionUpdates(key: String) {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                val pb = _playback.value
                if (pb == null || pb.key != key) break
                val duration = if (player.duration > 0) player.duration else pb.durationMs
                _playback.update {
                    it?.copy(
                        positionMs = player.currentPosition.coerceIn(0, duration.coerceAtLeast(0)),
                        durationMs = duration
                    )
                }
                delay(50)
            }
        }
    }

    /**
     * 工具方法：当某条语音消息被删除时清理其播放态（如果是当前在播的）。
     */
    suspend fun clearIfKey(key: String) = withContext(Dispatchers.Main.immediate) {
        if (_currentKey.value == key) stop()
    }
}
