package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "agent_monitor_tasks")
@Serializable
data class AgentMonitorTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "monitor_name")
    val monitorName: String,
    @ColumnInfo(name = "data_requirements")
    val dataRequirements: String, // JSON array of required data fields
    @ColumnInfo(name = "conditions")
    val conditions: String, // JSON object of trigger conditions
    @ColumnInfo(name = "actions")
    val actions: String, // JSON array of actions to perform
    @ColumnInfo(name = "is_enabled", defaultValue = "1")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
