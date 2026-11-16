package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantAffectScope
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.deleteMessages
import me.rerere.rikkahub.core.data.model.replaceRegexes
import me.rerere.rikkahub.data.ai.transformers.AudioToTextTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.asr.provider.ASRManager
import me.rerere.asr.provider.ASRProviderSetting
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.voice.VoiceMessagePlayer
import me.rerere.rikkahub.ui.hooks.QuotedMessage
import me.rerere.rikkahub.utils.deleteChatFiles
import kotlin.uuid.Uuid

private const val TAG = "TemporaryChatVM"

/**
 * 临时会话 ViewModel：独立于正式 ChatVM 的短生命周期聊天空间。
 *
 * 职责边界（见 agents.md 临时会话架构）：
 * - 会话身份：进入时通过 [ChatService.createTemporarySession] 创建（固定助手、注册表登记），
 *   消息仅存在于 ChatService 内存 StateFlow，绝不写任何正式数据库表。
 * - 记忆只读：AI 可基于角色已有记忆回答（生成链路照常检索），
 *   写记忆工具在 GenerationHandler 构建层+执行层双重拦截，VM 层无需处理。
 * - 退出销毁：[requestDestroy] 由页面退出确认后调用；[onCleared] 兜底销毁，
 *   保证任何离开路径都不会遗留活跃临时会话与生成任务。
 * - 复用 ChatService 全部生成/工具/ASR 能力，不复制 Agent 实现。
 */
