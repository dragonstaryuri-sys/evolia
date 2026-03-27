package me.rerere.rikkahub.discover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.discover.repo.ScheduleRepository

class ScheduleViewModel(
    private val repository: ScheduleRepository
) : ViewModel() {

    // 今日所有日程 (用于发现页进度计算)
    val todaySchedules: StateFlow<List<ScheduleEntity>> = repository.getTodaySchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有待办事项 (用于发现页卡片展示最前面的两条，以及列表页)
    val allPendingSchedules: StateFlow<List<ScheduleEntity>> = repository.getAllPending()
        .map { list ->
            // 按照 优先级 -> 紧急程度 排序，确保发现页显示的是最重要的
            list.sortedWith(
                compareByDescending<ScheduleEntity> { it.priority }
                    .thenByDescending { it.urgency }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有已完成事项
    val allCompletedSchedules: StateFlow<List<ScheduleEntity>> = repository.getAllCompleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 今日进度 (0.0 - 1.0)
    val todayProgress: StateFlow<Float> = todaySchedules.map { list ->
        if (list.isEmpty()) 0f
        else {
            val completed = list.count { it.isCompleted }
            completed.toFloat() / list.size
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    // 未完成数量
    val unfinishedCount: StateFlow<Int> = repository.getTodayUnfinishedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun toggleComplete(schedule: ScheduleEntity) {
        viewModelScope.launch {
            repository.toggleComplete(schedule)
        }
    }

    fun addSchedule(
        title: String,
        content: String = "",
        priority: Int = 1,
        urgency: Int = 1,
        difficulty: Int = 1,
        startTime: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            repository.addSchedule(
                ScheduleEntity(
                    title = title,
                    content = content,
                    priority = priority,
                    urgency = urgency,
                    difficulty = difficulty,
                    startTime = startTime
                )
            )
        }
    }

    fun deleteSchedule(id: Long) {
        viewModelScope.launch {
            repository.deleteSchedule(id)
        }
    }
}
