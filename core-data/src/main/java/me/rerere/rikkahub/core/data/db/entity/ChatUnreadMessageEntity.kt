package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 未读消息节点表：仅记录「已持久化但用户尚未查看」的 AI 消息节点。
 *
 * 设计要点：
 * - node_id 即 chat_message_nodes.id（一个节点至多一条未读记录，多版本消息天然去重）；
 * - 挂 FK CASCADE 到 chat_message_nodes：删除消息节点 / 删除会话时由数据库级联清理，
 *   无需在每个删除路径手工同步（运行时 Room 已开启 PRAGMA foreign_keys = ON）；
 * - assistant_id 不落库，统计时 JOIN conversationentity 获取，避免冗余存储与不一致；
 * - 备份恢复：表已登记 DatabaseSanitizer 白名单；恢复期间外键关闭，孤儿记录由
 *   Sanitizer 的清理 SQL 兜底删除。
 */
@Entity(
    tableName = "chat_unread_messages",
    indices = [
        Index("conversation_id"),
        Index("node_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatUnreadMessageEntity(
    @PrimaryKey
    @ColumnInfo("node_id")
    val nodeId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /**
     * 未读消息的节点时间线创建时间（节点首次创建时间），仅用于排序/诊断，不参与计数。
     */
    @ColumnInfo("created_at")
    val createdAt: Long
)

/**
 * 按 Agent 汇总的未读计数 POJO（Room 查询结果映射）。
 */
data class AssistantUnreadCount(
    @ColumnInfo("assistantId") val assistantId: String,
    @ColumnInfo("unreadCount") val unreadCount: Int
)
