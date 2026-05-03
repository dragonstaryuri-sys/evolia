package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import androidx.paging.filter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatListVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val conversationJobs = chatService.getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val recentlyRestoredIds = chatService.recentlyRestoredIds

    // 模式切换状态：用于控制全屏转场动画
    private val _isSwitchingMode = MutableStateFlow(false)
    val isSwitchingMode: StateFlow<Boolean> = _isSwitchingMode.asStateFlow()

    /**
     * 每个助手的最后一条消息内容 (根据当前模式显示)
     */
    val assistantsLastMessages: StateFlow<Map<Uuid, String>> = settings
        .flatMapLatest { settings ->
            if (settings.assistants.isEmpty()) return@flatMapLatest flowOf(emptyMap())
            combine(
                settings.assistants.map { assistant ->
                    conversationRepo.getConversationsOfAssistant(assistant.id, isVirtual = assistant.isVirtualWorldMode)
                        .map { conversations ->
                            assistant.id to (conversations.firstOrNull { it.messageNodes.isNotEmpty() }?.lastMessageContent ?: "")
                        }
                }
            ) { pairs ->
                pairs.toMap()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val conversations: Flow<PagingData<ConversationListItem>> = combine(
        settings.map { it.assistantId }.distinctUntilChanged(),
        _searchQuery,
        settings.map { s -> s.assistants.find { it.id == s.assistantId }?.isVirtualWorldMode ?: false }.distinctUntilChanged()
    ) { assistantId, query, isVirtual -> Triple(assistantId, query, isVirtual) }
        .flatMapLatest { (assistantId, query, isVirtual) ->
            if (query.isBlank()) {
                conversationRepo.getConversationsOfAssistantPaging(assistantId, isVirtual = isVirtual)
            } else {
                conversationRepo.searchConversationsOfAssistantPaging(assistantId, query, isVirtual = isVirtual)
            }
        }
        .map { pagingData: PagingData<Conversation> ->
            pagingData
                // 核心逻辑：列表自动过滤掉没有任何消息的空会话（除非它是置顶的）
                .filter { it.messageNodes.isNotEmpty() || it.isPinned }
                .map { ConversationListItem.Item(it) as ConversationListItem }
                .insertSeparators { before, after ->
                    val b = before as? ConversationListItem.Item
                    val a = after as? ConversationListItem.Item
                    when {
                        b == null && a != null -> {
                            if (a.conversation.isPinned) {
                                ConversationListItem.PinnedHeader
                            } else {
                                val afterDate = a.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                ConversationListItem.DateHeader(
                                    date = afterDate,
                                    label = getDateLabel(afterDate)
                                )
                            }
                        }
                        b != null && a != null -> {
                            if (b.conversation.isPinned && !a.conversation.isPinned) {
                                val afterDate = a.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                ConversationListItem.DateHeader(
                                    date = afterDate,
                                    label = getDateLabel(afterDate)
                                )
                            } else if (!a.conversation.isPinned) {
                                val beforeDate = b.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                val afterDate = a.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                if (beforeDate != afterDate) {
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                } else null
                            } else null
                        }
                        else -> null
                    }
                }
        }
        .catch { e ->
            e.printStackTrace()
            emit(PagingData.empty())
        }
        .cachedIn(viewModelScope)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteConversation(conversation: Conversation) {
        chatService.deleteConversation(conversation)
    }

    fun undoDeleteConversation(id: Uuid) {
        chatService.undoDeleteConversation(id)
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun updateConversationTitle(conversation: Conversation, title: String) {
        viewModelScope.launch {
            conversationRepo.updateConversation(conversation.copy(title = title))
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }

    fun consolidateConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.markAsNotConsolidated(conversation.id)
            val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>()
                .setInputData(androidx.work.workDataOf("FORCE_CONVERSATION_ID" to conversation.id.toString()))
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }

    /**
     * 切换虚拟 world 模式，优化逻辑：
     * 1. 离场清理：如果是空对话，删除；如果有内容，归档并标记 Consolidated。
     * 2. 模式切换。
     */
    fun toggleVirtualMode(assistant: Assistant) {
        viewModelScope.launch {
            _isSwitchingMode.value = true
            try {
                // 1. 获取当前模式的最后一次对话
                val lastConv = conversationRepo.getConversationsOfAssistant(
                    assistantId = assistant.id,
                    isVirtual = assistant.isVirtualWorldMode
                ).firstOrNull()?.firstOrNull()

                if (lastConv != null) {
                    if (lastConv.messageNodes.isNotEmpty()) {
                        // 有内容的，归档并标记为 Consolidated，暗示话题已结束
                        android.util.Log.i("ChatListVM", "Switching mode: Archiving current session")
                        val archiveJob = launch {
                            try {
                                chatService.archiveConversation(lastConv.id, force = true)
                                conversationRepo.markAsConsolidated(lastConv.id)
                            } catch (e: Exception) {
                                android.util.Log.e("ChatListVM", "Archive failed", e)
                            }
                        }
                        withTimeoutOrNull(5000) { archiveJob.join() }
                    } else if (!lastConv.isPinned) {
                        // 没发过消息且未置顶，直接删掉，不留痕迹
                        conversationRepo.deleteConversation(lastConv, deleteFiles = false)
                    }
                }

                // 2. 更新设置
                val currentSettings = settings.value
                val updatedSettings = currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == assistant.id) it.copy(isVirtualWorldMode = !it.isVirtualWorldMode) else it
                    }
                )
                settingsStore.update(updatedSettings)

                kotlinx.coroutines.delay(300)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSwitchingMode.value = false
            }
        }
    }

    suspend fun selectAssistant(assistantId: Uuid) {
        settingsStore.updateAssistant(assistantId)
        settingsStore.markAssistantUsed(assistantId)
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
