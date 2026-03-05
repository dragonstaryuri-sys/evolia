package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(tableName = "AgentDiaryEntity")
data class AgentDiaryEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "date")
    val date: String, // yyyy-MM-dd
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
