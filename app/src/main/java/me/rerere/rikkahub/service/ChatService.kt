package me.rerere.rikkahub.service

import android.Manifest
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
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.truncate
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.common.JsonInstantPretty
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.MemoryType
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantSearchMode
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.core.data.model.toMessageNode
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TEMP_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_KEYWORD_EXTRACTION_PROMPT
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
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.core.data.utils.KeywordExtractor
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

private const val TAG = "ChatService"

private val inputTransformers by lazy {
    listOf(
        PlaceholderTransformer,
        me.rerere.rikkahub.data.ai.transformers.UnsupportedFileTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
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
    private val _generationJobs = MutableStateFlow<Map<Uuid, Job?>>(emptyMap())
    private val generationJobs: StateFlow<Map<Uuid, Job?>> = _generationJobs.asStateFlow()
    private val _errorFlow = MutableSharedFlow<Throwable>()
    val errorFlow: SharedFlow<Throwable> = _errorFlow.asSharedFlow()
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val _syncingConversationIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val syncingConversationIds: StateFlow<Set<Uuid>> = _syncingConversationIds.asStateFlow()

    private var lastConversationId: Uuid? = null

    private val promptPlaceholderRegex = Regex("\\{\\{(\\w+)\\}\\}")

    // 并发防抖锁集合
    private val archivingConversations = ConcurrentHashMap.newKeySet<Uuid>()
    private val summarizingConversations = ConcurrentHashMap.newKeySet<Uuid>()

    // 会话级别的互斥锁，确保同一时间只有一个生成任务在处理网络/状态更新
    private val conversationMutexes = ConcurrentHashMap<Uuid, Mutex>()

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
                    } else {
                        Log.d(TAG, "Jobs running in background, service will continue if already started.")
                    }
                } else {
                    // 只要任务清空，必须停止服务
                    ChatForegroundService.stop(context)
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
            // 方案 C：如果没有任何引用了，且没有生成任务在跑，立即清理内存缓存
            appScope.launch {
                delay(100) // 给转场留一点点余地
                if (!hasReference(conversationId)) {
                    cleanupConversation(conversationId)
                }
            }
        } else {
            conversationReferences[conversationId] = currentCount - 1
        }
    }

    suspend fun executeAgentTask(task: me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity) {
        val data = me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(task.taskData) as? JsonObject ?: return

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
            Log.d(TAG, "Task Trigger: Found active session $activeConvId for assistant")
            activeConvId
        } else {
            val lastDbId =
                conversationRepo.getAllConversations().first().filter { it.assistantId == originalAssistantId }
                    .maxByOrNull { it.updateAt }?.id

            Log.d(TAG, "Task Trigger: No active session, using DB last session: $lastDbId")
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
                val currentConv = getConversationFlow(conversationId).value

                val newNode = UIMessage(
                    role = MessageRole.USER, parts = listOf(UIMessagePart.Text(monitorMsg)), skipContext = true
                ).toMessageNode()

                val updatedConv = currentConv.copy(
                    messageNodes = currentConv.messageNodes + newNode, updateAt = Instant.now()
                )

                updateConversation(conversationId, updatedConv)
                saveConversation(conversationId, updatedConv)

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
                    setGenerationJob(conversationId, null)
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
        // 方案 C：遍历当前缓存，清理掉所有没有引用且没有任务的会话
        conversations.keys.forEach { if (!hasReference(it)) cleanupConversation(it) }
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        val settings = settingsStore.settingsFlow.value
        val currentAssistant = settings.getCurrentAssistant()

        // 方案 C：强制限制同时在内存中的 Flow 数量（LRU 简单实现）
        if (!conversations.containsKey(conversationId) && conversations.size >= 5) {
            val toRemove = conversations.keys.firstOrNull { !hasReference(it) }
            toRemove?.let { cleanupConversation(it) }
        }

        return conversations.getOrPut(conversationId) {
            MutableStateFlow(
                Conversation.ofId(
                    id = conversationId,
                    assistantId = currentAssistant.id,
                    isVirtual = currentAssistant.isVirtualWorldMode
                )
            )
        }
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> = generationJobs.map { it[conversationId] }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = generationJobs

    private fun setGenerationJob(conversationId: Uuid, job: Job?) {
        if (job == null) {
            removeGenerationJob(conversationId)
            return
        }
        // 使用原子更新防止任务泄漏
        _generationJobs.update { it + (conversationId to job) }
    }

    private fun removeGenerationJob(conversationId: Uuid) {
        // 使用原子更新
        _generationJobs.update { it - conversationId }
    }

    suspend fun initializeConversation(conversationId: Uuid, targetAssistantId: Uuid? = null) {
        val currentConvInDb = conversationRepo.getConversationById(conversationId)
        val currentConv = conversations[conversationId]?.value ?: currentConvInDb

        val currentAssistantId = targetAssistantId
            ?: currentConv?.assistantId
            ?: settingsStore.settingsFlowRaw.first().getCurrentAssistant().id

        lastConversationId?.let { oldId ->
            if (oldId != conversationId) {
                // 【关键点】：去数据库查一下，看看当前进入的这个 ID 是不是一个从未见过的新会话
                val isNewConversation = currentConvInDb == null ||
                    currentConvInDb.currentMessages.none { it.role == MessageRole.USER }

                // 只有进入“全新会话”时，才触发对上一个会话 (oldId) 的归档和同步动画
                if (isNewConversation) {
                    val oldConv = conversationRepo.getConversationById(oldId)

                    if (oldConv != null && oldConv.assistantId == currentAssistantId) {
                        val settings = settingsStore.settingsFlow.first()
                        val assistant = settings.getAssistantById(oldConv.assistantId) ?: settings.getCurrentAssistant()

                        if (assistant.enableMemory) {
                            appScope.launch {
                                if (assistant.enableRecentChatsReference) {
                                    // 只有新建会话且开启了连贯模式，才显示动画
                                    _syncingConversationIds.update { it + conversationId }
                                    try {
                                        if (assistant.enableDetailMemory) {
                                            launch { summarizeAndRefresh(oldId, onlySegments = true, skipArchive = true) }
                                        }
                                        archiveConversation(oldId, force = true)
                                    } finally {
                                        _syncingConversationIds.update { it - conversationId }
                                    }
                                } else {
                                    // 未开启连贯模式：静默后台执行
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
                    // 如果是切换到已有的旧会话，或者返回主页，这里什么都不做，直接跳过归档
                    Log.d(TAG, "切换到已有会话或返回主页，跳过自动归档逻辑。")
                }
            }
        }

        lastConversationId = conversationId

        val conversation = currentConv
        if (currentConvInDb != null) {
            updateConversation(conversationId, currentConvInDb)
            if (targetAssistantId == null) {
                settingsStore.updateAssistant(currentConvInDb.assistantId)
            }
        } else {
            val settings = settingsStore.settingsFlowRaw.first()
            val assistant = settings.getAssistantById(currentAssistantId) ?: settings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId, assistantId = assistant.id, isVirtual = assistant.isVirtualWorldMode
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
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

            if (!force) {
                if (existingEpisode != null) {
                    if (messages.size - episodeSignificance < 4) return
                } else if (messages.size < 4) {
                    return
                }
            }

            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()

            if (!assistant.enableMemoryConsolidation && !force) return

            val baseSummary = existingEpisode?.content
            val newMessages = messages.drop(episodeSignificance)

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
                // 方案 B 优化：使用 StringBuilder 避免超长会话拼接时的 OOM
                val messagesText = StringBuilder().apply {
                    newMessages.forEach { msg ->
                        append(msg.role).append(": ").append(msg.toContentText().take(2000)).append("\n")
                    }
                }.toString()

                val locale = Locale.getDefault().displayName

                val prompt = fillPrompt(DEFAULT_FULL_SUMMARY_PROMPT, mapOf(
                    "previous_summary" to (baseSummary?.removePrefix("虚拟世界：") ?: "None"),
                    "new_messages" to messagesText,
                    "locale" to locale,
                    "char" to assistant.name
                ))

                val providerHandler = handler as Provider<ProviderSetting>
                val resp = retryIO(times = 2) {
                    providerHandler.generateText(
                        provider, listOf(UIMessage.user(prompt)), TextGenerationParams(model, 0.3f, 1.0f, thinkingBudget = 0)
                    )
                }
                resp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
                resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
            }

            if (summary.isNotBlank() && (!newMessages.isEmpty() || force)) {
                val aiKeywords = extractKeywords(
                    handler = backgroundHandler,
                    providerSetting = backgroundProvider,
                    model = backgroundModel,
                    summary = summary,
                    assistantId = assistant.id.toString()
                )
                val localKeywords = KeywordExtractor.extract(summary)
                val keywords = mergeKeywords(aiKeywords, localKeywords)

                val embeddingResult = if (skipEmbedding) {
                    null
                } else {
                    val effectiveContent =
                        if (keywords.isNotBlank()) "Keywords: $keywords\nContent: $summary" else summary
                    try {
                        embeddingService.embedWithModelId(effectiveContent, assistant.id.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate embedding", e)
                        null
                    }
                }

                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0,
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    content = if (conv.isVirtual) "虚拟世界：${summary.removePrefix("虚拟世界：")}" else summary,
                    keywords = keywords,
                    embedding = if (skipEmbedding) {
                        existingEpisode?.embedding
                    } else {
                        embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) }
                    },
                    embeddingModelId = embeddingResult?.modelId,
                    startTime = conv.createAt.toEpochMilli(),
                    endTime = conv.updateAt.toEpochMilli(),
                    significance = messages.size,
                    lastAccessedAt = System.currentTimeMillis()
                )
                memoryRepository.saveEpisode(episode)
                Log.i(TAG, "Archived L2 memory for $conversationId. force=$force, skipEmbedding=$skipEmbedding")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive conversation $conversationId", e)
        } finally {
            archivingConversations.remove(conversationId)
        }
    }

    fun sendMessage(
        conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true, isTemporaryChat: Boolean = false
    ) {
        if (isTemporaryChat) temporaryConversations.add(conversationId)

        val oldJob = _generationJobs.value[conversationId]
        oldJob?.cancel()

        val job = appScope.launch {
            try {
                // 确保互斥：等旧任务彻底释放锁，或者超时 200ms 强制继续
                val mutex = conversationMutexes.getOrPut(conversationId) { Mutex() }
                withTimeoutOrNull(200) { mutex.lock() }

                try {
                    initializeConversation(conversationId)

                    val currentConversation = getConversationFlow(conversationId).value
                    val newNode = UIMessage(role = MessageRole.USER, parts = content).toMessageNode()
                    val newConversation = currentConversation.copy(
                        messageNodes = currentConversation.messageNodes + newNode
                    )
                    saveConversation(conversationId, newConversation)
                    conversationRepo.recordDailyActivity()
                    if (answer) handleMessageComplete(conversationId)
                    _generationDoneFlow.emit(conversationId)
                } finally {
                    if (mutex.isLocked) mutex.unlock()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _errorFlow.emit(translateError(e))
                }
            }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion {
            setGenerationJob(
                conversationId, null
            ); appScope.launch { delay(500); checkAllConversationsReferences() }
        }
    }

    fun regenerateAtMessage(
        conversationId: Uuid, message: UIMessage, regenerateAssistantMsg: Boolean = true, forceWipe: Boolean = false
    ) {
        val oldJob = _generationJobs.value[conversationId]
        oldJob?.cancel()

        val job = appScope.launch {
            try {
                // 方案 A: 优雅的互斥锁 + 呼吸期
                // 我们不使用 while 循环死等，而是使用 Mutex 来保证网络流不会重叠。
                val mutex = conversationMutexes.getOrPut(conversationId) { Mutex() }

                // 给旧任务 150ms 释放 native 资源的机会（通常断开网络连接只需要 20-50ms）
                // 如果 150ms 还没排队排到，强行开始，防止 UI 卡顿
                withTimeoutOrNull(150) { mutex.lock() }

                try {
                    initializeConversation(conversationId)
                    val conversation = getConversationFlow(conversationId).value
                    if (message.role == MessageRole.USER) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val indexAt = conversation.messageNodes.indexOf(node)
                        val newConversation =
                            conversation.copy(messageNodes = conversation.messageNodes.subList(0, indexAt + 1))
                        saveConversation(conversationId, newConversation)
                        handleMessageComplete(conversationId)
                    } else if (regenerateAssistantMsg) {
                        val clickedNode = conversation.getMessageNodeByMessage(message)
                        val clickedIndex = conversation.messageNodes.indexOf(clickedNode)
                        val lastUserIndex = conversation.messageNodes.subList(0, clickedIndex + 1)
                            .indexOfLast { it.role == MessageRole.USER }

                        if (lastUserIndex >= 0) {
                            val firstAssistantIndex = lastUserIndex + 1
                            val turnEndIndex =
                                conversation.messageNodes.subList(firstAssistantIndex, conversation.messageNodes.size)
                                    .indexOfFirst { it.role == MessageRole.USER }
                                    .let { if (it == -1) conversation.messageNodes.size else firstAssistantIndex + it }

                            if (forceWipe) {
                                val nodes = conversation.messageNodes.subList(0, lastUserIndex + 1).toMutableList()
                                nodes.add(
                                    MessageNode(
                                        id = Uuid.random(),
                                        messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
                                    )
                                )
                                if (turnEndIndex < conversation.messageNodes.size) nodes.addAll(
                                    conversation.messageNodes.subList(
                                        turnEndIndex, conversation.messageNodes.size
                                    )
                                )
                                saveConversation(conversationId, conversation.copy(messageNodes = nodes))
                                handleMessageComplete(conversationId)
                            } else {
                                val versionTag = Uuid.random().toString()
                                val nodes = conversation.messageNodes.subList(0, lastUserIndex + 1).toMutableList()
                                val firstAssistant = conversation.messageNodes.getOrNull(firstAssistantIndex)
                                if (firstAssistant != null) {
                                    val newMessages = firstAssistant.messages + UIMessage(
                                        role = MessageRole.ASSISTANT, parts = emptyList(), versionTag = versionTag
                                    )
                                    nodes.add(
                                        firstAssistant.copy(
                                            messages = newMessages, selectIndex = newMessages.lastIndex
                                        )
                                    )
                                    if (turnEndIndex < conversation.messageNodes.size) nodes.addAll(
                                        conversation.messageNodes.subList(
                                            turnEndIndex, conversation.messageNodes.size
                                        )
                                    )
                                }
                                saveConversation(conversationId, conversation.copy(messageNodes = nodes))
                                handleMessageComplete(conversationId)
                            }
                        } else handleMessageComplete(conversationId, messageRange = 0..<clickedIndex)
                    }
                } finally {
                    // 只有拿到了锁才需要释放
                    if (mutex.isLocked) mutex.unlock()
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _errorFlow.emit(translateError(e))
                }
            }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion {
            setGenerationJob(
                conversationId, null
            ); appScope.launch { delay(500); checkAllConversationsReferences() }
        }
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        assistantOverride: Assistant? = null,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = false
    ) {
        val settings = settingsStore.settingsFlow.first()
        runCatching {

            val conversation = getConversationFlow(conversationId).value
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))
            val assistant = assistantOverride ?: settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
            val modelId = assistant.chatModelId ?: settings.chatModelId
            val model = settings.findModelById(modelId) ?: settings.getCurrentChatModel() ?: return@runCatching
            var firstTokenTime: Long? = null
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                val hasExternalTools =
                    (assistant.searchMode !is AssistantSearchMode.Off) || mcpManager.getAllAvailableTools().isNotEmpty()
                if (hasExternalTools) _errorFlow.emit(IllegalStateException(context.getString(R.string.tools_warning)))
            }

            checkInvalidMessages(conversationId)
            val retrievedMemories = withContext(Dispatchers.IO) {
                if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF && !temporaryConversations.contains(
                        conversationId
                    )
                ) {
                    kotlinx.coroutines.withTimeoutOrNull(8000) {
                        if (assistant.useRagMemoryRetrieval) {
                            val lastUserMsg =
                                conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.toText() ?: ""
                            if (lastUserMsg.isNotBlank()) {
                                val results = memoryRepository.retrieveRelevantMemoriesWithScores(
                                    assistantId = assistant.id.toString(),
                                    query = lastUserMsg,
                                    limit = assistant.ragLimit,
                                    similarityThreshold = assistant.ragSimilarityThreshold,
                                    includeCore = assistant.ragIncludeCore,
                                    includeEpisodes = assistant.ragIncludeEpisodes,
                                    mode = assistant.memoryRetrievalMode,
                                    excludeConversationId = conversationId.toString()
                                )
                                val memories = results.map { it.first }
                                if (settings.enableRagLogging) {
                                    results.forEach { (mem, score) ->
                                        Log.d(
                                            "RAG", " - [${mem.type}] (Score: ${
                                                String.format(
                                                    "%.4f", score
                                                )
                                            }) ${mem.content.take(50)}..."
                                        )
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

            // 硬超时保护：20 分钟
            kotlinx.coroutines.withTimeout(20 * 60 * 1000L) {
                generationHandler.generateText(
                    settings = settings,
                    model = model,
                    messages = conversation.currentMessages.let {
                        if (messageRange != null) it.subList(
                            messageRange.start, messageRange.endInclusive + 1
                        ) else it
                    },
                    assistant = assistant,
                    memories = retrievedMemories,
                    inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer) },
                    outputTransformers = outputTransformers,
                    tools = buildList {
                        val isMain = assistant.isMain
                        val isVirtual = conversation.isVirtual

                        val supportsBuiltIn =
                            model.tools.isNotEmpty() || ModelRegistry.GEMINI_SERIES.match(model.modelId)
                        val useBuiltIn = assistant.preferBuiltInSearch && supportsBuiltIn
                        val searchMode = assistant.searchMode

                        if (searchMode is AssistantSearchMode.Provider && !useBuiltIn) {
                            addAll(createSearchTool(settings, assistant, searchMode.index))
                        }

                        val targetOptions = if (isVirtual) {
                            assistant.localTools.filter { it is LocalToolOption.TimeSense }
                        } else if (isMain) {
                            if (assistant.enableMemory) {
                                assistant.localTools.toMutableList().apply {
                                    if (!any { it is LocalToolOption.MilestoneManagement }) {
                                        add(LocalToolOption.MilestoneManagement)
                                    }
                                }
                            } else {
                                assistant.localTools.filter { it !is LocalToolOption.MilestoneManagement }
                            }
                        } else {
                            assistant.localTools.filter {
                                it is LocalToolOption.TimeSense || it is LocalToolOption.EmailService
                            }
                        }

                        addAll(
                            localTools.getTools(
                                options = targetOptions,
                                assistantId = assistant.id,
                                conversationId = conversation.id,
                                userImageUrls = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.parts?.filterIsInstance<UIMessagePart.Image>()
                                    ?.map { it.url } ?: emptyList()))

                        if (isMain && !isVirtual) {
                            val nameRegex = Regex("[^a-zA-Z0-9_.:-]")
                            mcpManager.getAllAvailableTools().forEach { mcpTool ->
                                val originalName = mcpTool.name
                                val sanitizedName = originalName.replace(nameRegex, "_").let {
                                    if (it.firstOrNull()?.isLetter() == true || it.startsWith("_")) it else "_$it"
                                }

                                add(
                                    Tool(
                                        name = sanitizedName,
                                        description = mcpTool.description ?: "",
                                        parameters = { mcpTool.inputSchema },
                                        execute = {
                                            mcpManager.callTool(originalName, it.jsonObject).truncateLargeJsonText()
                                        })
                                )
                            }
                        }
                    },
                    truncateIndex = conversation.truncateIndex,
                    enabledModeIds = conversation.enabledModeIds,
                    contextSummary = currentEpisode?.content?.removePrefix("虚拟世界："),
                    temporarySummaries = emptyList(),
                    skipContextForResponse = skipContextForResponse,
                    includeSkipContextMessages = includeSkipContextMessages,
                    conversationId = conversationId
                ).onCompletion {
                    val duration = firstTokenTime?.let { System.currentTimeMillis() - it }
                    val current = getConversationFlow(conversationId).value
                    val updated = current.copy(messageNodes = current.messageNodes.mapIndexed { idx, node ->
                        val isLast = idx == current.messageNodes.lastIndex
                        node.copy(messages = node.messages.map { msg ->
                            val finished = msg.finishReasoning()
                            if (isLast && finished.role == MessageRole.ASSISTANT && finished.generationDurationMs == null) finished.copy(
                                generationDurationMs = duration
                            ) else finished
                        })
                    }, updateAt = Instant.now())
                    updateConversation(conversationId, updated)
                    if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) sendGenerationDoneNotification(
                        conversationId
                    )
                }.collect { chunk ->
                    if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                    if (chunk is GenerationChunk.Messages) updateConversation(
                        conversationId, getConversationFlow(conversationId).value.updateCurrentMessages(chunk.messages)
                    )
                }
            }
        }.onFailure { e ->
            Log.d(TAG, "Generation failed/cancelled for $conversationId, saving current state. Error: ${e.message}")
            val finalConv = getConversationFlow(conversationId).value
            appScope.launch {
                saveConversation(conversationId, finalConv)

                val currentSettings = settingsStore.settingsFlow.value
                val updatedAssistants = currentSettings.assistants.map {
                    if (it.id == finalConv.assistantId) it.copy(lastConversationId = conversationId.toString()) else it
                }
                settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
            }

            if (e !is kotlinx.coroutines.CancellationException) {
                val friendlyError = translateError(e)
                _errorFlow.emit(friendlyError)
                Logging.log(TAG, "handleMessageComplete: $friendlyError")
            }
        }.onSuccess {
            val finalConv = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConv)

            val lastAssistantMsg = finalConv.currentMessages.lastOrNull() ?: return@onSuccess
            lastAssistantMsg.usage?.let { usage ->
                appScope.launch {
                    conversationRepo.recordTokenUsage(finalConv.assistantId.toString(), usage)
                }
            }

            appScope.launch {
                val currentSettings = settingsStore.settingsFlow.value
                val updatedAssistants = currentSettings.assistants.map {
                    if (it.id == finalConv.assistantId) {
                        it.copy(lastConversationId = conversationId.toString())
                    } else it
                }
                settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
            }
            addConversationReference(conversationId)
            appScope.launch {
                coroutineScope {
                    launch { generateSuggestion(conversationId, finalConv) }
                    launch { checkAndAutoSummarize(conversationId, finalConv, settings) }
                }
            }.invokeOnCompletion { removeConversationReference(conversationId) }
        }
    }

    private fun translateError(e: Throwable): Throwable {
        val message = e.message ?: ""
        return when {
            message.contains("Unexpected JSON token") && message.contains("< instead") -> {
                IllegalStateException(
                    "请求地址有误或 API 服务异常（收到了 HTML 错误页），请检查提供商设置页面的 API 配置是否正确。",
                    e
                )
            }

            message.contains("400 Bad Request", ignoreCase = true) -> {
                IllegalStateException("请求参数错误 (400)，请确认 API Key 是否有效或模型名称是否正确。", e)
            }

            message.contains("401 Unauthorized", ignoreCase = true) -> {
                IllegalStateException("认证失败 (401)，请在提供商设置页面检查 API Key。", e)
            }

            message.contains("404 Not Found", ignoreCase = true) -> {
                IllegalStateException("服务地址未找到 (404)，请检查 API 基础地址是否正确。", e)
            }

            else -> e
        }
    }

    private fun createSearchTool(settings: Settings, assistant: Assistant, providerIndex: Int? = null): Set<Tool> {
        val idx = providerIndex ?: settings.searchServiceSelected
        var callCount = 0
        return buildSet {
            add(Tool(name = "search_web", description = "search web", parameters = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                SearchService.getService(opt).parameters
            }, execute = {
                if (callCount >= 1) {
                    return@Tool buildJsonObject {
                        put(
                            "error",
                            "Search limit reached (1/1). Do not attempt to search again in this turn. Please summarize what you have or ask the user for clarification."
                        )
                    }
                }
                callCount++
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                val resultSize = 6
                val commonOptions = settings.searchCommonOptions.copy(resultSize = resultSize)

                val searchResult = SearchService.getService(opt).search(it.jsonObject, commonOptions, opt).getOrThrow()

                Log.d(TAG, "Web Search Raw Results (Fixed Request: $resultSize, Got: ${searchResult.items.size})")
                searchResult.items.forEachIndexed { i, item ->
                    Log.v(TAG, "Raw Item [$i]: ${item.title} (${item.url})")
                }

                val htmlRegex = Regex("<[^>]*>")
                val cleanedItems = searchResult.items.take(resultSize).map { item ->
                    // 防御性处理：截断网页正文，防止 OOM
                    item.copy(text = item.text.replace(htmlRegex, "").trim().take(4000))
                }

                Log.i(TAG, "Return raw search results directly (no summary model)")
                buildJsonObject {
                    put("items", JsonArray(cleanedItems.mapIndexed { i, item ->
                        buildJsonObject {
                            put("id", Uuid.random().toString().take(6))
                            put("index", i + 1)
                            put("title", item.title)
                            put("url", item.url)
                            put("text", item.text)
                        }
                    }))
                }
            }, systemPrompt = { _, _ ->
                "## ## tool: search_web\\n\\nNote: Only 1 search allowed per turn. If search fails, inform user."
            }))
        }
    }

    private suspend fun checkAndAutoSummarize(id: Uuid, conv: Conversation, settings: Settings) {
        val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
        if (!assistant.enableMemory) return
        if (!assistant.enableDetailMemory) return
        val max = assistant.detailMemoryThreshold

        val count = if (conv.contextSummaryUpToIndex >= 0) {
            conv.currentMessages.size - (conv.contextSummaryUpToIndex + 1)
        } else {
            conv.currentMessages.size
        }

        if (count >= max) summarizeAndRefresh(id)
    }

    suspend fun summarizeAndRefresh(
        id: Uuid, onlySegments: Boolean = false, skipArchive: Boolean = false
    ): ContextRefreshResult = withContext(Dispatchers.IO) {
        // 方案 1: 原子锁拦截 (修复之前非原子判断的问题)
        if (!summarizingConversations.add(id)) {
            Log.d(TAG, "summarizeAndRefresh: Archiving for $id already in progress, skipping.")
            return@withContext ContextRefreshResult(false, errorMessage = "正在总结中...请勿重复操作")
        }

        try {
            val settings = settingsStore.settingsFlow.first()
            val conv = conversationRepo.getConversationById(id) ?: return@withContext ContextRefreshResult(
                false, errorMessage = "会话不存在"
            )

            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
            val messages = conv.currentMessages

            if (messages.isEmpty()) return@withContext ContextRefreshResult(false)

            // 方案 2: 获取数据库中确切的最新索引，确保不产生重叠
            val latestSegmentEndIndex = memoryRepository.getLatestSegmentEndIndex(id.toString()) ?: -1
            val actualStartIdx = if (latestSegmentEndIndex >= 0) latestSegmentEndIndex + 1 else 0

            val lastIdx = (messages.size - 1).coerceAtLeast(0)

            // 只有当待归档消息确实存在，且符合步长要求时才处理
            if (actualStartIdx >= lastIdx || lastIdx - actualStartIdx < 2) {
                Log.d(TAG, "summarizeAndRefresh: Range [$actualStartIdx, $lastIdx] is covered or too small, skipping.")
                return@withContext ContextRefreshResult(false, errorMessage = "当前没有足够的新消息需要压缩")
            }

            val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
            val model = settings.findModelById(modelId)
                ?: assistant.chatModelId?.let { settings.findModelById(it) }
                ?: settings.getCurrentChatModel()
                ?: return@withContext ContextRefreshResult(false, errorMessage = "没有找到可用模型")
            val provider = model.findProvider(settings.providers) ?: return@withContext ContextRefreshResult(false)
            val handler = providerManager.getProviderByType(provider)

            // 使用重新计算的 actualStartIdx 截取消息
            val toSummarize = messages.subList(actualStartIdx, lastIdx + 1)

            // 方案 B 优化：使用 StringBuilder 避免压缩时的 OOM
            val text = StringBuilder().apply {
                toSummarize.forEach { msg ->
                    append(msg.role).append(": ").append(msg.toContentText().take(500)).append("\n")
                }
            }.toString()

            val locale = Locale.getDefault().displayName
            val tempPrompt = fillPrompt(DEFAULT_TEMP_SUMMARY_PROMPT, mapOf(
                "new_messages" to text,
                "locale" to locale,
                "char" to assistant.name
            ))

            val providerHandler = handler as Provider<ProviderSetting>
            val tempResp = providerHandler.generateText(
                provider, listOf(UIMessage.user(tempPrompt)), TextGenerationParams(model, 0.3f, 1.0f, thinkingBudget = 0)
            )
            tempResp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
            val aiResponse = tempResp.choices.firstOrNull()?.message?.toContentText() ?: ""

            if (aiResponse.isNotBlank()) {
                val backgroundRegex = Regex("""\[(?:Background|背景)\][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)
                val keywordsRegex = Regex("""\[(?:Keywords|关键词)\][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)

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
                    startMessageIndex = actualStartIdx,
                    endMessageIndex = lastIdx,
                    embedding = embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) },
                    embeddingModelId = embeddingResult?.modelId
                )
                memoryRepository.saveSegment(segment)
            }

            val updated = conv.copy(
                contextSummaryUpToIndex = lastIdx, lastRefreshTime = System.currentTimeMillis()
            )
            conversationRepo.updateConversation(updated)
            updateConversation(id, updated)

            if (!skipArchive) {
                archiveConversation(id, force = true, skipEmbedding = true)
            }

            ContextRefreshResult(true, "Segments updated", toSummarize.size)
        } catch (e: Exception) {
            Log.e(TAG, "summarizeAndRefresh failed for $id", e)
            ContextRefreshResult(false, errorMessage = e.message)
        } finally {
            summarizingConversations.remove(id)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun extractKeywords(
        handler: Provider<*>,
        providerSetting: ProviderSetting,
        model: me.rerere.ai.provider.Model,
        summary: String,
        assistantId: String
    ): String {
        return try {
            val locale = Locale.getDefault().getDisplayName(Locale.ROOT)
            val prompt = fillPrompt(DEFAULT_KEYWORD_EXTRACTION_PROMPT, mapOf(
                "summary" to summary,
                "locale" to locale
            ))
            val h = handler as Provider<ProviderSetting>
            val resp = h.generateText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model, temperature = 0.3f, topP = 1.0f, maxTokens = 256
                )
            )
            resp.usage?.let { conversationRepo.recordTokenUsage(assistantId, it) }
            resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract keywords", e)
            ""
        }
    }

    private fun mergeKeywords(ai: String, local: String): String {
        val aiList = ai.split(Regex("[,，、；;]")).map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val localList = local.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return (aiList + localList).distinct().joinToString(",")
    }

    suspend fun saveConversation(id: Uuid, conversation: Conversation) {
        if (temporaryConversations.contains(id)) {
            updateConversation(id, conversation); return
        }
        updateConversation(id, conversation)

        if (conversationRepo.getConversationById(id) == null) conversationRepo.insertConversation(conversation) else conversationRepo.updateConversation(
            conversation
        )
    }

    fun translateMessage(id: Uuid, message: UIMessage, target: Locale) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val conv = getConversationFlow(id).value
                val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
                val modelId = assistant.chatModelId ?: settings.chatModelId
                val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim()
                if (text.isNotBlank()) {
                    updateTranslationField(id, message.id, context.getString(R.string.translating))
                    generationHandler.translateText(settings, text, target, modelId) {
                        updateTranslationField(
                            id, message.id, it
                        )
                    }.collect {}
                    saveConversation(id, getConversationFlow(id).value)
                }
            } catch (e: Exception) {
                updateTranslationField(id, message.id, null); _errorFlow.emit(translateError(e))
            }
        }
    }

    private suspend fun updateTranslationField(id: Uuid, mid: Uuid, text: String?) {
        val conv = getConversationFlow(id).value
        val nodes = conv.messageNodes.map { node ->
            if (node.messages.any { it.id == mid }) node.copy(messages = node.messages.map {
                if (it.id == mid) it.copy(
                    translation = text
                ) else it
            }) else node
        }
        updateConversation(id, conv.copy(messageNodes = nodes))
    }

    fun cleanupConversation(id: Uuid) {
        _generationJobs.value[id]?.cancel()
        removeGenerationJob(id)

        // 核心修复：显式清空 Flow 中的引用，辅助 GC 更快地回收大型 Conversation 对象
        conversations[id]?.value = Conversation.ofId(id, Uuid.random())

        conversations.remove(id)
        conversationMutexes.remove(id)
        Log.d(TAG, "Unloaded conversation $id from RAM cache.")
    }

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val conv = getConversationFlow(conversationId).value
        var nodes = conv.messageNodes.filter { it.messages.isNotEmpty() }
            .map { if (it.selectIndex !in it.messages.indices) it.copy(selectIndex = 0) else it }
        nodes = nodes.mapIndexed { idx, node ->
            val next = nodes.getOrNull(idx + 1)
            if (node.currentMessage.hasPart<UIMessagePart.ToolCall>() && next?.currentMessage?.hasPart<UIMessagePart.ToolResult>() != true) node.copy(
                messages = node.messages.filter { it.id != node.currentMessage.id },
                selectIndex = (node.selectIndex - 1).coerceAtLeast(0)
            )
            else node
        }.filter { it.messages.isNotEmpty() }
        updateConversation(conversationId, conv.copy(messageNodes = nodes))
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
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
            val suggestions =
                result.choices[0].message?.toContentText()?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList()
            saveConversation(conversationId, conversation.copy(chatSuggestions = suggestions))
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
                conversationRepo.insertConversation(conv); _recentlyRestoredIds.value += conversationId; delay(
                1000
            ); _recentlyRestoredIds.value -= conversationId
            }
        }
    }

    private fun sendGenerationDoneNotification(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val lastMsg = conversation.currentMessages.lastOrNull()
        val msg = lastMsg?.toContentText()?.take(50) ?: ""
        val notification =
            NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID).setContentTitle(assistant.name)
                .setContentText(msg).setSmallIcon(R.drawable.about_logo).setAutoCancel(true)
                .setContentIntent(getPendingIntent(context, conversationId))

        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) NotificationManagerCompat.from(context).notify(1, notification.build())
    }

    private fun getPendingIntent(context: Context, id: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra(
            "conversationId", id.toString()
        )
        }
        return PendingIntent.getActivity(
            context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private suspend fun updateConversation(id: Uuid, conversation: Conversation) {
        if (conversation.id != id) return
        val old = getConversationFlow(id).value
        val deleted = old.files.filter { f -> conversation.files.none { it == f } }
        if (deleted.isNotEmpty()) context.deleteChatFiles(deleted)
        conversations.getOrPut(id) { MutableStateFlow(conversation) }.value = conversation
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

