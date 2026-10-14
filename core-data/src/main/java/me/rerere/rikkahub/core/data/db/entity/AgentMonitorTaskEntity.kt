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
    /**
     * 临时暂停截止时间戳（毫秒）。0 表示未暂停；>0 且当前时间 < pauseUntil 表示暂停中，
     * 暂停期间该监视器不会被评估触发（checkAllMonitors 直接跳过），到期后自动恢复。
     */
    @ColumnInfo(name = "pause_until", defaultValue = "0")
    val pauseUntil: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
