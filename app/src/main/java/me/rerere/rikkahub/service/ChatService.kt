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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
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
import me.rerere.rikkahub.common.JsonInstantPretty
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.MemoryType
import me.rerere.rikkahub.core.data.model.AssistantSearchMode
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.toMessageNode
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_EPISODIC_CONSOLIDATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TEMP_SUMMARY_PROMPT
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

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        _generationJobs.value.values.forEach { it?.cancel() }
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

    private fun hasReference(conversationId: Uuid): Boolean =
        conversationReferences.containsKey(conversationId) || _generationJobs.value.containsKey(conversationId)

    fun checkAllConversationsReferences() {
        conversations.keys.forEach { if (!hasReference(it)) cleanupConversation(it) }
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        val settings = settingsStore.settingsFlow.value
        return conversations.getOrPut(conversationId) {
            MutableStateFlow(Conversation.ofId(id = conversationId, assistantId = settings.getCurrentAssistant().id))
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
            val newConversation = Conversation.ofId(id = conversationId, assistantId = assistant.id)
                .updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    /**
     * 将会话存档为唯一的片段记忆 (1:1 映射)
     * @param force 如果为 true，则无视消息数量限制，立即更新记忆
     */
    suspend fun archiveConversation(conversationId: Uuid, force: Boolean = false) {
        if (temporaryConversations.contains(conversationId)) return

        try {
            val conv = conversationRepo.getConversationById(conversationId) ?: return
            val messages = conv.currentMessages
            if (messages.size < 4 && !force) return

            val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conversationId.toString())

            // 如果已存在记忆，检查是否满足更新条件（新消息数量 > 4）
            if (existingEpisode != null && !force) {
                // 利用 significance 暂存上次归档时的消息数
                val lastArchivedCount = existingEpisode.significance
                if (messages.size - lastArchivedCount < 4) {
                    return
                }
            }

            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
            if (!assistant.enableMemoryConsolidation && !force) return

            val modelId = assistant.summarizerModelId ?: assistant.chatModelId ?: settings.chatModelId
            val model = settings.findModelById(modelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val handler = providerManager.getProviderByType(provider)

            // 生成会话总体摘要
            val text = messages.joinToString("\n") { "${it.role}: ${it.toText().take(500)}" }
            val prompt = DEFAULT_EPISODIC_CONSOLIDATION_PROMPT
                .replace("{{text}}", text)
                .replace("{{locale}}", Locale.getDefault().displayName)

            val providerSetting = provider as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
            val resp = providerSetting.generateText(provider, listOf(UIMessage.user(prompt)), TextGenerationParams(model, 0.3f))
            val summary = resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""

            if (summary.isNotBlank()) {
                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0, // 替换旧的或新建
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    content = summary,
                    startTime = conv.createAt.toEpochMilli(),
                    endTime = conv.updateAt.toEpochMilli(),
                    significance = messages.size, // 关键：记录当前消息总数
                    lastAccessedAt = System.currentTimeMillis()
                )
                chatEpisodeDAO.insertEpisode(episode)
                Log.i(TAG, "Archived/Updated episodic memory for conversation $conversationId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive conversation $conversationId", e)
        }
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true, isTemporaryChat: Boolean = false) {
        if (isTemporaryChat) temporaryConversations.add(conversationId)
        _generationJobs.value[conversationId]?.cancel()

        val job = appScope.launch {
            try {
                val currentConversation = getConversationFlow(conversationId).value
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

    private suspend fun handleMessageComplete(conversationId: Uuid, messageRange: ClosedRange<Int>? = null) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel() ?: return
        var firstTokenTime: Long? = null

        runCatching {
            val conversation = getConversationFlow(conversationId).value
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))
            val assistant = settings.getCurrentAssistant()

            if (!model.abilities.contains(ModelAbility.TOOL)) {
                val hasExternalTools = (assistant.searchMode !is AssistantSearchMode.Off) || mcpManager.getAllAvailableTools().isNotEmpty()
                if (hasExternalTools) _errorFlow.emit(IllegalStateException(context.getString(R.string.tools_warning)))
            }

            checkInvalidMessages(conversationId)

            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = conversation.currentMessages.let { if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it },
                assistant = assistant,
                memories = if (assistant.enableMemory && !temporaryConversations.contains(conversationId)) {
                    if (assistant.useRagMemoryRetrieval) {
                        val lastUserMsg = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.toText() ?: ""
                        if (lastUserMsg.isNotBlank()) {
                            val results = memoryRepository.retrieveRelevantMemories(assistantId = settings.assistantId.toString(), query = lastUserMsg, limit = assistant.ragLimit, similarityThreshold = assistant.ragSimilarityThreshold, includeCore = assistant.ragIncludeCore, includeEpisodes = assistant.ragIncludeEpisodes)
                            if (settings.enableRagLogging) results.forEach { Log.d("RAG", " - [${it.type}] ${it.content.take(50)}...") }
                            results
                        } else memoryRepository.getMemoriesOfAssistant(settings.assistantId.toString())
                    } else memoryRepository.getMemoriesOfAssistant(settings.assistantId.toString())
                } else emptyList(),
                inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer) },
                outputTransformers = outputTransformers,
                tools = buildList {
                    val supportsBuiltIn = model.tools.isNotEmpty() || me.rerere.ai.registry.ModelRegistry.GEMINI_SERIES.match(model.modelId)
                    val useBuiltIn = assistant.preferBuiltInSearch && supportsBuiltIn
                    val searchMode = assistant.searchMode
                    if (searchMode is AssistantSearchMode.Provider && !useBuiltIn) addAll(createSearchTool(settings, searchMode.index))
                    addAll(localTools.getTools(options = assistant.localTools, assistantId = assistant.id, conversationId = conversation.id, userImageUrls = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.parts?.filterIsInstance<UIMessagePart.Image>()?.map { it.url } ?: emptyList()))

                    // MCP Tools
                    val nameRegex = Regex("[^a-zA-Z0-9_.:-]")
                    mcpManager.getAllAvailableTools().forEach { mcpTool ->
                        val originalName = mcpTool.name
                        // 处理名称：替换非法字符为下划线，确保以字母或下划线开头
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
                },
                truncateIndex = conversation.truncateIndex,
                enabledModeIds = conversation.enabledModeIds,
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
        }.onFailure { _errorFlow.emit(it); Logging.log(TAG, "handleMessageComplete: $it") }
        .onSuccess {
            val finalConv = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConv)
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

    private fun createSearchTool(settings: Settings, providerIndex: Int? = null): Set<Tool> {
        val idx = providerIndex ?: settings.searchServiceSelected
        return buildSet {
            add(Tool(name = "search_web", description = "search web", parameters = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                SearchService.getService(opt).parameters
            }, execute = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                val result = SearchService.getService(opt).search(it.jsonObject, settings.searchCommonOptions, opt)
                JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                    val map = json.toMutableMap()
                    val items = map["items"]
                    if (items is JsonArray) map["items"] = JsonArray(items.mapIndexed { i, item ->
                        if (item is JsonObject) JsonObject(item.toMutableMap().apply { put("id", JsonPrimitive(Uuid.random().toString().take(6))); put("index", JsonPrimitive(i + 1)) }) else item
                    })
                    JsonObject(map)
                }
            }, systemPrompt = { _, msgs ->
                if (msgs.any { it.getToolCalls().any { tc -> tc.toolName == "search_web" } }) "## tool: search_web\n\nUse `[citation,domain](id)` after facts." else "## tool: search_web"
            }))
        }
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
        if (!force && conversation.title.isNotBlank()) return
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: settings.getCurrentChatModel() ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val content = conversation.currentMessages.truncate(conversation.truncateIndex).joinToString("\n\n") { it.summaryAsText() }
            if (content.isBlank()) return
            val result = (providerManager.getProviderByType(provider) as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateText(provider, listOf(UIMessage.user(settings.titlePrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to content))), TextGenerationParams(model, 0.3f, 0f))
            saveConversation(conversationId, conversation.copy(title = result.choices[0].message?.toContentText()?.trim() ?: ""))
        }
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val result = (providerManager.getProviderByType(provider) as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateText(provider, listOf(UIMessage.user(settings.suggestionPrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to conversation.currentMessages.truncate(conversation.truncateIndex).takeLast(8).joinToString("\n\n") { it.summaryAsText() }))), TextGenerationParams(model, 1.0f, 0f))
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
        val msg = getConversationFlow(conversationId).value.currentMessages.lastOrNull()?.toText()?.take(50) ?: ""
        val notification = NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_chat_done_title)).setContentText(msg)
            .setSmallIcon(R.drawable.about_logo).setAutoCancel(true).setContentIntent(getPendingIntent(context, conversationId))
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

    private suspend fun checkAndAutoSummarize(id: Uuid, conv: Conversation, settings: Settings) {
        val assistant = settings.getCurrentAssistant()
        if (!assistant.enableContextRefresh || !assistant.autoRegenerateSummary) return
        val max = assistant.maxHistoryMessages ?: return
        val count = if (conv.contextSummaryUpToIndex >= 0) (conv.currentMessages.size - conv.contextSummaryUpToIndex - 1 - 4).coerceAtLeast(0) else (conv.currentMessages.size - 4).coerceAtLeast(0)
        if (count >= max) summarizeAndRefresh(id)
    }

    suspend fun summarizeAndRefresh(id: Uuid): ContextRefreshResult = withContext(Dispatchers.IO) {
        try {
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getCurrentAssistant()
            val conv = conversationRepo.getConversationById(id) ?: return@withContext ContextRefreshResult(false, errorMessage = "Not found")
            val messages = conv.currentMessages
            if (messages.isEmpty()) return@withContext ContextRefreshResult(false)
            val modelId = assistant.summarizerModelId ?: assistant.chatModelId ?: settings.chatModelId
            val model = settings.findModelById(modelId) ?: return@withContext ContextRefreshResult(false)
            val provider = model.findProvider(settings.providers) ?: return@withContext ContextRefreshResult(false)
            val handler = providerManager.getProviderByType(provider)
            val lastIdx = (messages.size - 5).coerceAtLeast(0)
            val startIdx = if (conv.contextSummaryUpToIndex >= 0) (conv.contextSummaryUpToIndex + 1) else 0
            val toSummarize = if (startIdx <= lastIdx) messages.subList(startIdx, lastIdx + 1) else emptyList()
            if (toSummarize.isEmpty()) return@withContext ContextRefreshResult(false)
            val text = toSummarize.joinToString("\n") { "${it.role}: ${it.toText().take(500)}" }
            val locale = Locale.getDefault().displayName
            val tempPrompt = assistant.temporarySummaryPrompt.ifBlank { DEFAULT_TEMP_SUMMARY_PROMPT }.replace("{{new_messages}}", text).replace("{{locale}}", locale)
            val tempResp = handler.generateText(provider, listOf(UIMessage.user(tempPrompt)), TextGenerationParams(model, 0.3f))
            val tempSum = tempResp.choices.firstOrNull()?.message?.toContentText() ?: ""

            // 保留：上下文摘要存入片段记忆数据库
            if (tempSum.isNotBlank()) memoryRepository.addMemory(assistant.id.toString(), "Recent: $tempSum", type = MemoryType.EPISODIC)

            val currentSummary = conv.contextSummary
            val fullPrompt = if (!currentSummary.isNullOrBlank()) assistant.fullSummaryPrompt.ifBlank { DEFAULT_FULL_SUMMARY_PROMPT }.replace("{{previous_summary}}", currentSummary).replace("{{new_messages}}", text).replace("{{locale}}", locale) else "Summarize:\n$text"
            val fullResp = handler.generateText(provider, listOf(UIMessage.user(fullPrompt)), TextGenerationParams(model, 0.3f))
            val fullSum = fullResp.choices.firstOrNull()?.message?.toContentText() ?: return@withContext ContextRefreshResult(false)
            val updated = conv.copy(contextSummary = fullSum, temporarySummaries = conv.temporarySummaries + tempSum, contextSummaryUpToIndex = lastIdx, lastRefreshTime = System.currentTimeMillis())
            conversationRepo.updateConversation(updated)
            updateConversation(id, updated)
            ContextRefreshResult(true, fullSum, toSummarize.size)
        } catch (e: Exception) { ContextRefreshResult(false, errorMessage = e.message) }
    }

    suspend fun saveConversation(id: Uuid, conversation: Conversation) {
        if (temporaryConversations.contains(id)) { updateConversation(id, conversation); return }
        updateConversation(id, conversation)
        if (conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return
        if (conversationRepo.getConversationById(id) == null) conversationRepo.insertConversation(conversation) else conversationRepo.updateConversation(conversation)
    }

    fun translateMessage(id: Uuid, message: UIMessage, target: Locale) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val conv = getConversationFlow(id).value
                val modelId = settings.getAssistantById(conv.assistantId)?.chatModelId ?: settings.chatModelId
                val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim()
                if (text.isNotBlank()) {
                    updateTranslationField(id, message.id, context.getString(R.string.translating))
                    generationHandler.translateText(settings, text, target, modelId) { updateTranslationField(id, message.id, it) }.collect {}
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
}

private fun kotlinx.serialization.json.JsonElement.truncateLargeJsonText(maxLength: Int = 32000): kotlinx.serialization.json.JsonElement {
    return when (this) {
        is JsonPrimitive -> if (this.isString && this.content.length > maxLength) JsonPrimitive(this.content.take(maxLength) + "... (truncated)") else this
        is JsonObject -> JsonObject(this.mapValues { it.value.truncateLargeJsonText(maxLength) })
        is JsonArray -> JsonArray(this.map { it.truncateLargeJsonText(maxLength) })
        else -> this
    }
}
