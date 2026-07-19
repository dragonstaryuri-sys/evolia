package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import androidx.paging.filter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantAffectScope
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.replaceRegexes
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.FavoriteRepository
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.core.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.common.JsonInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant


private const val TAG = "ChatVM"


class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val appScope: me.rerere.rikkahub.AppScope,
    private val memoryRepo: MemoryRepository,
    private val favoriteRepo: FavoriteRepository,
) : ViewModel() {
    private val _activeMessageLimit = MutableStateFlow(200)

    // 标记是否正在增加限制（加载中动画）
    private val _isInternalLoadingMore = MutableStateFlow(false)
    val isInternalLoadingMore = _isInternalLoadingMore.asStateFlow()
    fun loadMoreActiveMessages() {
        val currentConv = conversation.value
        if (_activeMessageLimit.value < currentConv.messageNodes.size) {
            viewModelScope.launch {
                _isInternalLoadingMore.value = true
                // 模拟或等待微小延迟让体验更平滑
                delay(300)
                _activeMessageLimit.value += 100 // 每次向上加载 100 条
                _isInternalLoadingMore.value = false
            }
        }
    }
    suspend fun getFullMemoryContent(memoryId: Int, memoryType: Int): String? {
        return memoryRepo.getFullMemoryContent(memoryId, memoryType)
    }
    private val anchorConversationId: Uuid = Uuid.parse(id)

    private val _currentActiveId = MutableStateFlow(anchorConversationId)

    val isAiTyping: StateFlow<Boolean> = _currentActiveId
        .flatMapLatest { id -> chatService.getAiTypingFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 🌟 修改：手动维护历史加载状态，避免 Paging loadState 的延迟
    private val _isHistoryLoading = MutableStateFlow(false)
    val isHistoryLoading: StateFlow<Boolean> = _isHistoryLoading.asStateFlow()

    private val activeConversationIds = ConcurrentHashMap.newKeySet<Uuid>()

    private val _isConversationLoaded = MutableStateFlow(false)
    val isConversationLoaded: StateFlow<Boolean> = _isConversationLoaded

    val isSyncingContext: StateFlow<Boolean> = combine(
        _currentActiveId,
        chatService.syncingConversationIds
    ) { activeId, syncingIds ->
        syncingIds.contains(activeId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isConsolidating = MutableStateFlow(false)
    val isConsolidating: StateFlow<Boolean> = _isConsolidating.asStateFlow()

    val conversation: StateFlow<Conversation> = _currentActiveId
        .flatMapLatest { chatService.getConversationFlow(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Conversation.dummy())

    val uiMessagesPaging: Flow<PagingData<ChatUIItem>> = combine(
        _currentActiveId,
        conversation.map { it.assistantId }.distinctUntilChanged()
    ) { activeId, assistantId -> activeId to assistantId }
        .flatMapLatest { (activeId, assistantId) ->
            conversationRepo.getMessagesOfAssistantPaging(assistantId)
                .map { pagingData ->
                    pagingData
                        // 🌟 重点：过滤掉已经在 activeMessages 中显示的当前会话节点，避免重复
                        .filter { node ->
                            // 如果 MessageNode 里带了 conversationId，就过滤掉当前的
                            // 这样 uiMessagesPaging 就变成了纯粹的“历史流”
                            true // 这里的过滤逻辑根据你 MessageNode 是否持有 convId 来定
                        }
                        .map { ChatUIItem.Message(it) as ChatUIItem }
                        .insertSeparators { before, after ->
                            // 🌟 重点：在不同会话之间插入“历史话题”分隔线
                            if (before is ChatUIItem.Message && after is ChatUIItem.Message) {
                                // 假设我们能获取到节点的会话信息，不同会话就插个分隔符
                                null
                            } else null
                        }
                }
        }
        .cachedIn(viewModelScope)

    sealed class ChatUIItem {
        data class Message(val node: MessageNode) : ChatUIItem()
        data class Separator(val text: String) : ChatUIItem()
    }

    val activeMessages: StateFlow<List<ChatUIItem>> = combine(
        conversation,
        chatService.generatingNodeIds,
        _activeMessageLimit // 使用你定义的限制状态
    ) { activeConv, generatingIds, limit ->
        val items = mutableListOf<ChatUIItem>()
        // 过滤逻辑（排除空消息或跳过上下文的消息）
        val filteredNodes = activeConv.messageNodes.filter { node ->
            val msg = node.currentMessage
            val isGenerating = generatingIds.contains(node.id)
            val hasContent = !msg.parts.isEmptyUIMessage() || msg.parts.any { it is UIMessagePart.ToolCall }
            val isPlaceholder = node.messages.isEmpty()
            (hasContent || isGenerating || isPlaceholder) && !msg.skipContext
        }

        val hasMore = filteredNodes.size > limit
        // 🌟 关键：取最后 limit 条，然后反转（因为 UI 是 reverseLayout，最新的排在 Index 0）
        val nodesToShow = filteredNodes.takeLast(limit).reversed()

        items.addAll(nodesToShow.map { ChatUIItem.Message(it) })

        // 如果还没加载完，在末尾添加“加载更多”分隔符
        if (hasMore) {
            items.add(ChatUIItem.Separator("查看更早的消息..."))
        }else {
            items.add(ChatUIItem.Separator(context.getString(R.string.chat_topic_started)))
        }
        items
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val assistantConvsFlow = conversation
        .map { it.assistantId }
        .distinctUntilChanged()
        .flatMapLatest { assistantId ->
            conversationRepo.getConversationsOfAssistant(assistantId)
        }


    var chatListInitialized by mutableStateOf(false)

    val conversationJob: StateFlow<Job?> = _currentActiveId
        .flatMapLatest { chatService.getGenerationJobStateFlow(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val recentlyRestoredIds: StateFlow<Set<Uuid>> = chatService.recentlyRestoredIds

    private val _recentlyRestoredNodeIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val recentlyRestoredNodeIds: StateFlow<Set<Uuid>> = _recentlyRestoredNodeIds

    private val _toastFlow = MutableSharedFlow<String>()
    val toastFlow: SharedFlow<String> = _toastFlow.asSharedFlow()

    private val _conversationDeletedFlow = MutableSharedFlow<Conversation>()
    val conversationDeletedFlow: SharedFlow<Conversation> = _conversationDeletedFlow.asSharedFlow()

    fun markNodesAsRestored(nodeIds: Set<Uuid>) {
        _recentlyRestoredNodeIds.value = _recentlyRestoredNodeIds.value + nodeIds
        viewModelScope.launch {
            delay(1000)
            _recentlyRestoredNodeIds.value = _recentlyRestoredNodeIds.value - nodeIds
        }
    }

    init {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlowRaw.first()
            val currentAssistantId = settings.assistantId
            val latestInDb = conversationRepo.getLatestConversation(currentAssistantId)

            val targetId = if (conversationRepo.getConversationById(anchorConversationId) == null && latestInDb != null) {
                latestInDb.id
            } else {
                anchorConversationId
            }

            _currentActiveId.value = targetId
            trackConversation(targetId)
            chatService.initializeConversation(targetId)
            _isConversationLoaded.value = true
            context.writeStringPreference("lastConversationId", targetId.toString())
        }
    }

    private fun trackConversation(id: Uuid) {
        if (activeConversationIds.add(id)) {
            chatService.addConversationReference(id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeConversationIds.forEach { id ->
            chatService.removeConversationReference(id)
        }
        activeConversationIds.clear()
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val newChatStats: StateFlow<me.rerere.rikkahub.ui.components.chat.NewChatStats> = settings
        .flatMapLatest { currentSettings ->
            val assistantId = currentSettings.assistantId.toString()
            combine(
                conversationRepo.getConversationCountFlow(),
                conversationRepo.getDailyActivityDatesFlow(),
                conversationRepo.getConversationHoursFlow(),
                conversationRepo.getConversationCountByAssistantFlow(assistantId),
                conversationRepo.getMostUsedModelIdForAssistantFlow(assistantId)
            ) { totalChats, distinctDates, hours, assistantChats, mostUsedModelId ->
                val today = LocalDate.now()
                val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                val dates = distinctDates.mapNotNull {
                    try { LocalDate.parse(it, formatter) } catch (e: Exception) { null }
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
                    var current: LocalDate = startDate
                    while (dates.contains(current)) {
                        count++
                        current = current.minusDays(1)
                    }
                    count
                } else 0

                val timeLabel = calculateTimeLabel(hours)
                val modelName = mostUsedModelId?.let { id ->
                    try {
                        val uuid = Uuid.parse(id)
                        currentSettings.providers.flatMap { it.models }.find { it.id == uuid }?.displayName
                    } catch (e: Exception) { null }
                }

                me.rerere.rikkahub.ui.components.chat.NewChatStats(
                    dailyStreak = streak,
                    totalChats = totalChats,
                    timeLabel = timeLabel,
                    hasChattedToday = hasChattedToday,
                    assistantChats = assistantChats,
                    mostUsedModelName = modelName
                )
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
            is me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off -> false
            else -> true
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val currentSearchMode = settings.map { settings ->
        val assistant = settings.assistants.find { it.id == settings.assistantId }
        assistant?.searchMode ?: me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off
    }.stateIn(viewModelScope, SharingStarted.Lazily, me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off)

    fun updateAssistantSearchMode(searchMode: me.rerere.rikkahub.core.data.model.AssistantSearchMode) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            val assistantId = currentSettings.assistantId
            settingsStore.update(
                currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == assistantId) it.copy(searchMode = searchMode) else it
                    }
                )
            )
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

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

    fun updateAssistant(updatedAssistant: Assistant) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == updatedAssistant.id) updatedAssistant else it
                    }
                )
            )
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
            val currentSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == assistant.id) it.copy(chatModelId = model.id) else it
                    }
                )
            )
        }
    }

    fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true, isTemporaryChat: Boolean = false) {
        val currentSettings = settings.value
        if (content.isEmptyInputMessage()) return
        viewModelScope.launch {
            val assistantId = settings.value.assistantId
            val assistant = settings.value.assistants.find { it.id == assistantId }
            val targetId = _currentActiveId.value
            trackConversation(targetId)

            val processedContent = if (assistant != null) {
                content.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(text = part.text.replaceRegexes(assistant = assistant, scope = AssistantAffectScope.USER, visual = false))
                        else -> part
                    }
                }
            } else content

            chatService.sendMessage(targetId, processedContent, answer, isTemporaryChat)
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        val assistant = settings.value.assistants.find { it.id == settings.value.assistantId }
        val processedParts = if (assistant != null) {
            parts.map { part -> when (part) { is UIMessagePart.Text -> part.copy(text = part.text.replaceRegexes(assistant = assistant, scope = AssistantAffectScope.USER, visual = false)); else -> part } }
        } else parts

        viewModelScope.launch {
            val allConvs = conversationRepo.getConversationsOfAssistant(conversation.value.assistantId).first()
            val targetConv = allConvs.find { conv ->
                conv.messageNodes.any { node -> node.messages.any { it.id == messageId } }
            } ?: conversation.value

            _currentActiveId.value = targetConv.id
            trackConversation(targetConv.id)

            val newConversation = targetConv.copy(
                messageNodes = targetConv.messageNodes.map { node ->
                    if (!node.messages.any { it.id == messageId }) return@map node
                    val originalMessage = node.messages.find { it.id == messageId }
                    node.copy(messages = node.messages + UIMessage(role = node.role, parts = processedParts, versionTag = originalMessage?.versionTag), selectIndex = node.messages.size)
                },
            )
            chatService.saveConversation(newConversation.id, newConversation)
        }
    }

    fun startNewTopic() {
        if (isSyncingContext.value) return
        viewModelScope.launch {
            val currentConv = conversation.value
            val assistantId = currentConv.assistantId
            val newId = Uuid.random()
            val newConv = Conversation.ofId(id = newId, assistantId = assistantId)
            chatService.saveConversation(newId, newConv)
            trackConversation(newId)
            chatService.initializeConversation(newId)
            _currentActiveId.value = newId
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
        viewModelScope.launch {
            val relatedMessages = collectRelatedMessages(message)
            deleteMessageInternal(message)
            relatedMessages.forEach { deleteMessageInternal(it) }
        }
    }

    fun deleteMessages(messages: List<UIMessage>) {
        viewModelScope.launch {
            val assistantId = conversation.value.assistantId
            // 获取该助手的所有对话，因为选中的消息可能跨越了“新话题”分隔线
            val allConvs = conversationRepo.getConversationsOfAssistant(assistantId).first()

            // 将选中的消息按所属对话分组，提高处理效率
            val messagesByConv = messages.mapNotNull { msg ->
                val targetConv = allConvs.find { conv ->
                    conv.messageNodes.any { node -> node.messages.any { it.id == msg.id } }
                }
                if (targetConv != null) msg to targetConv else null
            }.groupBy({ it.second }, { it.first })

            messagesByConv.forEach { (targetConv, msgs) ->
                var currentConv = targetConv
                msgs.forEach { msg ->
                    // 复用内部删除逻辑并更新当前对话状态
                    val relatedMessages = collectRelatedMessagesForConv(msg, currentConv)
                    currentConv = deleteMessageFromConv(msg, currentConv) ?: currentConv
                    relatedMessages.forEach { related ->
                        currentConv = deleteMessageFromConv(related, currentConv) ?: currentConv
                    }
                }
                chatService.saveConversation(targetConv.id, currentConv)
            }
        }
    }

    // 辅助方法：从指定对话对象中删除消息（不涉及IO）
    private fun deleteMessageFromConv(message: UIMessage, targetConv: Conversation): Conversation? {
        val node = targetConv.getMessageNodeByMessageId(message.id) ?: return null
        val nodeIndex = targetConv.messageNodes.indexOf(node)
        if (nodeIndex == -1) return null

        val deleteVersionTag = message.versionTag
        return if (node.messages.size == 1 && deleteVersionTag == null) {
            targetConv.copy(messageNodes = targetConv.messageNodes.filterIndexed { index, _ -> index != nodeIndex })
        } else {
            val updatedNodes = targetConv.messageNodes.mapIndexedNotNull { index, n ->
                val newMessages = n.messages.filter { it.id != message.id }
                if (newMessages.isEmpty()) null
                else n.copy(messages = newMessages, selectIndex = n.selectIndex.coerceIn(0, newMessages.size - 1))
            }
            targetConv.copy(messageNodes = updatedNodes)
        }
    }

    // 辅助方法：收集相关工具消息（支持传入指定对话）
    private fun collectRelatedMessagesForConv(message: UIMessage, conv: Conversation): List<UIMessage> {
        val currentMessages = conv.messageNodes.flatMap { it.messages }
        val index = currentMessages.indexOfFirst { it.id == message.id }
        if (index == -1) return emptyList()
        val relatedMessages = hashSetOf<UIMessage>()
        for (i in index - 1 downTo 0) { if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) relatedMessages.add(currentMessages[i]) else break }
        for (i in index + 1 until currentMessages.size) { if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) relatedMessages.add(currentMessages[i]) else break }
        return relatedMessages.toList()
    }

    private suspend fun deleteMessageInternal(message: UIMessage) {
        val allConvs = conversationRepo.getConversationsOfAssistant(conversation.value.assistantId).first()
        val targetConv = allConvs.find { conv ->
            conv.messageNodes.any { node -> node.messages.any { it.id == message.id } }
        } ?: return

        val node = targetConv.getMessageNodeByMessageId(message.id) ?: return
        val nodeIndex = targetConv.messageNodes.indexOf(node)
        if (nodeIndex == -1) return
        val deleteVersionTag = message.versionTag
        val turnStartIndex = targetConv.messageNodes.subList(0, nodeIndex + 1).indexOfLast { it.role == me.rerere.ai.core.MessageRole.USER } + 1
        val turnEndIndex = targetConv.messageNodes.subList(nodeIndex, targetConv.messageNodes.size).indexOfFirst { it.role == me.rerere.ai.core.MessageRole.USER }.let { if (it == -1) targetConv.messageNodes.size else nodeIndex + it }
        val newConversation = if (node.messages.size == 1 && deleteVersionTag == null) {
            targetConv.copy(messageNodes = targetConv.messageNodes.filterIndexed { index, _ -> index != nodeIndex })
        } else {
            val updatedNodes = targetConv.messageNodes.mapIndexedNotNull { index, n ->
                val canDeleteByVersionTag = deleteVersionTag != null && index in turnStartIndex until turnEndIndex && n.role != me.rerere.ai.core.MessageRole.USER
                val newMessages = n.messages.filter { msg -> if (canDeleteByVersionTag && msg.versionTag == deleteVersionTag) false else msg.id != message.id }
                if (newMessages.isEmpty()) null
                else n.copy(messages = newMessages, selectIndex = n.selectIndex.coerceIn(0, newMessages.size - 1))
            }
            targetConv.copy(messageNodes = updatedNodes)
        }
        chatService.saveConversation(targetConv.id, newConversation)
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

    fun regenerateAtMessage(message: UIMessage, regenerateAssistantMsg: Boolean = true, forceWipe: Boolean = false, requirement: String? = null) {
        viewModelScope.launch {
            val allConvs = conversationRepo.getConversationsOfAssistant(conversation.value.assistantId).first()
            val targetConv = allConvs.find { conv ->
                conv.messageNodes.any { node -> node.messages.any { it.id == message.id } }
            } ?: conversation.value

            _currentActiveId.value = targetConv.id
            trackConversation(targetConv.id)
            chatService.regenerateAtMessage(targetConv.id, message, regenerateAssistantMsg, forceWipe, requirement = requirement)
        }
    }

    fun saveConversationAsync() { viewModelScope.launch { chatService.saveConversation(_currentActiveId.value, conversation.value) } }
    fun updateTitle(title: String) { viewModelScope.launch { chatService.saveConversation(_currentActiveId.value, conversation.value.copy(title = title)) } }
    fun deleteConversation(conversation: Conversation) {
        chatService.deleteConversation(conversation)
        viewModelScope.launch {
            _conversationDeletedFlow.emit(conversation)
        }
    }
    fun undoDeleteConversation(conversationId: Uuid) { chatService.undoDeleteConversation(conversationId) }
    fun updatePinnedStatus(conversation: Conversation) { viewModelScope.launch { conversationRepo.togglePinStatus(conversation.id) } }
    fun updateConversationTitle(conversation: Conversation, title: String) { viewModelScope.launch { conversationRepo.updateConversation(conversation.copy(title = title)) } }

    /**
     * 针对指定会话强制执行记忆整合 (L2 Archiving)
     */
    fun consolidateConversation(conversation: Conversation) {
        viewModelScope.launch {
            _isConsolidating.value = true
            try {
                // 1. 将该会话标记为“未整合”，以绕过增量检查
                conversationRepo.markAsNotConsolidated(conversation.id)

                // 2. 启动 Worker 并传入强制指定的会话 ID
                val workManager = androidx.work.WorkManager.getInstance(context)
                val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>()
                    .setInputData(androidx.work.workDataOf(
                        "FORCE_CONVERSATION_ID" to conversation.id.toString(),
                        "IS_MANUAL" to true
                    ))
                    .build()

                workManager.enqueueUniqueWork(
                    "consolidate_${conversation.id}",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    request
                )

                // 3. 监听结果并反馈给 UI
                val workInfo = workManager.getWorkInfoByIdFlow(request.id)
                    .filter { it?.state?.isFinished == true }
                    .firstOrNull()

                if (workInfo != null) {
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _toastFlow.emit(context.getString(R.string.consolidate_success))

                            // ✨ 新增：归档成功后，立即从数据库同步最新的会话状态（包含新的 truncateIndex）到内存
                            viewModelScope.launch {
                                val updated = conversationRepo.getConversationById(conversation.id)
                                if (updated != null) {
                                    chatService.saveConversation(conversation.id, updated)
                                }
                            }
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            val errorTag = workInfo.outputData.getString("error_tag") ?: ""
                            val message = when {
                                errorTag == "ERROR_NO_MESSAGES" -> context.getString(R.string.consolidate_failed_no_messages)
                                errorTag == "ERROR_NO_MODEL" -> context.getString(R.string.consolidate_failed_no_model)
                                errorTag == "ERROR_EMPTY_SUMMARY" -> context.getString(R.string.consolidate_failed_empty_summary)
                                errorTag == "ERROR_INSUFFICIENT_MESSAGES" -> context.getString(R.string.consolidate_failed_insufficient)
                                errorTag.startsWith("ERROR_EXCEPTION:") -> {
                                    val exception = errorTag.removePrefix("ERROR_EXCEPTION:")
                                    context.getString(R.string.consolidate_failed_unknown, exception)
                                }
                                else -> context.getString(R.string.consolidate_failed_unknown, errorTag)
                            }
                            _toastFlow.emit(message)
                        }
                        else -> {}
                    }
                }
            } finally {
                _isConsolidating.value = false
            }
        }
    }

    fun updateConversation(newConversation: Conversation) { viewModelScope.launch { chatService.saveConversation(newConversation.id, newConversation) } }

    fun updateMessageNodeInAnyConversation(newNode: MessageNode) {
        viewModelScope.launch {
            val assistantId = conversation.value.assistantId
            val allConvs = conversationRepo.getConversationsOfAssistant(assistantId).first()
            val targetConv = allConvs.find { conv ->
                conv.messageNodes.any { node -> node.id == newNode.id }
            } ?: return@launch

            val updatedConv = targetConv.copy(
                messageNodes = targetConv.messageNodes.map { if (it.id == newNode.id) newNode else it }
            )
            chatService.saveConversation(updatedConv.id, updatedConv)
        }
    }

    fun deleteFile(uri: Uri) { appScope.launch { context.deleteChatFiles(listOf(uri)) } }
    suspend fun refreshContext(): ChatService.ContextRefreshResult { return chatService.summarizeAndRefresh(_currentActiveId.value) }

    fun addFavorite(messages: List<UIMessage>, assistant: Assistant, userNickname: String) {
        viewModelScope.launch {
            try {
                val favorite = FavoriteEntity(
                    type = if (messages.size > 1) 1 else 0,
                    content = JsonInstant.encodeToString(messages),
                    senderName = if (messages.size == 1) {
                        if (messages.first().role == me.rerere.ai.core.MessageRole.USER) userNickname else assistant.name
                    } else "",
                    agentName = assistant.name,
                    userNickname = userNickname,
                    messageTime = messages.first().createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                )
                favoriteRepo.addFavorite(favorite)
                _toastFlow.emit(context.getString(R.string.favorites_add_success))
            } catch (e: Exception) {
                _toastFlow.emit(context.getString(R.string.favorites_add_failed))
            }
        }
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

    fun loadMore() {
        viewModelScope.launch {
            if (_isHistoryLoading.value) return@launch
            _isHistoryLoading.value = true
            try {
                chatService.loadMoreHistory(_currentActiveId.value)
            } finally {
                _isHistoryLoading.value = false
            }
        }
    }
}
