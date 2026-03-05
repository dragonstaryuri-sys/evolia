package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentDiaryEntity

@Dao
interface AgentDiaryDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiary(diary: AgentDiaryEntity)

    @Query("SELECT * FROM AgentDiaryEntity WHERE assistant_id = :assistantId ORDER BY date DESC")
    fun getDiariesByAssistant(assistantId: String): Flow<List<AgentDiaryEntity>>

    @Query("SELECT * FROM AgentDiaryEntity WHERE assistant_id = :assistantId AND date = :date LIMIT 1")
    suspend fun getDiaryByDate(assistantId: String, date: String): AgentDiaryEntity?

    @Query("DELETE FROM AgentDiaryEntity WHERE id = :id")
    suspend fun deleteDiaryById(id: String)
}
