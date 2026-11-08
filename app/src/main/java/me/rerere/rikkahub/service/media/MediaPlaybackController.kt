package me.rerere.rikkahub.service.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
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
import me.rerere.rikkahub.data.radio.RadioPlaybackHook

/**
 * 睡眠定时状态。
 *
 * @property minutes 定时分钟数；0 = 未启用
 * @property endOfEpisode 「定时到本集结束」；启用后本集播放完毕即暂停
 * @property triggered 定时已触发过（暂停过一次）
 */
data class SleepTimerState(
    /** 设定的分钟数；0 = 未启用分钟定时。 */
    val minutes: Int = 0,
    /** 「定时到本集结束」；启用后本集播放完毕即暂停。 */
    val endOfEpisode: Boolean = false,
    /** 分钟定时的剩余秒数（每秒 tick），用于抽屉倒计时展示。 */
    val remainingSeconds: Int = 0,
    /** 定时已触发过（暂停过一次）。 */
    val triggered: Boolean = false
)

/**
 * 统一媒体播放控制器（UI 唯一入口，AGENTS.md 12.6）。
 *
 * 对 [MediaPlaybackService]（MediaSessionService）持有的唯一 ExoPlayer 封装：
 * - 电台节目与通话录音共用同一播放会话，同一时刻只有一个媒体源在播；
 * - UI 只发指令/订阅状态，禁止各页面自建 ExoPlayer；
 * - 队列项只存 ID 引用，标题变更经 [refreshMetadata] 刷新播放器与通知栏；
 * - **全部公开方法内部切主线程**（MediaController/ExoPlayer 要求主线程访问，
 *   调用方可能在 IO 协程中触发——栏目页「播放全部」闪退的根因）。
 *
 * 同时实现 [RadioPlaybackHook]：RadioService 在标题变更/节目删除/音频更换时回调，
 * 由本控制器联动当前播放队列。
 */
