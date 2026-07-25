package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
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
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.core.data.db.entity.FavoriteEntity
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val targetMessageId: String? = null,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val appScope: me.rerere.rikkahub.AppScope,
    private val memoryRepo: MemoryRepository,
    private val favoriteRepo: FavoriteRepository,
) : ViewModel() {

    // --- 分页管理器管理 ---
    private var paginationManager: ConversationRepository.MessagePaginationManager? = null
    private val _managerFlow = MutableStateFlow<ConversationRepository.MessagePaginationManager?>(null)

    // 暴露分页状态流
    val chatPaginationState: StateFlow<ConversationRepository.ChatPaginationState?> = _managerFlow
        .flatMapLatest { manager ->
            manager?.state ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun triggerLoadOlder() {
        viewModelScope.launch { paginationManager?.loadOlder() }
    }

    fun triggerLoadNewer() {
        viewModelScope.launch { paginationManager?.loadNewer() }
    }

    fun retryPagination() {
        viewModelScope.launch { paginationManager?.retry() }
    }

    private fun initPaginationManager(assistantId: Uuid, targetMessageId: String? = null) {
        destroyPaginationManager()
        val manager = conversationRepo.createPaginationManager(assistantId)
        paginationManager = manager
        _managerFlow.value = manager
        viewModelScope.launch {
            if (targetMessageId.isNullOrBlank()) {
                manager.loadInitial()
            } else {
                manager.loadAroundMessage(targetMessageId)
            }
        }
    }

    fun destroyPaginationManager() {
        viewModelScope.launch {
            paginationManager?.clear()
        }
        paginationManager = null
        _managerFlow.value = null
    }

    // --- 原有状态保留 ---
    private val deletedNodeIds = MutableStateFlow<Set<Uuid>>(emptySet())

    suspend fun getFullMemoryContent(memoryId: Int, memoryType: Int): String? {
        return memoryRepo.getFullMemoryContent(memoryId, memoryType)
    }

    private val anchorConversationId: Uuid = Uuid.parse(id)
    private val _currentActiveId = MutableStateFlow(anchorConversationId)

    val isAiTyping: StateFlow<Boolean> = _currentActiveId
        .flatMapLatest { id -> chatService.getAiTypingFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    var chatListInitialized by mutableStateOf(false)

    val conversationJob: StateFlow<Job?> = _currentActiveId
        .flatMapLatest { chatService.getGenerationJobStateFlow(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

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

            val jumpConvId = if (!targetMessageId.isNullOrBlank()) {
                conversationRepo.chatMessageDAO.getConversationIdByMessageId(targetMessageId)?.let { Uuid.parse(it) }
            } else null

            val latestInDb = jumpConvId?.let {
                conversationRepo.getConversationById(it, targetMessageId)
            }
                ?: conversationRepo.getConversationById(anchorConversationId)
                ?: conversationRepo.getLatestConversation(currentAssistantId)

            val targetId = latestInDb?.id ?: anchorConversationId
            val assistantId = latestInDb?.assistantId ?: currentAssistantId

            _currentActiveId.value = targetId
            trackConversation(targetId)

            initPaginationManager(assistantId, targetMessageId)

            chatService.initializeConversation(
                conversationId = targetId,
                skipAutoArchive = !targetMessageId.isNullOrBlank()
            )

            _isConversationLoaded.value = true
        }

        // 监听 AI 生成新节点或新消息并注入
        viewModelScope.launch {
            conversation.map { it.messageNodes }
                .distinctUntilChanged { old, new ->
                    old.size == new.size &&
                        old.lastOrNull()?.id == new.lastOrNull()?.id &&
                        old.lastOrNull() == new.lastOrNull()
                }
                .collect { nodes ->
                    nodes.lastOrNull()?.let { node ->
                        // 只有当最新节点不在当前分页管理器中时才注入
                        paginationManager?.injectNewNode(node)
                    }
                }
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
        destroyPaginationManager()
    }

    // --- 以下为业务逻辑与原有代码保留 ---

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
            // 注入新发送的节点到窗口
            conversation.value.messageNodes.lastOrNull()?.let {
                paginationManager?.injectNewNode(it)
            }
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        val assistantId = settings.value.assistantId
        val assistant = settings.value.assistants.find { it.id == assistantId }
        val processedParts = if (assistant != null) {
            parts.map { part -> when (part) { is UIMessagePart.Text -> part.copy(text = part.text.replaceRegexes(assistant = assistant, scope = AssistantAffectScope.USER, visual = false)); else -> part } }
        } else parts

        viewModelScope.launch {
            val allConvs = conversationRepo.getConversationsOfAssistant(assistantId).first()
            val targetConv = allConvs.find { conv -> conv.messageNodes.any { node -> node.messages.any { it.id == messageId } } } ?: conversation.value

            if (_currentActiveId.value != targetConv.id) {
                _currentActiveId.value = targetConv.id
                trackConversation(targetConv.id)
                initPaginationManager(targetConv.assistantId)
            }

            val newConversation = targetConv.copy(
                messageNodes = targetConv.messageNodes.map { node ->
                    if (!node.messages.any { it.id == messageId }) return@map node
                    val originalMessage = node.messages.find { it.id == messageId }
                    node.copy(messages = node.messages + UIMessage(role = node.role, parts = processedParts, versionTag = originalMessage?.versionTag), selectIndex = node.messages.size)
                },
            )
            chatService.saveConversation(newConversation.id, newConversation)
            paginationManager?.loadInitial() // 编辑后刷新窗口
        }
    }

    fun startNewTopic() {
        if (isSyncingContext.value) return
        viewModelScope.launch {
            val assistantId = conversation.value.assistantId
            val newId = Uuid.random()
            trackConversation(newId)
            chatService.initializeConversation(newId, targetAssistantId = assistantId)
            _currentActiveId.value = newId
            initPaginationManager(assistantId)
        }
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            deleteMessageInternal(message)
            collectRelatedMessages(message).forEach { deleteMessageInternal(it) }
            delay(50)
            paginationManager?.loadInitial()
        }
    }

    fun deleteMessages(messages: List<UIMessage>) {
        viewModelScope.launch {
            val assistantId = conversation.value.assistantId
            val allConvs = conversationRepo.getConversationsOfAssistant(assistantId).first()
            val messagesByConv = messages.mapNotNull { msg ->
                val targetConv = allConvs.find { conv -> conv.messageNodes.any { node -> node.messages.any { it.id == msg.id } } }
                if (targetConv != null) msg to targetConv else null
            }.groupBy({ it.second }, { it.first })

            messagesByConv.forEach { (targetConv, msgs) ->
                var currentConv = targetConv
                // ✨ 记录已经处理过的消息 ID，避免重复处理（防止 A 关联 B，处理了 A 又处理 B）
                val processedIds = mutableSetOf<Uuid>()

                msgs.forEach { msg ->
                    if (msg.id in processedIds) return@forEach

                    // 找出当前消息及其关联消息（如工具调用）
                    val related = collectRelatedMessagesForConv(msg, currentConv)
                    val toDeleteList = (listOf(msg) + related).distinctBy { it.id }

                    toDeleteList.forEach { m ->
                        processedIds.add(m.id)

                        // 1. ✨ 执行物理删除 (核心修复)
                        val node = currentConv.getMessageNodeByMessageId(m.id)
                        if (node != null) {
                            // 如果节点内只剩这一条消息，直接删除整个节点
                            if (node.messages.size <= 1) {
                                conversationRepo.deleteNodes(listOf(node.id))
                            } else {
                                // 否则只标记该消息已删除
                                conversationRepo.markMessageAsDeleted(m.id)
                            }
                        }

                        // 2. 更新当前对话的内存快照，确保后续判断 node.messages.size 时是准的
                        currentConv = deleteMessageFromConv(m, currentConv) ?: currentConv
                    }
                }
                // 保存最终更新后的对话状态
                chatService.saveConversation(targetConv.id, currentConv)
            }

            // 给数据库一点写入时间并刷新 UI
            delay(50)
            paginationManager?.loadInitial()
        }
    }

    private fun deleteMessageFromConv(message: UIMessage, targetConv: Conversation): Conversation? {
        val node = targetConv.getMessageNodeByMessageId(message.id) ?: return null
        val nodeIndex = targetConv.messageNodes.indexOf(node)
        if (nodeIndex == -1) return null
        val deleteVersionTag = message.versionTag
        return if (node.messages.size == 1 && deleteVersionTag == null) {
            targetConv.copy(messageNodes = targetConv.messageNodes.filterIndexed { index, _ -> index != nodeIndex })
        } else {
            val updatedNodes = targetConv.messageNodes.mapIndexedNotNull { _, n ->
                val newMessages = n.messages.filter { it.id != message.id }
                if (newMessages.isEmpty()) null
                else n.copy(messages = newMessages, selectIndex = n.selectIndex.coerceIn(0, newMessages.size - 1))
            }
            targetConv.copy(messageNodes = updatedNodes)
        }
    }

    // 辅助方法：收集相关工具消息（支持传入指定对话）
    private fun collectRelatedMessagesForConv(message: UIMessage, conv: Conversation): List<UIMessage> {
        val msgs = conv.messageNodes.flatMap { it.messages }
        val idx = msgs.indexOfFirst { it.id == message.id }
        if (idx == -1) return emptyList()
        val related = hashSetOf<UIMessage>()
        for (i in idx - 1 downTo 0) { if (msgs[i].hasPart<UIMessagePart.ToolCall>() || msgs[i].hasPart<UIMessagePart.ToolResult>()) related.add(msgs[i]) else break }
        for (i in idx + 1 until msgs.size) { if (msgs[i].hasPart<UIMessagePart.ToolCall>() || msgs[i].hasPart<UIMessagePart.ToolResult>()) related.add(msgs[i]) else break }
        return related.toList()
    }

    private suspend fun deleteMessageInternal(message: UIMessage) {
        val assistantId = conversation.value.assistantId
        val allConvs = conversationRepo.getConversationsOfAssistant(assistantId).first()
        val targetConv = allConvs.find { conv -> conv.messageNodes.any { node -> node.messages.any { it.id == message.id } } } ?: return
        val node = targetConv.getMessageNodeByMessageId(message.id) ?: return
        val nodeIndex = targetConv.messageNodes.indexOf(node)
        if (node.messages.size == 1 && message.versionTag == null) {
            // 如果节点只有一条消息，直接删除整个节点
            conversationRepo.deleteNodes(listOf(node.id))
        } else {
            conversationRepo.markMessageAsDeleted(message.id)
        }
        val deleteVersionTag = message.versionTag
        val start = targetConv.messageNodes.subList(0, nodeIndex + 1).indexOfLast { it.role == me.rerere.ai.core.MessageRole.USER } + 1
        val end = targetConv.messageNodes.subList(nodeIndex, targetConv.messageNodes.size).indexOfFirst { it.role == me.rerere.ai.core.MessageRole.USER }.let { if (it == -1) targetConv.messageNodes.size else nodeIndex + it }
        val newConv = if (node.messages.size == 1 && deleteVersionTag == null) {
            targetConv.copy(messageNodes = targetConv.messageNodes.filterIndexed { index, _ -> index != nodeIndex })
        } else {
            val updated = targetConv.messageNodes.mapIndexedNotNull { index, n ->
                val canDelByTag = deleteVersionTag != null && index in start until end && n.role != me.rerere.ai.core.MessageRole.USER
                val newMsgs = n.messages.filter { msg -> if (canDelByTag && msg.versionTag == deleteVersionTag) false else msg.id != message.id }
                if (newMsgs.isEmpty()) null else n.copy(messages = newMsgs, selectIndex = n.selectIndex.coerceIn(0, newMsgs.size - 1))
            }
            targetConv.copy(messageNodes = updated)
        }
        chatService.saveConversation(targetConv.id, newConv)
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
            val assistantId = conversation.value.assistantId
            val allConvs = conversationRepo.getConversationsOfAssistant(assistantId).first()
            val targetConv = allConvs.find { conv -> conv.messageNodes.any { node -> node.messages.any { it.id == message.id } } } ?: conversation.value

            if (_currentActiveId.value != targetConv.id) {
                _currentActiveId.value = targetConv.id
                trackConversation(targetConv.id)
                initPaginationManager(targetConv.assistantId)
            }

            chatService.regenerateAtMessage(targetConv.id, message, regenerateAssistantMsg, forceWipe, requirement = requirement)
            paginationManager?.loadInitial() // 重置窗口
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
                            // ✨ 修复点：直接在当前协程等待同步完成，避免状态延迟
                            val updated = conversationRepo.getConversationById(conversation.id)
                            if (updated != null) {
                                chatService.saveConversation(conversation.id, updated)
                            }
                            _toastFlow.emit(context.getString(R.string.consolidate_success))
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
                    content = me.rerere.rikkahub.common.JsonInstant.encodeToString(messages),
                    senderName = if (messages.first().role == me.rerere.ai.core.MessageRole.USER) userNickname else assistant.name,
                    agentName = assistant.name,
                    userNickname = userNickname,
                    messageTime = messages.first().createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                )
                favoriteRepo.addFavorite(favorite)
                _toastFlow.emit(context.getString(R.string.favorites_add_success))
            } catch (e: Exception) { _toastFlow.emit(context.getString(R.string.favorites_add_failed)) }
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) { today -> context.getString(R.string.chat_page_today); yesterday -> context.getString(R.string.chat_page_yesterday); else -> date.toLocalString(date.year != today.year) }
    }
}
