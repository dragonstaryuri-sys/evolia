package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val appScope: me.rerere.rikkahub.AppScope
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)

    // Track if conversation data has been loaded from the service
    private val _isConversationLoaded = MutableStateFlow(false)
    val isConversationLoaded: StateFlow<Boolean> = _isConversationLoaded

    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
        .onEach {
            // Any emission from the service means we've successfully connected to the data stream
            _isConversationLoaded.value = true
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Conversation.dummy())

    var chatListInitialized by mutableStateOf(false)

    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val recentlyRestoredIds: StateFlow<Set<Uuid>> = chatService.recentlyRestoredIds

    private val _recentlyRestoredNodeIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val recentlyRestoredNodeIds: StateFlow<Set<Uuid>> = _recentlyRestoredNodeIds

    fun markNodesAsRestored(nodeIds: Set<Uuid>) {
        _recentlyRestoredNodeIds.value = _recentlyRestoredNodeIds.value + nodeIds
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            _recentlyRestoredNodeIds.value = _recentlyRestoredNodeIds.value - nodeIds
        }
    }

    init {
        chatService.addConversationReference(_conversationId)
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }
        // Move I/O to IO Dispatcher
        viewModelScope.launch(Dispatchers.IO) {
            context.writeStringPreference("lastConversationId", _conversationId.toString())
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatService.removeConversationReference(_conversationId)
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    @Suppress("UNCHECKED_CAST")
    val newChatStats: StateFlow<me.rerere.rikkahub.ui.components.chat.NewChatStats> = settings
        .flatMapLatest { currentSettings ->
            val assistantId = currentSettings.assistantId.toString()
            val baseFlow = kotlinx.coroutines.flow.combine(
                conversationRepo.getConversationCountFlow(),
                conversationRepo.getDailyActivityDatesFlow(),
                conversationRepo.getConversationHoursFlow(),
                conversationRepo.getConversationCountByAssistantFlow(assistantId)
            ) { totalChats, distinctDates, hours, assistantChats ->
                kotlin.Pair(Triple(totalChats, distinctDates, hours), assistantChats)
            }

            kotlinx.coroutines.flow.combine(baseFlow, conversationRepo.getMostUsedModelIdForAssistantFlow(assistantId)) { (base, assistantChats), mostUsedModelId ->
                val (totalChats, distinctDates, hours) = base
                val today = java.time.LocalDate.now()
                val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                val dates = (distinctDates as List<String>).mapNotNull {
                    try { java.time.LocalDate.parse(it, formatter) } catch (e: Exception) { null }
                }.sortedDescending()
                val hasChattedToday = dates.contains(today)
                val yesterday = today.minusDays(1)
                val startDate = when {
                    hasChattedToday -> today
                    dates.contains(yesterday) -> yesterday
                    else -> null
                }
                val streak = if (startDate != null) {
                    var count = 0
                    var current: java.time.LocalDate = startDate
                    while (dates.contains(current)) { count++; current = current.minusDays(1) }
                    count
                } else 0
                val timeLabel = calculateTimeLabel(hours as List<Int>)
                val modelName = mostUsedModelId?.let { id ->
                    try {
                        val uuid = kotlin.uuid.Uuid.parse(id)
                        currentSettings.providers.flatMap { it.models }.find { it.id == uuid }?.displayName
                    } catch (e: Exception) { null }
                }
                me.rerere.rikkahub.ui.components.chat.NewChatStats(dailyStreak = streak, totalChats = totalChats as Int, timeLabel = timeLabel, hasChattedToday = hasChattedToday, assistantChats = assistantChats, mostUsedModelName = modelName)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), me.rerere.rikkahub.ui.components.chat.NewChatStats())

    private fun calculateTimeLabel(hours: List<Int>): me.rerere.rikkahub.ui.pages.menu.TimeLabel {
        if (hours.isEmpty()) return me.rerere.rikkahub.ui.pages.menu.TimeLabel.DAYTIME_CHATTER
        var earlyBird = 0; var daytime = 0; var nightOwl = 0
        for (hour in hours) { when (hour) { in 5..10 -> earlyBird++; in 11..17 -> daytime++; else -> nightOwl++ } }
        return when { earlyBird >= daytime && earlyBird >= nightOwl -> me.rerere.rikkahub.ui.pages.menu.TimeLabel.EARLY_BIRD; daytime >= earlyBird && daytime >= nightOwl -> me.rerere.rikkahub.ui.pages.menu.TimeLabel.DAYTIME_CHATTER; else -> me.rerere.rikkahub.ui.pages.menu.TimeLabel.NIGHT_OWL }
    }

    val enableWebSearch = settings.map { settings ->
        val assistant = settings.assistants.find { it.id == settings.assistantId }
        when (assistant?.searchMode) {
            is me.rerere.rikkahub.data.model.AssistantSearchMode.Off -> false
            else -> true
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val currentSearchMode = settings.map { settings ->
        val assistant = settings.assistants.find { it.id == settings.assistantId }
        assistant?.searchMode ?: me.rerere.rikkahub.data.model.AssistantSearchMode.Off
    }.stateIn(viewModelScope, SharingStarted.Lazily, me.rerere.rikkahub.data.model.AssistantSearchMode.Off)

    fun updateAssistantSearchMode(searchMode: me.rerere.rikkahub.data.model.AssistantSearchMode) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val assistantId = settings.assistantId
                settings.copy(assistants = settings.assistants.map { if (it.id == assistantId) it.copy(searchMode = searchMode) else it })
            }
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Use distinctUntilChanged to prevent frequent flatMapLatest during message streaming
    private val currentAssistantIdFlow = conversation.map { it.assistantId }.distinctUntilChanged()

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(
            currentAssistantIdFlow,
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
                                if (after.conversation.isPinned) ConversationListItem.PinnedHeader
                                else {
                                    val date = after.conversation.updateAt.atZone(ZoneId.systemDefault()).toLocalDate()
                                    ConversationListItem.DateHeader(date = date, label = getDateLabel(date))
                                }
                            }
                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val date = after.conversation.updateAt.atZone(ZoneId.systemDefault()).toLocalDate()
                                    ConversationListItem.DateHeader(date = date, label = getDateLabel(date))
                                } else if (!after.conversation.isPinned) {
                                    val bDate = before.conversation.updateAt.atZone(ZoneId.systemDefault()).toLocalDate()
                                    val aDate = after.conversation.updateAt.atZone(ZoneId.systemDefault()).toLocalDate()
                                    if (bDate != aDate) ConversationListItem.DateHeader(date = aDate, label = getDateLabel(aDate))
                                    else null
                                } else null
                            }
                            else -> null
                        }
                    }
            }
            .catch { e -> e.printStackTrace(); emit(PagingData.empty()) }
            .cachedIn(viewModelScope)

    fun updateSearchQuery(query: String) { _searchQuery.value = query }

    val currentChatModel = settings.map { settings -> settings.getCurrentChatModel() }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val errorFlow: SharedFlow<Throwable> = chatService.errorFlow
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow
    val mcpManager = chatService.mcpManager

    val updateState: StateFlow<UiState<UpdateInfo>> = updateChecker.checkUpdate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settings.value
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    private suspend fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar
        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            context.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants.map { if (it.id == assistant.id) it.copy(chatModelId = model.id) else it })
            }
        }
    }

    fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true, isTemporaryChat: Boolean = false) {
        if (content.isEmptyInputMessage()) return
        val assistant = settings.value.assistants.find { it.id == settings.value.assistantId }
        val processedContent = if (assistant != null) {
            content.map { part -> when (part) { is UIMessagePart.Text -> part.copy(text = part.text.replaceRegexes(assistant = assistant, scope = AssistantAffectScope.USER, visual = false)); else -> part } }
        } else content
        chatService.sendMessage(_conversationId, processedContent, answer, isTemporaryChat)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        val assistant = settings.value.assistants.find { it.id == settings.value.assistantId }
        val processedParts = if (assistant != null) {
            parts.map { part -> when (part) { is UIMessagePart.Text -> part.copy(text = part.text.replaceRegexes(assistant = assistant, scope = AssistantAffectScope.USER, visual = false)); else -> part } }
        } else parts
        val newConversation = conversation.value.copy(
            messageNodes = conversation.value.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) return@map node
                val originalMessage = node.messages.find { it.id == messageId }
                node.copy(messages = node.messages + UIMessage(role = node.role, parts = processedParts, versionTag = originalMessage?.versionTag), selectIndex = node.messages.size)
            },
        )
        updateConversation(newConversation)
    }

    fun handleMessageTruncate() {
        viewModelScope.launch {
            val lastTruncateIndex = conversation.value.messageNodes.lastIndex + 1
            val newConversation = conversation.value.copy(truncateIndex = if (conversation.value.truncateIndex == lastTruncateIndex) -1 else lastTruncateIndex, title = "", chatSuggestions = emptyList())
            chatService.saveConversation(conversationId = _conversationId, conversation = newConversation)
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        val node = conversation.value.getMessageNodeByMessage(message)
        val nodes = conversation.value.messageNodes.subList(0, conversation.value.messageNodes.indexOf(node) + 1).map { messageNode ->
            messageNode.copy(messages = messageNode.messages.map { msg ->
                msg.copy(parts = msg.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> if (part.url.startsWith("file:")) context.createChatFilesByContents(listOf(part.url.toUri())).firstOrNull()?.let { part.copy(url = it.toString()) } ?: part else part
                        is UIMessagePart.Document -> if (part.url.startsWith("file:")) context.createChatFilesByContents(listOf(part.url.toUri())).firstOrNull()?.let { part.copy(url = it.toString()) } ?: part else part
                        is UIMessagePart.Video -> if (part.url.startsWith("file:")) context.createChatFilesByContents(listOf(part.url.toUri())).firstOrNull()?.let { part.copy(url = it.toString()) } ?: part else part
                        is UIMessagePart.Audio -> if (part.url.startsWith("file:")) context.createChatFilesByContents(listOf(part.url.toUri())).firstOrNull()?.let { part.copy(url = it.toString()) } ?: part else part
                        else -> part
                    }
                })
            })
        }
        val newConversation = Conversation(id = Uuid.random(), assistantId = conversation.value.assistantId, messageNodes = nodes)
        chatService.saveConversation(newConversation.id, newConversation)
        return newConversation
    }

    fun deleteMessage(message: UIMessage) {
        val relatedMessages = collectRelatedMessages(message)
        deleteMessageInternal(message)
        relatedMessages.forEach { deleteMessageInternal(it) }
        saveConversationAsync()
    }

    private fun deleteMessageInternal(message: UIMessage) {
        val conversation = conversation.value
        val node = conversation.getMessageNodeByMessageId(message.id) ?: return
        val nodeIndex = conversation.messageNodes.indexOf(node)
        if (nodeIndex == -1) return
        val deleteVersionTag = message.versionTag
        val turnStartIndex = conversation.messageNodes.subList(0, nodeIndex + 1).indexOfLast { it.role == me.rerere.ai.core.MessageRole.USER } + 1
        val turnEndIndex = conversation.messageNodes.subList(nodeIndex, conversation.messageNodes.size).indexOfFirst { it.role == me.rerere.ai.core.MessageRole.USER }.let { if (it == -1) conversation.messageNodes.size else nodeIndex + it }
        val newConversation = if (node.messages.size == 1 && deleteVersionTag == null) {
            conversation.copy(messageNodes = conversation.messageNodes.filterIndexed { index, _ -> index != nodeIndex })
        } else {
            val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, n ->
                val canDeleteByVersionTag = deleteVersionTag != null && index in turnStartIndex until turnEndIndex && n.role != me.rerere.ai.core.MessageRole.USER
                val newMessages = n.messages.filter { msg -> if (canDeleteByVersionTag && msg.versionTag == deleteVersionTag) false else msg.id != message.id }
                if (newMessages.isEmpty()) null
                else n.copy(messages = newMessages, selectIndex = if (n.selectIndex >= newMessages.size) newMessages.lastIndex else n.selectIndex)
            }
            conversation.copy(messageNodes = updatedNodes)
        }
        viewModelScope.launch { chatService.saveConversation(_conversationId, newConversation) }
    }

    private fun collectRelatedMessages(message: UIMessage): List<UIMessage> {
        val currentMessages = conversation.value.currentMessages
        val index = currentMessages.indexOfFirst { it.id == message.id }
        if (index == -1) return emptyList()
        val relatedMessages = hashSetOf<UIMessage>()
        for (i in index - 1 downTo 0) { if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) relatedMessages.add(currentMessages[i]) else break }
        for (i in index + 1 until currentMessages.size) { if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) relatedMessages.add(currentMessages[i]) else break }
        return relatedMessages.toList()
    }

    fun canPreserveVersionHistory(message: UIMessage): Boolean {
        val currentMessages = conversation.value.messageNodes.map { it.currentMessage }
        val index = currentMessages.indexOfFirst { it.id == message.id }
        if (index == -1) return false
        val lastUserIndex = currentMessages.subList(0, index + 1).indexOfLast { it.role == me.rerere.ai.core.MessageRole.USER }
        val turnStart = if (lastUserIndex >= 0) lastUserIndex else 0
        val turnEnd = currentMessages.subList(index, currentMessages.size).indexOfFirst { it.role == me.rerere.ai.core.MessageRole.USER }.let { if (it == -1) currentMessages.size else index + it }
        for (i in turnStart until turnEnd) { if (currentMessages[i].parts.any { it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult }) return false }
        return true
    }

    fun regenerateAtMessage(message: UIMessage, regenerateAssistantMsg: Boolean = true, forceWipe: Boolean = false) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg, forceWipe)
    }

    fun saveConversationAsync() { viewModelScope.launch { chatService.saveConversation(_conversationId, conversation.value) } }
    fun updateTitle(title: String) { viewModelScope.launch { chatService.saveConversation(_conversationId, conversation.value.copy(title = title)) } }
    fun deleteConversation(conversation: Conversation) { chatService.deleteConversation(conversation) }
    fun undoDeleteConversation(conversationId: Uuid) { chatService.undoDeleteConversation(conversationId) }
    fun updatePinnedStatus(conversation: Conversation) { viewModelScope.launch { conversationRepo.togglePinStatus(conversation.id) } }
    fun updateConversationTitle(conversation: Conversation, title: String) { viewModelScope.launch { conversationRepo.updateConversation(conversation.copy(title = title)) } }
    fun generateTitle(conversation: Conversation, force: Boolean = false) { viewModelScope.launch { val full = conversationRepo.getConversationById(conversation.id) ?: return@launch; chatService.generateTitle(_conversationId, full, force) } }
    fun consolidateConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.markAsNotConsolidated(conversation.id)
            val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>().setInputData(androidx.work.workDataOf("FORCE_CONVERSATION_ID" to conversation.id.toString())).build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }
    fun updateConversation(newConversation: Conversation) { viewModelScope.launch { chatService.saveConversation(_conversationId, newConversation) } }
    fun deleteFile(uri: Uri) { appScope.launch { context.deleteChatFiles(listOf(uri)) } }
    suspend fun refreshContext(): ChatService.ContextRefreshResult { return chatService.summarizeAndRefresh(_conversationId) }

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
