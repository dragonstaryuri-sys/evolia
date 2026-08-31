package me.rerere.rikkahub.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 助手扩展状态实体（用于主智能体或开启了扩展状态的智能体）
 */
@Entity(tableName = "assistant_extended_state")
data class AssistantExtendedStateEntity(
    @PrimaryKey
    val assistantId: String,           // 对应 Assistant.id
    val personality: String = "",      // 性格
    val appearance: String = "",       // 外貌（自由文本，由 AI/用户维护描述内容）
    val preferences: String = "",      // 喜好
    val diet: String = "",             // 饮食
    val taboos: String = "",           // 禁忌
    val interactionHabits: String = "",// 互动习惯
    val relationships: String = ""     // 重要人际关系
)
