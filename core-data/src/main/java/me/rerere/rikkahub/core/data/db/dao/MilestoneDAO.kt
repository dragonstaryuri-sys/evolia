package me.rerere.rikkahub.core.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.MilestoneEntity

@Dao
interface MilestoneDAO {
    @Query("SELECT * FROM MilestoneEntity WHERE assistant_id = :assistantId ORDER BY time ASC")
    fun getMilestonesFlow(assistantId: String): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM MilestoneEntity WHERE assistant_id = :assistantId ORDER BY time ASC")
    suspend fun getMilestones(assistantId: String): List<MilestoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMilestone(milestone: MilestoneEntity)

    @Update
    suspend fun updateMilestone(milestone: MilestoneEntity)

    @Query("DELETE FROM MilestoneEntity WHERE id = :id")
    suspend fun deleteMilestone(id: String)

    @Query("DELETE FROM MilestoneEntity WHERE assistant_id = :assistantId")
    suspend fun deleteMilestonesOfAssistant(assistantId: String)
}
