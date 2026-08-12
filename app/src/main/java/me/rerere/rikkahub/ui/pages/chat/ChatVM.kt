package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
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
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.jsonPrimitiveOrNull
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantAffectScope
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.deleteMessages
import me.rerere.rikkahub.core.data.model.replaceRegexes
import me.rerere.rikkahub.core.data.model.restoreMessagesFrom
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.voice.VoiceCallManager
import me.rerere.rikkahub.service.voice.VoiceMessagePlayer
import me.rerere.rikkahub.data.ai.transformers.AudioToTextTransformer
import me.rerere.asr.provider.ASRManager
import me.rerere.rikkahub.ui.components.chat.CallStatus
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import me.rerere.rikkahub.core.data.db.entity.FavoriteEntity
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole

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
    private val voiceCallManager: VoiceCallManager,
    private val asrManager: ASRManager,
    val voiceMessagePlayer: VoiceMessagePlayer,
) : ViewModel() {

    // --- 通话状态暴露（直接转发 VoiceCallManager 的 StateFlow, 避免冗余复制） ---
    val callStatus: StateFlow<CallStatus> = voiceCallManager.callStatus
    val callIsMuted: StateFlow<Boolean> = voiceCallManager.isMuted
    val callIsSpeakerOn: StateFlow<Boolean> = voiceCallManager.isSpeakerOn
    val isCallActive: StateFlow<Boolean> = voiceCallManager.isActive
    val callError: SharedFlow<String> = voiceCallManager.callError

    // --- 正在后台 ASR 转写的 USER 节点 ID 集合（允许多条并行；集合非空 = 转写中） ---
    private val pendingASRNodeIds: MutableSet<Uuid> = ConcurrentHashMap.newKeySet<Uuid>()

    // --- 语音消息转写状态：有任一条 pending 即为 true（但**不再阻塞发送**，仅用于 UI 显示"转写中"） ---
    private val _voiceTranscribing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val voiceTranscribing: StateFlow<Boolean> = _voiceTranscribing.asStateFlow()
    private fun refreshVoiceTranscribingState() {
        _voiceTranscribing.value = pendingASRNodeIds.isNotEmpty()
    }

    // --- 语音消息相关错误事件（一次性，UI 用 toast 提示） ---
    private val _voiceEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val voiceEvents: SharedFlow<String> = _voiceEvents.asSharedFlow()

    // --- 语音条「转文字」显示状态：key = audioUrl，存在 VM 里避免 UI 刷新丢失 ---
    val shownTranscriptions: MutableSet<String> = mutableStateSetOf()

    // 是否有待触发 AI 的 USER 消息（当存在 pending ASR 时发送新消息会置 true，
    // pending ASR 全部完成后会统一 trigger 一次 AI，避免中间每来一条就触发一次）
    private val _hasPendingUserMessagesForAI = AtomicBoolean(false)

    /**
     * 调度器：决定是否现在就触发 AI 回复。
     *
     * 规则：
     * 1. 如果有 pending ASR（语音正在转写）→ 先不触发，记录"有待发送 USER 消息"。
     * 2. 否则立即 triggerAI。
     */
    private suspend fun maybeTriggerAIAfterAsr(targetId: Uuid, markPending: Boolean = true) {
        if (pendingASRNodeIds.isEmpty()) {
            // 没有待完成的 ASR → 立即触发
            chatService.triggerAIResponse(conversationId = targetId)
            _hasPendingUserMessagesForAI.set(false)
        } else {
            if (markPending) {
                _hasPendingUserMessagesForAI.set(true)
            }
            // 等 pendingASRNodeIds 被 ASR 完成流程清空后统一 trigger
        }
    }

    /**
     * 当一条 pending ASR 完成时调用。若集合已清空且有待触发的 USER 消息，
     * 就触发一次 AI（同一 turn 的多条 USER 消息 + 文字一次性发给 AI）。
     *
     * 注意：这是一个 suspend 函数，必须在协程体内**同步**调用，确保 mutateConversationAndSave
     * 完成后再执行 triggerAI（避免异步 launch 导致 ChatService 的对话快照还没刷新）。
     */
    private suspend fun onASRNodeCompleted(targetId: Uuid, nodeId: Uuid) {
        pendingASRNodeIds.remove(nodeId)
        refreshVoiceTranscribingState()
        if (pendingASRNodeIds.isEmpty() && _hasPendingUserMessagesForAI.compareAndSet(true, false)) {
            chatService.triggerAIResponse(conversationId = targetId)
        }
    }

    /**
     * 手动为指定 Audio part 发起一次 ASR 请求（长按菜单"转文字"使用）。
     *
     * 适用于：首次 ASR 失败后，用户手动长按语音条 → "转文字" → 重新发起一次。
     * 成功后更新对应 node 的 metadata，同时如果 shownTranscriptions 中已包含则自动显示。
     */
    fun manualTranscribeAudio(nodeId: Uuid, audioUrl: String) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            val targetId = _currentActiveId.value
            val asrProvider = currentSettings.getSelectedASRProvider()
            if (asrProvider == null) {
                _voiceEvents.emit(context.getString(R.string.chat_voice_no_asr_configured))
                return@launch
            }

            val audioUri = try { Uri.parse(audioUrl) } catch (_: Exception) {
                _voiceEvents.emit(context.getString(R.string.chat_voice_asr_failed))
                return@launch
            }

            // 不阻塞发送，仅转写 metadata
            pendingASRNodeIds.add(nodeId)
            refreshVoiceTranscribingState()
            val transcription: String? = try {
                asrManager.transcribeFile(asrProvider, context, audioUri)
                    .takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "manualTranscribeAudio: failed", e)
                _voiceEvents.emit(context.getString(R.string.chat_voice_asr_failed))
                null
            } finally {
                pendingASRNodeIds.remove(nodeId)
                refreshVoiceTranscribingState()
            }

            if (transcription == null) return@launch

            // 将转写结果写入对应 node 的 Audio metadata
            val durationMs = conversation.value.messageNodes
                .firstOrNull { it.id == nodeId }
                ?.currentMessage?.parts
                ?.filterIsInstance<UIMessagePart.Audio>()
                ?.firstOrNull { it.url == audioUrl }
                ?.metadata?.get(AudioToTextTransformer.METADATA_DURATION_MS)
                ?.jsonPrimitiveOrNull?.longOrNull ?: 0L

            chatService.mutateConversationAndSave(targetId) { current ->
                current.copy(
                    messageNodes = current.messageNodes.map { node ->
                        if (node.id != nodeId) return@map node
                        val updatedMessages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    if (part !is UIMessagePart.Audio || part.url != audioUrl) return@map part
                                    val newMetadata = buildJsonObject {
                                        put(AudioToTextTransformer.METADATA_DURATION_MS, durationMs)
                                        put(AudioToTextTransformer.METADATA_TRANSCRIPTION, transcription)
                                    }
                                    part.copy(metadata = newMetadata)
                                }
                            )
                        }
                        node.copy(messages = updatedMessages)
                    }
                )
            }
            paginationManager?.loadInitial()
            // 转写成功后自动显示在 UI 上（用户既然主动点"转文字"，就直接展示文本）
            shownTranscriptions.add(audioUrl)
        }
    }

    fun startCall(conversationId: Uuid) = voiceCallManager.startCall(conversationId)
    fun hangupCall() = voiceCallManager.hangup()
    fun toggleCallMute() = voiceCallManager.toggleMute()
    fun toggleCallSpeaker() = voiceCallManager.toggleSpeaker()

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

    /**
     * 立即停止当前会话的 AI 生成（同步清理状态，不依赖协程的异步 finally/invokeOnCompletion）。
     * 相比单纯 cancel conversationJob，能保证终止按钮和 isAiTyping 状态立即同步消失。
     */
    fun stopGeneration() {
        chatService.stopGeneration(_currentActiveId.value)
    }

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

        // 同步整个活跃会话。刷新一个多节点工具回合时，首节点的版本和后续
        // 工具节点都可能变化，不能只注入列表末尾的节点。
        viewModelScope.launch {
            conversation
                .map { active -> active.id to active.messageNodes }
                .distinctUntilChanged()
                .collect { (conversationId, nodes) ->
                    paginationManager?.syncConversationNodes(conversationId, nodes)
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

            chatService.sendMessage(
                conversationId = targetId,
                content = processedContent,
                answer = answer,
                isTemporaryChat = isTemporaryChat,
                includeSkipContextMessages = true
            )
            // 注入新发送的节点到窗口
            conversation.value.messageNodes.lastOrNull()?.let {
                paginationManager?.injectNewNode(it)
            }

            // 优化2：文字发送后也走调度器
            // 如果此时有语音 pending ASR，就等所有 ASR 完成后统一触发一次 AI（同一 turn 合并发送）
            // 如果没有 pending，就立即触发（原行为）
            if (answer) {
                maybeTriggerAIAfterAsr(targetId, markPending = true)
            }
        }
    }

    /**
     * 发送语音消息。
     *
     * 优化2：即使有 ASR 正在进行，也允许继续发送（不阻塞输入框/录音按钮）。
     * 所有同一 turn 的 USER 消息会在**所有 pending ASR 完成后**统一触发一次 AI，
     * 保证 ASR 结果能被正确拼接到发送给模型的上下文中，避免漏转写。
     *
     * 流程：
     * 1. 语音条**立即入列**显示（answer=false，不触发 AI）。
     * 2. 后台 ASR 转文字（每条语音独立记录 pending，允许并发多条）。
     * 3. ASR 完成后，通过 audioUrl 唯一匹配更新对应 Audio Part 的 metadata；
     *    当所有 pending ASR 清空且有待触发的 USER 消息时，统一 triggerAIResponse 一次。
     *
     * @param audioUri 录音文件 Uri（file:// 形式）
     * @param durationMs 录音时长（毫秒）
     */
    fun sendVoiceMessage(audioUri: Uri, durationMs: Long) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value

            val targetId = _currentActiveId.value
            trackConversation(targetId)

            // 1. 立即入列显示语音条（answer=false，暂不触发 AI）。
            // audioUrl 包含 UUID，可作为唯一匹配定位该 Audio Part。
            val audioUrl = audioUri.toString()
            val audioPart = UIMessagePart.Audio(
                url = audioUrl,
                metadata = buildJsonObject {
                    put(AudioToTextTransformer.METADATA_DURATION_MS, durationMs)
                }
            )
            chatService.sendMessage(
                conversationId = targetId,
                content = listOf(audioPart),
                answer = false,
                includeSkipContextMessages = true
            )
            // 定位刚插入节点的 nodeId。
            // 注意：ChatVM.conversation.value 是通过 StateFlow 异步收集的，可能还没刷新。
            // 必须从 ChatService 内部内存快照（getConversationFlow.value）同步读取最新数据。
            val serviceSnapshot = chatService.getConversationFlow(targetId).value
            val insertedNode = serviceSnapshot.messageNodes.lastOrNull { node ->
                node.currentMessage.role == MessageRole.USER &&
                    node.currentMessage.parts.any { p -> p is UIMessagePart.Audio && p.url == audioUrl }
            }
            val nodeId = insertedNode?.id
            // UI 注入刚入列的节点（让列表立即显示新语音条）：优先用 ChatService 快照的节点，
            // 否则回退到 ChatVM conversation 的最后一条节点。
            (insertedNode ?: conversation.value.messageNodes.lastOrNull())
                ?.let { paginationManager?.injectNewNode(it) }
            if (nodeId == null) {
                // 理论上不会发生：sendMessage 不抛异常就会插入节点
                chatService.triggerAIResponse(conversationId = targetId)
                return@launch
            }

            // 2. 加入 pending 集合，允许并发多条
            pendingASRNodeIds.add(nodeId)
            refreshVoiceTranscribingState()
            // 标记：本节点入列了，等 ASR 完成后考虑触发 AI
            // （即使 ASR 失败，这条消息也还是需要被 AI 看到，所以始终 set true）
            _hasPendingUserMessagesForAI.set(true)

            // 3. ASR 转文字（同一 viewModelScope 协程内挂起，串行完成后写 metadata → 触发）
            val asrProvider = currentSettings.getSelectedASRProvider()
            val transcription: String? = if (asrProvider != null) {
                try {
                    asrManager.transcribeFile(asrProvider, context, audioUri)
                        .takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "sendVoiceMessage: ASR failed", e)
                    _voiceEvents.emit(context.getString(R.string.chat_voice_asr_failed))
                    null
                }
            } else {
                _voiceEvents.emit(context.getString(R.string.chat_voice_no_asr_configured))
                null
            }

            // 4. 成功则写入该 Audio Part 的 metadata（按 audioUrl 精确匹配，避免并发覆盖）
            if (transcription != null) {
                chatService.mutateConversationAndSave(targetId) { current ->
                    current.copy(
                        messageNodes = current.messageNodes.map { node ->
                            val hasMatch = node.currentMessage.parts.any { p ->
                                p is UIMessagePart.Audio && p.url == audioUrl
                            }
                            if (!hasMatch) return@map node
                            val updatedMessages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        if (part !is UIMessagePart.Audio || part.url != audioUrl) return@map part
                                        val newMetadata = buildJsonObject {
                                            put(AudioToTextTransformer.METADATA_DURATION_MS, durationMs)
                                            put(AudioToTextTransformer.METADATA_TRANSCRIPTION, transcription)
                                        }
                                        part.copy(metadata = newMetadata)
                                    }
                                )
                            }
                            node.copy(messages = updatedMessages)
                        }
                    )
                }
                paginationManager?.loadInitial()
            }

            // 5. 移除 pending；若已清空且有待触发消息，则**同步**触发一次 AI
            onASRNodeCompleted(targetId, nodeId)
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
            val targetConv = findConversationContainingMessage(messageId) ?: return@launch

            if (_currentActiveId.value != targetConv.id) {
                _currentActiveId.value = targetConv.id
                trackConversation(targetConv.id)
                initPaginationManager(targetConv.assistantId)
            }

            chatService.mutateConversationAndSave(targetConv.id) { current ->
                current.copy(
                    messageNodes = current.messageNodes.map { node ->
                        if (!node.messages.any { it.id == messageId }) return@map node
                        val originalMessage = node.messages.find { it.id == messageId }
                        node.copy(
                            messages = node.messages + UIMessage(
                                role = node.role,
                                parts = processedParts,
                                versionTag = originalMessage?.versionTag
                            ),
                            selectIndex = node.messages.size
                        )
                    }
                )
            }
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
        deleteMessages(listOf(message))
    }

    fun deleteMessages(messages: List<UIMessage>) {
        if (messages.isEmpty()) return
        viewModelScope.launch {
            val messagesByConversation = linkedMapOf<Uuid, MutableSet<Uuid>>()
            messages.forEach { message ->
                val conversationId = findConversationIdContainingMessage(message.id) ?: return@forEach
                messagesByConversation.getOrPut(conversationId) { linkedSetOf() } += message.id
            }

            messagesByConversation.forEach { (conversationId, messageIds) ->
                chatService.replaceConversationMessages(conversationId) { current ->
                    current.deleteMessages(messageIds).takeIf { deletion ->
                        deletion.deletedMessageIds.isNotEmpty()
                    }
                }
            }
            paginationManager?.loadInitial()
        }
    }

    private suspend fun findConversationIdContainingMessage(messageId: Uuid): Uuid? {
        val active = conversation.value
        if (active.getMessageNodeByMessageId(messageId) != null) return active.id
        return conversationRepo.chatMessageDAO
            .getConversationIdByMessageId(messageId.toString())
            ?.let(Uuid::parse)
    }

    private suspend fun findConversationContainingMessage(messageId: Uuid): Conversation? {
        val conversationId = findConversationIdContainingMessage(messageId) ?: return null
        return if (conversation.value.id == conversationId) {
            conversation.value
        } else {
            conversationRepo.getConversationById(conversationId)
        }
    }

    fun canPreserveVersionHistory(message: UIMessage): Boolean {
        val currentMessages = conversation.value.messageNodes.mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.lastOrNull()
        }
        val index = currentMessages.indexOfFirst { it.id == message.id }
        if (index == -1) return false
        val lastUserIndex = currentMessages.subList(0, index + 1).indexOfLast { it.role == me.rerere.ai.core.MessageRole.USER }
        val turnStart = if (lastUserIndex >= 0) lastUserIndex else 0
        val turnEnd = currentMessages.subList(index, currentMessages.size).indexOfFirst { it.role == me.rerere.ai.core.MessageRole.USER }.let { if (it == -1) currentMessages.size else index + it }
        // 微信模式分句会把同一个 turn 拆成多个 MessageNode；版本分支逻辑只能在单个节点内追加 version。
        // 当 turn 内 Assistant/TOOL 节点超过 1 个时，无法仅通过"在同一节点里追加新 message version"完成 regenerate，
        // 必须走整 turn 删除 + 重生成（与 ToolCall 场景一致）。
        val assistantNodeCountInTurn = currentMessages.subList(turnStart, turnEnd).count {
            it.role == me.rerere.ai.core.MessageRole.ASSISTANT || it.role == me.rerere.ai.core.MessageRole.TOOL
        }
        if (assistantNodeCountInTurn > 1) return false
        for (i in turnStart until turnEnd) { if (currentMessages[i].parts.any { it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult }) return false }
        return true
    }

    fun regenerateAtMessage(message: UIMessage, regenerateAssistantMsg: Boolean = true, forceWipe: Boolean = false, requirement: String? = null) {
        viewModelScope.launch {
            val targetConv = findConversationContainingMessage(message.id) ?: return@launch

            if (_currentActiveId.value != targetConv.id) {
                _currentActiveId.value = targetConv.id
                trackConversation(targetConv.id)
                initPaginationManager(targetConv.assistantId)
            }

            chatService.regenerateAtMessage(
                conversationId = targetConv.id,
                message = message,
                regenerateAssistantMsg = regenerateAssistantMsg,
                forceWipe = forceWipe,
                requirement = requirement,
                includeSkipContextMessages = true // ✨ 设置为 true
            )
            paginationManager?.loadInitial() // 重置窗口
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.mutateConversationAndSave(_currentActiveId.value) { current ->
                current.copy(title = title)
            }
        }
    }
    fun deleteConversation(conversation: Conversation) {
        chatService.deleteConversation(conversation)
        viewModelScope.launch {
            _conversationDeletedFlow.emit(conversation)
        }
    }
    fun undoDeleteConversation(conversationId: Uuid) { chatService.undoDeleteConversation(conversationId) }
    fun updatePinnedStatus(conversation: Conversation) { viewModelScope.launch { conversationRepo.togglePinStatus(conversation.id) } }
    fun updateConversationTitle(conversation: Conversation, title: String) {
        viewModelScope.launch {
            chatService.mutateConversationAndSave(conversation.id) { current ->
                current.copy(title = title)
            }
        }
    }

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
                                chatService.mutateConversationAndSave(conversation.id) { current ->
                                    current.copy(
                                        isConsolidated = updated.isConsolidated,
                                        lastSummarizedMessageTime = updated.lastSummarizedMessageTime,
                                        lastPruneTime = updated.lastPruneTime,
                                        lastPruneMessageCount = updated.lastPruneMessageCount,
                                        lastRefreshTime = updated.lastRefreshTime,
                                        // 同步手动归档后写入 DB 的截断索引，否则内存态会保留旧值并在
                                        // saveConversation 时把 DB 里正确的 truncateIndex 覆盖回旧值，
                                        // 导致下次生成上下文时截断不生效（清理上下文失效）
                                        truncateIndex = updated.truncateIndex
                                    )
                                }
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

    fun updateConversation(newConversation: Conversation) {
        viewModelScope.launch {
            chatService.mutateConversationAndSave(newConversation.id) { current ->
                current.copy(enabledModeIds = newConversation.enabledModeIds)
            }
        }
    }

    fun restoreConversation(backup: Conversation) {
        viewModelScope.launch {
            chatService.mutateConversationAndSave(backup.id) { current ->
                current.restoreMessagesFrom(backup)
            }
        }
    }

    fun updateMessageNodeInAnyConversation(newNode: MessageNode) {
        viewModelScope.launch {
            chatService.mutateConversationAndSave(newNode.conversationId) { current ->
                if (current.messageNodes.none { it.id == newNode.id }) {
                    current
                } else {
                    current.copy(
                        messageNodes = current.messageNodes.map { node ->
                            if (node.id == newNode.id) newNode else node
                        }
                    )
                }
            }
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
