package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.util.UUID

private const val TAG = "TtsController"

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private val chunker = TextChunker(maxChunkLength = 160)
    private val synthesizer = TtsSynthesizer(context, ttsManager)
    private val audio = AudioPlayer(context)

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var isPaused = false

    // 队列与缓存（基于稳定 ID）
    private val queue: java.util.concurrent.ConcurrentLinkedQueue<TtsChunk> = java.util.concurrent.ConcurrentLinkedQueue()
    private val allChunks: MutableList<TtsChunk> = mutableListOf()
    private val cache = java.util.concurrent.ConcurrentHashMap<UUID, kotlinx.coroutines.Deferred<TTSResponse>>()
    private var lastPrefetchedIndex: Int = -1

    // 行为参数
    private val chunkDelayMs = 120L
    private val prefetchCount = 4

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // 已播放完成的总字符数（基于 chunk.text 累加）。
    // 用于 VoiceCallManager 手动打断时精确判断"用户真正听到了多少"。
    // flush=true 时重置为0。
    private val _playedTextLength = MutableStateFlow(0)
    val playedTextLength: StateFlow<Int> = _playedTextLength.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else audioState.status
                    )
                }
            }
        }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider == null) stop()
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     */
    fun speak(text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = chunker.split(text)
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
            allChunks.addAll(newChunks)
            queue.addAll(newChunks)
            _currentChunk.update { 0 }
        } else {
            // 追加时，重映射 index 以保持全局顺序
            val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
            val remapped = newChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
            allChunks.addAll(remapped)
            queue.addAll(remapped)
        }
        _totalChunks.update { queue.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorker()
        prefetchFrom((_currentChunk.value).coerceAtLeast(0))
    }

    /**
     * Speak text with a specific provider (temporary override)
     * Used for testing TTS settings before saving
     */
    fun speakWithProvider(text: String, provider: TTSProviderSetting, flush: Boolean = true) {
        if (text.isBlank()) return

        val newChunks = chunker.split(text)
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
            allChunks.addAll(newChunks)
            queue.addAll(newChunks)
            _currentChunk.update { 0 }
        } else {
            val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
            val remapped = newChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
            allChunks.addAll(remapped)
            queue.addAll(remapped)
        }
        _totalChunks.update { queue.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorkerWithProvider(provider)
        prefetchFromWithProvider((_currentChunk.value).coerceAtLeast(0), provider)
    }

    private fun internalReset() {
        // Reset current session while keeping provider availability
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        _playedTextLength.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** 两阶段打断的 Duck：压低音量（240ms 动画） */
    fun duckVolume(targetVolume: Float = 0.2f, durationMs: Long = 240L) {
        audio.duckVolume(targetVolume, durationMs)
    }

    /** 从 duck 状态恢复音量（未确认 interrupt 回弹） */
    fun restoreVolume(durationMs: Long = 160L) {
        audio.restoreVolume(durationMs)
    }

    /** 直接设置音量 */
    fun setVolume(volume: Float) {
        audio.setVolume(volume)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (queue.isNotEmpty()) {
            queue.poll()
            _totalChunks.update { queue.size }
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Stopped")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _playedTextLength.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** WebRTC AEC far-end 参考信号：TTS 即将播放的 PCM 回调 */
    fun setOnFarPcmListener(listener: AudioPlayer.OnFarPcmListener?) {
        audio.setOnFarPcmListener(listener)
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedCount = _currentChunk.value
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll() ?: break

                    // 更新状态（1-based）
                    _currentChunk.update { processedCount + 1 }
                    _totalChunks.update { queue.size + 1 }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value
                        )
                    }

                    // 预取下一窗口
                    prefetchFrom(chunk.index + 1)

                    // Retry logic for synthesis with exponential backoff
                    var response: TTSResponse? = null
                    var lastError: Exception? = null
                    val maxRetries = 5
                    var totalAttempts = 0

                    while (response == null && isActive) {
                        for (attempt in 1..maxRetries) {
                            totalAttempts++
                            try {
                                response = awaitOrCreate(chunk, provider)
                                break // Success, exit retry loop
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                lastError = e
                                Log.w(TAG, "Synthesis attempt $attempt/$maxRetries failed for chunk ${chunk.index}", e)

                                if (attempt < maxRetries) {
                                    // Exponential backoff: 500ms, 1s, 2s, 4s, 8s
                                    delay((500L * (1 shl (attempt - 1))).coerceAtMost(8000L))
                                }
                            }
                        }

                        if (response == null) {
                            val errorMsg = lastError?.message ?: "Unknown error"
                            Log.w(TAG, "Retrying chunk ${chunk.index} after extended delay (total attempts: $totalAttempts): $errorMsg")
                            _error.update { "TTS Error: $errorMsg" }

                            // 如果是限流错误，增加更长的退避时间 (429 / Too many requests / rate_limit / limitation)
                            val isRateLimit = errorMsg.contains("rate_limit", ignoreCase = true)
                                || errorMsg.contains("concurrent", ignoreCase = true)
                                || errorMsg.contains("429")
                                || errorMsg.contains("too many", ignoreCase = true)
                                || errorMsg.contains("limitation", ignoreCase = true)
                                || errorMsg.contains("quota", ignoreCase = true)
                            val waitTime = if (isRateLimit) {
                                Log.i(TAG, "Rate limit hit, backing off 15s for chunk ${chunk.index}")
                                15000L // 15s for rate limits (MiMo voiceclone 容易触发 429)
                            } else {
                                5000L
                            }
                            delay(waitTime)

                            if (totalAttempts >= 15) {
                                val finalErrorMsg = lastError?.message ?: "Unknown error"
                                Log.e(TAG, "Giving up on chunk ${chunk.index} after $totalAttempts attempts: $finalErrorMsg")
                                _error.update { "TTS failed after $totalAttempts attempts: $finalErrorMsg" }
                                break
                            }
                        }
                    }

                    if (response == null) {
                        processedCount++
                        continue
                    }

                    // 播放 with retry
                    // 对于 MIMO Provider：官方 API 不支持服务端 audio.speed 字段，改用 ExoPlayer 客户端播放速度
                    if (provider is TTSProviderSetting.Mimo) {
                        audio.setSpeed(provider.speed)
                    }
                    var playbackSuccess = false
                    for (attempt in 1..3) {
                        try {
                            audio.play(response)
                            playbackSuccess = true
                            break
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Playback attempt $attempt failed", e)
                            if (attempt == 3) {
                                Log.e(TAG, "All playback retries failed", e)
                                _error.update { e.message ?: "Audio playback error" }
                            } else {
                                delay(500)
                            }
                        }
                    }
                    // 播放成功 → 累加已播放完成的字符数（用于手动打断精确截断）
                    if (playbackSuccess) {
                        _playedTextLength.update { it + chunk.text.length }
                    }

                    if (queue.isNotEmpty()) delay(chunkDelayMs)

                    processedCount++
                }
            } finally {
                _isSpeaking.update { false }
                if (queue.isEmpty()) {
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }
        }
    }

    private fun prefetchFrom(startIndex: Int) {
        val provider = currentProvider ?: return
        val begin = startIndex.coerceAtLeast(lastPrefetchedIndex + 1)
        val endExclusive = (begin + prefetchCount).coerceAtMost(allChunks.size)
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = allChunks.getOrNull(i) ?: continue
            cache.computeIfAbsent(chunk.id) {
                scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
            }
        }
        lastPrefetchedIndex = endExclusive - 1
    }

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        var deferred = cache[chunk.id]
        if (deferred == null || deferred.isCancelled) {
            deferred = scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
            cache[chunk.id] = deferred
        }

        return try {
            deferred.await()
        } catch (e: Exception) {
            // CRITICAL: Remove from cache if it fails so that next attempt (retry loop) triggers a fresh request
            cache.remove(chunk.id)
            throw e
        }
    }

    // Worker with explicit provider (for testing)
    private fun startWorkerWithProvider(provider: TTSProviderSetting) {
        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedCount = _currentChunk.value
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll() ?: break

                    _currentChunk.update { processedCount + 1 }
                    _totalChunks.update { queue.size + 1 }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value
                        )
                    }

                    prefetchFromWithProvider(chunk.index + 1, provider)

                    var response: TTSResponse? = null
                    var lastError: Exception? = null
                    val maxRetries = 5
                    var totalAttempts = 0

                    while (response == null && isActive) {
                        for (attempt in 1..maxRetries) {
                            totalAttempts++
                            try {
                                response = awaitOrCreate(chunk, provider)
                                break
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                lastError = e
                                Log.w(TAG, "Synthesis attempt $attempt/$maxRetries failed for chunk ${chunk.index}", e)

                                if (attempt < maxRetries) {
                                    delay((500L * (1 shl (attempt - 1))).coerceAtMost(8000L))
                                }
                            }
                        }

                        if (response == null) {
                            val errorMsg = lastError?.message ?: "Unknown error"
                            Log.w(TAG, "Retrying chunk ${chunk.index} after extended delay (total attempts: $totalAttempts): $errorMsg")
                            _error.update { "TTS Error: $errorMsg" }

                            val isRateLimit = errorMsg.contains("rate_limit", ignoreCase = true)
                                || errorMsg.contains("concurrent", ignoreCase = true)
                                || errorMsg.contains("429")
                                || errorMsg.contains("too many", ignoreCase = true)
                                || errorMsg.contains("limitation", ignoreCase = true)
                                || errorMsg.contains("quota", ignoreCase = true)
                            val waitTime = if (isRateLimit) {
                                Log.i(TAG, "Rate limit hit (withProvider), backing off 15s for chunk ${chunk.index}")
                                15000L
                            } else {
                                5000L
                            }
                            delay(waitTime)

                            if (totalAttempts >= 15) {
                                val finalErrorMsg = lastError?.message ?: "Unknown error"
                                Log.e(TAG, "Giving up on chunk ${chunk.index} after $totalAttempts attempts: $finalErrorMsg")
                                _error.update { "TTS failed after $totalAttempts attempts: $finalErrorMsg" }
                                break
                            }
                        }
                    }

                    if (response == null) {
                        processedCount++
                        continue
                    }

                    // MIMO Provider：客户端播放速度兜底（官方 API 不支持服务端 audio.speed）
                    if (provider is TTSProviderSetting.Mimo) {
                        audio.setSpeed(provider.speed)
                    }
                    // Playback with retry
                    var playbackSuccess = false
                    for (attempt in 1..3) {
                        try {
                            audio.play(response)
                            playbackSuccess = true
                            break
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Playback attempt $attempt failed", e)
                            if (attempt == 3) {
                                Log.e(TAG, "All playback retries failed", e)
                                _error.update { e.message ?: "Audio playback error" }
                            } else {
                                delay(500)
                            }
                        }
                    }
                    // 播放成功 → 累加已播放完成的字符数
                    if (playbackSuccess) {
                        _playedTextLength.update { it + chunk.text.length }
                    }

                    if (queue.isNotEmpty()) delay(chunkDelayMs)
                    processedCount++
                }
            } finally {
                _isSpeaking.update { false }
                if (queue.isEmpty()) {
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }
        }
    }

    private fun prefetchFromWithProvider(startIndex: Int, provider: TTSProviderSetting) {
        val begin = startIndex.coerceAtLeast(lastPrefetchedIndex + 1)
        val endExclusive = (begin + prefetchCount).coerceAtMost(allChunks.size)
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = allChunks.getOrNull(i) ?: continue
            cache.computeIfAbsent(chunk.id) {
                scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
            }
        }
        lastPrefetchedIndex = endExclusive - 1
    }
    // endregion
}
