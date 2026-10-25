package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ScheduleEventEntity

@Dao
interface ScheduleEventDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: ScheduleEventEntity): Long

    @Update
    suspend fun updateEvent(event: ScheduleEventEntity)

    @Query("DELETE FROM schedule_events WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    @Query("SELECT * FROM schedule_events WHERE id = :id")
    suspend fun getEventById(id: Long): ScheduleEventEntity?

    /**
     * 某一天的日程，按开始时间升序；开始时间相同按创建时间升序，
     * 创建时间也相同则按 id 升序（稳定次级排序）。
     */
    @Query(
        "SELECT * FROM schedule_events WHERE `date` = :epochDay " +
            "ORDER BY start_time ASC, created_at ASC, id ASC"
    )
    fun observeEventsForDate(epochDay: Long): Flow<List<ScheduleEventEntity>>

    @Query("SELECT * FROM schedule_events WHERE `date` = :epochDay ORDER BY start_time ASC, created_at ASC, id ASC")
    suspend fun getEventsForDate(epochDay: Long): List<ScheduleEventEntity>

    @Query("SELECT * FROM schedule_events WHERE task_id = :taskId ORDER BY date ASC, start_time ASC")
    fun observeEventsForTask(taskId: Long): Flow<List<ScheduleEventEntity>>

    /** 已排程待办的 taskId 集合（存在至少一条关联日程的待办） */
    @Query("SELECT DISTINCT task_id FROM schedule_events")
    fun observeScheduledTaskIds(): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM schedule_events WHERE task_id = :taskId")
    suspend fun countEventsForTask(taskId: Long): Int
}
