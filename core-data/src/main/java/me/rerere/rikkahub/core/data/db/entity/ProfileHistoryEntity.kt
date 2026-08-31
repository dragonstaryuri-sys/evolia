package me.rerere.rikkahub.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 档案历史版本实体
 *
 * 每次 AI 调用 update_profile 工具更新档案前，把"被覆盖前的旧值"按字段级别
 * 保存到这张表。同一次调用（同一 [batchId]）视为一个"版本"，系统只保留
 * 每个 target 的最近 [MAX_KEEP_VERSIONS] 个版本，更早的会自动清理。
 *
 * - [targetType] = "user" / "assistant"
 * - [targetId]   = "user" 常量 或 assistantId（字符串形式）
 * - [fieldKey]   = 字段名（如 "preferences"、"personality"）
 * - [oldValue]   = 被覆盖前的旧值
 * - [newValue]   = 覆盖后的新值
 * - [batchId]   = 同一次 update_profile 调用共享一个时间戳，用于按"版本"分组
 */
@Entity(
    tableName = "profile_history",
    indices = [
        Index(value = ["targetType", "targetId", "batchId"]),
        Index(value = ["targetType", "targetId", "createdAt"])
    ]
)
data class ProfileHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetType: String,
    val targetId: String,
    val fieldKey: String,
    val oldValue: String,
    val newValue: String,
    val batchId: Long,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 每个 target 最多保留的版本数（一次 update_profile 调用算一个版本） */
        const val MAX_KEEP_VERSIONS = 3

        const val TARGET_USER = "user"
        const val TARGET_ASSISTANT = "assistant"
    }
}
