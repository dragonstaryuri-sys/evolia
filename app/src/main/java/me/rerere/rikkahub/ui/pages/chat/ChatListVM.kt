package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.core.data.model.Assistant
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
                            assistant.id to (conversations.firstOrNull()?.lastMessageContent ?: "")
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
        .map { pagingData ->
            pagingData
                .map { ConversationListItem.Item(it) }
                .insertSeparators { before, after ->
                    when {
                        before == null && after is ConversationListItem.Item -> {
                            if (after.conversation.isPinned) {
                                ConversationListItem.PinnedHeader
                            } else {
                                val afterDate = after.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                ConversationListItem.DateHeader(
                                    date = afterDate,
                                    label = getDateLabel(afterDate)
                                )
                            }
                        }
                        before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                            if (before.conversation.isPinned && !after.conversation.isPinned) {
                                val afterDate = after.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                ConversationListItem.DateHeader(
                                    date = afterDate,
                                    label = getDateLabel(afterDate)
                                )
                            } else if (!after.conversation.isPinned) {
                                val beforeDate = before.conversation.updateAt
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                val afterDate = after.conversation.updateAt
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

    fun deleteConversation(conversation: me.rerere.rikkahub.core.data.model.Conversation) {
        chatService.deleteConversation(conversation)
    }

    fun undoDeleteConversation(id: Uuid) {
        chatService.undoDeleteConversation(id)
    }

    fun updatePinnedStatus(conversation: me.rerere.rikkahub.core.data.model.Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun updateConversationTitle(conversation: me.rerere.rikkahub.core.data.model.Conversation, title: String) {
        viewModelScope.launch {
            conversationRepo.updateConversation(conversation.copy(title = title))
        }
    }

    fun generateTitle(conversation: me.rerere.rikkahub.core.data.model.Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }

    fun consolidateConversation(conversation: me.rerere.rikkahub.core.data.model.Conversation) {
        viewModelScope.launch {
            conversationRepo.markAsNotConsolidated(conversation.id)
            val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>()
                .setInputData(androidx.work.workDataOf("FORCE_CONVERSATION_ID" to conversation.id.toString()))
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }

    /**
     * 切换虚拟 world 模式，并触发自动归档 (Episode Generation)
     */
    fun toggleVirtualMode(assistant: Assistant) {
        viewModelScope.launch {
            _isSwitchingMode.value = true
            try {
                // 1. 获取最后一次对话
                val lastConv = conversationRepo.getConversationsOfAssistant(
                    assistantId = assistant.id,
                    isVirtual = assistant.isVirtualWorldMode
                ).firstOrNull()?.firstOrNull()

                // 2. 尝试归档逻辑
                if (lastConv != null) {
                    android.util.Log.i("ChatListVM", "Switching mode: triggering background archive")

                    // 在子协程启动，确保不会被 withTimeout 取消
                    val archiveJob = launch {
                        try {
                            chatService.archiveConversation(lastConv.id, force = true)
                        } catch (e: Exception) {
                            android.util.Log.e("ChatListVM", "Background archive failed", e)
                        }
                    }

                    // UI 最多等待 8 秒，无论是否完成都会进入下一步
                    withTimeoutOrNull(8000) {
                        archiveJob.join()
                    }
                }

                // 3. 更新设置
                val currentSettings = settings.value
                val updatedSettings = currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == assistant.id) it.copy(isVirtualWorldMode = !it.isVirtualWorldMode) else it
                    }
                )
                settingsStore.update(updatedSettings)

                // 额外给 UI 留一点缓冲时间
                kotlinx.coroutines.delay(300)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 确保无论如何动画都会结束
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
