package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeDeletionLogEntity

@Dao
interface RadioEpisodeDeletionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: RadioEpisodeDeletionLogEntity)

    @Query("SELECT * FROM radio_episode_deletion_logs ORDER BY deleted_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecent(limit: Int, offset: Int): List<RadioEpisodeDeletionLogEntity>

    @Query("SELECT COUNT(*) FROM radio_episode_deletion_logs")
    suspend fun countAll(): Int
}
