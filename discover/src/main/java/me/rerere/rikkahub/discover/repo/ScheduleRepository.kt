package me.rerere.rikkahub.discover.repo

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.ScheduleDAO
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import java.util.Calendar

class ScheduleRepository(
    private val scheduleDAO: ScheduleDAO
) {
    fun getAllSchedules(): Flow<List<ScheduleEntity>> = scheduleDAO.getAllSchedules()

    fun getActiveSchedules(): Flow<List<ScheduleEntity>> = scheduleDAO.getActiveSchedules()

    fun getTodaySchedules(): Flow<List<ScheduleEntity>> {
        val (start, end) = getTodayRange()
        return scheduleDAO.getSchedulesForDay(start, end)
    }

    /**
     * 获取所有待办事项和当日已完成事项 (用于智能体)
     */
    fun getPendingAndTodayCompleted(): Flow<List<ScheduleEntity>> {
        val (start, _) = getTodayRange()
        return scheduleDAO.getPendingAndTodayCompleted(start)
    }

    /**
     * 获取所有待办事项 (按创建时间降序)
     */
    fun getAllPending(): Flow<List<ScheduleEntity>> = scheduleDAO.getAllPending()

    /**
     * 获取所有已完成事项 (按更新时间/完成时间降序)
     */
    fun getAllCompleted(): Flow<List<ScheduleEntity>> = scheduleDAO.getAllCompleted()

    fun getTodayUnfinishedCount(): Flow<Int> {
        val (start, end) = getTodayRange()
        return scheduleDAO.getUnfinishedCountForDay(start, end)
    }

    suspend fun addSchedule(schedule: ScheduleEntity) = scheduleDAO.insertSchedule(schedule)

    suspend fun updateSchedule(schedule: ScheduleEntity) = scheduleDAO.updateSchedule(schedule)

    suspend fun deleteSchedule(id: Long) = scheduleDAO.deleteSchedule(id)

    suspend fun toggleComplete(schedule: ScheduleEntity) {
        scheduleDAO.updateSchedule(schedule.copy(
            isCompleted = !schedule.isCompleted,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun getTodayRange(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val end = calendar.timeInMillis
        return start to end
    }
}
