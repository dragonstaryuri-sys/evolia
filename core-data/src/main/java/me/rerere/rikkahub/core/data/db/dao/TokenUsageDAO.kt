package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.DailyUsageSummary
import me.rerere.rikkahub.core.data.db.entity.TokenUsageEntity

@Dao
interface TokenUsageDAO {
    @Query("SELECT * FROM token_usage WHERE assistant_id = :assistantId AND date = :date LIMIT 1")
    suspend fun getUsage(assistantId: String, date: String): TokenUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsage(usage: TokenUsageEntity)

    @Transaction
    suspend fun incrementUsage(assistantId: String, date: String, prompt: Int, completion: Int, cached: Int) {
        val existing = getUsage(assistantId, date)
        if (existing != null) {
            insertUsage(
                existing.copy(
                    promptTokens = existing.promptTokens + prompt,
                    completionTokens = existing.completionTokens + completion,
                    cachedTokens = existing.cachedTokens + cached
                )
            )
        } else {
            insertUsage(
                TokenUsageEntity(
                    assistantId = assistantId,
                    date = date,
                    promptTokens = prompt,
                    completionTokens = completion,
                    cachedTokens = cached
                )
            )
        }
    }

    @Query("SELECT * FROM token_usage WHERE assistant_id = :assistantId ORDER BY date DESC LIMIT :days")
    fun getRecentUsageFlow(assistantId: String, days: Int): Flow<List<TokenUsageEntity>>

    @Query("SELECT * FROM token_usage WHERE assistant_id = :assistantId AND date = :date")
    fun getDailyUsageFlow(assistantId: String, date: String): Flow<TokenUsageEntity?>

    @Query("SELECT * FROM token_usage WHERE date >= :startDate ORDER BY date DESC")
    fun getAllRecentUsageFlow(startDate: String): Flow<List<TokenUsageEntity>>

    @Query("SELECT date, SUM(prompt_tokens) as promptTokens, SUM(completion_tokens) as completionTokens, SUM(cached_tokens) as cachedTokens FROM token_usage WHERE date >= :startDate GROUP BY date ORDER BY date DESC")
    fun getDailyTotalUsageFlow(startDate: String): Flow<List<DailyUsageSummary>>
}
