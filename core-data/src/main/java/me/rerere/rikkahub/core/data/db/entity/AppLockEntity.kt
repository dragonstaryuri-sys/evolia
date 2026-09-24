package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用锁定记录。
 *
 * 主键为 package_name：同一应用只能有一条锁定记录，重复 APP_LOCK 会覆盖旧记录。
 *
 * 锁定生效规则：
 * - 仅设置了时间窗口（startTime/endTime）：仅在窗口内锁定，窗口外自动不显示遮罩。
 * - 仅设置了 unlockCondition：一直锁定，直到 AI 调用 app_unlock。
 * - 两者都设置：窗口内锁定，AI 也可提前解锁。
 *
 * 时间窗口支持跨夜（startTime > endTime 表示跨越零点）。
 */
@Entity(tableName = "app_locks")
data class AppLockEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_name")
    val appName: String? = null,
    @ColumnInfo(name = "start_time")
    val startTime: String? = null,   // "HH:mm"
    @ColumnInfo(name = "end_time")
    val endTime: String? = null,     // "HH:mm"
    @ColumnInfo(name = "unlock_condition")
    val unlockCondition: String? = null,
    @ColumnInfo(name = "overlay_image_path")
    val overlayImagePath: String? = null,
    @ColumnInfo(name = "emoji")
    val emoji: String? = null,
    @ColumnInfo(name = "prompt_message")
    val promptMessage: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
