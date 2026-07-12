package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("keywords")
    val keywords: String? = null,
    @ColumnInfo("embedding")
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "embedding_model_id", defaultValue = "")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "type", defaultValue = "0")
    val type: Int = 0,
    @ColumnInfo(name = "last_accessed_at", defaultValue = "0")
    val lastAccessedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
)

object MemoryType {
    const val CORE = 0
    const val EPISODIC = 1 // L2: Episode
    const val SEGMENT = 3  // L1: Segment
}
