package me.rerere.rikkahub.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageSource
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.truncate
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import kotlinx.coroutines.job
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantSearchMode
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.ConversationMessageDeletion
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.core.data.model.toMessageNode
import me.rerere.rikkahub.core.data.model.normalizeMessageNodes
import me.rerere.rikkahub.core.data.model.removeInvalidMessages
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_TEMP_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.core.data.utils.KeywordExtractor
import me.rerere.rikkahub.core.data.utils.VectorUtils
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import android.net.Uri
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import me.rerere.rikkahub.BuildConfig

private const val TAG = "ChatService"

// ------------------------------------------------------------------
//  安全解码 UIMessage：损坏的消息只记日志 + 抛 null，绝不闪退
// ------------------------------------------------------------------
internal fun decodeUIMessageOrNull(json: String, tagExtra: Any? = null): UIMessage? =
    runCatching { JsonInstant.decodeFromString<UIMessage>(json) }.getOrNull()
        ?: run {
            Log.e(TAG, "decodeUIMessageOrNull: 损坏消息, 上下文=$tagExtra; 预览: ${json.take(150)}…")
            null
        }


internal fun selectMessagesForGeneration(
    messageNodes: List<MessageNode>,
    contextEndNodeId: Uuid?,
    truncateIndex: Int
): List<UIMessage> {
    val rangeStart = truncateIndex.coerceAtLeast(0).coerceAtMost(messageNodes.size)
    val rangeEndExclusive = if (contextEndNodeId == null) {
        messageNodes.size
    } else {
        val contextEndIndex = messageNodes.indexOfFirst { node -> node.id == contextEndNodeId }
        if (contextEndIndex < 0) return emptyList()
        contextEndIndex + 1
    }
    if (rangeStart >= rangeEndExclusive) return emptyList()

    return messageNodes
        .subList(rangeStart, rangeEndExclusive)
        .mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.lastOrNull()
        }
}


