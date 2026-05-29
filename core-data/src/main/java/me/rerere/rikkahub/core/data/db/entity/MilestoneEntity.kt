package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(tableName = "MilestoneEntity")
data class MilestoneEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "time")
    val time: String, // YYYY-MM-DD
    @ColumnInfo(name = "label")
    val label: String, // e.g., 初识, 相爱
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
