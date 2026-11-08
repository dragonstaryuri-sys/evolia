package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 电台节目实体。
 *
 * 每期节目只有一份文字稿（[content]），不保存草稿历史版本。
 * 对话中生成但未执行 save_episode 的文字稿不属于已保存节目。
 *
 * 状态规则（由 RadioService 强制）：
 * - DRAFT：可修改标题与文字稿；文字稿变化必须立即清空全部音频资产。
 * - PUBLISHED：只允许修改标题，文字稿不可再修改。
 */
@Entity(
    tableName = "radio_episodes",
    indices = [
        Index(value = ["channel_id"]),
        Index(value = ["creator_agent_id"]),
        Index(value = ["channel_id", "status"])
    ]
)
data class RadioEpisodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "channel_id")
    val channelId: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** 当前唯一文字稿。 */
    @ColumnInfo(name = "content")
    val content: String = "",

    /** 创作该节目的 Agent ID。 */
    @ColumnInfo(name = "creator_agent_id")
    val creatorAgentId: String,

    /** 节目状态，见 [RadioEpisodeStatus]。 */
    @ColumnInfo(name = "status")
    val status: String = RadioEpisodeStatus.DRAFT,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /** 发布时间戳（毫秒），未发布为 null。 */
    @ColumnInfo(name = "published_at")
    val publishedAt: Long? = null,

    /** 用户点赞标记：0 = 未点赞；1 = 已点赞（播放器点赞按钮）。 */
    @ColumnInfo(name = "liked")
    val liked: Int = 0
)

/** 节目状态常量。 */
object RadioEpisodeStatus {
    const val DRAFT = "DRAFT"
    const val PUBLISHED = "PUBLISHED"
}
