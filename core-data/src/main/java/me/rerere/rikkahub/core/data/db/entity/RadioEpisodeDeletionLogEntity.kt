package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 节目删除审计日志。
 *
 * 删除节目是只向创作 Agent 开放的操作；无论删除由用户发起（经 Agent 执行）
 * 还是 Agent 自主决定，都必须留下独立审计记录。本表**不设外键、不随节目删除**，
 * 节目记录清理后审计记录必须保留。
 */
@Entity(
    tableName = "radio_episode_deletion_logs",
    indices = [
        Index(value = ["episode_id"]),
        Index(value = ["deleted_at"])
    ]
)
data class RadioEpisodeDeletionLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 被删除的节目 ID（节目记录已不存在，仅作追溯）。 */
    @ColumnInfo(name = "episode_id")
    val episodeId: String,

    /** 被删除时的节目标题快照。 */
    @ColumnInfo(name = "episode_title")
    val episodeTitle: String,

    /** 所属栏目 ID（可为空：栏目已被先行删除）。 */
    @ColumnInfo(name = "channel_id")
    val channelId: String? = null,

    /** 节目创作 Agent ID。 */
    @ColumnInfo(name = "creator_agent_id")
    val creatorAgentId: String,

    /** 实际执行删除操作者 ID（用户身份或 Agent ID）。 */
    @ColumnInfo(name = "operator_id")
    val operatorId: String,

    /** 操作者类型，见 [RadioOperatorType]。 */
    @ColumnInfo(name = "operator_type")
    val operatorType: String,

    /** 删除依据/原因；Agent 自主删除时必须提供。 */
    @ColumnInfo(name = "reason")
    val reason: String = "",

    /** 1 = 用户主动提出删除（经 Agent 执行）；0 = Agent 自主删除。 */
    @ColumnInfo(name = "requested_by_user")
    val requestedByUser: Int = 0,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long
)

/** 操作者类型常量。 */
object RadioOperatorType {
    const val USER = "user"
    const val AGENT = "agent"
}
