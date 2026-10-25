package me.rerere.rikkahub.discover.repo

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.DbTransactionRunner
import me.rerere.rikkahub.core.data.db.dao.ScheduleDAO
import me.rerere.rikkahub.core.data.db.dao.ScheduleEventDAO
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.core.data.db.entity.ScheduleEventEntity

/**
 * 时间轴上的一条日程，携带其关联的待办（用于卡片标题与完成标识）。
 * 关联待办缺失（理论上不应发生，防御性兜底）时 task 为 null。
 */
data class ScheduleEventWithTask(
    val event: ScheduleEventEntity,
    val task: ScheduleEntity?
)

class ScheduleEventRepository(
    private val scheduleDAO: ScheduleDAO,
    private val eventDAO: ScheduleEventDAO,
    private val transactionRunner: DbTransactionRunner
) {
    fun observeEventsForDate(epochDay: Long): Flow<List<ScheduleEventEntity>> =
        eventDAO.observeEventsForDate(epochDay)

    /**
     * 已排程待办 ID 集合：存在至少一条关联日程的待办。
     * 排程状态由此实时推导，不在待办表冗余存储状态字段。
     */
    fun observeScheduledTaskIds(): Flow<List<Long>> = eventDAO.observeScheduledTaskIds()

    suspend fun getEventById(id: Long): ScheduleEventEntity? = eventDAO.getEventById(id)

    suspend fun countEventsForTask(taskId: Long): Int = eventDAO.countEventsForTask(taskId)

    /**
     * 判断指定日期的时间段 [startMinute, endMinute) 是否与已有日程重叠。
     * 相邻不重叠（如 08:00-09:00 与 09:00-10:00）不算冲突。
     */
    suspend fun hasOverlap(
        epochDay: Long,
        startMinute: Int,
        endMinute: Int,
        excludeEventId: Long? = null
    ): Boolean {
        val events = eventDAO.getEventsForDate(epochDay)
        val others = if (excludeEventId != null) events.filter { it.id != excludeEventId } else events
        return others.any { other ->
            val overlaps = startMinute < other.endTime && endMinute > other.startTime
            overlaps
        }
    }

    suspend fun insertEvent(event: ScheduleEventEntity): Long = eventDAO.insertEvent(event)

    suspend fun updateEvent(event: ScheduleEventEntity) = eventDAO.updateEvent(event)

    suspend fun deleteEvent(id: Long) = eventDAO.deleteEvent(id)

    /**
     * 在同一数据库事务内"新建待办 + 新建关联日程"。
     * 任一步骤失败则整体回滚，不会留下用户无法识别的半成品待办。
     *
     * @param eventBuilder 接收新待办 ID，构造关联的日程记录
     * @return 新日程的 ID
     */
    suspend fun createTodoWithEvent(
        todo: ScheduleEntity,
        eventBuilder: (taskId: Long) -> ScheduleEventEntity
    ): Long = transactionRunner.runInTransaction {
        val taskId = scheduleDAO.insertSchedule(todo)
        eventDAO.insertEvent(eventBuilder(taskId))
    }
}