class MediaPlaybackController(
    private val context: Context
) : RadioPlaybackHook {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MediaPlaybackState())
    val state: StateFlow<MediaPlaybackState> = _state.asStateFlow()

    /** 睡眠定时状态（会话内有效，不持久化）。 */
    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    /**
     * 倍速作用域：false（默认）= 全局保持（切节目沿用当前倍速）；
     * true = 只应用到当前节目（切到下一节目时自动恢复 1.0x）。
     */
    private val _speedOnlyCurrent = MutableStateFlow(false)
    val speedOnlyCurrent: StateFlow<Boolean> = _speedOnlyCurrent.asStateFlow()

    /** mediaId → 入队条目快照（刷新元数据时重建 MediaItem 用）。 */
    private val itemRegistry = LinkedHashMap<String, PlaybackItem>()

    private var positionTicker: Job? = null
    private var sleepTimerJob: Job? = null
    private var controller: MediaController? = null
    private var connecting = false

    init {
        connect()
    }

    private fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                val newController = future.get()
                controller = newController
                connecting = false
                // 默认列表循环（AGENTS.md 12.6）
                newController.repeatMode = Player.REPEAT_MODE_ALL
                newController.addListener(playerListener)
                syncStateFromPlayer()
                startOrStopTicker()
                Log.d(TAG, "MediaController connected")
            } catch (e: Exception) {
                connecting = false
                Log.e(TAG, "MediaController connect failed", e)
            }
        }, MoreExecutors.directExecutor())
    }

    // ====================================================================
    // 播放入口（全部切主线程执行）
    // ====================================================================

    /**
     * 播放电台节目队列（如某栏目全部已发布节目）。替换当前队列并从头开始。
     */
    fun playEpisodes(items: List<PlaybackItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        scope.launch {
            val controller = awaitController() ?: return@launch
            itemRegistry.clear()
            items.forEach { itemRegistry[it.id] = it }
            controller.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, items.lastIndex), 0L)
            controller.prepare()
            controller.play()
        }
    }

    /**
     * 单条播放（通话录音初期形态；也用于电台节目单集播放——单集会替换整个队列）。
     */
    fun playSingleItem(item: PlaybackItem) {
        playEpisodes(listOf(item), 0)
    }

    /**
     * 「下一集播放」：把 [item] 插入当前队列的当前节目之后；
     * 当前没有队列时直接开播该条目；已在队列中则忽略。
     */
    fun playNext(item: PlaybackItem) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            if (controller.mediaItemCount == 0) {
                itemRegistry.clear()
                itemRegistry[item.id] = item
                controller.setMediaItems(listOf(item.toMediaItem()))
                controller.prepare()
                controller.play()
                return@launch
            }
            if (findMediaItemIndex(controller, item.id) >= 0) return@launch
            itemRegistry[item.id] = item
            val insertIndex = (controller.currentMediaItemIndex + 1)
                .coerceAtMost(controller.mediaItemCount)
            controller.addMediaItems(insertIndex, listOf(item.toMediaItem()))
            syncStateFromPlayer()
        }
    }

    /**
     * 从队列移除指定条目。若移除的正是正在播放的条目：
     * 自动前进播放下一首（队列空则停止），无需用户再点播放。
     */
    fun removeItem(sourceType: PlaybackSourceType, id: String) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            val wasPlaying = controller.isPlaying
            val currentItem = _state.value.currentItem
            val isRemovingCurrent = currentItem?.sourceType == sourceType && currentItem.id == id
            val index = findMediaItemIndex(controller, id)
            if (index < 0) return@launch
            itemRegistry.remove(id)
            controller.removeMediaItem(index)
            if (isRemovingCurrent) {
                if (controller.mediaItemCount > 0) {
                    // ExoPlayer 移除当前项后通常自动前进；保险起见确保继续播放
                    if (wasPlaying && !controller.isPlaying) {
                        controller.play()
                    }
                } else {
                    controller.stop()
                }
            }
            syncStateFromPlayer()
        }
    }

    fun togglePlayPause() {
        scope.launch {
            val controller = awaitController() ?: return@launch
            if (controller.isPlaying) controller.pause() else controller.play()
        }
    }

    fun pause() {
        scope.launch { awaitController()?.pause() }
    }

    fun stop() {
        scope.launch { awaitController()?.stop() }
    }

    fun seekTo(positionMs: Long) {
        scope.launch {
            awaitController()?.seekTo(positionMs.coerceAtLeast(0L))
        }
    }

    /** 回退/快进指定毫秒（负值回退，正值前进）。 */
    fun seekBy(offsetMs: Long) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            val duration = controller.duration.takeIf { it > 0 }
            val target = controller.currentPosition + offsetMs
            val clamped = if (duration != null) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
            controller.seekTo(clamped)
        }
    }

    fun setSpeed(speed: Float) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
            controller.setPlaybackSpeed(clamped)
            _state.update { it.copy(speed = clamped) }
        }
    }

    /** 「只应用到当前节目」：开启后切到下一节目时倍速自动恢复 1.0x。 */
    fun setSpeedOnlyCurrent(onlyCurrent: Boolean) {
        _speedOnlyCurrent.value = onlyCurrent
    }

    fun setPlayMode(mode: PlaybackMode) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            when (mode) {
                PlaybackMode.SEQUENTIAL -> {
                    controller.repeatMode = Player.REPEAT_MODE_OFF
                    controller.shuffleModeEnabled = false
                }

                PlaybackMode.LIST_LOOP -> {
                    controller.repeatMode = Player.REPEAT_MODE_ALL
                    controller.shuffleModeEnabled = false
                }

                PlaybackMode.REPEAT_ONE -> {
                    controller.repeatMode = Player.REPEAT_MODE_ONE
                    controller.shuffleModeEnabled = false
                }

                PlaybackMode.SHUFFLE -> {
                    controller.repeatMode = Player.REPEAT_MODE_OFF
                    controller.shuffleModeEnabled = true
                }
            }
            _state.update { it.copy(playMode = mode) }
        }
    }

    /** 跳到队列中的上一条/下一条。 */
    fun seekToPrevious() {
        scope.launch { awaitController()?.seekToPreviousMediaItem() }
    }

    fun seekToNext() {
        scope.launch { awaitController()?.seekToNextMediaItem() }
    }

    /** 跳到队列中指定位置的条目并播放（播放列表抽屉点击）。 */
    fun seekToQueueItem(index: Int) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            if (index in 0 until controller.mediaItemCount) {
                controller.seekTo(index, 0L)
                controller.play()
            }
        }
    }

    // ====================================================================
    // 睡眠定时
    // ====================================================================

    /** 设置 N 分钟后暂停；minutes <= 0 取消分钟定时（不影响「本集结束」）。 */
    fun setSleepTimer(minutes: Int) {
        scope.launch {
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            if (minutes <= 0) {
                _sleepTimer.value = SleepTimerState(
                    minutes = 0,
                    endOfEpisode = _sleepTimer.value.endOfEpisode,
                    remainingSeconds = 0
                )
                return@launch
            }
            _sleepTimer.value = SleepTimerState(minutes = minutes, endOfEpisode = false)
            sleepTimerJob = scope.launch {
                val totalSeconds = minutes * 60
                var elapsed = 0
                while (elapsed < totalSeconds && isActive) {
                    delay(1_000L)
                    elapsed++
                    _sleepTimer.update { it.copy(remainingSeconds = totalSeconds - elapsed) }
                }
                if (isActive) {
                    awaitController()?.pause()
                    _sleepTimer.update { it.copy(triggered = true, minutes = 0, remainingSeconds = 0) }
                }
            }
        }
    }

    /** 「定时到本集结束」：本集播放完毕即暂停（启用时替代分钟定时）。 */
    fun setSleepTimerEndOfEpisode(enabled: Boolean) {
        scope.launch {
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            _sleepTimer.value = SleepTimerState(
                minutes = 0,
                endOfEpisode = enabled,
                remainingSeconds = 0,
                triggered = if (enabled) _sleepTimer.value.triggered else false
            )
        }
    }

    fun clearSleepTimer() {
        scope.launch {
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            _sleepTimer.value = SleepTimerState()
        }
    }

    /**
     * 「立即播放」：把 [item] 插入当前在播节目之后并立即跳播该节目
     * （相当于跳过当前节目剩余部分）；无队列时直接开播该节目。
     */
    fun playNextNow(item: PlaybackItem) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            itemRegistry[item.id] = item
            if (controller.mediaItemCount == 0) {
                controller.setMediaItems(listOf(item.toMediaItem()))
                controller.prepare()
                controller.play()
                return@launch
            }
            // 已在队列中：跳播该位置
            val existing = findMediaItemIndex(controller, item.id)
            if (existing >= 0) {
                controller.seekTo(existing, 0L)
                controller.play()
                syncStateFromPlayer()
                return@launch
            }
            val insertIndex = (controller.currentMediaItemIndex + 1)
                .coerceAtMost(controller.mediaItemCount)
            controller.addMediaItems(insertIndex, listOf(item.toMediaItem()))
            controller.seekTo(insertIndex, 0L)
            controller.play()
            syncStateFromPlayer()
        }
    }

    /** 播放列表抽屉的模式循环切换顺序：列表循环 → 单集循环 → 随机 → 顺序停止。 */
    fun cyclePlayMode() {
        val next = when (_state.value.playMode) {
            PlaybackMode.LIST_LOOP -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.SEQUENTIAL
            PlaybackMode.SEQUENTIAL -> PlaybackMode.LIST_LOOP
        }
        setPlayMode(next)
    }

    /** 拖拽排序：移动队列内条目位置（播放列表抽屉）。 */
    fun moveQueueItem(from: Int, to: Int) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            if (from in 0 until controller.mediaItemCount &&
                to in 0 until controller.mediaItemCount &&
                from != to
            ) {
                controller.moveMediaItem(from, to)
                syncStateFromPlayer()
            }
        }
    }

    // ====================================================================
    // RadioPlaybackHook（RadioService 联动）
    // ====================================================================

    override fun onEpisodeTitleChanged(episodeId: String, title: String) {
        refreshMetadata(PlaybackSourceType.RADIO_EPISODE, episodeId, title)
    }

    override fun onEpisodeRemoved(episodeId: String) {
        removeItem(PlaybackSourceType.RADIO_EPISODE, episodeId)
    }

    override fun onEpisodeAudioReplaced(episodeId: String, newAsset: me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity?) {
        scope.launch {
            val controller = awaitController() ?: return@launch
            val currentItem = _state.value.currentItem
            val isPlayingThis = currentItem?.sourceType == PlaybackSourceType.RADIO_EPISODE &&
                currentItem.id == episodeId
            if (newAsset == null) {
                // 音频被清除：若在播该集 → 停止（队列移除走 removeItem 的自动前进逻辑）
                if (isPlayingThis) {
                    removeItem(PlaybackSourceType.RADIO_EPISODE, episodeId)
                } else {
                    itemRegistry.remove(episodeId)
                    val index = findMediaItemIndex(controller, episodeId)
                    if (index >= 0) controller.removeMediaItem(index)
                    syncStateFromPlayer()
                }
                return@launch
            }
            // 有新音频：更新注册表 URI；若正在播该集 → 就地换源并从头播放
            replaceEpisodeUri(episodeId, newAsset, playFromStart = isPlayingThis)
        }
    }

    /** 用新音频资产重建该集的队列条目（可选拍从头播放）。 */
    private suspend fun replaceEpisodeUri(
        episodeId: String,
        newAsset: me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity,
        playFromStart: Boolean
    ) {
        val controller = ensureController() ?: return
        val existing = itemRegistry[episodeId] ?: return
        val file = me.rerere.rikkahub.data.radio.RadioAudioGenerator.resolveAudioFile(
            // context 由 controller 持有
            context, newAsset.filePath
        )
        if (!file.exists()) return
        val updated = existing.copy(
            uri = "file://${file.absolutePath}".toUri(),
            mimeType = if (file.name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
        )
        itemRegistry[episodeId] = updated
        val index = findMediaItemIndex(controller, episodeId)
        if (index >= 0) {
            controller.replaceMediaItem(index, updated.toMediaItem())
            if (playFromStart) {
                controller.seekTo(index, 0L)
                controller.play()
            }
        }
        _state.update { state ->
            state.copy(
                queue = state.queue.map { if (it.id == episodeId) updated else it },
                currentItem = state.currentItem?.takeIf { it.id == episodeId }?.let { updated } ?: state.currentItem
            )
        }
    }

    /**
     * 标题等元数据变更后刷新队列内对应条目（播放器 UI 与通知栏同步）。
     */
    fun refreshMetadata(sourceType: PlaybackSourceType, id: String, newTitle: String) {
        scope.launch {
            val existing = itemRegistry[id] ?: return@launch
            val updated = existing.copy(title = newTitle)
            itemRegistry[id] = updated
            val controller = awaitController() ?: return@launch
            val index = findMediaItemIndex(controller, id)
            if (index >= 0) {
                controller.replaceMediaItem(index, updated.toMediaItem())
            }
            _state.update { state ->
                state.copy(
                    queue = state.queue.map { if (it.id == id) updated else it },
                    currentItem = state.currentItem?.takeIf { it.id == id }?.let { updated } ?: state.currentItem
                )
            }
        }
    }

    // ====================================================================
    // 内部
    // ====================================================================

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            startOrStopTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isReady = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING,
                    durationMs = controller?.duration?.takeIf { d -> d > 0 } ?: 0L
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 「定时到本集结束」：本集自然播完（自动切集）即暂停并重置开关。
            // 列表循环/单集循环下 STATE_ENDED 永不触发，必须用切集事件；
            // 用户手动 seek/切集（SEEK）不算播完。
            if (_sleepTimer.value.endOfEpisode && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _sleepTimer.value = SleepTimerState(triggered = true)
            }
            // 「只应用到当前节目」：切节目（非单曲循环重播）时倍速恢复 1.0x 并自动关闭开关；
            // 关闭开关时切节目保持当前倍速
            if (_speedOnlyCurrent.value && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                controller?.setPlaybackSpeed(1f)
                _speedOnlyCurrent.value = false
            }
            syncStateFromPlayer()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(speed = playbackParameters.speed) }
        }
    }

    private fun syncStateFromPlayer() {
        val controller = controller ?: return
        val currentId = controller.currentMediaItem?.mediaId
        val current = currentId?.let { itemRegistry[it] }
        val queueItems = (0 until controller.mediaItemCount).mapNotNull { i ->
            controller.getMediaItemAt(i).mediaId.let { itemRegistry[it] }
        }
        _state.update {
            it.copy(
                currentItem = current,
                queue = queueItems,
                durationMs = controller.duration.takeIf { d -> d > 0 } ?: 0L,
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                speed = controller.playbackParameters.speed,
                playMode = when {
                    controller.shuffleModeEnabled -> PlaybackMode.SHUFFLE
                    controller.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackMode.REPEAT_ONE
                    controller.repeatMode == Player.REPEAT_MODE_ALL -> PlaybackMode.LIST_LOOP
                    else -> PlaybackMode.SEQUENTIAL
                }
            )
        }
    }

    private fun startOrStopTicker() {
        positionTicker?.cancel()
        positionTicker = null
        if (_state.value.isPlaying) {
            positionTicker = scope.launch {
                while (isActive && _state.value.isPlaying) {
                    val controller = controller
                    if (controller != null) {
                        _state.update {
                            it.copy(
                                positionMs = controller.currentPosition.coerceAtLeast(0L),
                                durationMs = controller.duration.takeIf { d -> d > 0 } ?: it.durationMs
                            )
                        }
                    }
                    delay(POSITION_TICK_MS)
                }
            }
        }
    }

    private fun ensureController(): MediaController? {
        if (controller == null) connect()
        return controller
    }

    /**
     * 等待 MediaController 完成异步连接（首次点击时连接可能尚未建立，
     * ensureController 返回 null 会让操作静默失败——表现为「点了没反应」）。
     * 超时返回 null。
     */
    private suspend fun awaitController(timeoutMs: Long = 3_000L): MediaController? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ensureController()
            controller?.let { return it }
            delay(100)
        }
        Log.w(TAG, "awaitController timeout, playback request dropped")
        return null
    }

    private fun findMediaItemIndex(controller: MediaController, mediaId: String): Int {
        for (i in 0 until controller.mediaItemCount) {
            if (controller.getMediaItemAt(i).mediaId == mediaId) return i
        }
        return -1
    }

    private fun PlaybackItem.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    companion object {
        private const val TAG = "MediaPlaybackController"
        private const val POSITION_TICK_MS = 50L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L
        val SLEEP_TIMER_OPTIONS = listOf(0, 15, 30, 60, 90)

        /** 快捷构造：电台节目音频条目（App 内部相对路径文件）。 */
        fun radioEpisode(id: String, filePath: String, title: String, channelName: String): PlaybackItem {
            val isWav = filePath.endsWith(".wav", ignoreCase = true)
            return PlaybackItem(
                sourceType = PlaybackSourceType.RADIO_EPISODE,
                id = id,
                uri = "file://$filePath".toUri(),
                title = title,
                subtitle = channelName,
                mimeType = if (isWav) "audio/wav" else "audio/mp4"
            )
        }

        /** 快捷构造：通话录音条目。 */
        fun callRecording(id: String, filePath: String, title: String, subtitle: String): PlaybackItem {
            val isWav = filePath.endsWith(".wav", ignoreCase = true)
            return PlaybackItem(
                sourceType = PlaybackSourceType.CALL_RECORDING,
                id = id,
                uri = if (filePath.startsWith("/")) "file://$filePath".toUri() else filePath.toUri(),
                title = title,
                subtitle = subtitle,
                mimeType = if (isWav) "audio/wav" else "audio/mp4"
            )
        }

        /**
         * 媒体通知点击的跳转 Intent（复用现有页面栈，SINGLE_TOP|CLEAR_TOP）。
         * 不携带动态 ID（PendingIntent 无法随播放项更新）——点击后由
         * RouteActivity 按 [MediaPlaybackController.state.currentItem] 状态驱动导航。
         */
        fun sessionActivityIntent(context: Context): Intent? {
            val intent = Intent(context, Class.forName("me.rerere.rikkahub.RouteActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("target_screen", "radio_player")
            return intent
        }
    }
}
