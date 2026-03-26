package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "schedules")
@Serializable
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "content")
    val content: String = "",
    @ColumnInfo(name = "start_time")
    val startTime: Long, // Timestamp in ms
    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,
    @ColumnInfo(name = "reminder_time")
    val reminderTime: Long? = null,
    @ColumnInfo(name = "priority",defaultValue = "1")
    val priority: Int = 0, // 0: Not Important, 1: Normal, 2: Important
    @ColumnInfo(name = "urgency",defaultValue = "1")
    val urgency: Int = 1, // 0: Not Urgent, 1: Normal, 2: Very Urgent
    @ColumnInfo(name = "difficulty",defaultValue = "1")
    val difficulty: Int = 0, // 0: Simple, 1: Normal, 2: Not Simple
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,
    @ColumnInfo(name = "category")
    val category: String = "General",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
