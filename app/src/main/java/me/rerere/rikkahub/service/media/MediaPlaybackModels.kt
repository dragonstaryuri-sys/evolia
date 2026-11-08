package me.rerere.rikkahub.service.media

import android.net.Uri

/** 统一播放器媒体源类型：电台节目 / 通话录音（实时语音通话不接入）。 */
enum class PlaybackSourceType { RADIO_EPISODE, CALL_RECORDING }

/**
 * 统一播放器的媒体项。只携带 ID 引用与入队时快照的标题/副标题；
 * 标题变更后必须经 [MediaPlaybackController.refreshMetadata] 刷新（禁止维护标题副本）。
 */
data class PlaybackItem(
    val sourceType: PlaybackSourceType,
    /** 电台 episodeId / 通话 callId（同时作为 MediaItem.mediaId）。 */
    val id: String,
    val uri: Uri,
    val title: String,
    val subtitle: String = "",
    /** 音频 MIME；m4a 用 audio/mp4，wav 用 audio/wav。 */
    val mimeType: String = "audio/mp4"
)

/**
 * 播放模式：
 * - SEQUENTIAL：顺序停止（播完最后一集即停）
 * - LIST_LOOP：列表循环（播完第 n 集回到第 1 集，**默认**）
 * - REPEAT_ONE：单集循环
 * - SHUFFLE：随机
 */
enum class PlaybackMode { SEQUENTIAL, LIST_LOOP, REPEAT_ONE, SHUFFLE }

/**
 * 统一播放状态快照（UI 与通知栏同源）。
 */
data class MediaPlaybackState(
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val playMode: PlaybackMode = PlaybackMode.LIST_LOOP,
    val currentItem: PlaybackItem? = null,
    /** 当前队列中全部条目（引用快照）。 */
    val queue: List<PlaybackItem> = emptyList()
) {
    val sourceType: PlaybackSourceType? get() = currentItem?.sourceType
}
