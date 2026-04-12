package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * L1 层：片段摘要记录。
 * 一个 Episode (L2) 会关联多个 Segment (L1)。
 */
@Entity(
    tableName = "chat_segments",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["assistant_id"])
    ]
)
data class ChatSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("content")
    val content: String, // 该片段的具体摘要内容
    @ColumnInfo("keywords")
    val keywords: String? = null,
    @ColumnInfo("start_index")
    val startMessageIndex: Int, // 对应对话中的起始消息索引
    @ColumnInfo("end_index")
    val endMessageIndex: Int,   // 对应对话中的结束消息索引
    @ColumnInfo("timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo("embedding")
    val embedding: List<Float>? = null // 新增：向量字段，用于 L1 层细节下钻检索
)
