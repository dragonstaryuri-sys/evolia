package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息节点表：对应原来的 MessageNode 结构
 */
@Entity(
    tableName = "chat_message_nodes",
    indices = [
        Index("conversation_id"),
        // ⚡ 性能优化：为会话内排序建立复合索引，极大加快 order_index 排序速度
        Index(value = ["conversation_id", "order_index"]),
        // 对话详情页的全局时间线 keyset 分页索引
        Index(value = ["conversation_id", "created_at", "id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatMessageNodeEntity(
    @PrimaryKey
    val id: String, // Node UUID
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("select_index")
    val selectIndex: Int,
    @ColumnInfo("order_index")
    val orderIndex: Int, // 在会话中的排列顺序
    @ColumnInfo("created_at")
    val createdAt: Long // 节点首次创建时间，用于稳定全局时间线
)

/**
 * 具体消息内容表：对应 UIMessage，支持多版本（一个节点下多个消息）
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index("node_id"), Index("conversation_id")],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String, // UIMessage UUID
    @ColumnInfo("node_id")
    val nodeId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("content_json")
    val contentJson: String, // 序列化后的 UIMessage
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false
)

/**
 * 消息游标 POJO：用于 segment 归档范围上界。
 * 仅承载 (created_at, id) 两个字段，供 Room 查询结果映射。
 * 空会话或全删除时 DAO 返回 null。
 */
data class MessageCursor(
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "id") val id: String
)
