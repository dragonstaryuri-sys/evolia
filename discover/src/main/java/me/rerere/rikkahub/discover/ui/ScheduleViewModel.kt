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

    // 今日所有日程 (用于发现页卡片展示)
    val todaySchedules: StateFlow<List<ScheduleEntity>> = repository.getTodaySchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有待办事项 (按创建时间降序)
    val allPendingSchedules: StateFlow<List<ScheduleEntity>> = repository.getAllPending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有已完成事项 (按完成时间降序)
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

    fun addSchedule(title: String, content: String = "", priority: Int = 0, startTime: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            repository.addSchedule(
                ScheduleEntity(
                    title = title,
                    content = content,
                    priority = priority,
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
