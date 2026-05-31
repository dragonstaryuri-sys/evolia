package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "user_device_state")
@Serializable
data class UserDeviceStateEntity(
    @PrimaryKey
    val id: Int = 0,
    @ColumnInfo(name = "foreground_app")
    val foregroundApp: String = "",
    @ColumnInfo(name = "foreground_app_name")
    val foregroundAppName: String = "",
    @ColumnInfo(name = "is_screen_on")
    val isScreenOn: Boolean = true,
    @ColumnInfo(name = "today_duration_ms")
    val todayDurationMs: Long = 0,
    @ColumnInfo(name = "recent_actions")
    val recentActions: String = "", // 最近的操作记录描述
    @ColumnInfo(name = "screen_context")
    val screenContext: String = "", // 屏幕上下文内容（如商品信息）
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = System.currentTimeMillis()
)
