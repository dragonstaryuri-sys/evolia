package me.rerere.rikkahub.discover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.discover.repo.ScheduleRepository

class ScheduleViewModel(
    private val repository: ScheduleRepository
) : ViewModel() {

    // 当前选择的分类: user 或 assistant
    private val _selectedCategory = MutableStateFlow("user")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // 今日所有日程 (用于发现页进度计算)
    val todaySchedules: StateFlow<List<ScheduleEntity>> = repository.getTodaySchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有待办事项 (过滤分类，并按照 优先级 -> 紧急程度 排序)
    val allPendingSchedules: StateFlow<List<ScheduleEntity>> = combine(
        repository.getAllPending(),
        _selectedCategory
    ) { list, category ->
        list.filter { it.category == category }
            .sortedWith(
                compareByDescending<ScheduleEntity> { it.priority }
                    .thenByDescending { it.urgency }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun deleteSchedule(id: Long) {
        viewModelScope.launch {
            repository.deleteSchedule(id)
        }
    }
}
