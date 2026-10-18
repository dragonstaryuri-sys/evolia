package me.rerere.rikkahub.ui.pages.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.core.data.repository.CallHistorySort
import me.rerere.rikkahub.core.data.repository.CallRecordRepository

/**
 * 智能体历史通话列表 ViewModel。
 * 排序在 DAO 层完成（最新/最早按 create_time，最长/最短按 duration_ms），避免把全量记录载入 UI 排序。
 */
class HistoryCallVM(
    private val assistantId: String,
    private val callRecordRepository: CallRecordRepository,
) : ViewModel() {

    private val _sort = MutableStateFlow(CallHistorySort.LATEST)
    val sort: StateFlow<CallHistorySort> = _sort.asStateFlow()

    val records: StateFlow<List<CallRecordEntity>> = _sort
        .flatMapLatest { callRecordRepository.getByAssistant(assistantId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(sort: CallHistorySort) {
        _sort.value = sort
    }
}
