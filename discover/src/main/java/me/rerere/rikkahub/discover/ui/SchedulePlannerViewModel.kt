package me.rerere.rikkahub.discover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.core.data.db.entity.ScheduleEventEntity
import me.rerere.rikkahub.discover.repo.ScheduleEventRepository
import me.rerere.rikkahub.discover.repo.ScheduleEventWithTask
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import java.time.LocalDate

/** 某一天的日程及其所属日期（供时间轴按日期做翻页动画） */
data class PlannerDayEvents(
    val date: LocalDate,
    val events: List<ScheduleEventWithTask>,
)

/** 日程页面的两种视图：全天时间轴 / 待办列表 */
enum class PlannerView { TIMELINE, TODO }

/** 当天分钟的边界值（不支持跨天日程，结束时间最晚 23:59） */
const val DAY_LAST_MINUTE = 24 * 60 - 1

class SchedulePlannerViewModel(
    private val scheduleRepository: ScheduleRepository,
    private val eventRepository: ScheduleEventRepository
) : ViewModel() {

    // 当前查看的日期，默认当天
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    // 当前视图，默认时间轴
    private val _viewMode = MutableStateFlow(PlannerView.TIMELINE)
    val viewMode: StateFlow<PlannerView> = _viewMode.asStateFlow()

    // 保存中标记：防止连续新增时的重复提交
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // 待办候选列表的排程状态筛选（默认只显示未排程）
    private val _candidateFilter = MutableStateFlow(ScheduleStatusFilter.UNSCHEDULED)
    val candidateFilter: StateFlow<ScheduleStatusFilter> = _candidateFilter.asStateFlow()

    /**
     * 所选日期的日程（含关联待办），已按 开始时间 → 创建时间 → ID 排序。
     * 本期日程规划只面向 user 类待办，防御性过滤 category。
     * 携带所属日期，供时间轴翻页动画区分新旧两天的数据。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val eventsForDate: StateFlow<PlannerDayEvents> = _selectedDate
        .flatMapLatest { date ->
            combine(
                eventRepository.observeEventsForDate(date.toEpochDay()),
                scheduleRepository.getAllSchedules()
            ) { events, todos ->
                val todoById = todos.associateBy { it.id }
                val list = events.mapNotNull { event ->
                    val todo = todoById[event.taskId] ?: return@mapNotNull null
                    if (event.category != "user") return@mapNotNull null
                    ScheduleEventWithTask(event, todo)
                }
                PlannerDayEvents(date, list)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PlannerDayEvents(_selectedDate.value, emptyList())
        )

    /**
     * 日程表单的待办候选列表：仅 user 类待办，按排程状态筛选。
     * 只提供未完成的待办（已完成事项无需再安排时间）。
     */
    val candidateTodos: StateFlow<List<ScheduleEntity>> = combine(
        scheduleRepository.getAllPending(),
        eventRepository.observeScheduledTaskIds(),
        _candidateFilter
    ) { pending, scheduledIds, filter ->
        val scheduledSet = scheduledIds.toSet()
        pending.filter { it.category == "user" }
            .filter { todo ->
                when (filter) {
                    ScheduleStatusFilter.UNSCHEDULED -> todo.id !in scheduledSet
                    ScheduleStatusFilter.SCHEDULED -> todo.id in scheduledSet
                    ScheduleStatusFilter.ALL -> true
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun setViewMode(mode: PlannerView) {
        _viewMode.value = mode
    }

    fun setCandidateFilter(filter: ScheduleStatusFilter) {
        _candidateFilter.value = filter
    }

    /**
     * 新建日程。
     *
     * @param candidateTaskId 选择的已有待办；为 null 时用 [title] 创建全新待办
     * @param allowOverlap true 表示用户已确认时间重叠，跳过冲突检查直接保存
     */
    fun saveNewEvent(
        candidateTaskId: Long?,
        title: String,
        date: LocalDate,
        startMinute: Int,
        endMinute: Int,
        allowOverlap: Boolean,
        onConflict: () -> Unit,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            if (_isSaving.value) return@launch
            // 防御性校验：既没有选择待办也没有名称时，不产生任何写入
            if (candidateTaskId == null && title.isBlank()) return@launch
            _isSaving.value = true
            try {
                val epochDay = date.toEpochDay()
                if (!allowOverlap) {
                    val conflict = eventRepository.hasOverlap(epochDay, startMinute, endMinute)
                    if (conflict) {
                        onConflict()
                        return@launch
                    }
                }

                val eventId = if (candidateTaskId != null) {
                    // 为已有待办新增一条日程：不修改原待办的任何字段
                    eventRepository.insertEvent(
                        buildEvent(candidateTaskId, date, startMinute, endMinute)
                    )
                } else {
                    // 全新事项：同一事务内创建待办 + 关联日程。
                    // 待办 start_time 保持"创建时间"既有语义，不写入日程开始时间；
                    // end_time（截止时间）留空，不触发截止提醒与系统日历同步。
                    val trimmedTitle = title.trim()
                    val newTodo = ScheduleEntity(
                        title = trimmedTitle,
                        content = "",
                        priority = 1,
                        urgency = 1,
                        difficulty = 1,
                        isCompleted = false,
                        category = "user",
                        startTime = System.currentTimeMillis(),
                        endTime = null
                    )
                    eventRepository.createTodoWithEvent(newTodo) { taskId ->
                        buildEvent(taskId, date, startMinute, endMinute)
                    }
                }
                onSuccess()
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * 编辑日程：更新日期与时间段；若名称有修改则同步更新关联待办的 title
     *（影响该待办所有日程卡片的显示），不改动待办完成状态与截止时间。
     */
    fun updateEvent(
        eventId: Long,
        title: String,
        date: LocalDate,
        startMinute: Int,
        endMinute: Int,
        allowOverlap: Boolean,
        onConflict: () -> Unit,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            if (_isSaving.value) return@launch
            _isSaving.value = true
            try {
                val event = eventRepository.getEventById(eventId) ?: return@launch
                val epochDay = date.toEpochDay()
                if (!allowOverlap) {
                    val conflict = eventRepository.hasOverlap(
                        epochDay, startMinute, endMinute, excludeEventId = eventId
                    )
                    if (conflict) {
                        onConflict()
                        return@launch
                    }
                }

                // 名称来自关联待办：修改名称即修改待办标题
                val trimmedTitle = title.trim()
                val task = scheduleRepository.getScheduleById(event.taskId)
                if (task != null && trimmedTitle.isNotEmpty() && trimmedTitle != task.title) {
                    scheduleRepository.updateSchedule(
                        task.copy(title = trimmedTitle, updatedAt = System.currentTimeMillis())
                    )
                }

                eventRepository.updateEvent(
                    event.copy(
                        date = epochDay,
                        startTime = startMinute,
                        endTime = endMinute,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                onSuccess()
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** 删除单条日程，不影响关联待办及其完成状态 */
    fun deleteEvent(eventId: Long) {
        viewModelScope.launch {
            eventRepository.deleteEvent(eventId)
        }
    }

    private fun buildEvent(
        taskId: Long,
        date: LocalDate,
        startMinute: Int,
        endMinute: Int
    ): ScheduleEventEntity = ScheduleEventEntity(
        taskId = taskId,
        date = date.toEpochDay(),
        startTime = startMinute,
        endTime = endMinute,
        category = "user"
    )
}
