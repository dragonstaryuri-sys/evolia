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
 *
 * 可见性规则（[visibility]，由 RadioService 强制）：user 与创作 Agent 恒可见；
 * PRIVATE 节目对其他 Agent 不可查询（节目列表 / 文字稿正文等读取操作）。
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

    /**
     * 可见性，见 [RadioEpisodeVisibility]。
     * 控制其他 Agent 能否查询到该节目（user 与创作 Agent 恒可见）；
     * 仅影响 Agent 查询类操作，不影响 UI 展示（user 全部可见）。
     */
    @ColumnInfo(name = "visibility", defaultValue = RadioEpisodeVisibility.PUBLIC)
    val visibility: String = RadioEpisodeVisibility.PUBLIC,

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

/**
 * 节目可见性常量（DB v47 新增列，历史节目迁移时全部置为 PUBLIC）。
 * 仅约束 Agent 侧查询；user 与创作 Agent 恒可见。
 */
object RadioEpisodeVisibility {
    const val PUBLIC = "PUBLIC"
    const val PRIVATE = "PRIVATE"
}