private val _isAiTypingMap = MutableStateFlow<Map<Uuid, Boolean>>(emptyMap())
private val inputTransformers by lazy {
    listOf(
        PlaceholderTransformer,
        me.rerere.rikkahub.data.ai.transformers.UnsupportedFileTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        me.rerere.rikkahub.data.ai.transformers.AudioToTextTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val embeddingService: EmbeddingService,

    ) {
    data class ContextRefreshResult(
        val success: Boolean,
        val summary: String = "",
        val messagesSummarized: Int = 0,
        val tokensSaved: Int = 0,
        val errorMessage: String? = null
    )

    private val conversations = ConcurrentHashMap<Uuid, MutableStateFlow<Conversation>>()
    private val conversationReferences = ConcurrentHashMap<Uuid, Int>()
    private val temporaryConversations = ConcurrentHashMap.newKeySet<Uuid>()
    // 通话中的会话集合：标记后跳过主路径的 L1 自动摘要, 改由 VoiceCallManager 的 25 分钟定时器驱动
    private val callModeConversations = ConcurrentHashMap.newKeySet<Uuid>()
    private val _generationJobs = MutableStateFlow<Map<Uuid, Job?>>(emptyMap())
    private val generationJobs: StateFlow<Map<Uuid, Job?>> = _generationJobs.asStateFlow()
    private val _errorFlow = MutableSharedFlow<Throwable>()
    val errorFlow: SharedFlow<Throwable> = _errorFlow.asSharedFlow()
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val _syncingConversationIds = MutableStateFlow<Set<Uuid>>(emptySet())

    private val _generatingNodeIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val generatingNodeIds: StateFlow<Set<Uuid>> = _generatingNodeIds.asStateFlow()
    suspend fun loadMoreHistory(conversationId: Uuid) {
        val currentConv = conversations[conversationId]?.value ?: return

        // 1. 获取已解析内容的 ID 集合
        val loadedContentIds = currentConv.messageNodes
            .filter { it.messages.isNotEmpty() }
            .map { it.id }.toSet()

        // 2. 获取内存中已存在的节点 ID 集合 (含占位符)
        val existingNodeIds = currentConv.messageNodes.map { it.id }.toSet()

        // 3. 触发数据库加载
        val moreNodes = conversationRepo.loadMoreMessages(conversationId, loadedContentIds)

        if (moreNodes.isNotEmpty()) {
            updateConversation(conversationId) { old ->
                val newMap = moreNodes.associateBy { it.id }

                // A. 更新内存中已有的占位节点内容（将空节点替换为有内容的节点）
                val updatedNodes = old.messageNodes.map { node ->
                    newMap[node.id] ?: node
                }

                // B. 找出那些完全不在内存里的历史节点（更早之前的消息）
                val nodesToPrepend = moreNodes.filter { it.id !in existingNodeIds }

                // C. 合并：[新的历史节点] + [更新后的现有节点]
                // 注意：这里需要保持 orderIndex 升序，所以直接 Prepend
                val finalNodes = (nodesToPrepend + updatedNodes).distinctBy { it.id }
                old.copy(messageNodes = finalNodes)
            }
        }
    }

    val syncingConversationIds: StateFlow<Set<Uuid>> = _syncingConversationIds.asStateFlow()

    private var lastConversationId: Uuid? = null

    // 微信模式：消息合并发送的计时器
    private val wechatDebounceJobs = ConcurrentHashMap<Uuid, Job>()

    private val promptPlaceholderRegex = Regex("\\{\\{(\\w+)\\}\\}")

    // 并发防抖锁集合
    private val archivingConversations = ConcurrentHashMap.newKeySet<Uuid>()
    private val summarizingConversations = ConcurrentHashMap.newKeySet<Uuid>()

    // 会话级别的互斥锁，确保同一时间只有一个生成任务在处理网络/状态更新
    private val conversationMutexes = ConcurrentHashMap<Uuid, Mutex>()
    // 数据库写入单独串行化，避免发送、刷新、分页切换和后台任务交错覆盖。
    private val persistenceMutexes = ConcurrentHashMap<Uuid, Mutex>()
    // getConversationFlow may create a lightweight fallback. Cache presence alone does
    // not mean that the real database conversation has been loaded.
    private val initializedConversationIds = ConcurrentHashMap.newKeySet<Uuid>()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

        // 自动管理前台服务状态
        appScope.launch {
            // 联合监听任务列表和前台状态，确保状态同步
            combine(generationJobs, _isForeground) { jobs, isForeground ->
                jobs.isNotEmpty() to isForeground
            }.collect { (hasJobs, isForeground) ->
                if (hasJobs) {
                    if (isForeground) {
                        ChatForegroundService.start(context)
                    }
                } else {
                    delay(2000)
                    if (_generationJobs.value.isEmpty()) {
                        ChatForegroundService.stop(context)
                    }
                }
            }
        }
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        _generationJobs.value.values.forEach { it?.cancel() }
    }

    private fun fillPrompt(template: String, placeholders: Map<String, String>): String {
        return promptPlaceholderRegex.replace(template) { match ->
            placeholders[match.groupValues[1]] ?: match.value
        }
    }

    private suspend fun <T> retryIO(
        times: Int = 2, initialDelay: Long = 2000, block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times) {
            try {
                return block()
            } catch (e: Exception) {
                val isNetworkError = e is java.io.IOException || e.message?.contains(
                    "timeout",
                    ignoreCase = true
                ) == true || e.message?.contains("canceled", ignoreCase = true) == true
                if (isNetworkError) {
                    Log.w(TAG, "网络异常，正在重试 (第 ${it + 1} 次): ${e.message}")
                    delay(currentDelay)
                    currentDelay *= 2
                } else {
                    throw e
                }
            }
        }
        return block()
    }

    fun addConversationReference(conversationId: Uuid) {
        conversationReferences[conversationId] = conversationReferences.getOrDefault(conversationId, 0) + 1
    }

    fun removeConversationReference(conversationId: Uuid) {
        val currentCount = conversationReferences[conversationId] ?: 0
        if (currentCount <= 1) {
            conversationReferences.remove(conversationId)
            appScope.launch {
                delay(100)
                if (!hasReference(conversationId)) {
                    cleanupConversation(conversationId)
                }
            }
        } else {
            conversationReferences[conversationId] = currentCount - 1
        }
    }

    suspend fun executeAgentTask(task: me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity) {
        val data = JsonInstant.parseToJsonElement(task.taskData) as? JsonObject ?: return

        val instruction = data["instruction"]?.jsonPrimitive?.contentOrNull ?: ""
        val settings = settingsStore.settingsFlow.first()
        val originalAssistantId = Uuid.parse(task.assistantId)
        val originalAssistant = settings.getAssistantById(originalAssistantId) ?: return

        val activeConvId = conversationReferences.keys
            .mapNotNull { id -> conversations[id]?.value }
            .filter { it.assistantId == originalAssistantId }
            .maxByOrNull { it.updateAt }
            ?.id

        val conversationId = if (activeConvId != null) {
            activeConvId
        } else {
            val lastDbId =
                conversationRepo.getAllConversations().first().filter { it.assistantId == originalAssistantId }
                    .maxByOrNull { it.updateAt }?.id

            lastDbId ?: Uuid.random()
        }

        val monitorMsg = buildString {
            append("【系统自动化指令 - 任务触发】\n")
            when (task.taskType) {
                "EMAIL" -> {
                    val to = data["to"]?.jsonPrimitive?.contentOrNull
                    val subject = data["subject"]?.jsonPrimitive?.contentOrNull
                    append("类型: 邮件自动化\n")
                    if (!to.isNullOrBlank()) append("目标收件人: $to\n")
                    if (!subject.isNullOrBlank()) append("预设主题: $subject\n")
                }

                "NOTIFICATION" -> {
                    val title = data["title"]?.jsonPrimitive?.contentOrNull
                    append("类型: 定时提醒\n")
                    if (!title.isNullOrBlank()) append("提醒主题: $title\n")
                }

                "MONITOR_TRIGGER" -> {
                    val monitorName = data["monitor_name"]?.jsonPrimitive?.contentOrNull
                    append("类型: 实时监控触发\n")
                    if (!monitorName.isNullOrBlank()) append("监控器名称: $monitorName\n")
                }
            }
            append("\n指令内容：$instruction\n\n注意：这是一条由自动化引擎触发的【隐形指令】，用户不会在消息列表中看到它。请根据上述指令要求直接采取行动或给出回应。")
        }

        appScope.launch {
            try {
                var retry = 0
                while (generationJobs.value[conversationId] != null && retry < 30) {
                    delay(100)
                    retry++
                }

                if (generationJobs.value[conversationId] != null) {
                    Log.w(TAG, "会话 $conversationId 忙碌，强行接管执行自动化任务。")
                    generationJobs.value[conversationId]?.cancel()
                    delay(200)
                }

                initializeConversation(conversationId, targetAssistantId = originalAssistantId)
                updateConversation(conversationId, normalizeNodes = true) { old ->
                    val newNode = UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(monitorMsg)),
                        skipContext = true,
                        messageSource = MessageSource.AGENT_TASK // 自动化任务来源：完整带入不截断
                    ).toMessageNode(conversationId)

                    old.copy(
                        messageNodes = old.messageNodes + newNode,
                        updateAt = Instant.now()
                    )
                }
                mutateConversationAndSave(conversationId) { current -> current }

                val job = launch {
                    try {
                        handleMessageComplete(
                            conversationId = conversationId,
                            assistantOverride = originalAssistant,
                            skipContextForResponse = false,
                            includeSkipContextMessages = true
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "自动化任务生成失败", e)
                    }
                }

                setGenerationJob(conversationId, job)
                job.invokeOnCompletion {
                    _generationJobs.update { current ->
                        if (current[conversationId] == job) current - conversationId else current
                    }
                    appScope.launch { delay(500); checkAllConversationsReferences() }
                }

            } catch (e: Exception) {
                Log.e(TAG, "后台任务调度失败", e)
            }
        }
    }

    private fun hasReference(conversationId: Uuid): Boolean =
        conversationReferences.containsKey(conversationId) || _generationJobs.value.containsKey(conversationId)

    fun checkAllConversationsReferences() {
        conversations.keys.forEach { if (!hasReference(it)) cleanupConversation(it) }
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        val settings = settingsStore.settingsFlow.value
        val currentAssistant = settings.getCurrentAssistant()

        if (!conversations.containsKey(conversationId) && conversations.size >= 5) {
            val toRemove = conversations.keys.firstOrNull { !hasReference(it) }
            toRemove?.let { cleanupConversation(it) }
        }

        return conversations.computeIfAbsent(conversationId) {
            MutableStateFlow(
                Conversation.ofId(
                    id = conversationId,
                    assistantId = currentAssistant.id,
                )
            )
        }
    }

    /**
     * 无副作用地从 DB 预热会话到内存缓存。
     * 仅当缓存未命中且 DB 中存在该会话时写入缓存，不触发归档、不改变 lastConversationId、不切换 currentAssistant。
     * 用于后台流程（如 DiaryVM）在调用 sendMessage 前确保缓存中是 DB 真实数据，
     * 防止 getConversationFlow fallback 用 currentAssistant.id 创建错误占位会话。
     * @return true 表示缓存已就绪（命中缓存或 DB 加载成功）；false 表示 DB 中不存在该会话，调用方应中止后续操作。
     */
    suspend fun ensureConversationLoaded(conversationId: Uuid): Boolean {
        if (
            conversationId in initializedConversationIds &&
            conversations.containsKey(conversationId)
        ) {
            return true
        }
        val fromDb = conversationRepo.getConversationById(conversationId) ?: return false
        val flow = conversations.computeIfAbsent(conversationId) { MutableStateFlow(fromDb) }
        flow.value = fromDb
        initializedConversationIds += conversationId
        return true
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> = generationJobs.map { it[conversationId] }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = generationJobs

    private fun setGenerationJob(conversationId: Uuid, job: Job?) {
        if (job == null) {
            removeGenerationJob(conversationId)
            return
        }
        _generationJobs.update { it + (conversationId to job) }
    }

    private fun removeGenerationJob(conversationId: Uuid) {
        _generationJobs.update { it - conversationId }
    }

    suspend fun initializeConversation(
        conversationId: Uuid,
        targetAssistantId: Uuid? = null,
        skipAutoArchive: Boolean = false // ✨ 新增：支持跳过离场归档逻辑
    ) {
        val currentConvInDb = conversationRepo.getConversationById(conversationId)

        val currentJob = currentCoroutineContext().job
        val registeredJob = _generationJobs.value[conversationId]
        val isGenerating = registeredJob != null && registeredJob.isActive && registeredJob != currentJob
        val currentConv = conversations[conversationId]?.value ?: currentConvInDb

        val currentAssistantId = targetAssistantId
            ?: currentConv?.assistantId
            ?: settingsStore.settingsFlow.value.getCurrentAssistant().id

        lastConversationId?.let { oldId ->
            if (oldId != conversationId && !skipAutoArchive) { // ✨ 在跳转搜索消息时不执行重归档逻辑
                val isNewConversation = currentConvInDb == null ||
                    currentConvInDb.currentMessages.none { it.role == MessageRole.USER }

                if (isNewConversation) {
                    val oldConv = conversationRepo.getConversationById(oldId)

                    if (oldConv != null && oldConv.assistantId == currentAssistantId) {
                        val settings = settingsStore.settingsFlow.first()
                        val assistant = settings.getAssistantById(oldConv.assistantId) ?: settings.getCurrentAssistant()

                        if (assistant.enableMemory) {
                            appScope.launch {
                                if (archivingConversations.contains(oldId)) return@launch
                                if (assistant.enableRecentChatsReference) {
                                    _syncingConversationIds.update { it + conversationId }
                                    try {
                                        if (assistant.enableDetailMemory) {
                                            launch {
                                                summarizeAndRefresh(
                                                    oldId,
                                                    onlySegments = true,
                                                    skipArchive = true
                                                )
                                            }
                                        }
                                        archiveConversation(oldId, force = true)
                                    } finally {
                                        _syncingConversationIds.update { it - conversationId }
                                    }
                                } else {
                                    if (assistant.enableDetailMemory) {
                                        summarizeAndRefresh(oldId, onlySegments = true)
                                    } else if (assistant.enableMemoryConsolidation) {
                                        archiveConversation(oldId, force = true)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Log.v(TAG, "切换到已有会话或返回主页，跳过自动归档逻辑。")
                }
            }
        }

        lastConversationId = conversationId
        if (currentConvInDb != null) {
            val currentInMem = conversations[conversationId]?.value
            if (isGenerating && currentInMem != null && currentInMem.messageNodes.isNotEmpty()) {
                Log.v(TAG, "会话 $conversationId 正在后台生成中，跳过内存重置以保护当前生成状态")
            } else {
                updateConversation(conversationId) { currentConvInDb }
            }
            if (targetAssistantId == null) {
                settingsStore.updateAssistant(currentConvInDb.assistantId)
            }
        } else {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.getAssistantById(currentAssistantId) ?: settings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId, assistantId = assistant.id
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId) { newConversation }
        }
        initializedConversationIds += conversationId
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun archiveConversation(
        conversationId: Uuid, force: Boolean = false, skipEmbedding: Boolean = false
    ) {
        if (!archivingConversations.add(conversationId)) return

        try {
            val conv = conversationRepo.getConversationById(conversationId) ?: return
            val messages = conv.currentMessages
            val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conversationId.toString())
            val episodeSignificance = existingEpisode?.significance ?: 0
            val increment = conversationRepo.countNewMessages(conversationId.toString(), existingEpisode?.endTime ?: 0L)
            if (!force) {
                if (existingEpisode != null) {
                    if (!force && increment < 4) return
                } else if (messages.size < 4) {
                    return
                }
            }

            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()

            if (!assistant.enableMemoryConsolidation && !force) return

            val baseSummary = existingEpisode?.content
            val newMessagesEntities = conversationRepo.getMessagesForSummary(
                conversationId.toString(),
                existingEpisode?.endTime ?: 0L,
                limit = 300
            )
            val newMessages = newMessagesEntities.mapNotNull {
                decodeUIMessageOrNull(it.contentJson, tagExtra = "archive:$conversationId")
            }

            if (newMessages.isEmpty() && baseSummary != null && !force) {
                return
            }

            val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
            val model = settings.findModelById(modelId)
                ?: assistant.chatModelId?.let { settings.findModelById(it) }
                ?: settings.getCurrentChatModel()
                ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val handler = providerManager.getProviderByType(provider)

            val backgroundModelId = assistant.backgroundModelId ?: settings.backgroundModelId
            val backgroundModel = settings.findModelById(backgroundModelId) ?: model
            val backgroundProvider = backgroundModel.findProvider(settings.providers) ?: provider
            val backgroundHandler = providerManager.getProviderByType(backgroundProvider)

            val summary = if (newMessages.isEmpty() && baseSummary != null) {
                baseSummary
            } else {
                val messagesText = StringBuilder().apply {
                    newMessages.forEach { msg ->
                        append(msg.role).append(": ").append(msg.toContentText().take(1000)).append("\n")
                    }
                }.toString()

                val locale = Locale.getDefault().displayName

                val prompt = fillPrompt(
                    DEFAULT_FULL_SUMMARY_PROMPT, mapOf(
                        "previous_summary" to (baseSummary?.removePrefix("虚拟世界：") ?: "None"),
                        "new_messages" to messagesText,
                        "locale" to locale,
                        "char" to assistant.name
                    )
                )

                val providerHandler = handler as Provider<ProviderSetting>
                val resp = retryIO(times = 2) {
                    providerHandler.generateText(
                        provider,
                        listOf(UIMessage.user(prompt)),
                        TextGenerationParams(model, 0.3f, 1.0f, thinkingBudget = 0)
                    )
                }
                resp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
                resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
            }

            if (summary.isNotBlank() && (!newMessages.isEmpty() || force)) {

                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0,
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    content = summary,
                    keywords = "",
                    embedding = null,
                    embeddingModelId = null,
                    startTime = newMessagesEntities.firstOrNull()?.createdAt ?: conv.createAt.toEpochMilli(),
                    endTime = newMessagesEntities.lastOrNull()?.createdAt ?: conv.updateAt.toEpochMilli(),
                    significance = messages.size,
                    lastAccessedAt = System.currentTimeMillis()
                )
                memoryRepository.saveEpisode(episode)
                Log.i(TAG, "Archived L2 memory for $conversationId. force=$force")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive conversation $conversationId", e)
        } finally {
            archivingConversations.remove(conversationId)
        }
    }

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        isTemporaryChat: Boolean = false,
        predefinedUserNode: MessageNode? = null,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = true, // 普通对话也包含 skipContext 消息，由 messageSource 控制截断策略
        responseMessageSource: MessageSource = MessageSource.NORMAL // AI 回复的消息来源标识
    ) {
        if (isTemporaryChat) {
            temporaryConversations.add(conversationId)
        } else {
            // ✨ 如果用户显式切换回正常模式，从临时集合中移除
            temporaryConversations.remove(conversationId)
        }

        val oldJob = _generationJobs.value[conversationId]
        if (oldJob != null) {
            oldJob.cancel()
            removeGenerationJob(conversationId)
            _isAiTypingMap.update { it - conversationId }
        }
        wechatDebounceJobs[conversationId]?.cancel()
        val newNode = predefinedUserNode ?: UIMessage(role = MessageRole.USER, parts = content).toMessageNode(conversationId)
        updateConversation(conversationId, normalizeNodes = true) { old ->
            old.copy(
                messageNodes = old.messageNodes + newNode,
                updateAt = Instant.now()
            )
        }
        appScope.launch {
            // Capture the latest state only after entering the serialized persistence path.
            // A previously captured snapshot can otherwise erase the first streamed chunk.
            mutateConversationAndSave(conversationId) { current -> current }
            conversationRepo.recordDailyActivity()
        }

        if (answer) {
            triggerAIResponse(
                conversationId = conversationId,
                skipContextForResponse = skipContextForResponse,
                includeSkipContextMessages = includeSkipContextMessages,
                responseMessageSource = responseMessageSource
            )
        }
    }

    /**
     * 单独触发 AI 回复（不添加用户消息）。
     *
     * 用于需要先把用户消息入列显示、再异步准备 context 后触发 AI 生成的场景，
     * 例如语音消息：先入列显示语音条 → 后台 ASR 转写 → 更新消息 metadata → 调用此方法触发 AI。
     */
    fun triggerAIResponse(
        conversationId: Uuid,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = true,
        responseMessageSource: MessageSource = MessageSource.NORMAL
    ) {
        // 取消已存在的生成任务，防止出现孤儿任务继续流式存储未保存的分句。
        // sendMessage 已取消一次，但 maybeTriggerAIAfterAsr 等调用方可能未取消，
        // 两次 triggerAIResponse 调用会导致第一个任务成为孤儿（不在任何 Map 中），
        // 继续持有 mutex 流式写入分句，用户发新消息时无法被打断。
        _generationJobs.value[conversationId]?.cancel()
        wechatDebounceJobs[conversationId]?.cancel()
        _isAiTypingMap.update { it - conversationId }

        val settings = settingsStore.settingsFlow.value
        val wechatMode = settings.getEffectiveDisplaySetting(settings.getCurrentAssistant()).wechatMode

        val debounceJob = appScope.launch {
            if (wechatMode) {
                delay(5000)
                _isAiTypingMap.update { it + (conversationId to true) }
            } else {
                _isAiTypingMap.update { it + (conversationId to true) }
            }

            val timeoutJob = launch {
                delay(15 * 60 * 1000L)
                // 超时后取消整个生成任务并提示用户
                _errorFlow.emit(java.net.SocketTimeoutException(context.getString(R.string.chat_generation_timeout)))
                cancel()
            }
            try {
                handleMessageComplete(
                    conversationId = conversationId,
                    skipContextForResponse = skipContextForResponse,
                    includeSkipContextMessages = includeSkipContextMessages,
                    responseMessageSource = responseMessageSource
                )
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _errorFlow.emit(translateError(e))
                }
            } finally {
                timeoutJob.cancel()
                _isAiTypingMap.update { it - conversationId }
            }
        }

        setGenerationJob(conversationId, debounceJob)
        wechatDebounceJobs[conversationId] = debounceJob

        debounceJob.invokeOnCompletion {
            _generationJobs.update { current ->
                if (current[conversationId] == debounceJob) current - conversationId else current
            }
            wechatDebounceJobs.compute(conversationId) { _, current ->
                if (current == debounceJob) null else current
            }
            appScope.launch { delay(500); checkAllConversationsReferences() }
        }
    }

    suspend fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        forceWipe: Boolean = false,
        requirement: String? = null,
        includeSkipContextMessages: Boolean = true
    ) {
        // 取消旧任务
        val oldJob = _generationJobs.value[conversationId]
        oldJob?.cancelAndJoin()
        removeGenerationJob(conversationId)

        var targetMsgId: Uuid? = null
        var contextEndNodeId: Uuid? = null

        // Build the replacement from the latest state while holding the persistence
        // lock. A serialized write of a snapshot captured before the lock can still
        // overwrite a newer mutation.
        val preparedReplacement = withTimeoutOrNull(5000) {
            initializeConversation(conversationId)
            replaceConversationMessages(conversationId) { conversation ->
                if (message.role == MessageRole.USER) {
                    val indexAt = conversation.messageNodes.indexOfFirst { node ->
                        node.messages.any { candidate -> candidate.id == message.id }
                    }
                    if (indexAt < 0) return@replaceConversationMessages null

                    val nodesToDelete = conversation.messageNodes.drop(indexAt + 1)
                    return@replaceConversationMessages ConversationMessageDeletion(
                        conversation = conversation.copy(
                            messageNodes = conversation.messageNodes.take(indexAt + 1)
                        ),
                        deletedNodeIds = nodesToDelete.mapTo(linkedSetOf()) { node -> node.id },
                        deletedMessageIds = emptySet()
                    )
                }

                if (!regenerateAssistantMsg) return@replaceConversationMessages null

                val clickedNode = conversation.getMessageNodeByMessageId(message.id)
                val clickedIndex = conversation.messageNodes.indexOf(clickedNode)
                if (clickedIndex < 0) return@replaceConversationMessages null
                val lastUserIndex = conversation.messageNodes.take(clickedIndex + 1)
                    .indexOfLast { node ->
                        node.messages.isNotEmpty() && node.role == MessageRole.USER
                    }
                if (lastUserIndex < 0) return@replaceConversationMessages null

                contextEndNodeId = conversation.messageNodes[lastUserIndex].id
                val firstAssistantIndex = lastUserIndex + 1
                val nextUserOffset = conversation.messageNodes
                    .drop(firstAssistantIndex)
                    .indexOfFirst { node ->
                        node.messages.isNotEmpty() && node.role == MessageRole.USER
                    }
                val turnEndIndex = if (nextUserOffset < 0) {
                    conversation.messageNodes.size
                } else {
                    firstAssistantIndex + nextUserOffset
                }
                val turnNodes = conversation.messageNodes.slice(firstAssistantIndex until turnEndIndex)
                val hasToolInteraction = turnNodes.any { node ->
                    node.messages.any { turnMessage ->
                        turnMessage.parts.any { part ->
                            part is UIMessagePart.ToolCall || part is UIMessagePart.ToolResult
                        }
                    }
                }

                if (forceWipe || hasToolInteraction) {
                    val placeholderMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList()
                    )
                    targetMsgId = placeholderMessage.id
                    val replacementNodes = conversation.messageNodes
                        .take(lastUserIndex + 1)
                        .toMutableList()
                    replacementNodes += placeholderMessage.toMessageNode(conversation.id)
                    if (turnEndIndex < conversation.messageNodes.size) {
                        replacementNodes += conversation.messageNodes.drop(turnEndIndex)
                    }
                    ConversationMessageDeletion(
                        conversation = conversation.copy(messageNodes = replacementNodes),
                        deletedNodeIds = turnNodes.mapTo(linkedSetOf()) { node -> node.id },
                        deletedMessageIds = emptySet()
                    )
                } else {
                    val versionTag = Uuid.random().toString()
                    val newMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        versionTag = versionTag
                    )
                    targetMsgId = newMessage.id
                    val replacementNodes = conversation.messageNodes.toMutableList()
                    val nodeToUpdate = replacementNodes[clickedIndex]
                    val newMessages = nodeToUpdate.messages + newMessage
                    replacementNodes[clickedIndex] = nodeToUpdate.copy(
                        messages = newMessages,
                        selectIndex = newMessages.lastIndex
                    )
                    ConversationMessageDeletion(
                        conversation = conversation.copy(messageNodes = replacementNodes),
                        deletedNodeIds = emptySet(),
                        deletedMessageIds = emptySet()
                    )
                }
            }
        } ?: return

        if (preparedReplacement.deletedNodeIds.isNotEmpty()) {
            Log.v(TAG, "Regeneration removed ${preparedReplacement.deletedNodeIds.size} nodes")
        }

        val job = appScope.launch {
                _isAiTypingMap.update { it + (conversationId to true) }
                try {
                    handleMessageComplete(
                        conversationId = conversationId,
                        contextEndNodeId = contextEndNodeId,
                        requirement = requirement,
                        targetMessageId = targetMsgId,
                        includeSkipContextMessages = includeSkipContextMessages
                    )
                    _generationDoneFlow.emit(conversationId)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        _errorFlow.emit(translateError(e))
                    }
                }finally {
                    _isAiTypingMap.update { it - conversationId }
                }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion {
            _generationJobs.update { current ->
                if (current[conversationId] == job) current - conversationId else current
            }
            appScope.launch { delay(500); checkAllConversationsReferences() }
        }
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        contextEndNodeId: Uuid? = null,
        assistantOverride: Assistant? = null,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = true,
        responseMessageSource: MessageSource = MessageSource.NORMAL,
        requirement: String? = null,
        targetMessageId: Uuid? = null
    ) {
        val mutex = conversationMutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            checkInvalidMessages(conversationId)
            val currentJob = coroutineContext.job
            var currentSearchCount = 0
            val settings = settingsStore.settingsFlow.first()
            var currentConversation = conversations[conversationId]?.value
                ?: conversationRepo.getConversationById(conversationId)
                ?: return@withLock
            val processMessageIds = mutableMapOf<Int, Uuid>()
            val placeholderId = targetMessageId ?: currentConversation.currentMessages.lastOrNull()?.let {
                if (it.role == MessageRole.ASSISTANT && it.toContentText()
                        .isBlank() && it.parts.isEmpty()
                ) it.id else null
            }
            if (placeholderId != null) {
                // 强制让 AI 生成的第一条消息复用这个占位符的 ID
                processMessageIds[0] = placeholderId
                _generatingNodeIds.update { it + placeholderId }
            }

            // 修复：每次生成全新重置句子计数器

            var lastTotalFullText = ""
            var lastAiId: Uuid? = null

            // 微信模式分句状态：流式过程中累积文本，按标点切句，逐句存为独立节点
            val wechatSentenceBuffer = StringBuilder()
            var wechatProcessedTextLen = 0
            var wechatStoredSentenceCount = 0
            var wechatFirstNodeNonTextParts: List<UIMessagePart>? = null
            var wechatLastSentenceLength = 0 // 上一句的长度，用于计算下一句的 delay 节奏（与原 UI 打字动画一致）

            runCatching {
                currentConversation = currentConversation.copy(chatSuggestions = emptyList())
                updateConversation(conversationId) { old ->
                    old.copy(chatSuggestions = emptyList()).also { currentConversation = it }
                }
                val assistant = assistantOverride ?: settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
                val modelId = assistant.chatModelId ?: settings.chatModelId
                val model = settings.findModelById(modelId) ?: settings.getCurrentChatModel() ?: return@runCatching
                var firstTokenTime: Long? = null
                if (!model.abilities.contains(ModelAbility.TOOL)) {
                    val hasExternalTools =
                        (assistant.searchMode !is AssistantSearchMode.Off) || mcpManager.getAllAvailableTools()
                            .isNotEmpty()
                    if (hasExternalTools) _errorFlow.emit(IllegalStateException(context.getString(R.string.tools_warning)))
                }
                val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode

                checkInvalidMessages(conversationId)
                currentConversation = getConversationFlow(conversationId).value
                val retrievedMemories = withContext(Dispatchers.IO) {
                    if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF && !temporaryConversations.contains(
                            conversationId
                        )
                    ) {
                        withTimeoutOrNull(4000) {
                            if (assistant.useRagMemoryRetrieval) {
                                val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
                                val rawUserMsg = if (wechatMode) {
                                    val messages = currentConversation.currentMessages
                                    val lastNonUser = messages.indexOfLast { it.role != MessageRole.USER }
                                    messages.subList(lastNonUser + 1, messages.size)
                                        .joinToString(" ") { it.toText() }
                                } else {
                                    currentConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }
                                        ?.toText()
                                        ?: ""
                                }
                                val lastUserMsg = rawUserMsg.replace("\n", " ").trim()
                                if (lastUserMsg.isNotBlank()) {
                                    val results = memoryRepository.retrieveRelevantMemoriesWithScores(
                                        assistantId = assistant.id.toString(),
                                        query = lastUserMsg,
                                        limit = assistant.ragLimit,
                                        similarityThreshold = assistant.ragSimilarityThreshold,
                                        mode = assistant.memoryRetrievalMode,
                                        excludeConversationId = conversationId.toString()
                                    )
                                    val memories = results.map { it.first }
                                    if (settings.enableRagLogging) {
                                        results.forEach { (mem, score) ->
                                        }
                                    }
                                    memories
                                } else {
                                    emptyList()
                                }
                            } else memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                        } ?: emptyList()
                    } else emptyList()
                }

                val currentEpisode = chatEpisodeDAO.getEpisodeByConversationId(conversationId.toString())

                val baseMessages = selectMessagesForGeneration(
                    messageNodes = currentConversation.messageNodes,
                    contextEndNodeId = contextEndNodeId,
                    truncateIndex = currentConversation.truncateIndex
                )
                    .filter { msg ->
                        msg.role != MessageRole.ASSISTANT ||
                            msg.toContentText().isNotBlank() ||
                            msg.parts.any { it is UIMessagePart.ToolCall }
                    }

                // 临时处理优化要求：仅针对发送给 AI 的上下文做拼接，不修改 baseMessages (用于 UI 同步)
                val messagesForModel = if (!requirement.isNullOrBlank()) {
                    val list = baseMessages.toMutableList()
                    val lastUserIdx = list.indexOfLast { it.role == MessageRole.USER }
                    if (lastUserIdx != -1) {
                        val lastUserMsg = list[lastUserIdx]
                        val updatedParts = lastUserMsg.parts.toMutableList()
                        val lastTextIdx = updatedParts.indexOfLast { it is UIMessagePart.Text }
                        if (lastTextIdx != -1) {
                            val textPart = updatedParts[lastTextIdx] as UIMessagePart.Text
                            updatedParts[lastTextIdx] = textPart.copy(text = textPart.text + " (回复要求：$requirement)")
                        } else {
                            updatedParts.add(UIMessagePart.Text("(回复要求：$requirement)"))
                        }
                        list[lastUserIdx] = lastUserMsg.copy(parts = updatedParts)
                    }
                    list
                } else baseMessages

                val finalContextMessages = if (wechatMode) {
                    val messages = messagesForModel.toMutableList()
                    val lastUserGroupStart = messages.indexOfLast { it.role != MessageRole.USER } + 1
                    if (lastUserGroupStart in messages.indices && messages.size - lastUserGroupStart > 1) {
                        val userMessages = messages.subList(lastUserGroupStart, messages.size)
                        val allParts =
                            userMessages.flatMap { it.parts }.distinctBy { (it as? UIMessagePart.Image)?.url ?: it }

                        val combinedMsg = UIMessage(
                            role = MessageRole.USER,
                            parts = allParts,
                            createdAt = userMessages.last().createdAt
                        )
                        messages.subList(lastUserGroupStart, messages.size).clear()
                        messages.add(combinedMsg)
                    }
                    messages
                } else messagesForModel

                generationHandler.generateText(
                        settings = settings,
                        model = model,
                        messages = finalContextMessages,
                        assistant = assistant,
                        memories = retrievedMemories,
                        inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer) },
                        outputTransformers = outputTransformers,
                        isCallMode = callModeConversations.contains(conversationId),
                        tools = buildList {
                            val isMain = assistant.isMain


                            val supportsBuiltIn =
                                model.tools.isNotEmpty() || ModelRegistry.GEMINI_SERIES.match(model.modelId)
                            val useBuiltIn = assistant.preferBuiltInSearch && supportsBuiltIn
                            val searchMode = assistant.searchMode

                            if (searchMode is AssistantSearchMode.Provider && !useBuiltIn) {
                                addAll(
                                    createSearchTool(
                                        settings,
                                        assistant,
                                        searchMode.index,
                                        searchCounter = { currentSearchCount },
                                        onSearchCalled = { currentSearchCount++ }
                                    ))
                            }

                            val targetOptions = if (isMain) {
                                // 1. 获取当前数据库里的工具配置
                                val list = assistant.localTools.toMutableList()

                                // 2. ✨ 只有在 Release 模式下，才强制把这 9 个工具塞进去
                                if (!BuildConfig.DEBUG) {
                                    val coreTools = listOf(
                                        LocalToolOption.ScheduleManagement,
                                        LocalToolOption.JavascriptEngine,
                                        LocalToolOption.DeviceControl,
                                        LocalToolOption.PythonEngine,
                                        LocalToolOption.AgentAutomation,
                                        LocalToolOption.WebPageReader,
                                        LocalToolOption.PeekUser,
                                        LocalToolOption.UpdateProfile,
                                        LocalToolOption.TimeSense
                                    )
                                    coreTools.forEach { tool ->
                                        if (list.none { it::class == tool::class }) {
                                            list.add(tool)
                                        }
                                    }
                                }
                                if (assistant.enableMemory) {
                                    if (list.none { it is LocalToolOption.MilestoneManagement }) {
                                        list.add(LocalToolOption.MilestoneManagement)
                                    }
                                } else {
                                    list.removeAll { it is LocalToolOption.MilestoneManagement }
                                }
                                list
                            } else {
                                // 普通助手逻辑保持不变
                                assistant.localTools.filter {
                                    it is LocalToolOption.TimeSense ||
                                        it is LocalToolOption.EmailService ||
                                        it is LocalToolOption.ImageGeneration
                                }
                            }

                            addAll(
                                localTools.getTools(
                                    options = targetOptions,
                                    assistantId = assistant.id,
                                    conversationId = currentConversation.id,
                                    userImageUrls = currentConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.parts?.filterIsInstance<UIMessagePart.Image>()
                                        ?.map { it.url } ?: emptyList()))


                            val nameRegex = Regex("[^a-zA-Z0-9_-]")
                            mcpManager.getAllAvailableTools().forEach { mcpTool ->
                                val originalName = mcpTool.name
                                val sanitizedName = "mcp_" + originalName.replace(nameRegex, "_").let {
                                    if (it.firstOrNull()?.isLetter() == true || it.startsWith("_")) it else "_$it"
                                }

                                add(
                                    Tool(
                                        name = sanitizedName,
                                        description = mcpTool.description ?: "",
                                        parameters = { mcpTool.inputSchema },
                                        execute = { jsonElement ->
                                            val input = jsonElement as? JsonObject ?: JsonObject(emptyMap())
                                            mcpManager.callTool(originalName, input).truncateLargeJsonText()
                                        })
                                )
                            }

                        },
                        truncateIndex = 0,
                        enabledModeIds = currentConversation.enabledModeIds,
                        contextSummary = currentEpisode?.content?.removePrefix("虚拟世界："),
                        temporarySummaries = emptyList(),
                        skipContextForResponse = skipContextForResponse,
                        includeSkipContextMessages = includeSkipContextMessages,
                        responseMessageSource = responseMessageSource,
                        conversationId = conversationId
                    ).onCompletion {
                        _generatingNodeIds.update { old ->
                            old - currentConversation.messageNodes.map { it.id }.toSet()
                        }
                        val duration = firstTokenTime?.let { System.currentTimeMillis() - it }
                        updateConversation(conversationId) { old ->
                            val finalUpdated = old.copy(
                                messageNodes = old.messageNodes.mapIndexed { idx, node ->
                                    val isLast = idx == old.messageNodes.lastIndex
                                    node.copy(messages = node.messages.map { msg ->
                                        val finished = msg.finishReasoning()
                                        if (isLast && finished.role == MessageRole.ASSISTANT && finished.generationDurationMs == null) {
                                            finished.copy(generationDurationMs = duration)
                                        } else {
                                            finished
                                        }
                                    })
                                },
                                chatSuggestions = emptyList(),
                                updateAt = Instant.now()
                            )
                            currentConversation = finalUpdated
                            finalUpdated
                        }
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                            sendGenerationDoneNotification(conversationId)
                        }
                    }.collect { chunk ->
                        if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                        if (chunk is GenerationChunk.Messages) {
                            var conversationSnapshot = currentConversation
                            val finalMessages = chunk.messages
                            val newMessages = finalMessages.drop(finalContextMessages.size).mapIndexed { index, msg ->
                                msg.copy(id = processMessageIds.getOrPut(index) { msg.id })
                            }
                            if (newMessages.isEmpty()) return@collect
                            val nodesBeingGenerated = conversationSnapshot.messageNodes
                                .filter { n -> newMessages.any { m -> n.messages.any { nm -> nm.id == m.id } } }
                                .map { it.id }.toSet()
                            _generatingNodeIds.update { it + nodesBeingGenerated }

                            val lastAI = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                            val lastAiMsg = newMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                            val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode

                            // 1.提前 definition fullText
                            val fullText = lastAI?.toContentText() ?: ""

                            // 2. 检查是否有任何消息（包括工具结果、AI 最终回答）尚未加入会话
                            val anyNewMessages = newMessages.any { nm ->
                                conversationSnapshot.messageNodes.none { node ->
                                    node.messages.any { m -> m.id == nm.id }
                                }
                            }

                            // 3. 如果 AI 消息 ID 变了（比如从工具调用切换到了最终回答），重置微信模式的分句状态
                            if (lastAI != null && lastAI.id != lastAiId) {
                                lastAiId = lastAI.id
                                wechatSentenceBuffer.clear()
                                wechatProcessedTextLen = 0
                                wechatStoredSentenceCount = 0
                                wechatFirstNodeNonTextParts = null
                                wechatLastSentenceLength = 0
                            }

                            // 4. 微信模式 + 有最终文本：按标点分句，逐句存为独立节点（含标点，非文本 parts 放第一个节点）
                            if (wechatMode && lastAI != null && fullText.isNotBlank()) {
                                // 4a. 同步非最终 AI 的消息（工具调用、工具结果等）
                                // 微信模式下去重：含 Text 的 ASSISTANT 已被分句存储，只保留 nonTextParts
                                val otherMessages = newMessages
                                    .filter { it.id != lastAI.id }
                                    .forWechatSync()
                                if (otherMessages.isNotEmpty()) {
                                    val nextState = conversationSnapshot.updateCurrentMessages(baseMessages + otherMessages)
                                        .copy(chatSuggestions = emptyList())
                                    currentConversation = nextState
                                    updateConversation(conversationId) { nextState }
                                    conversationSnapshot = nextState
                                }

                                // 4b. 记录第一句节点的非文本 parts（工具调用图标等）
                                // 微信模式下不存储深度思考内容（Reasoning/Thinking），避免重复且符合口语化场景
                                val nonTextParts = lastAI.parts.filter {
                                    it !is UIMessagePart.Text &&
                                        it !is UIMessagePart.Reasoning &&
                                        it !is UIMessagePart.Thinking
                                }
                                if (nonTextParts.isNotEmpty()) {
                                    wechatFirstNodeNonTextParts = nonTextParts
                                }

                                // 4c. 文本增量累积 + 按标点切分完整句子（保留标点在句末）
                                if (fullText.length > wechatProcessedTextLen) {
                                    val increment = fullText.substring(wechatProcessedTextLen)
                                    wechatSentenceBuffer.append(increment)
                                    wechatProcessedTextLen = fullText.length

                                    // 排除 ![ 和 ！[ 开头的图片 markdown 语法，避免图片链接被切断
                                    val sentenceRegex = Regex("[，。？~\\n]|[,?~\\n]|！(?!\\[)|!(?!\\[)")
                                    while (true) {
                                        val match = sentenceRegex.find(wechatSentenceBuffer) ?: break
                                        // 如果匹配到 !/！ 且在缓冲区末尾，可能后续还有 [（图片语法），等更多文本到达
                                        if ((match.value == "!" || match.value == "！") &&
                                            match.range.last == wechatSentenceBuffer.lastIndex
                                        ) {
                                            break
                                        }
                                        val sentenceEnd = match.range.last + 1
                                        val rawSentence = wechatSentenceBuffer.substring(0, sentenceEnd).trim()
                                        wechatSentenceBuffer.delete(0, sentenceEnd)

                                        // 去掉中英文逗号和中文句号（微信聊天更口语化，保留感叹号/问号等有情感的标点）
                                        val sentence = rawSentence
                                            .replace("，", "")
                                            .replace(",", "")
                                            .replace("。", "")
                                            .trim()
                                        if (sentence.isBlank()) continue

                                        // delay 节奏控制：基于上一句长度（与原 UI 延迟打字公式一致，第一句默认 500ms）
                                        val delayTime = (wechatLastSentenceLength * 200L + 100L).coerceIn(500L, 3000L)
                                        delay(delayTime)

                                        // 创建新节点：第一句带非文本 parts
                                        val parts = if (wechatStoredSentenceCount == 0 && wechatFirstNodeNonTextParts != null) {
                                            wechatFirstNodeNonTextParts!! + UIMessagePart.Text(sentence)
                                        } else {
                                            listOf(UIMessagePart.Text(sentence))
                                        }
                                        val newMsg = UIMessage(role = MessageRole.ASSISTANT, parts = parts)
                                        val newNode = newMsg.toMessageNode(conversationId)

                                        currentConversation = currentConversation.copy(
                                            messageNodes = currentConversation.messageNodes + newNode
                                        )
                                        updateConversation(conversationId, normalizeNodes = true) {
                                            currentConversation
                                        }
                                        mutateConversationAndSave(conversationId) { current -> current }
                                        wechatStoredSentenceCount++
                                        wechatLastSentenceLength = sentence.length
                                    }
                                }
                            } else {
                                // 非微信模式 或 无最终文本：原同步逻辑
                                // 微信模式下通过 forWechatSync 去重：
                                // - 含 Text 的 ASSISTANT 已被分句存储，只保留 nonTextParts
                                // - 仅含思考过程 + 空白文本的流式 AI 消息被过滤
                                val messagesToSync = if (wechatMode) {
                                    newMessages.forWechatSync()
                                } else newMessages

                                // 保留唯一的一次 update：updateCurrentMessages 内部既会创建新节点
                                // （anyNewMessages=true 的场景）也会增量更新已存在节点的内容。
                                // 连续 update 两次反而会让下游 StateFlow/distinctUntilChanged 可能
                                // 合并中间的流式 delta，造成用户侧感知"整段跳出来"。
                                if (messagesToSync.isNotEmpty()) {
                                    val toUpdate = baseMessages + messagesToSync
                                    val nextState = conversationSnapshot.updateCurrentMessages(toUpdate)
                                        .copy(chatSuggestions = emptyList())
                                    currentConversation = nextState
                                    updateConversation(conversationId) { nextState }
                                }
                            }

                        }
                    }

                    // 微信模式：流式结束后处理缓冲区中剩余的未成句文本（作为最后一句存入）
                    // 注意：用户打断时 collect 抛 CancellationException，不会执行到这里，符合"未存的不继续"
                    if (wechatMode && wechatSentenceBuffer.isNotEmpty()) {
                        // 去掉中英文逗号和中文句号
                        val lastSentence = wechatSentenceBuffer.toString()
                            .replace("，", "")
                            .replace(",", "")
                            .replace("。", "")
                            .trim()
                        if (lastSentence.isNotBlank()) {
                            val delayTime = (wechatLastSentenceLength * 200L + 100L).coerceIn(500L, 3000L)
                            delay(delayTime)

                            val parts = if (wechatStoredSentenceCount == 0 && wechatFirstNodeNonTextParts != null) {
                                wechatFirstNodeNonTextParts!! + UIMessagePart.Text(lastSentence)
                            } else {
                                listOf(UIMessagePart.Text(lastSentence))
                            }
                            val newMsg = UIMessage(role = MessageRole.ASSISTANT, parts = parts)
                            val newNode = newMsg.toMessageNode(conversationId)

                            currentConversation = currentConversation.copy(
                                messageNodes = currentConversation.messageNodes + newNode
                            )
                            updateConversation(conversationId, normalizeNodes = true) {
                                currentConversation
                            }
                            mutateConversationAndSave(conversationId) { current -> current }
                            wechatStoredSentenceCount++
                        }
                        wechatSentenceBuffer.clear()
                    }

            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) {
                    return@onFailure
                }
                val finalConv = currentConversation
                appScope.launch {
                    // Persist whatever is current when this job gets the write lock. The
                    // user may already have sent another message after the failure.
                    mutateConversationAndSave(conversationId) { current -> current }
                    if (!temporaryConversations.contains(conversationId)) {
                        val currentSettings = settingsStore.settingsFlow.value
                        val updatedAssistants = currentSettings.assistants.map {
                            if (it.id == finalConv.assistantId) it.copy(lastConversationId = conversationId.toString()) else it
                        }
                        settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
                    }
                }
                if (e !is kotlinx.coroutines.CancellationException) {
                    val friendlyError = translateError(e)
                    _errorFlow.emit(friendlyError)
                    Logging.log(TAG, "handleMessageComplete: $friendlyError")
                }
            }.onSuccess {
                if (!currentJob.isActive) return@onSuccess
                val finalConv = currentConversation
                mutateConversationAndSave(conversationId) { current -> current }
                val lastAssistantMsg = finalConv.currentMessages.lastOrNull() ?: return@onSuccess
                lastAssistantMsg.usage?.let { usage ->
                    appScope.launch { conversationRepo.recordTokenUsage(finalConv.assistantId.toString(), usage) }
                }
                appScope.launch {
                    if (!temporaryConversations.contains(conversationId)) {
                        val currentSettings = settingsStore.settingsFlow.value
                        val updatedAssistants = currentSettings.assistants.map {
                            if (it.id == finalConv.assistantId) it.copy(lastConversationId = conversationId.toString()) else it
                        }
                        settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
                    }
                }
                addConversationReference(conversationId)
                appScope.launch {
                    coroutineScope {
                        launch { generateSuggestion(conversationId, finalConv) }
                        // 通话中的会话跳过自动 L1 摘要, 由 VoiceCallManager 的 25 分钟定时器驱动
                        if (!callModeConversations.contains(conversationId)) {
                            launch { checkAndAutoSummarize(conversationId, finalConv, settings) }
                        }
                    }
                }.invokeOnCompletion { removeConversationReference(conversationId) }
            }
        }
    }

    /**
     * 微信模式下同步消息到会话时的去重处理。
     *
     * 含 Text 的 ASSISTANT 消息已通过分句存储为独立节点（每句一个新 ID 的 UIMessage），
     * 这里只保留其 nonTextParts（如 ToolCall），避免 Text 被重复存储为完整节点。
     *
     * 同时过滤掉"仅含思考过程 + 空白文本"的流式 AI 消息，
     * 避免推理阶段保存的节点与后续分句节点重复存储思考内容。
     */
    private fun List<UIMessage>.forWechatSync(): List<UIMessage> = mapNotNull { msg ->
        when {
            // 非 ASSISTANT 消息（如 TOOL result、USER）原样保留
            msg.role != MessageRole.ASSISTANT -> msg

            // 含 Text 的 ASSISTANT：Text 已被分句存储，只保留 nonTextParts（ToolCall 等）
            msg.toContentText().isNotBlank() -> {
                val nonText = msg.parts.filter {
                    it !is UIMessagePart.Text &&
                        it !is UIMessagePart.Reasoning &&
                        it !is UIMessagePart.Thinking
                }
                if (nonText.isEmpty()) null else msg.copy(parts = nonText)
            }

            // 含 ToolCall 但无 Text 的 ASSISTANT：按完整消息同步（ToolCall 需与 ToolResult 配对）
            msg.parts.any { it is UIMessagePart.ToolCall } -> msg

            // 仅含思考过程 + 空白文本：跳过
            else -> null
        }
    }

    private fun translateError(e: Throwable): Throwable {
        val message = e.message ?: ""
        return when {
            message.contains("Unexpected JSON token") && message.contains("< instead") -> {
                IllegalStateException(context.getString(R.string.api_error_html), e)
            }

            message.contains("400 Bad Request", ignoreCase = true) -> {
                IllegalStateException("请求参数错误 (400)，请确认 API Key 或模型名称。", e)
            }

            message.contains("401 Unauthorized", ignoreCase = true) -> {
                IllegalStateException(context.getString(R.string.api_error_401), e)
            }

            message.contains("404 Not Found", ignoreCase = true) -> {
                IllegalStateException(context.getString(R.string.api_error_404), e)
            }

            else -> e
        }
    }

    private fun createSearchTool(
        settings: Settings,
        assistant: Assistant,
        providerIndex: Int? = null,
        searchCounter: () -> Int,
        onSearchCalled: () -> Unit
    ): Set<Tool> {
        val idx = providerIndex ?: settings.searchServiceSelected
        return buildSet {
            add(Tool(name = "search_web", description = "联网检索实时信息", parameters = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                SearchService.getService(opt).parameters
            }, execute = { jsonElement ->
                if (searchCounter() >= 1) {
                    return@Tool buildJsonObject {
                        put("error", "你本轮已调用过一次搜索工具，请整合现有信息回答用户，或询问用户是否要继续查询。")
                    }
                }
                onSearchCalled()
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                val resultSize = 6
                val commonOptions = settings.searchCommonOptions.copy(resultSize = resultSize)
                val input = jsonElement as? JsonObject ?: JsonObject(emptyMap())
                val searchResult = SearchService.getService(opt).search(input, commonOptions, opt).getOrThrow()
                searchResult.items.forEachIndexed { i, item ->
                    Log.v(
                        TAG,
                        "Raw Item [$i]: ${item.title} (${item.url})"
                    )
                }
                val htmlRegex = Regex("<[^>]*>")
                val maxCharsPerItem = (12000 / resultSize.coerceAtLeast(1)).coerceIn(1500, 4000)
                val cleanedItems = searchResult.items.take(resultSize).map { item ->
                    val cleanedText = item.text.replace(htmlRegex, "").trim()
                    item.copy(text = cleanedText.take(maxCharsPerItem))
                }
                buildJsonObject {
                    put("items", JsonArray(cleanedItems.map { item ->
                        buildJsonObject {
                            put("title", item.title)
                            put("url", item.url)
                            put("text", item.text)
                            put("link", item.url)
                            put("snippet", item.text)
                        }
                    }))
                    put("results", JsonArray(cleanedItems.map { item ->
                        buildJsonObject {
                            put("title", item.title)
                            put("link", item.url)
                            put("snippet", item.text)
                        }
                    }))
                }
            }, systemPrompt = { _, _ ->
                "## tool: search_web\n" +
                    "严重提示：每条用户消息仅允许调用该工具一次。" +
                    "如果搜索结果不够理想，就基于现有信息尽力作答，无需重新搜索。"
            }))
        }
    }

    private suspend fun checkAndAutoSummarize(id: Uuid, conv: Conversation, settings: Settings) {
        val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
        if (!assistant.enableMemory || !assistant.enableDetailMemory) return
        val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
        val max = if (wechatMode) (assistant.detailMemoryThreshold * 2).toInt() else assistant.detailMemoryThreshold

        // 获取最近的消息（取 200 条以防 tool 消息过多），然后在内存中统计 user 和 assistant 角色
        val newMessagesEntities = conversationRepo.getMessagesForSummary(id.toString(), conv.lastSummarizedMessageTime, 200)
        val coreMessageCount = newMessagesEntities.count { entity ->
            val uiMsg = decodeUIMessageOrNull(entity.contentJson, tagExtra = "checkSummary:$id")
                ?: return@count false
            uiMsg.role == MessageRole.USER || uiMsg.role == MessageRole.ASSISTANT
        }

        if (coreMessageCount >= max) summarizeAndRefresh(id, skipArchive = true)
    }
    @SuppressLint("SuspiciousIndentation")
    suspend fun summarizeAndRefresh(
        id: Uuid, onlySegments: Boolean = false, skipArchive: Boolean = false
    ): ContextRefreshResult = withContext(Dispatchers.IO) {
        if (!summarizingConversations.add(id)) {
            return@withContext ContextRefreshResult(false, errorMessage = "正在总结中...请勿重复操作")
        }
        var totalSummarized = 0
        try {
            while (true) {
                val settings = settingsStore.settingsFlow.first()
                val conv = conversationRepo.getConversationById(id) ?: break
                val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
                val toSummarizeEntities =
                    conversationRepo.getMessagesForSummary(id.toString(), conv.lastSummarizedMessageTime,100)
                if (toSummarizeEntities.size < 2) break
                val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
                val model = settings.findModelById(modelId)
                    ?: assistant.chatModelId?.let { settings.findModelById(it) }
                    ?: settings.getCurrentChatModel()
                    ?: return@withContext ContextRefreshResult(false, errorMessage = "没有找到可用模型")
                val provider = model.findProvider(settings.providers) ?: return@withContext ContextRefreshResult(false)
                val handler = providerManager.getProviderByType(provider)
                val text = StringBuilder().apply {
                    toSummarizeEntities.forEach { entity ->
                        val uiMsg = decodeUIMessageOrNull(entity.contentJson, tagExtra = "summarize:$id")
                            ?: return@forEach
                        // ✨ 仅将 user 和 assistant 的内容拼接给总结模型
                        if (uiMsg.role == MessageRole.USER || uiMsg.role == MessageRole.ASSISTANT) {
                            append(uiMsg.role).append(": ").append(uiMsg.toContentText()).append("\n")
                        }
                    }
                }.toString()
                if (text.isBlank()) {
                    // 如果这一批 100 条全是 tool 消息，虽然没有核心内容可总结，
                    // 但我们仍然需要更新 lastSummarizedMessageTime，否则会卡在这里死循环
                    val lastMsgTime = toSummarizeEntities.last().createdAt
                    mutateConversationAndSave(id) { current ->
                        current.copy(lastSummarizedMessageTime = lastMsgTime)
                    }
                    continue // 跳过 AI 调用，处理下一批
                }
                val locale = Locale.getDefault().displayName
                val tempPrompt = fillPrompt(
                    DEFAULT_TEMP_SUMMARY_PROMPT, mapOf(
                        "new_messages" to text,
                        "locale" to locale,
                        "char" to assistant.name
                    )
                )
                val providerHandler = handler as Provider<ProviderSetting>
                val tempResp = providerHandler.generateText(
                    provider,
                    listOf(UIMessage.user(tempPrompt)),
                    TextGenerationParams(model, 0.3f, 1.0f, thinkingBudget = 0)
                )
                tempResp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
                val aiResponse = tempResp.choices.firstOrNull()?.message?.toContentText() ?: ""
                if (aiResponse.isNotBlank()) {
                    val backgroundRegex = Regex("""\[(?:Background|背景)][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)
                    val keywordsRegex = Regex("""\[(?:Keywords|关键词)][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)
                    val backgroundMatch = backgroundRegex.find(aiResponse)?.groupValues?.get(1)?.trim()
                    val keywordsMatch = keywordsRegex.find(aiResponse)?.groupValues?.get(1)?.trim()
                    val finalBackground =
                        backgroundMatch ?: aiResponse.lines().firstOrNull { it.isNotBlank() && !it.startsWith("[") }
                        ?: aiResponse
                    val aiKeywords = keywordsMatch ?: ""
                    val fullContextualContent = """
                        [Background]: $finalBackground
                        [Original Text]:
                        $text
                    """.trimIndent()
                    val localKeywords = KeywordExtractor.extract(finalBackground)
                    val keywords = mergeKeywords(aiKeywords, localKeywords)
                    val embeddingResult = try {
                        embeddingService.embedWithModelId(fullContextualContent, assistant.id.toString())
                    } catch (e: Exception) {
                        null
                    }
                    val segment = ChatSegmentEntity(
                        assistantId = assistant.id.toString(),
                        conversationId = id.toString(),
                        content = finalBackground,
                        keywords = keywords,
                        startMessageIndex = -1,
                        endMessageIndex = -1,
                        startTime = toSummarizeEntities.first().createdAt,
                        endTime = toSummarizeEntities.last().createdAt,
                        embedding = embeddingResult?.embeddings?.firstOrNull()?.let { VectorUtils.fromList(it) },
                        embeddingModelId = embeddingResult?.modelId
                    )
                    memoryRepository.saveSegment(segment)
                }
                val lastMsgTime = toSummarizeEntities.last().createdAt
                mutateConversationAndSave(id) { current ->
                    current.copy(
                        lastSummarizedMessageTime = lastMsgTime,
                        lastRefreshTime = System.currentTimeMillis()
                    )
                }
                totalSummarized += toSummarizeEntities.size
                if (toSummarizeEntities.size < 100) break
            }
            if (!skipArchive) {
                archiveConversation(id, force = true, skipEmbedding = true)
            }
            ContextRefreshResult(true, "Segments updated", totalSummarized)
        } catch (e: Exception) {
            Log.e(TAG, "summarizeAndRefresh failed for $id", e)
            ContextRefreshResult(false, errorMessage = e.message)
        } finally {
            summarizingConversations.remove(id)
        }
    }


    private fun mergeKeywords(ai: String, local: String): String {
        val aiList = ai.split(Regex("[,，、；;]")).map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (aiList.isNotEmpty()) return aiList.distinct().joinToString(",")
        val localList = local.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return localList.distinct().joinToString(",")
    }

    /**
     * 截断当前 conversation 里"最新一条 ASSISTANT 消息"的文字内容。
     * 用于通话模式手动打断 AI 回复：只保留"已经 TTS 念过的前缀"，删掉没念完的尾巴。
     *
     * - 每条 UIMessage 里可能有多个 Text part：按它们在 parts 列表中的顺序
     *   逐个拼接文本长度进行截断。非 Text part（图片、附件等）原样保留。
     * - 如果 maxTextLen <= 0：删除整个消息体（为空 UIMessage.parts = emptyList()）。
     * - 如果 maxTextLen >= 总文字长度：什么都不做。
     */
    suspend fun truncateLastAssistantMessage(conversationId: Uuid, maxTextLen: Int) {
        if (maxTextLen < 0) return
        mutateConversationAndSave(conversationId) { conv ->
            val nodes = conv.messageNodes
            val idx = nodes.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (idx == -1) return@mutateConversationAndSave conv
            val oldNode = nodes[idx]
            val curMsg = oldNode.currentMessage
            if (curMsg.parts.isEmpty()) return@mutateConversationAndSave conv
            if (curMsg.role != MessageRole.ASSISTANT) return@mutateConversationAndSave conv

            val allTextLen = curMsg.parts.sumOf { (it as? UIMessagePart.Text)?.text?.length ?: 0 }
            if (maxTextLen >= allTextLen) return@mutateConversationAndSave conv

            val newParts = mutableListOf<UIMessagePart>()
            var remaining = maxTextLen
            for (part in curMsg.parts) {
                when (part) {
                    is UIMessagePart.Text -> {
                        if (remaining <= 0) continue
                        val t = part.text
                        if (t.length <= remaining) {
                            newParts.add(part); remaining -= t.length
                        } else {
                            val cut = t.substring(0, remaining.coerceAtMost(t.length))
                            newParts.add(part.copy(text = cut))
                            remaining = 0
                        }
                    }
                    else -> newParts.add(part)
                }
            }

            val newMsg = curMsg.copy(parts = newParts)
            val newNodeMessages = oldNode.messages.mapIndexed { i, m ->
                if (i == oldNode.selectIndex) newMsg else m
            }
            val newNode = oldNode.copy(messages = newNodeMessages)
            conv.copy(messageNodes = nodes.toMutableList().apply { set(idx, newNode) })
        }
    }

    /**
     * 用后台模型判断用户是否有挂断通话的意图（不影响对话模型）。
     * 异步调用，返回 true 表示用户想结束通话。
     */
    suspend fun checkCallHangupIntent(
        conversationId: Uuid,
        userText: String,
        aiReplyText: String
    ): Boolean {
        return runCatching {
            val settings = settingsStore.settingsFlow.first()
            val conv = getConversationFlow(conversationId).value
            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
            val modelId = assistant.backgroundModelId ?: settings.backgroundModelId
            val model = settings.findModelById(modelId) ?: return false
            val provider = model.findProvider(settings.providers) ?: return false
            val handler = providerManager.getProviderByType(provider) as? Provider<ProviderSetting> ?: return false
            Log.i(TAG, "HangupCheck: bgModel=${model.modelId} ")

            val prompt = buildString {
                appendLine("你是通话结束检测助手。下面是用户和AI在通话中最后一轮的对话：")
                appendLine("用户说：\"$userText\"")
                appendLine("AI回复：\"$aiReplyText\"")
                appendLine()
                appendLine("判断用户是否在表达结束通话/挂断的意图（如再见/拜拜/挂了/不聊了/回头说等）。")
                appendLine("只回复 YES 或 NO，不要回复其他任何内容。")
            }

            val result = handler.generateText(
                provider,
                listOf(UIMessage.user(prompt)),
                TextGenerationParams(model, temperature = 0.0f, thinkingBudget = 0)
            )
            val rawResponse = result.choices.firstOrNull()?.message?.toContentText()?.trim()
            val responseText = rawResponse?.uppercase()
            val shouldHangup = responseText?.startsWith("YES") == true
            Log.i(TAG, "HangupCheck: modelRawReply=\"$rawResponse\" -> parsed=$shouldHangup")
            shouldHangup
        }.onFailure {
            Log.w("ChatService", "checkCallHangupIntent failed", it)
        }.getOrDefault(false)
    }

    /** Apply a field/node mutation to the latest in-memory value, then persist it. */
    suspend fun mutateConversationAndSave(
        id: Uuid,
        transform: (Conversation) -> Conversation
    ): Conversation? {
        val mutex = persistenceMutexes.computeIfAbsent(id) { Mutex() }
        return mutex.withLock {
            if (!ensureConversationLoaded(id)) return@withLock null
            val previous = getConversationFlow(id).value
            val updated = normalizeConversationForSave(
                id = id,
                conversation = transform(previous),
                expectedAssistantId = previous.assistantId
            )
            val filesToDelete = findRemovedFiles(previous, updated)
            updateConversation(id) { updated }
            if (!temporaryConversations.contains(id)) {
                try {
                    conversationRepo.saveConversation(updated)
                    deleteFilesAsync(id, filesToDelete)
                } catch (error: Throwable) {
                    updateConversation(id) { current -> if (current == updated) previous else current }
                    throw error
                }
            } else {
                deleteFilesAsync(id, filesToDelete)
            }
            updated
        }
    }

    suspend fun replaceConversationMessages(
        conversationId: Uuid,
        transform: (Conversation) -> ConversationMessageDeletion?
    ): ConversationMessageDeletion? {
        val mutex = persistenceMutexes.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock {
            if (!ensureConversationLoaded(conversationId)) return@withLock null
            val previous = getConversationFlow(conversationId).value
            val replacement = transform(previous) ?: return@withLock null
            val safe = normalizeConversationForSave(
                id = conversationId,
                conversation = replacement.conversation,
                expectedAssistantId = previous.assistantId
            )
            val normalizedReplacement = replacement.copy(conversation = safe)
            val filesToDelete = findRemovedFiles(previous, safe)
            updateConversation(conversationId) { safe }
            if (temporaryConversations.contains(conversationId)) {
                deleteFilesAsync(conversationId, filesToDelete)
                return@withLock normalizedReplacement
            }
            try {
                conversationRepo.replaceConversationMessages(
                    conversation = safe,
                    deletedNodeIds = normalizedReplacement.deletedNodeIds,
                    deletedMessageIds = normalizedReplacement.deletedMessageIds
                )
                deleteFilesAsync(conversationId, filesToDelete)
            } catch (error: Throwable) {
                updateConversation(conversationId) { current -> if (current == safe) previous else current }
                throw error
            }
            normalizedReplacement
        }
    }

    private fun normalizeConversationForSave(
        id: Uuid,
        conversation: Conversation,
        expectedAssistantId: Uuid
    ): Conversation {
        require(conversation.id == id) {
            "Conversation id ${conversation.id} does not match persistence target $id"
        }
        val normalized = conversation.normalizeMessageNodes()
        return if (expectedAssistantId != normalized.assistantId) {
            normalized.copy(assistantId = expectedAssistantId)
        } else {
            normalized
        }
    }

    fun getAiTypingFlow(id: Uuid): Flow<Boolean> = _isAiTypingMap.map { it[id] ?: false }

    fun cleanupConversation(id: Uuid) {
        _generationJobs.value[id]?.cancel()
        removeGenerationJob(id)
        conversations.remove(id)
        initializedConversationIds.remove(id)
        conversationMutexes.remove(id)
    }

    /**
     * 仅停止指定会话的生成任务（不清理会话状态）。
     * 用于通话打断等场景：需要立即取消 AI 生成但保留会话上下文。
     */
    fun stopGeneration(conversationId: Uuid) {
        _generationJobs.value[conversationId]?.cancel()
        removeGenerationJob(conversationId)
        _isAiTypingMap.update { it - conversationId }
    }

    /**
     * 标记/取消会话的"通话模式"。
     * - 启用后, 主对话路径将跳过每次 AI 响应后的 L1 自动摘要；
     * - 改由 [VoiceCallManager] 内部的 25 分钟定时器调用 [summarizeForCallIfNeeded] 主动触发。
     * - 用于避免实时通话中频繁触发 AI 摘要调用占用并发槽位、增加端到端延迟。
     */
    fun setCallMode(conversationId: Uuid, active: Boolean) {
        if (active) callModeConversations.add(conversationId)
        else callModeConversations.remove(conversationId)
    }

    /**
     * 通话定时器入口：检查并按需触发该会话的 L1 自动摘要。
     * 内部直接复用主路径的 [checkAndAutoSummarize] 逻辑（含阈值判断, 未达阈值会快速返回）。
     */
    suspend fun summarizeForCallIfNeeded(conversationId: Uuid) {
        if (!callModeConversations.contains(conversationId)) return
        val settings = settingsStore.settingsFlow.value
        val conv = getConversationFlow(conversationId).value
        checkAndAutoSummarize(conversationId, conv, settings)
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        if (_generationJobs.value.containsKey(conversationId)) return
        updateConversation(conversationId) { conversation ->
            conversation.removeInvalidMessages()
        }
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val responseAnchorId = conversation.currentMessages.lastOrNull()?.id ?: return
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
            val modelId = assistant.suggestionModelId ?: settings.suggestionModelId
            val model = settings.findModelById(modelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val result = (providerManager.getProviderByType(provider) as Provider<ProviderSetting>).generateText(
                provider, listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages.truncate(conversation.truncateIndex).takeLast(8)
                                .joinToString("\n") { it.summaryAsText() })
                    )
                ), TextGenerationParams(model, 1.0f, 1.0f, thinkingBudget = 0)
            )
            result.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
            val suggestions = result.choices.firstOrNull()?.message?.toContentText()
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            mutateConversationAndSave(conversationId) { current ->
                if (current.currentMessages.lastOrNull()?.id == responseAnchorId) {
                    current.copy(chatSuggestions = suggestions)
                } else {
                    current
                }
            }
        }
    }

    private val conversationDeletionJobs = ConcurrentHashMap<Uuid, Job>()
    private val recentlyDeletedConversations = ConcurrentHashMap<Uuid, Conversation>()
    private val _recentlyRestoredIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val recentlyRestoredIds: StateFlow<Set<Uuid>> = _recentlyRestoredIds

    fun deleteConversation(conversation: Conversation) {
        appScope.launch {
            val full = conversationRepo.getConversationById(conversation.id) ?: return@launch
            conversationDeletionJobs[conversation.id]?.cancel()
            conversationRepo.deleteConversation(full, false)
            recentlyDeletedConversations[conversation.id] = full
            conversationDeletionJobs[conversation.id] = appScope.launch {
                delay(4000); context.deleteChatFiles(full.files); recentlyDeletedConversations.remove(conversation.id)
            }
        }
    }

    fun undoDeleteConversation(conversationId: Uuid) {
        conversationDeletionJobs[conversationId]?.cancel()
        recentlyDeletedConversations.remove(conversationId)?.let { conv ->
            appScope.launch {
                conversationRepo.insertConversation(conv); _recentlyRestoredIds.value += conversationId; delay(1000); _recentlyRestoredIds.value -= conversationId
            }
        }
    }

    private fun sendGenerationDoneNotification(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val lastMsg = conversation.currentMessages.lastOrNull()
        // skipContext=true 的消息是隐形对话（日记评论、agent_task 等），不推送系统通知避免泄露内容
        if (lastMsg == null || lastMsg.skipContext) return
        val msg = lastMsg.toContentText()?.take(50) ?: ""
        val notification = NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(assistant.name)
            .setContentText(msg)
            .setSmallIcon(R.drawable.about_logo)
            .setAutoCancel(true)
            .setContentIntent(getPendingIntent(context, conversationId))
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(1, notification.build())
        }
    }

    private fun getPendingIntent(context: Context, id: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", id.toString())
        }
        return PendingIntent.getActivity(
            context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateConversation(
        id: Uuid,
        normalizeNodes: Boolean = false,
        block: (Conversation) -> Conversation
    ) {
        val flow = (conversations[id] ?: getConversationFlow(id)) as MutableStateFlow<Conversation>
        flow.update { old ->
            val transformed = block(old)
            val new = if (normalizeNodes) transformed.normalizeMessageNodes() else transformed
            if (new.id != id) return@update old
            new
        }
    }

    private fun findRemovedFiles(previous: Conversation, updated: Conversation): List<Uri> {
        val updatedFiles = updated.files.toSet()
        return previous.files.filterNot { file -> file in updatedFiles }.distinct()
    }

    private fun deleteFilesAsync(conversationId: Uuid, filesToDelete: List<Uri>) {
        if (filesToDelete.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                // Keep message-level undo recoverable and re-check references before
                // performing the irreversible file operation.
                delay(5000)
                val latestConversation = conversations[conversationId]?.value
                    ?: conversationRepo.getConversationById(conversationId)
                val referencedFiles = latestConversation?.files?.toSet().orEmpty()
                val confirmedOrphans = filesToDelete.filterNot { file -> file in referencedFiles }
                if (confirmedOrphans.isNotEmpty()) {
                    runCatching { context.deleteChatFiles(confirmedOrphans) }
                }
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.truncateLargeJsonText(maxLength: Int = 32000): kotlinx.serialization.json.JsonElement {
    return when (this) {
        is JsonPrimitive -> if (this.isString && this.content.length > maxLength) JsonPrimitive(
            this.content.take(
                maxLength
            ) + "... (truncated)"
        ) else this

        is JsonObject -> JsonObject(this.mapValues { it.value.truncateLargeJsonText(maxLength) })
        is JsonArray -> JsonArray(this.map { it.truncateLargeJsonText(maxLength) })
    }
}
