package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for tracking daily token usage per assistant.
 */
@Entity(tableName = "token_usage")
data class TokenUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "assistant_id")
    val assistantId: String,

    @ColumnInfo(name = "date")
    val date: String, // ISO format: YYYY-MM-DD

    @ColumnInfo(name = "prompt_tokens")
    val promptTokens: Int = 0,

    @ColumnInfo(name = "completion_tokens")
    val completionTokens: Int = 0,

    @ColumnInfo(name = "cached_tokens")
    val cachedTokens: Int = 0
)

/**
 * Data class for daily token usage summary across all assistants.
 */
data class DailyUsageSummary(
    val date: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int
)