class TemporaryChatVM(
    assistantId: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val memoryRepo: me.rerere.rikkahub.core.data.repository.MemoryRepository,
    private val asrManager: ASRManager,
    val voiceMessagePlayer: VoiceMessagePlayer,
    private val appScope: me.rerere.rikkahub.AppScope,
) : ViewModel() {

    /** 创建临时会话（固定进入时的助手），此后所有消息读写都基于该 sessionId */
    private val sessionId: Uuid = chatService.createTemporarySession(Uuid.parse(assistantId))

    init {
        // 页面存续期间持有引用：防止生成结束 500ms 后的 checkAllConversationsReferences
        // 把消息缓存从 ChatService 内存逐出（与正式 ChatVM.trackConversation 同机制，
        // 双保险：ChatService 侧已另行排除临时会话）。onCleared 释放。
        chatService.addConversationReference(sessionId)
    }

    private val _toastFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastFlow: SharedFlow<String> = _toastFlow.asSharedFlow()

    /** 生成错误事件（透传 ChatService.errorFlow，UI 用 toast 提示） */
    val errorFlow: SharedFlow<Throwable> = chatService.errorFlow

    /** 右上滑"转文字到输入框"：转写录音文本（不发送消息） */
    fun transcribeVoiceToInput(audioUri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            val asrProvider = currentSettings.getSelectedASRProvider()
            if (asrProvider == null) {
                _toastFlow.emit(context.getString(R.string.chat_voice_no_asr_configured))
                onResult(null)
                return@launch
            }
            val text = try {
                asrManager.transcribeFile(asrProvider, context, audioUri).takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "transcribeVoiceToInput: failed", e)
                null
            }
            onResult(text)
        }
    }

    private val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottomEvent: SharedFlow<Unit> = _scrollToBottomEvent.asSharedFlow()

    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(sessionId)

    // --- 未读角标联动（状态唯一事实源在 ChatService） ---
    // 临时会话自身不参与未读计数（消息不落库、无未读记录），但必须登记「正在查看」：
    // 用户停留在临时会话期间，其他助手正式会话的回复应正确计入未读；
    // 离开（ON_PAUSE / 离开组合 / 销毁）时撤销登记。

    /** 进入组合 / 恢复 RESUMED 时登记「正在前台查看临时会话」。 */
    fun notifyTemporaryConversationViewed() {
        chatService.setActiveViewingConversation(sessionId)
    }

    /** 离开 RESUMED / 离开组合时撤销登记（仅当登记的仍是本临时会话）。 */
    fun notifyTemporaryConversationViewStopped() {
        if (chatService.activeViewingConversationId.value == sessionId) {
            chatService.setActiveViewingConversation(null)
        }
    }

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    /** 当前角色（只读身份）：始终是进入临时会话时固定的助手 */
    private val sessionAssistantId: Uuid = conversation.value.assistantId

    // ===== 会话内助手配置覆盖（仅内存态）=====
    // 世界书/推理预算/搜索/UI 模式等修改只作用于本次临时会话的展示与生成，
    // 绝不写回 SettingsStore（正式助手配置保持不变），退出销毁后自然失效。

    private val _sessionAssistantOverride = MutableStateFlow<Assistant?>(null)

    /** 当前生效助手 = 会话内覆盖值 ?: 进入时的助手（未做任何覆盖时与正式配置一致） */
    val assistant: StateFlow<Assistant?> = kotlinx.coroutines.flow.combine(
        settings.map { s -> s.getAssistantById(sessionAssistantId) },
        _sessionAssistantOverride,
    ) { base, override -> override ?: base }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 会话内助手配置更新（世界书/推理预算/UI模式等），仅内存态 */
    fun updateSessionAssistant(updated: Assistant) {
        _sessionAssistantOverride.value = updated
    }

    /** 会话内联网搜索开关/服务商切换，仅内存态 */
    fun updateSessionSearchMode(mode: me.rerere.rikkahub.core.data.model.AssistantSearchMode) {
        val current = _sessionAssistantOverride.value
            ?: settings.value.getAssistantById(sessionAssistantId) ?: return
        _sessionAssistantOverride.value = current.copy(searchMode = mode)
    }

    /** 当前生效助手快照（发送链路取用） */
    private fun effectiveAssistant(): Assistant? =
        _sessionAssistantOverride.value ?: settings.value.getAssistantById(sessionAssistantId)

    val isAiTyping: StateFlow<Boolean> = chatService.getAiTypingFlow(sessionId)
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val conversationJob: StateFlow<kotlinx.coroutines.Job?> = chatService.getGenerationJobStateFlow(sessionId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 会话中是否已有用户内容（决定退出时是否需要确认弹窗；预置消息不算） */
    fun hasUserContent(): Boolean =
        chatService.getConversationFlow(sessionId).value.messageNodes.any { it.role == MessageRole.USER }

    fun stopGeneration() = chatService.stopGeneration(sessionId)

    // ===== 临时会话内模型切换（仅内存态）=====
    // 切换只影响本次临时会话的生成与展示，绝不写回 Assistant.chatModelId 或全局设置；
    // 退出销毁后自然失效。null = 跟随助手/全局配置。

    private val _overrideChatModelId = MutableStateFlow<Uuid?>(null)
    val overrideChatModelId: StateFlow<Uuid?> = _overrideChatModelId.asStateFlow()

    fun setChatModel(model: me.rerere.ai.provider.Model) {
        _overrideChatModelId.value = model.id
    }

    /** 会话内模式启停（ModesPickerSheet）：只改 enabledModeIds 字段，随内存会话销毁 */
    fun updateConversationModes(newConversation: Conversation) {
        viewModelScope.launch {
            chatService.mutateConversationAndSave(sessionId) { current ->
                current.copy(enabledModeIds = newConversation.enabledModeIds)
            }
        }
    }

    /** MCP 工具管理器（供 ChatInput 展示/配置工具；临时会话能力受 GenerationHandler 层拦截约束） */
    val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager = chatService.mcpManager

    /** 语音条「转文字」显示状态：key = audioUrl（与正式聊天一致的交互） */
    val shownTranscriptions: MutableSet<String> = androidx.compose.runtime.mutableStateSetOf()

    /** 查看记忆详情（只读） */
    suspend fun getFullMemoryContent(memoryId: Int, memoryType: Int): String? =
        memoryRepo.getFullMemoryContent(memoryId, memoryType)

    /** 删除输入区引用的附件文件 */
    fun deleteFile(uri: Uri) {
        appScope.launch { context.deleteChatFiles(listOf(uri)) }
    }

    // ===== 消息发送 =====

    fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        viewModelScope.launch {
            val sessionAssistant = effectiveAssistant()
            val processedContent = if (sessionAssistant != null) {
                content.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(
                            text = part.text.replaceRegexes(
                                assistant = sessionAssistant,
                                scope = AssistantAffectScope.USER,
                                visual = false
                            )
                        )
                        else -> part
                    }
                }
            } else content

            chatService.sendMessage(
                conversationId = sessionId,
                content = processedContent,
                answer = answer,
                includeSkipContextMessages = true,
                chatModelOverrideId = _overrideChatModelId.value,
                assistantOverride = _sessionAssistantOverride.value
            )
            _scrollToBottomEvent.tryEmit(Unit)
            if (answer) {
                maybeTriggerAIAfterAsr(markPending = true)
            }
        }
    }

    // ===== 语音消息（紧凑实现：录音 → ASR 转写 → 触发 AI）=====
    // 与正式 ChatVM 的差异：不含录音期并行流式 SystemASR 优化，转写走 ASR provider
    // （SystemASR 自动 fallback EvoliaASR/OnlineASR）；其余 metadata 写入与
    // turn 合并触发语义保持一致。

    /** 正在后台 ASR 转写的 USER 节点 ID 集合（允许多条并行） */
    private val pendingASRNodeIds: MutableSet<Uuid> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 有 USER 消息等所有 pending ASR 完成后统一触发 AI */
    private val _hasPendingUserMessagesForAI = AtomicBoolean(false)

    /** ASR 串行锁：多条语音按发送顺序依次转写 */
    private val asrMutex = Mutex()

    private val _voiceTranscribing = MutableStateFlow(false)
    val voiceTranscribing: StateFlow<Boolean> = _voiceTranscribing.asStateFlow()

    private fun refreshVoiceTranscribingState() {
        _voiceTranscribing.value = pendingASRNodeIds.isNotEmpty()
    }

    private suspend fun maybeTriggerAIAfterAsr(markPending: Boolean = true) {
        if (pendingASRNodeIds.isEmpty()) {
            chatService.triggerAIResponse(
                conversationId = sessionId,
                chatModelOverrideId = _overrideChatModelId.value,
                assistantOverride = _sessionAssistantOverride.value
            )
            _hasPendingUserMessagesForAI.set(false)
        } else {
            if (markPending) {
                _hasPendingUserMessagesForAI.set(true)
            }
        }
    }

    private suspend fun onASRNodeCompleted(nodeId: Uuid) {
        pendingASRNodeIds.remove(nodeId)
        refreshVoiceTranscribingState()
        if (pendingASRNodeIds.isEmpty() && _hasPendingUserMessagesForAI.compareAndSet(true, false)) {
            chatService.triggerAIResponse(
                conversationId = sessionId,
                chatModelOverrideId = _overrideChatModelId.value,
                assistantOverride = _sessionAssistantOverride.value
            )
        }
    }

    fun sendVoiceMessage(
        audioUri: Uri,
        durationMs: Long,
        preTranscribedText: String? = null,
        quotedMessages: List<QuotedMessage> = emptyList(),
    ) {
        viewModelScope.launch {
            // 1. 语音条立即入列显示（answer=false，暂不触发 AI）
            val audioUrl = audioUri.toString()
            val audioPart = UIMessagePart.Audio(
                url = audioUrl,
                metadata = buildJsonObject {
                    put(AudioToTextTransformer.METADATA_DURATION_MS, durationMs)
                }
            )
            val quoteParts = quotedMessages.map { quote ->
                UIMessagePart.Quote(
                    senderName = quote.senderName,
                    content = quote.content,
                    isUser = quote.isUser,
                    displayContent = quote.displayContent
                )
            }
            chatService.sendMessage(
                conversationId = sessionId,
                content = quoteParts + listOf(audioPart),
                answer = false,
                includeSkipContextMessages = true,
                chatModelOverrideId = _overrideChatModelId.value,
                assistantOverride = _sessionAssistantOverride.value
            )
            val serviceSnapshot = chatService.getConversationFlow(sessionId).value
            val insertedNode = serviceSnapshot.messageNodes.lastOrNull { node ->
                node.currentMessage.role == MessageRole.USER &&
                    node.currentMessage.parts.any { p -> p is UIMessagePart.Audio && p.url == audioUrl }
            }
            val nodeId = insertedNode?.id
            _scrollToBottomEvent.tryEmit(Unit)
            if (nodeId == null) {
                chatService.triggerAIResponse(
                    conversationId = sessionId,
                    chatModelOverrideId = _overrideChatModelId.value,
                    assistantOverride = _sessionAssistantOverride.value
                )
                return@launch
            }

            pendingASRNodeIds.add(nodeId)
            refreshVoiceTranscribingState()
            _hasPendingUserMessagesForAI.set(true)

            // 2. ASR 转写（串行；preTranscribedText 非空时跳过文件转写）
            val transcription: String? = if (!preTranscribedText.isNullOrBlank()) {
                preTranscribedText
            } else {
                val currentSettings = settingsStore.settingsFlow.value
                val selectedAsrProvider = currentSettings.getSelectedASRProvider()
                val effectiveAsrProvider = if (selectedAsrProvider is ASRProviderSetting.SystemASR) {
                    val evoliaFallback = currentSettings.asrProviders
                        .asSequence()
                        .filterIsInstance<ASRProviderSetting.EvoliaASR>()
                        .firstOrNull()
                    val onlineFallback = currentSettings.asrProviders
                        .asSequence()
                        .filterIsInstance<ASRProviderSetting.OnlineASR>()
                        .firstOrNull { it.apiKey.isNotBlank() }
                    evoliaFallback ?: onlineFallback
                } else {
                    selectedAsrProvider
                }
                if (effectiveAsrProvider != null) {
                    asrMutex.withLock {
                        try {
                            asrManager.transcribeFile(effectiveAsrProvider, context, audioUri)
                                .takeIf { it.isNotBlank() }
                        } catch (e: Exception) {
                            Log.e(TAG, "sendVoiceMessage: ASR failed", e)
                            _toastFlow.emit(context.getString(R.string.chat_voice_asr_failed))
                            null
                        }
                    }
                } else {
                    _toastFlow.emit(context.getString(R.string.chat_voice_no_asr_configured))
                    null
                }
            }

            // 3. 成功则写入该 Audio Part 的 metadata（按 audioUrl 精确匹配）
            if (transcription != null) {
                chatService.mutateConversationAndSave(sessionId) { current ->
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
            }

            // 4. 移除 pending；若已清空且有待触发消息，则统一触发一次 AI
            onASRNodeCompleted(nodeId)
        }
    }

    // ===== 重新生成（刷新回复）=====
    // 与正式 ChatVM.regenerateAtMessage 语义一致：节点替换走 ChatService.replaceConversationMessages
    // （临时守卫 = 仅内存更新），生成走 handleMessageComplete（segments/L2/lastConversationId 守卫齐全），
    // 并透传会话内模型/助手覆盖，保证刷新行为与正常发送一致。

    /** 判断该消息所在 turn 能否保留版本历史（纯内存计算，复刻 ChatVM.canPreserveVersionHistory） */
    fun canPreserveVersionHistory(message: UIMessage): Boolean {
        val currentMessages = conversation.value.messageNodes.mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.lastOrNull()
        }
        val index = currentMessages.indexOfFirst { it.id == message.id }
        if (index == -1) return false
        val lastUserIndex = currentMessages.subList(0, index + 1).indexOfLast { it.role == MessageRole.USER }
        val turnStart = if (lastUserIndex >= 0) lastUserIndex else 0
        val turnEnd = currentMessages.subList(index, currentMessages.size)
            .indexOfFirst { it.role == MessageRole.USER }.let { if (it == -1) currentMessages.size else index + it }
        // 微信模式分句会把同一个 turn 拆成多个 MessageNode；版本分支逻辑只能在单个节点内追加 version。
        // turn 内 Assistant/TOOL 节点超过 1 个或含工具调用时，必须走整 turn 删除 + 重生成。
        val assistantNodeCountInTurn = currentMessages.subList(turnStart, turnEnd).count {
            it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL
        }
        if (assistantNodeCountInTurn > 1) return false
        for (i in turnStart until turnEnd) {
            if (currentMessages[i].parts.any { it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult }) return false
        }
        return true
    }

    fun regenerateAtMessage(message: UIMessage, forceWipe: Boolean = false, requirement: String? = null) {
        viewModelScope.launch {
            chatService.regenerateAtMessage(
                conversationId = sessionId,
                message = message,
                forceWipe = forceWipe,
                requirement = requirement,
                includeSkipContextMessages = true,
                chatModelOverrideId = _overrideChatModelId.value,
                assistantOverride = _sessionAssistantOverride.value
            )
        }
    }

    // ===== 消息操作（仅内存态）=====

    fun deleteMessage(messageId: Uuid) {
        viewModelScope.launch {
            chatService.replaceConversationMessages(sessionId) { current ->
                current.deleteMessages(setOf(messageId)).takeIf { deletion ->
                    deletion.deletedMessageIds.isNotEmpty()
                }
            }
        }
    }

    fun deleteMessages(messageIds: List<Uuid>) {
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            chatService.replaceConversationMessages(sessionId) { current ->
                current.deleteMessages(messageIds.toSet()).takeIf { deletion ->
                    deletion.deletedMessageIds.isNotEmpty()
                }
            }
        }
    }

    /**
     * 退出确认后的销毁入口。幂等：重复调用/会话不存在时为 no-op。
     * 先取消 viewModelScope，中止所有在途发送/ASR 协程，再销毁会话，
     * 保证销毁后没有协程继续向该 Session 写入。
     */
    fun requestDestroy() {
        viewModelScope.cancel()
        chatService.destroyTemporarySession(sessionId)
    }

    override fun onCleared() {
        super.onCleared()
        // 兜底：任何未经确认弹窗的离开路径（通知跳转、系统清理等）也会销毁临时会话，
        // 保证不遗留活跃生成任务与注册表条目。已销毁时为幂等 no-op。
        chatService.destroyTemporarySession(sessionId)
        chatService.removeConversationReference(sessionId)
    }
}
