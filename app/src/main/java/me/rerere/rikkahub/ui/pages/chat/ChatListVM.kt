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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
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
