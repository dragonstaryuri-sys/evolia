package me.rerere.rikkahub.core.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.AssistantExtendedStateEntity

@Dao
interface AssistantExtendedStateDAO {
    @Query("SELECT * FROM assistant_extended_state WHERE assistantId = :assistantId")
    suspend fun getStateById(assistantId: String): AssistantExtendedStateEntity?

    @Query("SELECT * FROM assistant_extended_state WHERE assistantId = :assistantId")
    fun getStateByIdFlow(assistantId: String): Flow<AssistantExtendedStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: AssistantExtendedStateEntity)

    @Delete
    suspend fun delete(state: AssistantExtendedStateEntity)

    @Query("DELETE FROM assistant_extended_state WHERE assistantId = :assistantId")
    suspend fun deleteByAssistantId(assistantId: String)
}
