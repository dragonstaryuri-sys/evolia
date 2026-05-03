package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index(value = ["assistant_id", "end_time"]),
        Index(value = ["conversation_id"], unique = true)
    ]
)
data class ChatEpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String, // Summary of the episode
    @ColumnInfo("keywords")
    val keywords: String? = null, // Extracted keywords for better RAG matching
    @ColumnInfo("embedding")
    val embedding: String? = null,
    @ColumnInfo(name = "embedding_model_id", defaultValue = "")
    val embeddingModelId: String? = null, // UUID of the embedding model used
    @ColumnInfo("start_time")
    val startTime: Long,
    @ColumnInfo("end_time")
    val endTime: Long,
    @ColumnInfo(name = "last_accessed_at", defaultValue = "0")
    val lastAccessedAt: Long,
    @ColumnInfo(name = "significance", defaultValue = "5")
    val significance: Int = 5,
    @ColumnInfo(name = "conversation_id", defaultValue = "")
    val conversationId: String? = null,
)
