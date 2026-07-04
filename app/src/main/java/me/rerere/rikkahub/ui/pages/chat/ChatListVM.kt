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

    /**
     * 每个助手的最后一条消息内容
     */
    val assistantsLastMessages: StateFlow<Map<Uuid, String>> = settings
        .flatMapLatest { settings ->
            if (settings.assistants.isEmpty()) return@flatMapLatest flowOf(emptyMap())
            combine(
                settings.assistants.map { assistant ->
                    conversationRepo.getConversationsOfAssistant(assistant.id)
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
        _searchQuery
    ) { assistantId, query -> assistantId to query }
        .flatMapLatest { (assistantId, query) ->
            if (query.isBlank()) {
                conversationRepo.getConversationsOfAssistantPaging(assistantId)
            } else {
                conversationRepo.searchConversationsOfAssistantPaging(assistantId, query)
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

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
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
