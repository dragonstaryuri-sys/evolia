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
        Index(value = ["conversation_id", "start_time"], unique = true), // 强制唯一索引，防止重叠
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
    @Deprecated("使用start_time代替")
    @ColumnInfo("start_index")
    val startMessageIndex: Int,
    @Deprecated("使用end_time代替")
    @ColumnInfo("end_index")
    val endMessageIndex: Int,
    @ColumnInfo("start_time")
    val startTime: Long,
    @ColumnInfo("end_time")
    val endTime: Long,
    @ColumnInfo("timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo("embedding")
    val embedding: ByteArray? = null, // 存储 BLOB 化的向量
    @ColumnInfo(name = "embedding_model_id", defaultValue = "")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "recall_count", defaultValue = "0")
    val recallCount: Int = 0 // 被召回的次数
)

data class SegmentEmbeddingProjection(
    val id: Int,
    val embedding: ByteArray?,
    @ColumnInfo(name = "embedding_model_id") // 显式映射
    val embeddingModelId: String?,
    val keywords: String?,
    val timestamp: Long,
    @ColumnInfo(name = "conversation_id") // 显式映射
    val conversationId: String
)
