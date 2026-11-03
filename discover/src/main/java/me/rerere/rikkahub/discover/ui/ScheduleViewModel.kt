package me.rerere.rikkahub.discover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.core.data.db.entity.ScheduleEventEntity
import me.rerere.rikkahub.discover.repo.ScheduleEventRepository
import me.rerere.rikkahub.discover.repo.ScheduleRepository

/** 待办视图的排程状态筛选（与难度分类是两个独立维度） */
enum class ScheduleStatusFilter { UNSCHEDULED, SCHEDULED, ALL }

/**
 * 待办删除的撤销数据：删除前的待办快照 + 删除时一并被 CASCADE 删除的关联日程快照。
 * 由 UI 层经 toast action 持有，撤销时原样恢复（保留原 id）。
 */
data class ScheduleDeleteUndo(
    val task: ScheduleEntity,
    val events: List<ScheduleEventEntity>
)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val eventRepository: ScheduleEventRepository
) : ViewModel() {

    // 当前选择的分类: user 或 assistant
    private val _selectedCategory = MutableStateFlow("user")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // 待办视图的排程状态筛选（仅 user 视角有效），默认只看已排程
    private val _scheduleFilter = MutableStateFlow(ScheduleStatusFilter.SCHEDULED)
    val scheduleFilter: StateFlow<ScheduleStatusFilter> = _scheduleFilter.asStateFlow()

    // 今日所有日程 (用于发现页进度计算)
    val todaySchedules: StateFlow<List<ScheduleEntity>> = repository.getTodaySchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 全部待办事项（不做排程状态筛选）：发现页预览与待办视图共用，
    // 按新排序规则排列（截止日期队列 → 重要度 → 难度 → 创建时间）
    val allPendingSchedules: StateFlow<List<ScheduleEntity>> = combine(
        repository.getAllPending(),
        _selectedCategory
    ) { list, category ->
        sortTodosForDisplay(list.filter { it.category == category })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 待办视图专用：user 视角叠加排程状态筛选（默认已排程）；
    // assistant 视角不区分排程状态，直接展示其全部待办
    val filteredPendingSchedules: StateFlow<List<ScheduleEntity>> = combine(
        repository.getAllPending(),
        _selectedCategory,
        eventRepository.observeScheduledTaskIds(),
        _scheduleFilter
    ) { list, category, scheduledIds, filter ->
        val scheduledSet = scheduledIds.toSet()
        val isAssistantCategory = category == "assistant"
        val scoped = list.filter { it.category == category }
            .filter { todo ->
                if (isAssistantCategory) {
                    true
                } else {
                    when (filter) {
                        ScheduleStatusFilter.UNSCHEDULED -> todo.id !in scheduledSet
                        ScheduleStatusFilter.SCHEDULED -> todo.id in scheduledSet
                        ScheduleStatusFilter.ALL -> true
                    }
                }
            }
        sortTodosForDisplay(scoped)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 已排程待办的 ID 集合：用于删除待办前的联动提示等 UI 判断
    val scheduledTaskIds: StateFlow<Set<Long>> = eventRepository.observeScheduledTaskIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // 所有已完成事项: 按照更新时间（完成时间）降序排列
    val allCompletedSchedules: StateFlow<List<ScheduleEntity>> = combine(
        repository.getAllCompleted(),
        _selectedCategory
    ) { list, category ->
        list.filter { it.category == category }
            .sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 今日进度 (0.0 - 1.0)
    val todayProgress: StateFlow<Float> = todaySchedules.map { list ->
        if (list.isEmpty()) 0f
        else {
            val completed = list.count { it.isCompleted }
            completed.toFloat() / list.size
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    // 今日已完成数量
    val todayCompletedCount: StateFlow<Int> = todaySchedules.map { list ->
        list.count { it.isCompleted }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 未完成数量
    val unfinishedCount: StateFlow<Int> = repository.getUnfinishedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),0)

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    fun setScheduleFilter(filter: ScheduleStatusFilter) {
        _scheduleFilter.value = filter
    }

    fun toggleComplete(schedule: ScheduleEntity) {
        viewModelScope.launch {
            repository.toggleComplete(schedule)
        }
    }

    fun saveSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            if (schedule.id == 0L) {
                repository.addSchedule(schedule)
            } else {
                repository.updateSchedule(schedule.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    /**
     * 删除待办。若待办关联了日程，数据库层通过外键 CASCADE 一并删除，
     * 是否需要提示由 UI 层根据 isTaskScheduled 决定。
     */
    fun deleteSchedule(id: Long) {
        viewModelScope.launch {
            repository.deleteSchedule(id)
        }
    }

    /**
     * 删除待办并回调撤销数据（供 UI 弹出可撤销 toast）。
     * 删除前快照关联日程（CASCADE 会连带删除），撤销时一并恢复。
     * 撤销数据经回调闭包随 toast 持有，连续多次删除互不干扰。
     */
    fun deleteScheduleWithUndo(task: ScheduleEntity, onDeleted: (ScheduleDeleteUndo) -> Unit) {
        viewModelScope.launch {
            val events = eventRepository.getEventsForTask(task.id)
            repository.deleteSchedule(task.id)
            onDeleted(ScheduleDeleteUndo(task = task, events = events))
        }
    }

    /** 撤销删除：单事务恢复待办及其删除时快照的关联日程 */
    fun undoDeleteSchedule(undo: ScheduleDeleteUndo) {
        viewModelScope.launch {
            eventRepository.restoreScheduleWithEvents(undo.task, undo.events)
        }
    }

    /** 撤销完成状态切换：恢复切换前的完整快照（含原 updatedAt） */
    fun restoreSchedule(original: ScheduleEntity) {
        viewModelScope.launch {
            repository.updateSchedule(original)
        }
    }

    /**
     * 待办排序规则（需求 9.3）：
     *
     * 第一优先级：截止日期。已逾期或未来 7 天内到期的待办构成优先队列，
     * 队列内按截止时间升序（逾期越久越靠前）；
     * 无截止日期或截止日期超过 7 天的归入普通队列。
     * 第二优先级：重要度，高的在前。
     * 第三优先级：难度，简单的在前。
     * 第四优先级：创建时间，早的在前。
     */
    private fun sortTodosForDisplay(list: List<ScheduleEntity>): List<ScheduleEntity> {
        val now = System.currentTimeMillis()
        val sevenDaysLaterMs = now + 7L * 24 * 60 * 60 * 1000

        return list.sortedWith(
            compareBy<ScheduleEntity> { todo ->
                val deadline = todo.endTime
                val isInPriorityQueue = deadline != null && deadline <= sevenDaysLaterMs
                if (isInPriorityQueue) 0 else 1
            }.thenBy { todo ->
                val deadline = todo.endTime
                val inPriorityQueue = deadline != null && deadline <= sevenDaysLaterMs
                if (inPriorityQueue && deadline != null) deadline else 0L
            }.thenByDescending { it.priority }
                .thenBy { it.difficulty }
                .thenBy { it.createdAt }
        )
    }
}
