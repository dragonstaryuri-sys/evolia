package me.rerere.rikkahub.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 助手外貌细节属性
 */
@Serializable
data class AssistantAppearance(
    val hairColor: String = "",       // 头发颜色
    val hairCurliness: String = "",    // 头发卷度
    val hairLength: String = "",       // 头发长度
    val eyeColor: String = "",        // 眼睛颜色
    val eyelidType: String = "",      // 眼皮（单/双）
    val eyelashLength: String = "",    // 睫毛长度
    val skinTone: String = "",        // 肤色
    val muscle: Int = 0,              // 肌肉 (0-100)
    val height: Int = 0,              // 身高 (cm)
    val bodyFat: Int = 0              // 体脂 (0-100)
)

/**
 * 助手扩展状态实体（用于主智能体或开启了扩展状态的智能体）
 */
@Entity(tableName = "assistant_extended_state")
data class AssistantExtendedStateEntity(
    @PrimaryKey
    val assistantId: String,           // 对应 Assistant.id
    val personality: String = "",      // 性格
    val appearance: AssistantAppearance = AssistantAppearance(), // 外貌
    val preferences: String = "",      // 喜好
    val diet: String = "",             // 饮食
    val taboos: String = "",           // 禁忌
    val interactionHabits: String = "",// 互动习惯
    val relationships: String = ""     // 重要人际关系
)
