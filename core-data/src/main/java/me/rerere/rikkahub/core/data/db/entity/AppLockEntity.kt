package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.common.JsonInstant
import java.util.Calendar

/**
 * 应用锁定记录。
 *
 * 主键为 package_name：同一应用只能有一条锁定记录，重复 APP_LOCK 会覆盖旧记录。
 *
 * 锁定生效规则：
 * - 仅设置了时间窗口（validTime 非空）：仅在任一时段内锁定，时段外自动不显示遮罩。
 * - 仅设置了 unlockCondition：一直锁定，直到 AI 调用 app_unlock。
 * - 两者都设置：时段内锁定，AI 也可提前解锁。
 *
 * validTime 存储格式：JSON 数组字符串，如 `[{"start":"13:00","end":"14:00"},{"start":"20:00","end":"23:00"}]`。
 * 每个时段支持跨夜（start > end 表示跨越零点）。null 或空数组表示无时间窗口。
 *
 * pauseUntil：临时暂停截止时间戳（毫秒）。0 表示未暂停。
 * AI 调用 APP_LOCK_PAUSE 命令设置该字段；在 pauseUntil 之前 isLockedNow 返回 false，
 * 到期后自动恢复锁定，无需手动重置。
 */
@Entity(tableName = "app_locks")
data class AppLockEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_name")
    val appName: String? = null,
    /**
     * 时间窗口（JSON 数组字符串 `[{"start":"HH:mm","end":"HH:mm"}]`）。
     * null 或空数组表示无时间窗口（仅靠 unlockCondition 生效）。
     */
    @ColumnInfo(name = "valid_time")
    val validTime: String? = null,
    @ColumnInfo(name = "unlock_condition")
    val unlockCondition: String? = null,
    @ColumnInfo(name = "overlay_image_path")
    val overlayImagePath: String? = null,
    @ColumnInfo(name = "emoji")
    val emoji: String? = null,
    @ColumnInfo(name = "prompt_message")
    val promptMessage: String,
    /**
     * 锁定该应用的 Assistant 的 Uuid 字符串。
     * 用户打开被锁应用时，会向该 Assistant 发送一条 skipContext 的 user 系统消息。
     * 旧记录无此字段时为空字符串，此时仅显示遮罩不发消息。
     */
    @ColumnInfo(name = "assistant_id")
    val assistantId: String = "",
    /**
     * 临时暂停截止时间戳（毫秒）。0 表示未暂停；>0 且当前时间 < pauseUntil 表示暂停中，
     * isLockedNow 返回 false，遮罩不显示也不向 AI 发 APP_LOCK_TRIGGER 消息。
     * 到期后自动恢复，无需手动清零。
     */
    @ColumnInfo(name = "pause_until")
    val pauseUntil: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * 判断锁定记录当前是否生效。
         * - 暂停中（now < pauseUntil）：返回 false
         * - 仅有解锁条件（无时间窗口）：始终生效，直到 AI 调用 APP_UNLOCK
         * - 仅有时间窗口：当前时间在任一时段内才生效（支持跨夜）
         * - 两者都有：时段内生效
         */
        fun isLockedNow(lock: AppLockEntity, nowMillis: Long = System.currentTimeMillis()): Boolean {
            // 暂停中：不锁定
            if (lock.pauseUntil > 0L && nowMillis < lock.pauseUntil) return false

            val periods = parseValidTimePeriods(lock.validTime)
            // 无时间窗口：仅有解锁条件，始终锁定
            if (periods.isEmpty()) return true

            val now = Calendar.getInstance()
            val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            return isInTimeWindow(periods, nowMinutes)
        }

        /**
         * 判断当前时间是否落在任意时段内（支持跨夜：start > end 表示跨越零点）。
         */
        fun isInTimeWindow(periods: List<JsonObject>, nowMinutes: Int): Boolean {
            return periods.any { p ->
                val start = (p["start"] as? JsonPrimitive)?.contentOrNull ?: ""
                val end = (p["end"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (start.isBlank() || end.isBlank()) return@any false
                val startMinutes = parseTimeToMinutes(start)
                val endMinutes = parseTimeToMinutes(end)
                if (startMinutes <= endMinutes) {
                    // 同一天窗口
                    nowMinutes in startMinutes..endMinutes
                } else {
                    // 跨夜窗口（start > end 表示跨越零点）
                    nowMinutes >= startMinutes || nowMinutes <= endMinutes
                }
            }
        }

        /**
         * 解析 validTime JSON 字符串为时段列表。
         * 支持数组形式 `[{"start":"12:00","end":"17:00"}]`。
         * 单对象形式 `{start, end}` 也兼容（自动包成单元素列表）。
         * 空字符串、null、解析失败均返回空列表。
         */
        fun parseValidTimePeriods(validTime: String?): List<JsonObject> {
            if (validTime.isNullOrBlank()) return emptyList()
            return runCatching {
                val element = JsonInstant.parseToJsonElement(validTime)
                when (element) {
                    is JsonArray -> element.mapNotNull { it as? JsonObject }
                    is JsonObject -> listOf(element)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }

        /**
         * 将 "HH:mm" 转换为当日分钟数。格式不合法返回 0。
         */
        fun parseTimeToMinutes(time: String): Int {
            val parts = time.split(":")
            if (parts.size != 2) return 0
            val hour = parts[0].toIntOrNull() ?: return 0
            val minute = parts[1].toIntOrNull() ?: return 0
            return hour * 60 + minute
        }
    }
}
