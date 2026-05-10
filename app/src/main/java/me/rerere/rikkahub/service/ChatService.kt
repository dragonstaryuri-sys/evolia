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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    listOf( ThinkTagTransformer,
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
    private val embeddingService: EmbeddingService, // 注入 EmbeddingService
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

    private var lastConversationId: Uuid? = null

    // 保留：归档中的会话锁
    private val archivingConversations = ConcurrentHashMap.newKeySet<Uuid>()

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
            generationJobs.collect { jobs ->
                if (jobs.isNotEmpty()) {
                    ChatForegroundService.start(context)
                } else {
                    ChatForegroundService.stop(context)
                }
            }
        }
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        _generationJobs.value.values.forEach { it?.cancel() }
    }

    private suspend fun <T> retryIO(
        times: Int = 2,
        initialDelay: Long = 2000,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times) {
            try {
                return block()
            } catch (e: Exception) {
                // 识别 IO 异常、超时或被取消的情况
                val isNetworkError = e is java.io.IOException ||
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("canceled", ignoreCase = true) == true
                if (isNetworkError) {
                    Log.w(TAG, "网络异常，正在重试 (第 ${it + 1} 次): ${e.message}")
                    delay(currentDelay)
                    currentDelay *= 2 // 指数退避
                } else {
                    throw e
                }
            }
        }
        return block() // 最后一次尝试
    }

    fun addConversationReference(conversationId: Uuid) {
        conversationReferences[conversationId] = conversationReferences.getOrDefault(conversationId, 0) + 1
    }

    fun removeConversationReference(conversationId: Uuid) {
        conversationReferences[conversationId]?.let { count ->
            if (count > 1) conversationReferences[conversationId] = count - 1
            else conversationReferences.remove(conversationId)
        }
        appScope.launch {
            delay(500)
            checkAllConversationsReferences()
        }
    }

    suspend fun executeAgentTask(task: me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity) {
        val data = me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(task.taskData) as? JsonObject ?: return

        val instruction = data["instruction"]?.jsonPrimitive?.contentOrNull ?: ""
        val settings = settingsStore.settingsFlow.first()
        val originalAssistantId = Uuid.parse(task.assistantId)
        val originalAssistant = settings.getAssistantById(originalAssistantId) ?: return

        // 1. 寻找目标会话
        // 核心优化：优先匹配当前用户正在“观看”的会话（存在引用计数的）
        val activeConvId = conversationReferences.keys.find { id ->
            conversations[id]?.value?.assistantId == originalAssistantId
        }

        val conversationId = if (activeConvId != null) {
            Log.d(TAG, "Task Trigger: Found active session $activeConvId for assistant")
            activeConvId
        } else {
            // 如果没在看，找数据库最近更新的
            val lastDbId = conversationRepo.getAllConversations()
                .first()
                .filter { it.assistantId == originalAssistantId }
                .maxByOrNull { it.updateAt }
                ?.id

            Log.d(TAG, "Task Trigger: No active session, using DB last session: $lastDbId")
            lastDbId ?: Uuid.random()
        }

        // 构建指令
        val monitorMsg = buildString {
            append("【系统自动化指令 - 任务触发】\n")
            if (task.taskType == "EMAIL") {
                val to = data["to"]?.jsonPrimitive?.contentOrNull
                val subject = data["subject"]?.jsonPrimitive?.contentOrNull
                append("类型: 邮件自动化\n")
                if (!to.isNullOrBlank()) append("目标收件人: $to\n")
                if (!subject.isNullOrBlank()) append("预设主题: $subject\n")
            }
            append("\n指令内容：$instruction\n\n注意：这是一条系统触发的消息，请根据上述要求直接调用相关工具或执行逻辑，无需向用户确认。")
        }

        // 启动后台生成逻辑
        appScope.launch {
            try {
                // 避让机制
                var retry = 0
                while (generationJobs.value[conversationId] != null && retry < 30) {
                    delay(100)
                    retry++
                }

                // 防止冲突：强行接管
                if (generationJobs.value[conversationId] != null) {
                    Log.w(TAG, "会话 $conversationId 忙碌，强行接管执行自动化任务。")
                    generationJobs.value[conversationId]?.cancel()
                    delay(200)
                }

                // 初始化并强制更新 Flow 状态
                initializeConversation(conversationId)
                val currentConv = getConversationFlow(conversationId).value

                val newNode = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(monitorMsg)),
                    skipContext = true
                ).toMessageNode()

                val updatedConv = currentConv.copy(
                    messageNodes = currentConv.messageNodes + newNode,
                    updateAt = Instant.now()
                )

                // 确保 Flow 和 数据库同步更新
                updateConversation(conversationId, updatedConv)
                saveConversation(conversationId, updatedConv)

                // 执行生成逻辑
                val job = launch {
                    try {
                        handleMessageComplete(
                            conversationId = conversationId,
                            assistantOverride = originalAssistant,
                            skipContextForResponse = true
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
        conversations.keys.forEach { if (!hasReference(it)) cleanupConversation(it) }
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        val settings = settingsStore.settingsFlow.value
        val currentAssistant = settings.getCurrentAssistant()
        return conversations.getOrPut(conversationId) {
            MutableStateFlow(
                Conversation.ofId(
                    id = conversationId,
                    assistantId = currentAssistant.id,
                    isVirtual = currentAssistant.isVirtualWorldMode // 修复：默认创建时根据助理当前模式同步 isVirtual
                )
            )
        }
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        generationJobs.map { it[conversationId] }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = generationJobs

    private fun setGenerationJob(conversationId: Uuid, job: Job?) {
        if (job == null) {
            removeGenerationJob(conversationId)
            return
        }
        _generationJobs.value = _generationJobs.value.toMutableMap().apply { this[conversationId] = job }
    }

    private fun removeGenerationJob(conversationId: Uuid) {
        _generationJobs.value = _generationJobs.value.toMutableMap().apply { remove(conversationId) }
    }

    suspend fun initializeConversation(conversationId: Uuid) {
        // 当切换会话时，尝试对上一个会话进行片段记忆归档
        lastConversationId?.let { oldId ->
            if (oldId != conversationId) {
                appScope.launch { archiveConversation(oldId) }
            }
        }
        lastConversationId = conversationId

        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            val assistant = settingsStore.settingsFlowRaw.first().getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                isVirtual = assistant.isVirtualWorldMode
            )
                .updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    /**
     * 将会话存档为唯一的情节记忆 (1:1 映射)
     * @param force 如果为 true，则无视消息数量限制，立即更新记忆
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun archiveConversation(conversationId: Uuid, force: Boolean = false) {
        if (temporaryConversations.contains(conversationId)) return

        // 并发防抖：如果该会话已经在归档中，则直接返回
        if (archivingConversations.contains(conversationId)) {
            Log.d(TAG, "archiveConversation: $conversationId is already archiving, skipping.")
            return
        }
        archivingConversations.add(conversationId)

        try {
            val conv = conversationRepo.getConversationById(conversationId) ?: return
            val messages = conv.currentMessages
            val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conversationId.toString())

            // 1. 获取当前所有总结的进度
            val episodeSignificance = existingEpisode?.significance ?: 0
            val summarySignificance = if (conv.contextSummary != null) {
                conv.contextSummaryUpToIndex + 1
            } else {
                0
            }

            // 2. 校验：除非强制触发，否则新消息需达到阈值 (4条) 才更新
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

            // 3. 选取最佳基准 (滚动式逻辑)
            // 对比 L1 和 L2，谁涵盖的消息多就用谁做基准
            val (baseSummary, skipCount) = if (summarySignificance >= episodeSignificance) {
                conv.contextSummary to summarySignificance
            } else {
                existingEpisode?.content to episodeSignificance
            }

            val newMessages = messages.drop(skipCount)

            // 如果没有新消息且已有基准，且不是强制刷新，则无需重新生成
            if (newMessages.isEmpty() && baseSummary != null && !force) {
                return
            }

            // 4. 模型准备
            val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
            val model = settings.findModelById(modelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val handler = providerManager.getProviderByType(provider)

            val backgroundModelId = assistant.backgroundModelId ?: settings.backgroundModelId
            val backgroundModel = settings.findModelById(backgroundModelId) ?: model
            val backgroundProvider = backgroundModel.findProvider(settings.providers) ?: provider
            val backgroundHandler = providerManager.getProviderByType(backgroundProvider)

            // 5. 生成摘要 (合并总结逻辑)
            val summary = if (newMessages.isEmpty() && baseSummary != null) {
                baseSummary
            } else {
                val messagesText = newMessages.joinToString("\n") {
                    "${it.role}: ${it.toContentText().take(500)}"
                }
                val locale = Locale.getDefault().displayName

                // 使用合并后的统一提示词
                val prompt = DEFAULT_FULL_SUMMARY_PROMPT
                    .replace("{{previous_summary}}", baseSummary ?: "None")
                    .replace("{{new_messages}}", messagesText)
                    .replace("{{locale}}", locale)
                    .replace("{{char}}", assistant.name)

                val providerHandler = handler as Provider<ProviderSetting>
                val resp = retryIO(times = 2) {
                    providerHandler.generateText(provider, listOf(UIMessage.user(prompt)), TextGenerationParams(model, 0.3f, 0.5f))
                }
                resp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
                resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
            }

            if (summary.isNotBlank()) {
                // 6. 提取关键词与处理向量
                val aiKeywords = extractKeywords(
                    handler = backgroundHandler,
                    providerSetting = backgroundProvider,
                    model = backgroundModel,
                    summary = summary,
                    assistantId = assistant.id.toString()
                )
                val localKeywords = KeywordExtractor.extract(summary)
                val keywords = mergeKeywords(aiKeywords, localKeywords)

                val effectiveContent = if (keywords.isNotBlank()) "Keywords: $keywords\nContent: $summary" else summary
                val embeddingResult = try {
                    embeddingService.embedWithModelId(effectiveContent, assistant.id.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate embedding for archived episode", e)
                    null
                }

                // 7. 保存/覆盖 Episode
                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0, // 核心：保留原 ID 以触发 REPLACE 覆盖，防止重复
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    content = summary,
                    keywords = keywords,
                    embedding = embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) },
                    embeddingModelId = embeddingResult?.modelId,
                    startTime = conv.createAt.toEpochMilli(),
                    endTime = conv.updateAt.toEpochMilli(),
                    significance = messages.size, // 记录当前总结到的消息总数
                    lastAccessedAt = System.currentTimeMillis()
                )
                chatEpisodeDAO.insertEpisode(episode)
                Log.i(TAG, "Archived/Updated episodic memory (L2) for $conversationId (Inc: ${newMessages.size} messages)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive conversation $conversationId", e)
        } finally {
            archivingConversations.remove(conversationId)
        }
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true, isTemporaryChat: Boolean = false) {
        if (isTemporaryChat) temporaryConversations.add(conversationId)
        _generationJobs.value[conversationId]?.cancel()

        val job = appScope.launch {
            try {
                // 确保数据 from 数据库 or 正确初始化开始
                initializeConversation(conversationId)

                val currentConversation = getConversationFlow(conversationId).value
                val newNode = UIMessage(role = MessageRole.USER, parts = content).toMessageNode()
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(role = MessageRole.USER, parts = content).toMessageNode()
                )
                saveConversation(conversationId, newConversation)
                conversationRepo.recordDailyActivity()
                if (answer) handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                _errorFlow.emit(e)
            }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion { setGenerationJob(conversationId, null); appScope.launch { delay(500); checkAllConversationsReferences() } }
    }

    fun regenerateAtMessage(conversationId: Uuid, message: UIMessage, regenerateAssistantMsg: Boolean = true, forceWipe: Boolean = false) {
        _generationJobs.value[conversationId]?.cancel()
        val job = appScope.launch {
            try {
                initializeConversation(conversationId)
                val conversation = getConversationFlow(conversationId).value
                if (message.role == MessageRole.USER) {
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(messageNodes = conversation.messageNodes.subList(0, indexAt + 1))
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else if (regenerateAssistantMsg) {
                    val clickedNode = conversation.getMessageNodeByMessage(message)
                    val clickedIndex = conversation.messageNodes.indexOf(clickedNode)
                    val lastUserIndex = conversation.messageNodes.subList(0, clickedIndex + 1).indexOfLast { it.role == MessageRole.USER }

                    if (lastUserIndex >= 0) {
                        val firstAssistantIndex = lastUserIndex + 1
                        val turnEndIndex = conversation.messageNodes.subList(firstAssistantIndex, conversation.messageNodes.size).indexOfFirst { it.role == MessageRole.USER }
                            .let { if (it == -1) conversation.messageNodes.size else firstAssistantIndex + it }

                        if (forceWipe) {
                            val nodes = conversation.messageNodes.subList(0, lastUserIndex + 1).toMutableList()
                            nodes.add(MessageNode(id = Uuid.random(), messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))))
                            if (turnEndIndex < conversation.messageNodes.size) nodes.addAll(conversation.messageNodes.subList(turnEndIndex, conversation.messageNodes.size))
                            saveConversation(conversationId, conversation.copy(messageNodes = nodes))
                            handleMessageComplete(conversationId)
                        } else {
                            val versionTag = Uuid.random().toString()
                            val nodes = conversation.messageNodes.subList(0, lastUserIndex + 1).toMutableList()
                            val firstAssistant = conversation.messageNodes.getOrNull(firstAssistantIndex)
                            if (firstAssistant != null) {
                                val newMessages = firstAssistant.messages + UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), versionTag = versionTag)
                                nodes.add(firstAssistant.copy(messages = newMessages, selectIndex = newMessages.lastIndex))
                                if (turnEndIndex < conversation.messageNodes.size) nodes.addAll(conversation.messageNodes.subList(turnEndIndex, conversation.messageNodes.size))
                            }
                            saveConversation(conversationId, conversation.copy(messageNodes = nodes))
                            handleMessageComplete(conversationId)
                        }
                    } else handleMessageComplete(conversationId, messageRange = 0..<clickedIndex)
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) { _errorFlow.emit(e) }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion { setGenerationJob(conversationId, null); appScope.launch { delay(500); checkAllConversationsReferences() } }
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        assistantOverride: Assistant? = null,
        skipContextForResponse: Boolean = false // 新增参数
    ) {
        val settings = settingsStore.settingsFlow.first()
        runCatching {

            val conversation = getConversationFlow(conversationId).value
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))
            val assistant = assistantOverride ?: settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
            val modelId = assistant.chatModelId ?: settings.chatModelId
            val model = settings.findModelById(modelId) ?: settings.getCurrentChatModel() ?: return@runCatching
            var firstTokenTime: Long? = null
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                val hasExternalTools = (assistant.searchMode !is AssistantSearchMode.Off) || mcpManager.getAllAvailableTools().isNotEmpty()
                if (hasExternalTools) _errorFlow.emit(IllegalStateException(context.getString(R.string.tools_warning)))
            }

            checkInvalidMessages(conversationId)
            // 💡 提取记忆检索逻辑并增加超时保护，防止 Embedding 挂起
            val retrievedMemories = withContext(Dispatchers.IO) {
                if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF && !temporaryConversations.contains(conversationId)) {
                    // 设置 8 秒超时，如果接口没响应就跳过记忆，保证聊天不卡死
                    kotlinx.coroutines.withTimeoutOrNull(8000) {
                        if (assistant.useRagMemoryRetrieval) {
                            val lastUserMsg = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.toText() ?: ""
                            if (lastUserMsg.isNotBlank()) {
                                val results = memoryRepository.retrieveRelevantMemoriesWithScores(
                                    assistantId = assistant.id.toString(),
                                    query = lastUserMsg,
                                    limit = assistant.ragLimit,
                                    similarityThreshold = assistant.ragSimilarityThreshold,
                                    includeCore = assistant.ragIncludeCore,
                                    includeEpisodes = assistant.ragIncludeEpisodes,
                                    mode = assistant.memoryRetrievalMode
                                )
                                val memories = results.map { it.first }
                                if (settings.enableRagLogging) {
                                    results.forEach { (mem, score) ->
                                        Log.d("RAG", " - [${mem.type}] (Score: ${String.format("%.4f", score)}) ${mem.content.take(50)}...")
                                    }
                                }
                                memories
                            } else {
                                emptyList()
                            }
                        } else memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                    } ?: emptyList() // 超时返回空列表
                } else emptyList()
            }

            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = conversation.currentMessages.let { if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it },
                assistant = assistant,
                memories = retrievedMemories,
                inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer) },
                outputTransformers = outputTransformers,
                tools = buildList {
                    // --- 工具权限过滤核心逻辑 ---
                    val isMain = assistant.isMain
                    val isVirtual = conversation.isVirtual

                    val supportsBuiltIn = model.tools.isNotEmpty() || me.rerere.ai.registry.ModelRegistry.GEMINI_SERIES.match(model.modelId)
                    val useBuiltIn = assistant.preferBuiltInSearch && supportsBuiltIn
                    val searchMode = assistant.searchMode

                    // 1. 搜索工具 (Web Search)：所有人都有权使用
                    if (searchMode is AssistantSearchMode.Provider && !useBuiltIn) {
                        addAll(createSearchTool(settings, assistant, searchMode.index))
                    }

                    // 2. 本地工具 (Local Tools)
                    val targetOptions = if (isVirtual) {
                         // 虚拟模式：仅保留 Memory, WebSearch (已在上面处理), TimeSense
                         assistant.localTools.filter { it is LocalToolOption.TimeSense }
                    } else if (isMain) {
                        assistant.localTools
                    } else {
                        // 非主智能体：仅保留 TimeSense（时间观念）
                        assistant.localTools.filter { it is LocalToolOption.TimeSense }
                    }

                    addAll(
                        localTools.getTools(
                            options = targetOptions,
                            assistantId = assistant.id,
                            conversationId = conversation.id,
                            userImageUrls = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.parts?.filterIsInstance<UIMessagePart.Image>()?.map { it.url } ?: emptyList()
                        )
                    )

                    // 3. MCP 外部工具：只有主智能体且非虚拟模式可以使用
                    if (isMain && !isVirtual) {
                        val nameRegex = Regex("[^a-zA-Z0-9_.:-]")
                        mcpManager.getAllAvailableTools().forEach { mcpTool ->
                            val originalName = mcpTool.name
                            val sanitizedName = originalName.replace(nameRegex, "_").let {
                                if (it.firstOrNull()?.isLetter() == true || it.startsWith("_")) it else "_$it"
                            }

                            add(Tool(
                                name = sanitizedName,
                                description = mcpTool.description ?: "",
                                parameters = { mcpTool.inputSchema },
                                execute = { mcpManager.callTool(originalName, it.jsonObject).truncateLargeJsonText() }
                            ))
                        }
                    }
                },
                truncateIndex = conversation.truncateIndex,
                enabledModeIds = conversation.enabledModeIds,
                contextSummary = conversation.contextSummary, // 传入全量总结
                temporarySummaries = emptyList(), // 不再传递内存列表，由 GenerationHandler 自行查询 DB
                skipContextForResponse = skipContextForResponse, // 传递参数
                conversationId = conversationId // 传递会话 ID
            ).onCompletion {
                val duration = firstTokenTime?.let { System.currentTimeMillis() - it }
                val current = getConversationFlow(conversationId).value
                val updated = current.copy(messageNodes = current.messageNodes.mapIndexed { idx, node ->
                    val isLast = idx == current.messageNodes.lastIndex
                    node.copy(messages = node.messages.map { msg ->
                        val finished = msg.finishReasoning()
                        if (isLast && finished.role == MessageRole.ASSISTANT && finished.generationDurationMs == null) finished.copy(generationDurationMs = duration) else finished
                    })
                }, updateAt = Instant.now())
                updateConversation(conversationId, updated)
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) sendGenerationDoneNotification(conversationId)
            }.collect { chunk ->
                if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                if (chunk is GenerationChunk.Messages) updateConversation(conversationId, getConversationFlow(conversationId).value.updateCurrentMessages(chunk.messages))
            }
        }.onFailure { e ->
            // 【核心修复】：无论何种错误（取消、超时、解析错误），都保存当前已生成的半截内容到数据库，防止消息丢失
            Log.d(TAG, "Generation failed/cancelled for $conversationId, saving current state. Error: ${e.message}")
            val finalConv = getConversationFlow(conversationId).value
            appScope.launch {
                // 1. 保存对话内容（包含 AI 可能生成到一半的部分）
                saveConversation(conversationId, finalConv)

                // 2. 同时更新助手的最后通话 ID，确保下次进入或归档时能关联上
                val currentSettings = settingsStore.settingsFlow.value
                val updatedAssistants = currentSettings.assistants.map {
                    if (it.id == finalConv.assistantId) it.copy(lastConversationId = conversationId.toString()) else it
                }
                settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
            }

            if (e !is kotlinx.coroutines.CancellationException) {
                // 普通错误仍需上报 UI
                _errorFlow.emit(e)
                Logging.log(TAG, "handleMessageComplete: $e")
            }
        }
        .onSuccess {
            val finalConv = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConv)

            // Record Token Usage from the last generation
            val lastAssistantMsg = finalConv.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            lastAssistantMsg?.usage?.let { usage ->
                appScope.launch {
                    conversationRepo.recordTokenUsage(finalConv.assistantId.toString(), usage)
                }
            }

            appScope.launch {
                val currentSettings = settingsStore.settingsFlow.value
                val updatedAssistants = currentSettings.assistants.map {
                    if (it.id == finalConv.assistantId) {
                        // 将该助手的 lastConversationId 更新为当前会话 ID
                        it.copy(lastConversationId = conversationId.toString())
                    } else it
                }
                // 持久化更新后的助手列表
                settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
            }
            addConversationReference(conversationId)
            appScope.launch {
                coroutineScope {
                    launch { conversationRepo.getConversationById(conversationId)?.let { generateTitle(conversationId, it) } }
                    launch { generateSuggestion(conversationId, finalConv) }
                    launch { checkAndAutoSummarize(conversationId, finalConv, settings) }
                }
            }.invokeOnCompletion { removeConversationReference(conversationId) }
        }
    }

    private fun createSearchTool(settings: Settings, assistant: Assistant, providerIndex: Int? = null): Set<Tool> {
        val idx = providerIndex ?: settings.searchServiceSelected
        return buildSet {
            add(Tool(name = "search_web", description = "search web", parameters = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                SearchService.getService(opt).parameters
            }, execute = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }

                // 1. 强制固定返回 6 条搜索结果
                val resultSize = 6
                val commonOptions = settings.searchCommonOptions.copy(resultSize = resultSize)

                val searchResult = SearchService.getService(opt).search(it.jsonObject, commonOptions, opt).getOrThrow()

                // 打印原始搜索日志
                Log.d(TAG, "Web Search Raw Results (Fixed Request: $resultSize, Got: ${searchResult.items.size})")
                searchResult.items.forEachIndexed { i, item ->
                    Log.v(TAG, "Raw Item [$i]: ${item.title} (${item.url})")
                }

                // 2. 过滤网页标签 (使用 Regex 清理 HTML)
                val htmlRegex = Regex("<[^>]*>")
                val cleanedItems = searchResult.items.take(resultSize).map { item ->
                    item.copy(text = item.text.replace(htmlRegex, "").trim())
                }

                // 3. Background model 处理
                val backgroundModelId = assistant.backgroundModelId ?: settings.backgroundModelId
                val backgroundModel = settings.findModelById(backgroundModelId) ?: settings.getCurrentChatModel()

                if (backgroundModel != null) {
                    val providerSetting = backgroundModel.findProvider(settings.providers)
                    if (providerSetting != null) {
                        try {
                            val handler = providerManager.getProviderByType(providerSetting)
                            val locale = Locale.getDefault().displayName

                            val searchContent = cleanedItems.joinToString("\n") { item ->
                                 "Title: ${item.title}\nURL: ${item.url}\nContent: ${item.text}"
                            }

                            val prompt = """
                                你是一个专业的信息密度压缩引擎。你的任务是将网页搜索结果处理成供大模型阅读的精炼摘要。
                                注意：该摘要是给 AI 阅读的，不是给人阅读的。
                                要求：
                                1. 信息密度拉满，只保留核心事实、关键数据和结论。
                                2. 情绪价值拉到最低，去除所有修饰词、客气话和主观色彩。保持 factual 和高效。
                                3. 在事实陈述后保留原始搜索结果的索引（如 [1], [2]）。
                                4. 保持机器友好的逻辑结构。
                                5. 语言：使用 $locale。

                                搜索内容：
                                $searchContent
                            """.trimIndent()

                            @Suppress("UNCHECKED_CAST")
                            val h = handler as Provider<ProviderSetting>
                            val resp = h.generateText(
                                providerSetting,
                                listOf(UIMessage.user(prompt)),
                                TextGenerationParams(backgroundModel, 0.3f, 0.5f)
                            )
                            resp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
                            val summary = resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""

                            if (summary.isNotBlank()) {
                                Log.i(TAG, "Web Search Background Summary (Model: ${backgroundModel.modelId}):\n$summary")
                                Log.i(TAG, "Background Model Token Usage: ${resp.usage ?: "unknown"}")
                                return@Tool buildJsonObject {
                                    put("summary", summary)
                                    put("note", "This summary is processed by a background model for efficiency.")
                                    put("items", JsonArray(cleanedItems.mapIndexed { i, item ->
                                        buildJsonObject {
                                            put("id", Uuid.random().toString().take(6))
                                            put("index", i + 1)
                                            put("title", item.title)
                                            put("url", item.url)
                                        }
                                    }))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Search background summary failed", e)
                        }
                    }
                }

                // Fallback: 返回原始的前 6 条（已清洗内容）
                Log.w(TAG, "Background model summary failed, falling back to raw cleaned content.")
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
            }, systemPrompt = { _, msgs ->
                if (msgs.any { it.getToolCalls().any { tc -> tc.toolName == "search_web" } }) "## tool: search_web\n\nUse `[citation,domain](id)` after facts." else "## tool: search_web"
            }))
        }
    }

    private suspend fun checkAndAutoSummarize(id: Uuid, conv: Conversation, settings: Settings) {
        val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
        if (!assistant.enableMemory) return
        if (!assistant.enableContextRefresh || !assistant.autoRegenerateSummary) return
        val max = assistant.maxHistoryMessages ?: return

        // 【核心修改】触发逻辑调整为：当前消息数减去最后一条已总结消息的索引，超过上限即触发
        val count = if (conv.contextSummaryUpToIndex >= 0) {
            conv.currentMessages.size - (conv.contextSummaryUpToIndex + 1)
        } else {
            conv.currentMessages.size
        }

        if (count >= max) summarizeAndRefresh(id)
    }

    suspend fun summarizeAndRefresh(id: Uuid): ContextRefreshResult = withContext(Dispatchers.IO) {
        try {
            val settings = settingsStore.settingsFlow.first()
            val conv = conversationRepo.getConversationById(id) ?: return@withContext ContextRefreshResult(false, errorMessage = "Not found")

            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
            val messages = conv.currentMessages

            if (messages.isEmpty()) return@withContext ContextRefreshResult(false)
            val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
            val model = settings.findModelById(modelId) ?: return@withContext ContextRefreshResult(false)
            val provider = model.findProvider(settings.providers) ?: return@withContext ContextRefreshResult(false)
            val handler = providerManager.getProviderByType(provider)

            val backgroundModelId = assistant.backgroundModelId ?: settings.backgroundModelId
            val backgroundModel = settings.findModelById(backgroundModelId) ?: model
            val backgroundProvider = backgroundModel.findProvider(settings.providers) ?: provider
            val backgroundHandler = providerManager.getProviderByType(backgroundProvider)

            // 保持适度重叠：总结到倒数第 5 条（即保留最后 4 条作为未总结缓冲）
            val lastIdx = (messages.size - 5).coerceAtLeast(0)
            val startIdx = if (conv.contextSummaryUpToIndex >= 0) (conv.contextSummaryUpToIndex + 1) else 0
            val toSummarize = if (startIdx <= lastIdx) messages.subList(startIdx, lastIdx + 1) else emptyList()

            if (toSummarize.isEmpty()) {
                return@withContext ContextRefreshResult(false)
            }

            // 使用 toContentText() 排除推理过程
            val text = toSummarize.joinToString("\n") {
                "${it.role}: ${it.toContentText().take(500)}"
            }
            val locale = Locale.getDefault().displayName

            // 1. 生成片段摘要 (L1 Segment)
            val tempPrompt = DEFAULT_TEMP_SUMMARY_PROMPT
                .replace("{{new_messages}}", text)
                .replace("{{locale}}", locale)
                .replace("{{char}}", assistant.name)

            val providerHandler = handler as Provider<ProviderSetting>
            val tempResp = providerHandler.generateText(provider, listOf(UIMessage.user(tempPrompt)), TextGenerationParams(model, 0.3f, 1.0f))
            tempResp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
            val tempSum = tempResp.choices.firstOrNull()?.message?.toContentText() ?: ""

            if (tempSum.isNotBlank()) {
                val aiKeywords = extractKeywords(
                    handler = backgroundHandler,
                    providerSetting = backgroundProvider,
                    model = backgroundModel,
                    summary = tempSum,
                    assistantId = assistant.id.toString()
                )
                val localKeywords = KeywordExtractor.extract(tempSum)
                val keywords = mergeKeywords(aiKeywords, localKeywords)

                val effectiveContent = if (keywords.isNotBlank()) "Keywords: $keywords\nContent: $tempSum" else tempSum
                val embedding = try {
                    embeddingService.embed(effectiveContent, assistant.id.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate embedding for segment", e)
                    null
                }

                val segment = ChatSegmentEntity(
                    assistantId = assistant.id.toString(),
                    conversationId = id.toString(),
                    content = tempSum,
                    keywords = keywords,
                    startMessageIndex = startIdx,
                    endMessageIndex = lastIdx,
                    embedding = embedding?.let { JsonInstant.encodeToString(it) }
                )
                memoryRepository.saveSegment(segment)
                Log.i(TAG, "Persistent segment (L1) saved for conversation $id: $startIdx to $lastIdx (Keywords: ${keywords.take(20)}...)")
            }

            // 2. 生成全量背景总结 (L1 Global Summary)
            val currentSummary = conv.contextSummary
            // 统一使用 DEFAULT_FULL_SUMMARY_PROMPT，并处理空值
            val fullPrompt = DEFAULT_FULL_SUMMARY_PROMPT
                .replace("{{previous_summary}}", currentSummary ?: "None")
                .replace("{{new_messages}}", text)
                .replace("{{locale}}", locale)
                .replace("{{char}}", assistant.name)

            Log.d(TAG, "Summarize L1 Full Context Prompt:\n$fullPrompt")

            val fullResp = retryIO(times = 1) {
                handler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(fullPrompt)),
                    params = TextGenerationParams(model, 0.3f, 1.0f)
                )
            }
            fullResp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }

            val fullSum = fullResp.choices.firstOrNull()?.message?.toContentText() ?: return@withContext ContextRefreshResult(false)

            val updated = conv.copy(
                contextSummary = fullSum,
                contextSummaryUpToIndex = lastIdx,
                lastRefreshTime = System.currentTimeMillis()
            )

            conversationRepo.updateConversation(updated)
            updateConversation(id, updated)
            ContextRefreshResult(true, fullSum, toSummarize.size)
        } catch (e: Exception) { ContextRefreshResult(false, errorMessage = e.message) }
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
            val locale = Locale.getDefault().displayName
            val prompt = DEFAULT_KEYWORD_EXTRACTION_PROMPT
                .replace("{{summary}}", summary)
                .replace("{{locale}}", locale)

            val h = handler as Provider<ProviderSetting>
            val resp = h.generateText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 1.0f,
                    maxTokens = 256
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
        if (temporaryConversations.contains(id)) { updateConversation(id, conversation); return }
        updateConversation(id, conversation)

        // In virtual mode, we allow saving empty conversations to ensure session partitioning
        // is preserved when "Start New Topic" is clicked.
        if (conversation.title.isBlank() && conversation.messageNodes.isEmpty() && !conversation.isVirtual) return

        if (conversationRepo.getConversationById(id) == null) conversationRepo.insertConversation(conversation) else conversationRepo.updateConversation(conversation)
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
                    generationHandler.translateText(settings, text, target, modelId) { updateTranslationField(id, message.id, it) }.collect {}
                    // We don't record usage for translateText because currently translateText flow only returns String.
                    // If needed, we'd need to modify translateText to return usage too.
                    saveConversation(id, getConversationFlow(id).value)
                }
            } catch (e: Exception) { updateTranslationField(id, message.id, null); _errorFlow.emit(e) }
        }
    }

    private suspend fun updateTranslationField(id: Uuid, mid: Uuid, text: String?) {
        val conv = getConversationFlow(id).value
        val nodes = conv.messageNodes.map { node ->
            if (node.messages.any { it.id == mid }) node.copy(messages = node.messages.map { if (it.id == mid) it.copy(translation = text) else it }) else node
        }
        updateConversation(id, conv.copy(messageNodes = nodes))
    }

    fun cleanupConversation(id: Uuid) {
        _generationJobs.value[id]?.cancel()
        removeGenerationJob(id)
        conversations.remove(id)
    }

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val conv = getConversationFlow(conversationId).value
        var nodes = conv.messageNodes.filter { it.messages.isNotEmpty() }
            .map { if (it.selectIndex !in it.messages.indices) it.copy(selectIndex = 0) else it }
        nodes = nodes.mapIndexed { idx, node ->
            val next = nodes.getOrNull(idx + 1)
            if (node.currentMessage.hasPart<UIMessagePart.ToolCall>() && next?.currentMessage?.hasPart<UIMessagePart.ToolResult>() != true)
                node.copy(messages = node.messages.filter { it.id != node.currentMessage.id }, selectIndex = (node.selectIndex - 1).coerceAtLeast(0))
            else node
        }.filter { it.messages.isNotEmpty() }
        updateConversation(conversationId, conv.copy(messageNodes = nodes))
    }

    suspend fun generateTitle(conversationId: Uuid, conversation: Conversation, force: Boolean = false) {
        if (conversation.isVirtual) return
        if (!force && conversation.title.isNotBlank()) return
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: settings.getCurrentChatModel() ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val content = conversation.currentMessages.truncate(conversation.truncateIndex).joinToString("\n\n") { it.summaryAsText() }
            if (content.isBlank()) return
            val result = (providerManager.getProviderByType(provider) as Provider<ProviderSetting>).generateText(provider, listOf(UIMessage.user(settings.titlePrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to content))), TextGenerationParams(model, 0.3f, 1.0f))
            result.usage?.let { conversationRepo.recordTokenUsage(conversation.assistantId.toString(), it) }
            saveConversation(conversationId, conversation.copy(title = result.choices[0].message?.toContentText()?.trim() ?: ""))
        }
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
            val modelId = assistant.suggestionModelId ?: settings.suggestionModelId
            val model = settings.findModelById(modelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val result = (providerManager.getProviderByType(provider) as Provider<ProviderSetting>).generateText(provider, listOf(UIMessage.user(settings.suggestionPrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to conversation.currentMessages.truncate(conversation.truncateIndex).takeLast(8).joinToString("\n") { it.summaryAsText() }))), TextGenerationParams(model, 1.0f, 1.0f))
            result.usage?.let { conversationRepo.recordTokenUsage(conversation.assistantId.toString(), it) }
            val suggestions = result.choices[0].message?.toContentText()?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
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
            conversationDeletionJobs[conversation.id] = appScope.launch { delay(4000); context.deleteChatFiles(full.files); recentlyDeletedConversations.remove(conversation.id) }
        }
    }

    fun undoDeleteConversation(conversationId: Uuid) {
        conversationDeletionJobs[conversationId]?.cancel()
        recentlyDeletedConversations.remove(conversationId)?.let { conv ->
            appScope.launch { conversationRepo.insertConversation(conv); _recentlyRestoredIds.value += conversationId; delay(1000); _recentlyRestoredIds.value -= conversationId }
        }
    }

    private fun sendGenerationDoneNotification(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.value
        // 获取当前会话对应的智能体
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val lastMsg = conversation.currentMessages.lastOrNull()
        val msg = lastMsg?.toContentText()?.take(50) ?: ""
        val notification = NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(assistant.name) // 设置标题为智能体名称
            .setContentText(msg)            // 设置内容（已过滤思考过程）
            .setSmallIcon(R.drawable.about_logo)
            .setAutoCancel(true)
            .setContentIntent(getPendingIntent(context, conversationId))

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            NotificationManagerCompat.from(context).notify(1, notification.build())
    }

    private fun getPendingIntent(context: Context, id: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra("conversationId", id.toString()) }
        return PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        is JsonPrimitive -> if (this.isString && this.content.length > maxLength) JsonPrimitive(this.content.take(maxLength) + "... (truncated)") else this
        is JsonObject -> JsonObject(this.mapValues { it.value.truncateLargeJsonText(maxLength) })
        is JsonArray -> JsonArray(this.map { it.truncateLargeJsonText(maxLength) })
    }
}
