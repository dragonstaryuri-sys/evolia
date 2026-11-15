package me.rerere.rikkahub.service

import kotlin.uuid.Uuid

/** L2 episode 归档结果状态。 */
enum class EpisodeArchiveStatus {
    /** 全部增量归档完成。 */
    COMPLETED,

    /** 中途取消/失败，已完成的 chunk 已持久化，endTime 停在最后完成的批，下次从断点续归。 */
    PARTIAL,

    /** 完全失败（无任何 chunk 持久化）。 */
    FAILED,

    /** 跳过（会话不存在 / 临时会话 / 已在归档中）。 */
    SKIPPED
}

/** 一次 episode 归档的结果（经 episodeArchiveResults 发给 UI）。 */
data class EpisodeArchiveResult(
    val conversationId: Uuid,
    val status: EpisodeArchiveStatus,
    val archivedCount: Int,
    /** 人类可读的补充信息（异常文本等，可直接展示）。 */
    val errorMessage: String?,
    /** 稳定的跳过/失败原因码，供 UI 映射本地化文案；null 表示无特定原因。 */
    val reasonCode: String? = null
)

/** SKIPPED / 语义化原因码（UI 按 code 映射本地化文案，服务层不做 i18n）。 */
object EpisodeArchiveReasons {
    const val NO_NEW_MESSAGES = "NO_NEW_MESSAGES"
    const val INSUFFICIENT_MESSAGES = "INSUFFICIENT_MESSAGES"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val TEMPORARY_SESSION = "TEMPORARY_SESSION"
    const val CONVERSATION_MISSING = "CONVERSATION_MISSING"
}

/** 遮罩进度：第 doneChunks / totalChunks 批。 */
data class EpisodeArchiveProgress(
    val conversationId: Uuid,
    val doneChunks: Int,
    val totalChunks: Int
)
