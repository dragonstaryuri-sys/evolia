package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.RadioChannelEntity

@Dao
interface RadioChannelDao {
    @Upsert
    suspend fun upsert(channel: RadioChannelEntity)

    @Query("SELECT * FROM radio_channels ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<RadioChannelEntity>>

    @Query("SELECT * FROM radio_channels ORDER BY sort_order ASC, created_at ASC")
    suspend fun getAll(): List<RadioChannelEntity>

    @Query("SELECT * FROM radio_channels WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RadioChannelEntity?

    @Query("SELECT * FROM radio_channels WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<RadioChannelEntity?>

    @Query("SELECT COUNT(*) FROM radio_channels")
    suspend fun countAll(): Int

    @Query("DELETE FROM radio_channels WHERE id = :id")
    suspend fun deleteById(id: String)
}
