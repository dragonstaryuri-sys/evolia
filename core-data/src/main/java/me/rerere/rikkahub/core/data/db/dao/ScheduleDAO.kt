package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity

@Dao
interface ScheduleDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Long)

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules ORDER BY start_time ASC")
    fun getAllSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE is_completed = 0 ORDER BY priority DESC, start_time ASC")
    fun getActiveSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE start_time >= :startOfDay AND start_time < :endOfDay ORDER BY start_time ASC")
    fun getSchedulesForDay(startOfDay: Long, endOfDay: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE is_completed = 0 OR (is_completed = 1 AND updated_at >= :todayStart) ORDER BY is_completed ASC, priority DESC, created_at DESC")
    fun getPendingAndTodayCompleted(todayStart: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE is_completed = 0 ORDER BY created_at DESC")
    fun getAllPending(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE is_completed = 1 ORDER BY updated_at DESC")
    fun getAllCompleted(): Flow<List<ScheduleEntity>>

    @Query("SELECT COUNT(*) FROM schedules WHERE start_time >= :startOfDay AND start_time < :endOfDay AND is_completed = 0")
    fun getUnfinishedCountForDay(startOfDay: Long, endOfDay: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM schedules WHERE is_completed = 0")
    fun getUnfinishedCount(): Flow<Int>
}
