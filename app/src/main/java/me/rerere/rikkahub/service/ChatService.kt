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
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import kotlinx.coroutines.job
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
import me.rerere.rikkahub.core.data.utils.KeywordExtractor
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import android.net.Uri

private const val TAG = "ChatService"

private val WECHAT_SENTENCE_REGEX = Regex("[，。！？~\\n\\s]|[,!?~\\n\\s]")
private val PUNC_REGEX = Regex("^[，,。！？!?.~\\n]$")
private val PUNCS = "，,。！!."

private val _isAiTypingMap = MutableStateFlow<Map<Uuid, Boolean>>(emptyMap())
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

    // 微信模式：消息合并发送的计时器
    private val wechatDebounceJobs = ConcurrentHashMap<Uuid, Job>()

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
                updateConversation(conversationId) { old ->
                    val newNode = UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(monitorMsg)),
                        skipContext = true
                    ).toMessageNode()

                    old.copy(
                        messageNodes = old.messageNodes + newNode,
                        updateAt = Instant.now()
                    )
                }
                saveConversation(conversationId, getConversationFlow(conversationId).value)

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

        return conversations.computeIfAbsent(conversationId) {
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
        val currentJob = coroutineContext.job
        val registeredJob = _generationJobs.value[conversationId]
        val isGenerating = registeredJob != null && registeredJob.isActive && registeredJob != currentJob
        val currentConv = conversations[conversationId]?.value ?: currentConvInDb

        val currentAssistantId = targetAssistantId
            ?: currentConv?.assistantId
            ?: settingsStore.settingsFlow.value.getCurrentAssistant().id

        lastConversationId?.let { oldId ->
            if (oldId != conversationId) {
                // 【关键点】：去数据库查一下，看看当前进入的这个 ID 是不是一个从未见过的新会话
                val isNewConversation = currentConvInDb == null ||
                    currentConvInDb.currentMessages.none { it.role == MessageRole.USER }

                // 只有进入“全新会话”时，才触发对上一个会话 (oldId) 的归档 and 同步动画
                if (isNewConversation) {
                    val oldConv = conversationRepo.getConversationById(oldId)

                    if (oldConv != null && oldConv.assistantId == currentAssistantId) {
                        val settings = settingsStore.settingsFlow.first()
                        val assistant = settings.getAssistantById(oldConv.assistantId) ?: settings.getCurrentAssistant()

                        if (assistant.enableMemory) {
                            appScope.launch {
                                if (archivingConversations.contains(oldId)) return@launch
                                if (assistant.enableRecentChatsReference) {
                                    // 只有新建会话且开启了连贯模式，才显示动画
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
                    Log.v(TAG, "切换到已有会话或返回主页，跳过自动归档逻辑。")
                }
            }
        }

        lastConversationId = conversationId
        if (currentConvInDb != null) {
            val currentInMem = conversations[conversationId]?.value
            // 改进判断：如果是正在生成，且内存里的消息看起来是正常的（非空），才跳过
            // 如果内存里是空的但数据库有数据，说明可能由于某种原因内存被回收了，需要从 DB 恢复
            if (isGenerating && currentInMem != null && currentInMem.messageNodes.isNotEmpty()) {
                // 【核心修复】：如果正在生成中，绝对不能用数据库的旧数据覆盖内存状态！
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
                id = conversationId, assistantId = assistant.id, isVirtual = assistant.isVirtualWorldMode
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId) { newConversation }
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
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        isTemporaryChat: Boolean = false,
        predefinedUserNode: MessageNode? = null
    ) {
        if (isTemporaryChat) temporaryConversations.add(conversationId)
        val oldJob = _generationJobs.value[conversationId]
        if (oldJob != null) {
            Log.d(TAG, "User sent new message, cancelling previous AI response.")
            oldJob.cancel()
            removeGenerationJob(conversationId)
            _isAiTypingMap.update { it - conversationId }
        }
        // 2. 取消旧的计时任务（如果用户在 5 秒内连续发消息，则重新计时）
        wechatDebounceJobs[conversationId]?.cancel()
        val newNode = predefinedUserNode ?: UIMessage(role = MessageRole.USER, parts = content).toMessageNode()
        updateConversation(conversationId) { old ->
            old.copy(
                messageNodes = old.messageNodes + newNode,
                updateAt = Instant.now()
            )
        }
        // 后台持久化到数据库
        appScope.launch {
            val latest = getConversationFlow(conversationId).value
            saveConversation(conversationId, latest)
            conversationRepo.recordDailyActivity()
        }

        // 4. 处理 AI 回复的延时触发
        if (answer) {
            val settings = settingsStore.settingsFlow.value
            val wechatMode = settings.getEffectiveDisplaySetting(settings.getCurrentAssistant()).wechatMode

            val debounceJob = appScope.launch {
                // 微信模式下等待 5 秒，普通模式立即触发
                if (wechatMode) {
                    delay(5000)
                    _isAiTypingMap.update { it + (conversationId to true) }
                } else {
                    // 普通模式不需要显示 TopBar 打字状态，但我们要重置标志位
                    _isAiTypingMap.update { it - conversationId }
                }

                val timeoutJob = launch {
                    delay(600000)
                    if (_isAiTypingMap.value.containsKey(conversationId)) _isAiTypingMap.update { it - conversationId }
                }
                // 执行生成任务
                try {
                    handleMessageComplete(conversationId)
                    _generationDoneFlow.emit(conversationId)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        _errorFlow.emit(translateError(e))
                    }
                } finally {
                    // 【新增】：结束后务必关闭状态
                    timeoutJob.cancel()
                    _isAiTypingMap.update { it - conversationId }
                }
            }

            // 将本次任务存入 map 以便下次打断
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
    }

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        forceWipe: Boolean = false
    ) {
        // 1. 打断当前会话正在进行的任务
        val oldJob = _generationJobs.value[conversationId]
        oldJob?.cancel()
        removeGenerationJob(conversationId)
        val job = appScope.launch {
            try {
                var pendingAction: (suspend () -> Unit)? = null
                // 2. 尝试获取锁，持有锁执行整个修改和生成逻辑
                val lockAcquired = withTimeoutOrNull(2000) {
                    // 初始化并确保获取最新对话状态
                    initializeConversation(conversationId)
                    val conversation = getConversationFlow(conversationId).value
                    var updatedConv: Conversation? = null

                    if (message.role == MessageRole.USER) {
                        // --- 情况 A: 重新生成该用户消息之后的回复 ---
                        val node = conversation.getMessageNodeByMessage(message)
                        val indexAt = conversation.messageNodes.indexOf(node)
                        if (indexAt != -1) {
                            updatedConv = conversation.copy(
                                messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                            )
                        }
                    } else if (regenerateAssistantMsg) {
                        // --- 情况 B: 重新生成该助手消息的内容 ---
                        val clickedNode = conversation.getMessageNodeByMessage(message)
                        val clickedIndex = conversation.messageNodes.indexOf(clickedNode)
                        val lastUserIndex = conversation.messageNodes.subList(0, clickedIndex + 1)
                            .indexOfLast { it.role == MessageRole.USER }

                        if (lastUserIndex >= 0) {
                            val firstAssistantIndex = lastUserIndex + 1
                            val turnEndIndex = conversation.messageNodes
                                .subList(firstAssistantIndex, conversation.messageNodes.size)
                                .indexOfFirst { it.role == MessageRole.USER }
                                .let { if (it == -1) conversation.messageNodes.size else firstAssistantIndex + it }

                            updatedConv = if (forceWipe) {
                                // 擦除后续回复，重新开始一个新的助手气泡
                                val nodes = conversation.messageNodes.subList(0, lastUserIndex + 1).toMutableList()
                                nodes.add(
                                    MessageNode(
                                        id = Uuid.random(),
                                        messages = listOf(
                                            UIMessage(
                                                role = MessageRole.ASSISTANT,
                                                parts = emptyList()
                                            )
                                        )
                                    )
                                )
                                if (turnEndIndex < conversation.messageNodes.size) {
                                    nodes.addAll(
                                        conversation.messageNodes.subList(
                                            turnEndIndex,
                                            conversation.messageNodes.size
                                        )
                                    )
                                }
                                conversation.copy(messageNodes = nodes)
                            } else {
                                // 增加一个新的版本分支 (Multi-turn versioning)
                                val versionTag = Uuid.random().toString()
                                val nodes = conversation.messageNodes.subList(0, lastUserIndex + 1).toMutableList()
                                val firstAssistant = conversation.messageNodes.getOrNull(firstAssistantIndex)
                                if (firstAssistant != null) {
                                    val newMessages = firstAssistant.messages + UIMessage(
                                        role = MessageRole.ASSISTANT, parts = emptyList(), versionTag = versionTag
                                    )
                                    nodes.add(
                                        firstAssistant.copy(
                                            messages = newMessages,
                                            selectIndex = newMessages.lastIndex
                                        )
                                    )
                                    if (turnEndIndex < conversation.messageNodes.size) {
                                        nodes.addAll(
                                            conversation.messageNodes.subList(
                                                turnEndIndex,
                                                conversation.messageNodes.size
                                            )
                                        )
                                    }
                                }
                                conversation.copy(messageNodes = nodes)
                            }
                        }
                    }

                    // 3. 应用更新并启动生成流程
                    if (updatedConv != null) {
                        updateConversation(conversationId) { updatedConv }
                        saveConversation(conversationId, updatedConv)
                        pendingAction = { handleMessageComplete(conversationId) }
                    } else if (regenerateAssistantMsg && message.role != MessageRole.USER) {
                        // 降级处理：如果没有找到明确的用户上文，则截断到当前消息索引并生成
                        val clickedNode = conversation.getMessageNodeByMessage(message)
                        val clickedIndex = conversation.messageNodes.indexOf(clickedNode)
                        pendingAction = { handleMessageComplete(conversationId, messageRange = 0..<clickedIndex) }
                    }
                    true // 成功在锁内执行逻辑

                } ?: false

                if (lockAcquired) {
                    setGenerationJob(conversationId, coroutineContext.job)
                    pendingAction?.invoke()
                    _generationDoneFlow.emit(conversationId)
                } else {
                    Log.w(TAG, "获取会话锁超时，重生操作已取消: $conversationId")
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _errorFlow.emit(translateError(e))
                }
            }
        }
        job.invokeOnCompletion {
            _generationJobs.update { current ->
                if (current[conversationId] == job) current - conversationId else current
            }
            appScope.launch { delay(500); checkAllConversationsReferences() }
        }
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        assistantOverride: Assistant? = null,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = false
    ) {
        val mutex = conversationMutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            checkInvalidMessages(conversationId)
            val currentJob = coroutineContext.job
            var currentSearchCount = 0
            val settings = settingsStore.settingsFlow.first()
            val processMessageIds = mutableMapOf<Int, Uuid>()
            var currentConversation = conversations[conversationId]?.value
                ?: conversationRepo.getConversationById(conversationId)
                ?: return@withLock

            var lastDisplayedSentenceCount = 0
            var lastTotalFullText = ""

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
                // 微信模式检测
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
                                val lastUserMsg =
                                    currentConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }
                                        ?.toText()
                                        ?: ""
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

                val baseMessages = currentConversation.currentMessages.let {
                    val raw =
                        if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it
                    // 核心过滤：剔除掉内容为空且没有工具调用的助手消息
                    val filtered = raw.filter { msg ->
                        msg.role != MessageRole.ASSISTANT ||
                            msg.toContentText().isNotBlank() ||
                            msg.parts.any { it is UIMessagePart.ToolCall }
                    }
                    if (filtered.size < raw.size) {
                        Log.w(TAG, "已自动过滤历史中的 ${raw.size - filtered.size} 条空 AI 消息，防止干扰上下文")
                    }
                    filtered
                }
                Log.d(TAG, "发送给 AI 的上下文消息总数: ${baseMessages.size}")

                val finalContextMessages = if (wechatMode) {
                    val messages = baseMessages.toMutableList()
                    // 找到最后一段连续的用户消息
                    val lastUserGroupStart = messages.indexOfLast { it.role != MessageRole.USER } + 1
                    if (lastUserGroupStart in messages.indices && messages.size - lastUserGroupStart > 1) {
                        // 合并本轮所有用户消息
                        val userMessages = messages.subList(lastUserGroupStart, messages.size)
                        val allParts =
                            userMessages.flatMap { it.parts }.distinctBy { (it as? UIMessagePart.Image)?.url ?: it }

                        val combinedMsg = UIMessage(
                            role = MessageRole.USER,
                            parts = allParts,
                            createdAt = userMessages.last().createdAt
                        )
                        // 替换最后一段消息为合并后的消息
                        messages.subList(lastUserGroupStart, messages.size).clear()
                        messages.add(combinedMsg)
                    }
                    messages
                } else baseMessages


                // 硬超时保护：20 分钟
                kotlinx.coroutines.withTimeout(20 * 60 * 1000L) {
                    generationHandler.generateText(
                        settings = settings,
                        model = model,
                        messages = finalContextMessages,
                        assistant = assistant,
                        memories = retrievedMemories,
                        inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer) },
                        outputTransformers = outputTransformers,
                        tools = buildList {
                            val isMain = assistant.isMain
                            val isVirtual = currentConversation.isVirtual

                            val supportsBuiltIn =
                                model.tools.isNotEmpty() || ModelRegistry.GEMINI_SERIES.match(model.modelId)
                            val useBuiltIn = assistant.preferBuiltInSearch && supportsBuiltIn
                            val searchMode = assistant.searchMode

                            if (searchMode is AssistantSearchMode.Provider && !useBuiltIn) {
                                addAll(createSearchTool(
                                    settings,
                                    assistant,
                                    searchMode.index,
                                    searchCounter = { currentSearchCount },
                                    onSearchCalled = { currentSearchCount++ }
                                ))
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
                                    conversationId = currentConversation.id,
                                    userImageUrls = currentConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.parts?.filterIsInstance<UIMessagePart.Image>()
                                        ?.map { it.url } ?: emptyList()))

                            if (isMain && !isVirtual) {
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
                            }
                        },
                        truncateIndex = currentConversation.truncateIndex,
                        enabledModeIds = currentConversation.enabledModeIds,
                        contextSummary = currentEpisode?.content?.removePrefix("虚拟世界："),
                        temporarySummaries = emptyList(),
                        skipContextForResponse = skipContextForResponse,
                        includeSkipContextMessages = includeSkipContextMessages,
                        conversationId = conversationId
                    ).onCompletion {
                        // 1. 提取耗时计算逻辑，不要直接在 Lambda 里引用外部多变变量
                        val duration = firstTokenTime?.let { System.currentTimeMillis() - it }
                        // 2. 调用原子更新函数（假设 updateConversation 内部使用了 Mutex 或 StateFlow.update）
                        updateConversation(conversationId) { old ->
                            // 在这里，'old' 是数据库/状态流中最准确的当前值
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
                                chatSuggestions = emptyList(), // 统一在这里清空建议
                                updateAt = Instant.now()
                            )

                            // 3. 唯一的内存变量赋值点，确保与数据库持久化的值完全一致
                            currentConversation = finalUpdated

                            // 4. 返回新值给 updateConversation 进行持久化
                            finalUpdated
                        }

                        // 5. 后置通知处理
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                            sendGenerationDoneNotification(conversationId)
                        }
                    }.collect { chunk ->
                        if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                        if (chunk is GenerationChunk.Messages) {
                            // 1. 【核心优化】获取当前状态快照，作为本轮流式转换的基准，避免并发竞态
                            var conversationSnapshot = currentConversation

                            val finalMessages = chunk.messages
                            val newMessages = finalMessages.drop(finalContextMessages.size).mapIndexed { index, msg ->
                                msg.copy(id = processMessageIds.getOrPut(index) { msg.id })
                            }
                            if (newMessages.isEmpty()) return@collect

                            val lastAI = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                            val lastAiMsg = newMessages.lastOrNull { it.role == MessageRole.ASSISTANT }

                            // 更新缓存的完整文本（用于生成结束后的收尾处理）
                            if (lastAiMsg != null) {
                                lastTotalFullText = lastAiMsg.toContentText()
                            }

                            val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode

                            // 2. 预处理：如果是初次收到 Assistant 消息，先将其加入 Snapshot
                            val containsNewAssistant = newMessages.any { it.role == MessageRole.ASSISTANT }
                            if (containsNewAssistant && conversationSnapshot.messageNodes.none { node ->
                                    node.messages.any { m -> newMessages.any { nm -> nm.id == m.id } }
                                }) {
                                val toUpdate = baseMessages + newMessages.map {
                                    if (it.role == MessageRole.ASSISTANT && wechatMode && it.parts.none { p -> p is UIMessagePart.ToolCall }) {
                                        // 只有在没有工具调用时，才为了动画效果初始隐藏文本
                                        it.copy(parts = it.parts.filter { p -> p !is UIMessagePart.Text })
                                    } else it
                                }
                                conversationSnapshot = conversationSnapshot.updateCurrentMessages(toUpdate)
                                currentConversation = conversationSnapshot
                                updateConversation(conversationId) { conversationSnapshot }
                            }

                            val fullText = lastAI?.toContentText() ?: ""
                            val isToolCalling = lastAI?.parts?.any { it is UIMessagePart.ToolCall } == true

                            // 3. 根据模式进入不同的渲染路径
                            if (wechatMode && lastAI != null && !isToolCalling && fullText.isNotBlank()) {
                                // --- 微信模式：流式转按句弹出 ---
                                val matches = WECHAT_SENTENCE_REGEX.findAll(fullText).toList()
                                val sentences = mutableListOf<String>()
                                var lastIndex = 0
                                matches.forEach { match ->
                                    var sentence = fullText.substring(lastIndex, match.range.last + 1).trim()
                                    if (sentence.isNotBlank() && !PUNC_REGEX.matches(sentence)) {
                                        if (sentence.length >= 2 && sentence.last() in PUNCS && sentence[sentence.length - 2] !in PUNCS) {
                                            sentence = sentence.dropLast(1)
                                        }
                                        sentences.add(sentence)
                                    }
                                    lastIndex = match.range.last + 1
                                }

                                while (lastDisplayedSentenceCount < sentences.size && currentJob.isActive) {
                                    val latestAiMsg = currentConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                                    if (latestAiMsg?.parts?.any { it is UIMessagePart.ToolCall } == true) {
                                        Log.d(TAG, "检测到工具调用，终止微信模式动画以防止覆盖数据")
                                        break
                                    }

                                    val sentence = sentences[lastDisplayedSentenceCount]
                                    // 模拟打字速度：根据字数计算延迟
                                    val charSpeed = 200L
                                    val baseTime = 300L
                                    val finalDelay = (sentence.length * charSpeed + baseTime).coerceIn(400L, 8000L)

                                    delay(finalDelay)
                                    if (!currentJob.isActive) {
                                        Log.d(TAG, "任务已被取消，停止 UI 局部更新")
                                        return@collect
                                    }

                                    lastDisplayedSentenceCount++

                                    // 基于 Snapshot 计算 UI 更新，确保转换逻辑的封闭性
                                    val updatedParts =
                                        buildWechatMessages(lastAI, sentences, lastDisplayedSentenceCount)
                                    val finalParts = updatedParts.toMutableList().apply {
                                        latestAiMsg?.parts?.filter { it !is UIMessagePart.Text }?.forEach {
                                            if (it !in this) add(it)
                                        }
                                    }
                                    val updatedAiMessage = lastAI.copy(parts = finalParts)
                                    val nextState = currentConversation.copy(
                                        messageNodes = currentConversation.messageNodes.map { node ->
                                            if (node.messages.any { it.id == lastAI.id }) {
                                                node.copy(messages = node.messages.map { if (it.id == lastAI.id) updatedAiMessage else it })
                                            } else node
                                        }
                                    )
                                    currentConversation = nextState

                                    // 性能优化：每 2 句或者最后一句时，才同步到数据库/Flow，减少写压力
                                    if (lastDisplayedSentenceCount % 2 == 0 || lastDisplayedSentenceCount == sentences.size) {
                                        updateConversation(conversationId) { nextState }
                                    }
                                }
                            } else {
                                // --- 普通模式：直接合并消息并更新 ---
                                val lastAiInNew = newMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                                val isEffectivelyEmpty = lastAiInNew != null &&
                                    lastAiInNew.toContentText().trim().isEmpty() &&
                                    lastAiInNew.parts.none { it is UIMessagePart.ToolCall }

                                if (lastAiInNew == null || !isEffectivelyEmpty) {
                                    val toUpdate = baseMessages + newMessages
                                    val nextState = conversationSnapshot.updateCurrentMessages(toUpdate)
                                        .copy(chatSuggestions = emptyList())
                                    currentConversation = nextState
                                    updateConversation(conversationId) { nextState }
                                }
                            }
                        }
                    }

                    // 最终收尾处理微信模式下未完全显示的句子及尾部剩余文本 (流式生成结束后的完整展现与延迟计算)
                    val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
                    if (wechatMode) {
                        val lastAI = currentConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                        if (lastAI != null) {
                            val fullText = lastTotalFullText
                            val isToolCalling = lastAI.parts.any { it is UIMessagePart.ToolCall }
                            if (!isToolCalling && fullText.isNotBlank()) {
                                val matches = WECHAT_SENTENCE_REGEX.findAll(fullText).toList()
                                val sentences = mutableListOf<String>()
                                var lastIndex = 0
                                matches.forEach { match ->
                                    var sentence = fullText.substring(lastIndex, match.range.last + 1).trim()
                                    if (sentence.isNotBlank() && !PUNC_REGEX.matches(sentence)) {
                                        val puncs = "，,。！!."
                                        if (sentence.length >= 2 && sentence.last() in puncs && sentence[sentence.length - 2] !in puncs) {
                                            sentence = sentence.dropLast(1)
                                        }
                                        sentences.add(sentence)
                                    }
                                    lastIndex = match.range.last + 1
                                }
                                val remainder = fullText.substring(lastIndex).trim()
                                if (remainder.isNotBlank()) {
                                    sentences.add(remainder)
                                }

                                while (lastDisplayedSentenceCount < sentences.size && currentJob.isActive) {
                                    val latestAiMsg = currentConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                                    if (latestAiMsg?.parts?.any { it is UIMessagePart.ToolCall } == true) {
                                        Log.d(TAG, "检测到工具调用，终止微信模式动画以防止覆盖数据")
                                        break
                                    }
                                    val sentence = sentences[lastDisplayedSentenceCount]
                                    val charSpeed = 200L
                                    val baseTime = 300L
                                    val finalDelay = (sentence.length * charSpeed + baseTime).coerceIn(500L, 20000L)
                                    delay(finalDelay)
                                    Log.v(TAG, "测试最终收尾：显示的句子：$sentence，延迟：$finalDelay ms")

                                    if (!currentJob.isActive) return@withTimeout

                                    lastDisplayedSentenceCount++

                                    val newParts = buildWechatMessages(lastAI, sentences, lastDisplayedSentenceCount)
                                    val updatedAiMessage = lastAI.copy(parts = newParts)
                                    val newNodes = currentConversation.messageNodes.map { node ->
                                        if (node.messages.any { it.id == lastAI.id }) {
                                            node.copy(messages = node.messages.map { if (it.id == lastAI.id) updatedAiMessage else it })
                                        } else node
                                    }
                                    val finalUpdate = currentConversation.copy(messageNodes = newNodes, chatSuggestions = emptyList())
                                    currentConversation = finalUpdate // 同步内存快照
                                    updateConversation(conversationId) { finalUpdate }
                                }
                            }
                        }
                    }
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "任务被用户中断，跳过最终状态保存")
                    return@onFailure
                }
                Log.d(TAG, "Generation failed/cancelled for $conversationId, saving current state. Error: ${e.message}")
                val finalConv = currentConversation
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
                if (!currentJob.isActive) return@onSuccess
                // 获取当前内存中最新的对话状态（这里面已经包含了微信模式拆分好的气泡）
                val finalConv = currentConversation

                // 核心修复：直接保存内存里的最终版本，确保不产生重复的长句覆盖
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
    }

    private fun buildWechatMessages(
        lastAI: UIMessage,
        sentences: List<String>,
        count: Int,
    ): List<UIMessagePart> {
        val parts = lastAI.parts.filter { it !is UIMessagePart.Text }.toMutableList()
        for (i in 0 until count) {
            parts.add(UIMessagePart.Text(sentences[i]))
        }
        return parts
    }

    private fun translateError(e: Throwable): Throwable {
        val message = e.message ?: ""
        return when {
            message.contains("Unexpected JSON token") && message.contains("< instead") -> {
                IllegalStateException(context.getString(R.string.api_error_html), e)
            }

            message.contains("400 Bad Request", ignoreCase = true) -> {
                // 如果你没加 400 的，可以沿用旧文本或者补一个 api_error_400
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
        var callCount = 0
        return buildSet {
            add(Tool(name = "search_web", description = "search web", parameters = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                SearchService.getService(opt).parameters
            }, execute = { jsonElement -> // 1. 显式命名参数，避免使用隐式的 it
                if (searchCounter() >= 1) { // 检查外部传入的计数
                    return@Tool buildJsonObject {
                        put("error", "Search limit reached (1/1)...")
                    }
                }
                onSearchCalled()
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                val resultSize = 6
                val commonOptions = settings.searchCommonOptions.copy(resultSize = resultSize)

                val input = jsonElement as? JsonObject ?: JsonObject(emptyMap())
                val searchResult = SearchService.getService(opt).search(input, commonOptions, opt).getOrThrow()

                Log.d(TAG, "Web Search Raw Results (Fixed Request: $resultSize, Got: ${searchResult.items.size})")
                searchResult.items.forEachIndexed { i, item ->
                    Log.v(TAG, "Raw Item [$i]: ${item.title} (${item.url})")
                }

                val htmlRegex = Regex("<[^>]*>")
                // 根据结果数量动态计算每条结果的配额，总预算控制在 12000 字左右
                val maxCharsPerItem = (12000 / resultSize.coerceAtLeast(1)).coerceIn(1500, 4000)

                val cleanedItems = searchResult.items.take(resultSize).map { item ->
                    val cleanedText = item.text.replace(htmlRegex, "").trim()
                    item.copy(text = cleanedText.take(maxCharsPerItem))
                }

                Log.i(TAG, "Return search results with dual-field support for UI and model compatibility")
                buildJsonObject {
                    // UI 依然使用 items, url, text 字段
                    put("items", JsonArray(cleanedItems.map { item ->
                        buildJsonObject {
                            put("title", item.title)
                            put("url", item.url)
                            put("text", item.text)
                            // 同时保留 link 和 snippet 提高模型理解度
                            put("link", item.url)
                            put("snippet", item.text)
                        }
                    }))
                    // 顶层也放一个 results 供某些对 JSON 结构敏感的模型直接读取
                    put("results", JsonArray(cleanedItems.map { item ->
                        buildJsonObject {
                            put("title", item.title)
                            put("link", item.url)
                            put("snippet", item.text)
                        }
                    }))
                }
            }, systemPrompt = { _, _ ->
                "## tool: search_web\n\nNote: Use the search results to answer the user's question directly. Only 1 search allowed per turn."
            }))
        }
    }

    private suspend fun checkAndAutoSummarize(id: Uuid, conv: Conversation, settings: Settings) {
        val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
        if (!assistant.enableMemory) return
        if (!assistant.enableDetailMemory) return
        val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
        val max = if (wechatMode) assistant.detailMemoryThreshold * 2 else assistant.detailMemoryThreshold

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

            // 使用重新计算 of actualStartIdx 截取消息
            val toSummarize = messages.subList(actualStartIdx, lastIdx + 1)

            val text = StringBuilder().apply {
                toSummarize.forEach { msg ->
                    append(msg.role).append(": ").append(msg.toContentText().take(500)).append("\n")
                }
            }.toString()

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
            updateConversation(id) { updated }

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
            val prompt = fillPrompt(
                DEFAULT_KEYWORD_EXTRACTION_PROMPT, mapOf(
                    "summary" to summary,
                    "locale" to locale
                )
            )
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
        Log.v(TAG, "Saving conversation $id, messages: ${conversation.currentMessages.size}")
        if (temporaryConversations.contains(id)) {
            updateConversation(id) { conversation }; return
        }
        updateConversation(id) { conversation }

        if (conversationRepo.getConversationById(id) == null) conversationRepo.insertConversation(conversation) else conversationRepo.updateConversation(
            conversation
        )
    }

    fun getAiTypingFlow(id: Uuid): Flow<Boolean> = _isAiTypingMap.map { it[id] ?: false }



    fun cleanupConversation(id: Uuid) {
        _generationJobs.value[id]?.cancel()
        removeGenerationJob(id)
        conversations.remove(id)
        conversationMutexes.remove(id)
        Log.d(TAG, "Unloaded conversation $id from RAM cache.")
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        // 正在生成时不执行清理，防止写冲突
        if (_generationJobs.value.containsKey(conversationId)) return

        updateConversation(conversationId) { conv ->
            // 1. 基础清理：移除完全空的消息节点，修正越界的 selectIndex
            var nodes = conv.messageNodes.filter { it.messages.isNotEmpty() }
                .map { if (it.selectIndex !in it.messages.indices) it.copy(selectIndex = 0) else it }

            val finalNodes = mutableListOf<MessageNode>()

            nodes.forEachIndexed { index, node ->
                val msg = node.currentMessage
                val nextNode = nodes.getOrNull(index + 1)

                // 判定 A: 工具调用必须跟有工具结果
                // 如果 AI 发起了工具调用，但下一条消息不是 ToolResult，说明该调用链断了（报错或被用户中断）
                val isBrokenToolCall = msg.parts.any { it is UIMessagePart.ToolCall } &&
                    nextNode?.currentMessage?.parts?.any { it is UIMessagePart.ToolResult } != true

                // 判定 B: 结尾空白消息
                // 如果这是最后一条消息，且内容为空、没有工具调用、也不是正在思考的状态
                val isBlankAssistantAtEnd = index == nodes.lastIndex &&
                    msg.role == MessageRole.ASSISTANT &&
                    msg.toContentText().isBlank() &&
                    msg.parts.none { it is UIMessagePart.ToolCall }

                // 判定 C: 连续助手消息 (有些模型如 Anthropic 严禁这种结构)
                val isDuplicateAssistant = msg.role == MessageRole.ASSISTANT &&
                    nextNode?.currentMessage?.role == MessageRole.ASSISTANT

                when {
                    // 如果是损坏的工具调用分支，尝试切换回该 Node 的其他版本，或者直接剔除该消息
                    isBrokenToolCall -> {
                        val fallbackMessages = node.messages.filter { it.id != msg.id }
                        if (fallbackMessages.isNotEmpty()) {
                            finalNodes.add(node.copy(messages = fallbackMessages, selectIndex = 0))
                        }
                        // 如果该 Node 只有这一条坏掉的消息，则该 Node 也会被舍弃
                    }

                    // 剔除结尾的空白占位符
                    isBlankAssistantAtEnd -> {
                        Log.d(TAG, "已移除结尾的无效 AI 占位消息")
                    }

                    isDuplicateAssistant -> {
                        // 如果当前这条助手消息是空的（没文本也没工具调用），就直接跳过，防止 API 报错
                        if (msg.toContentText().isBlank() && msg.parts.none { it is UIMessagePart.ToolCall }) {
                            Log.d(TAG, "检测到连续助手消息，已清理空的 AI 节点以满足 API 规范")
                        } else {
                            finalNodes.add(node)
                        }
                    }
                    // 正常消息，保留
                    else -> finalNodes.add(node)
                }
            }

            // 3. 返回更新后的会话对象
            conv.copy(messageNodes = finalNodes)
        }
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
            val suggestions = result.choices.firstOrNull()?.message?.toContentText()
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
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

    private fun updateConversation(id: Uuid, block: (Conversation) -> Conversation) {
        val flow = (conversations[id] ?: getConversationFlow(id)) as MutableStateFlow<Conversation>

        // 用于记录真正需要删除的文件列表
        var filesToDelete: List<Uri> = emptyList()

        flow.update { old ->
            val new = block(old)

            if (new.id != id) return@update old

            // 计算差集，暂存到局部变量
            // 只有最后一次成功的 update 产生的 filesToDelete 才会最终生效
            filesToDelete = old.files.filter { f -> new.files.none { it == f } }

            new
        }

        // 只有在 update 成功执行后，且确实有文件需要删除时，才启动协程
        if (filesToDelete.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                runCatching { context.deleteChatFiles(filesToDelete) }
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
