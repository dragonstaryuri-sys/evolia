package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "agent_tasks")
@Serializable
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "task_type")
    val taskType: String, // e.g., "EMAIL", "DIARY", "NOTIFICATION"
    @ColumnInfo(name = "task_data")
    val taskData: String, // JSON payload
    @ColumnInfo(name = "scheduled_time")
    val scheduledTime: Long, // Timestamp
    @ColumnInfo(name = "repeat_interval", defaultValue = "0")
    val repeatInterval: Long = 0, // 0 means no repeat, else interval in ms
    @ColumnInfo(name = "is_executed", defaultValue = "0")
    val isExecuted: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
