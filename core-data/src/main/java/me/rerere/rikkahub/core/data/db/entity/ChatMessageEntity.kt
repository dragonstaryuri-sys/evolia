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
    indices = [Index("conversation_id")],
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
    val orderIndex: Int // 在会话中的排列顺序
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
