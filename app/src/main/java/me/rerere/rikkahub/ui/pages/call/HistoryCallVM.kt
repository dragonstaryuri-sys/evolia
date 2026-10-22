package me.rerere.rikkahub.ui.pages.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.core.data.repository.CallHistorySort
import me.rerere.rikkahub.core.data.repository.CallRecordRepository
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * 智能体历史通话列表 ViewModel。
 * 排序在 DAO 层完成（最新/最早按 create_time，最长/最短按 duration_ms），避免把全量记录载入 UI 排序。
 */
class HistoryCallVM(
    private val assistantId: String,
    private val callRecordRepository: CallRecordRepository,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
) : ViewModel() {

    private val _sort = MutableStateFlow(CallHistorySort.LATEST)
    val sort: StateFlow<CallHistorySort> = _sort.asStateFlow()

    val records: StateFlow<List<CallRecordEntity>> = _sort
        .flatMapLatest { callRecordRepository.getByAssistant(assistantId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(sort: CallHistorySort) {
        _sort.value = sort
    }

    /**
     * 删除通话记录及其关联聊天消息，与对话页长按删除保持一致：
     * 1. 删除该 callId 归属的消息节点（含未接通触发轮兜底）；
     * 2. 删除 call_records 记录；
     * 3. 同步 ChatService 内存会话，避免返回对话页时仍显示已删除的消息。
     */
    fun deleteCallRecord(record: CallRecordEntity) {
        viewModelScope.launch {
            val conversationId = runCatching { Uuid.parse(record.conversationId) }.getOrNull()
                ?: return@launch
            val callId = record.id

            conversationRepo.deleteCallMessages(
                conversationId = record.conversationId,
                callId = callId,
                callCreateTime = record.createTime
            )

            callRecordRepository.deleteById(callId)

            chatService.mutateConversationAndSave(conversationId) { current ->
                val filteredNodes = current.messageNodes.filterNot { node ->
                    if (node.messages.isEmpty()) return@filterNot false
                    val message = node.currentMessage
                    val isCallMessage = message.callId == callId
                    val isCallControlTrigger = message.callId.isNullOrBlank() &&
                        message.parts.any { part ->
                            part is UIMessagePart.ToolCall && part.toolName == "call_control"
                        }
                    isCallMessage || isCallControlTrigger
                }
                current.copy(messageNodes = filteredNodes)
            }
        }
    }
}
