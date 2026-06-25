package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.TokenUsageEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantMemory
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.model.Tag
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import me.rerere.rikkahub.core.data.repository.AssistantExtendedStateRepository
import me.rerere.rikkahub.core.data.repository.AgentMonitorTaskRepository
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity
import me.rerere.rikkahub.core.data.db.entity.AssistantExtendedStateEntity
import me.rerere.rikkahub.core.data.db.entity.MemoryType
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_OPTIMIZATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MASTER_MEMORY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TEMP_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.applyPlaceholders
import me.rerere.rikkahub.core.data.utils.KeywordExtractor
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.core.data.model.toMessageNode
import me.rerere.rikkahub.core.data.model.Conversation
import java.time.Instant

private const val TAG = "AssistantDetailVM"

@Serializable
data class AssistantMemoryOp(
    val op: String,
    val id: Int? = null,
    val content: String? = null
)

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val context: Application,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val providerManager: ProviderManager,
    private val agentTaskRepository: AgentTaskRepository,
    private val extendedStateRepository: AssistantExtendedStateRepository,
    private val agentMonitorTaskRepository: AgentMonitorTaskRepository,
    private val embeddingService: EmbeddingService
) : ViewModel() {
    private val assistantId = try {
        Uuid.parse(id)
    } catch (e: Exception) {
        Uuid.NIL
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = Assistant()
        )

    val extendedState: StateFlow<AssistantExtendedStateEntity> = extendedStateRepository
        .getStateByIdFlow(assistantId.toString())
        .map { it ?: AssistantExtendedStateEntity(assistantId.toString()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, AssistantExtendedStateEntity(assistantId.toString()))

    fun updateExtendedState(state: AssistantExtendedStateEntity) {
        viewModelScope.launch {
            extendedStateRepository.updateState(state)
        }
    }

    val tokenUsageHistory: StateFlow<List<TokenUsageEntity>> = conversationRepository
        .getRecentTokenUsageFlow(assistantId.toString(), days = 7)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val todayTokenUsage: StateFlow<TokenUsageEntity?> = tokenUsageHistory.map { history ->
        val today = LocalDate.now().toString()
        history.find { it.date == today }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 过滤掉已执行的任务，只显示未执行的
    val agentTasks: StateFlow<List<AgentTaskEntity>> = agentTaskRepository
        .getTasksByAssistant(assistantId.toString())
        .map { tasks -> tasks.filter { !it.isExecuted } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun deleteAgentTask(task: AgentTaskEntity) {
        viewModelScope.launch {
            agentTaskRepository.deleteTask(task)
        }
    }

    // 获取监视器任务
    val monitorTasks: StateFlow<List<AgentMonitorTaskEntity>> = agentMonitorTaskRepository
        .getTasksByAssistant(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun deleteMonitorTask(task: AgentMonitorTaskEntity) {
        viewModelScope.launch {
            agentMonitorTaskRepository.deleteTask(task)
        }
    }

    val mcpServerConfigs: StateFlow<List<McpServerConfig>> = settingsStore.settingsFlow
        .map { it.mcpServers }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val tags: StateFlow<List<Tag>> = settingsStore.settingsFlow
        .map { it.assistantTags }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val episodes: StateFlow<List<ChatEpisodeEntity>> = chatEpisodeDAO.getEpisodesOfAssistantFlow(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val segments: StateFlow<List<AssistantMemory>> = memoryRepository.getSegmentsOfAssistantFlow(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 修正：判断是否存在任何级别的记忆 (L1-L3)
    val hasMemories: StateFlow<Boolean> = combine(
        memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString()),
        segments
    ) { core, segs ->
        core.isNotEmpty() || segs.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val hasLorebooks: StateFlow<Boolean> = assistant.map { it.enabledLorebookIds.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val episodeStats: StateFlow<EpisodeStats> = combine(
        memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString()),
        segments
    ) { core, segs ->
        EpisodeStats(
            totalEpisodes = segs.size,
            averageSignificance = 0.0, // Segments don't have significance in the same way
            coreMemoryCount = core.size
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, EpisodeStats(0, 0.0, 0))

    val systemPromptTokenCount: StateFlow<Int> = assistant.map { estimateTokens(it.systemPrompt) }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _memorySearchQuery = MutableStateFlow("")
    val memorySearchQuery = _memorySearchQuery.asStateFlow()

    fun updateMemorySearchQuery(query: String) {
        _memorySearchQuery.value = query
    }

    val memories: StateFlow<List<AssistantMemory>> = combine(
        memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString()),
        segments,
        _memorySearchQuery
    ) { coreMemories, segmentList, query ->
        val core = coreMemories.map { it.copy(content = it.content) }
        val l1Memories = segmentList.map {
            it.copy(id = -it.id) // Use negative ID for segments in the list to distinguish from core
        }
        val allMemories = core + l1Memories
        if (query.isBlank()) allMemories else allMemories.filter { it.content.contains(query, ignoreCase = true) }
    }.stateIn(
        scope = viewModelScope, started = SharingStarted.Lazily, initialValue = emptyList()
    )

    val currentEmbeddingModelId: StateFlow<String> = combine(
        assistant,
        settings
    ) { assistant, settings ->
        (assistant.embeddingModelId ?: settings.embeddingModelId).toString()
    }.stateIn(
        scope = viewModelScope, started = SharingStarted.Lazily, initialValue = ""
    )

    // 重排序模型现在仅使用全局配置
    val currentRerankModelId: StateFlow<String?> = settings
        .map { it.rerankModelId?.toString() }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = null
        )

    private val _isOptimizing = MutableStateFlow(false)
    val isOptimizing = _isOptimizing.asStateFlow()

    private val _isConsolidating = MutableStateFlow(false)
    val isConsolidating = _isConsolidating.asStateFlow()

    private val _isArchivingL1 = MutableStateFlow(false)
    val isArchivingL1 = _isArchivingL1.asStateFlow()

    private val _embeddingProgress = MutableStateFlow<EmbeddingProgress?>(null)
    val embeddingProgress = _embeddingProgress.asStateFlow()

    private var consolidationJob: Job? = null

    fun runManualConsolidation(
        consolidateEpisodes: Boolean = true,
        updateMaster: Boolean = true
    ) {
        if (updateMaster && !consolidateEpisodes) {
            val request =
                androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>()
                    .setInputData(
                        androidx.work.workDataOf(
                            "ASSISTANT_ID" to assistantId.toString(),
                            "FORCE_MASTER" to true,
                            "IS_MANUAL" to true
                        )
                    )
                    .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
            setSnackbarMessage(context.getString(R.string.master_memory_update_started))
            return
        }

        if (_isConsolidating.value) return
        consolidationJob = viewModelScope.launch {
            _isConsolidating.value = true
            try {
                val currentSettings = settings.value
                val currentAssistant = assistant.value
                val conversations = conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()

                val modelId = currentAssistant.memoryModelId ?: currentSettings.memoryModelId
                val model = currentSettings.findModelById(modelId) ?: error("No model found")
                val providerSetting = model.findProvider(currentSettings.providers) ?: error("No provider found")
                val handler = providerManager.getProviderByType(providerSetting)

                var episodicSuccessCount = 0
                if (consolidateEpisodes) {
                    val existingEpisodes = chatEpisodeDAO.getEpisodesOfAssistant(currentAssistant.id.toString())
                    val episodeMap = existingEpisodes.associateBy { it.conversationId }

                    val toConsolidateEpisodes = conversations.filter { conv ->
                        val existing = episodeMap[conv.id.toString()]
                        val messageCount = conv.currentMessages.size
                        if (existing != null) {
                            messageCount - existing.significance >= 4
                        } else {
                            messageCount >= 4
                        }
                    }

                    for (conv in toConsolidateEpisodes) {
                        yield()
                        val existingEpisode = episodeMap[conv.id.toString()]
                        val skipCount = existingEpisode?.significance ?: 0
                        val newMessages = conv.currentMessages.drop(skipCount)

                        val summary = if (newMessages.isEmpty() && existingEpisode != null) {
                            existingEpisode.content
                        } else {
                            generateConversationSummary(
                                handler = handler,
                                providerSetting = providerSetting,
                                model = model,
                                assistantName = currentAssistant.name,
                                previousSummary = existingEpisode?.content,
                                messages = newMessages,
                                temporarySummaries = conv.temporarySummaries
                            )
                        }

                        if (summary.isNotBlank()) {
                            val episode = ChatEpisodeEntity(
                                id = existingEpisode?.id ?: 0,
                                assistantId = currentAssistant.id.toString(),
                                conversationId = conv.id.toString(),
                                content = summary,
                                startTime = conv.createAt.toEpochMilli(),
                                endTime = conv.updateAt.toEpochMilli(),
                                significance = conv.currentMessages.size,
                                lastAccessedAt = System.currentTimeMillis()
                            )
                            memoryRepository.saveEpisode(episode)
                            conversationRepository.markAsConsolidated(conv.id)
                            episodicSuccessCount++
                        }
                    }
                }

                var updatedMasterContent: String? = null
                if (updateMaster && currentAssistant.enableMasterMemory) {
                    yield()
                    val contextParts = mutableListOf<String>()
                    for (conv in conversations.filter { it.currentMessages.size >= 2 }) {
                        val summary = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())?.content
                        if (!summary.isNullOrBlank()) {
                            contextParts.add("Conversation Summary: $summary")
                        } else {
                            contextParts.add(
                                "Recent Messages:\n${
                                    conv.currentMessages.takeLast(20)
                                        .joinToString("\n") { "${it.role}: ${it.toContentText().take(300)}" }
                                }"
                            )
                        }
                    }
                    val recentContext = contextParts.joinToString("\n\n---\n\n")
                    if (recentContext.isNotBlank()) {
                        updatedMasterContent = updateMasterMemory(
                            handler, providerSetting, model,
                            currentAssistant.name,
                            currentAssistant.masterMemoryContent,
                            recentContext,
                            DEFAULT_MASTER_MEMORY_PROMPT
                        )
                    }
                }

                val now = System.currentTimeMillis()
                val resultDesc = buildString {
                    if (episodicSuccessCount > 0) append("Consolidated $episodicSuccessCount episodes. ")
                    if (updatedMasterContent != null) append("Master Memory updated. ")
                    if (length == 0) append("No new items to consolidate.")
                }.trim()

                val updatedSettings = currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == currentAssistant.id) {
                            it.copy(
                                lastConsolidationTime = if (episodicSuccessCount > 0 || updatedMasterContent != null) now else it.lastConsolidationTime,
                                lastConsolidationResult = resultDesc,
                                masterMemoryContent = updatedMasterContent ?: it.masterMemoryContent,
                                lastMasterMemoryUpdate = if (updatedMasterContent != null) now else it.lastMasterMemoryUpdate
                            )
                        } else it
                    }
                )
                settingsStore.update(updatedSettings)
                setSnackbarMessage(resultDesc)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Consolidation failed", e)
                    setSnackbarMessage("Error: ${e.message}")

                    // 将错误也持久化到 lastConsolidationResult，以便 UI 展示
                    val currentSettings = settings.value
                    val currentAssistant = assistant.value
                    val updatedSettings = currentSettings.copy(
                        assistants = currentSettings.assistants.map {
                            if (it.id == currentAssistant.id) {
                                it.copy(
                                    lastConsolidationResult = "Error: ${e.message ?: "Unknown error"}"
                                )
                            } else it
                        }
                    )
                    settingsStore.update(updatedSettings)
                }
            } finally {
                _isConsolidating.value = false
                consolidationJob = null
            }
        }
    }

    fun cancelConsolidation() {
        consolidationJob?.cancel()
    }

    fun performManualL1Archive() {
        if (_isArchivingL1.value) return
        viewModelScope.launch {
            _isArchivingL1.value = true
            try {
                val currentAssistant = assistant.value
                val currentSettings = settings.value
                val lastConvId = currentAssistant.lastConversationId
                if (lastConvId.isNullOrBlank()) {
                    setSnackbarMessage(context.getString(R.string.manual_archive_l1_no_messages))
                    return@launch
                }

                val conversation = conversationRepository.getConversationById(Uuid.parse(lastConvId))
                if (conversation == null) {
                    setSnackbarMessage(context.getString(R.string.manual_archive_l1_no_messages))
                    return@launch
                }

                val messages = conversation.currentMessages
                val startIdx = if (conversation.contextSummaryUpToIndex >= 0) (conversation.contextSummaryUpToIndex + 1) else 0
                val totalNewMessages = messages.size - startIdx

                if (totalNewMessages < 2) {
                    setSnackbarMessage(context.getString(R.string.manual_archive_l1_no_messages))
                    return@launch
                }

                val modelId = currentAssistant.summarizerModelId ?: currentSettings.summarizerModelId
                val model = currentSettings.findModelById(modelId)
                if (model == null) {
                    setSnackbarMessage(context.getString(R.string.manual_archive_l1_no_model))
                    return@launch
                }
                val providerSetting = model.findProvider(currentSettings.providers) ?: run {
                    setSnackbarMessage(context.getString(R.string.manual_archive_l1_no_model))
                    return@launch
                }
                val handler = providerManager.getProviderByType(providerSetting) as? Provider<ProviderSetting> ?: run {
                    setSnackbarMessage(context.getString(R.string.manual_archive_l1_no_model))
                    return@launch
                }

                val wechatMode = settings.value.getEffectiveDisplaySetting(currentAssistant).wechatMode
                val baseThreshold = currentAssistant.detailMemoryThreshold.coerceAtLeast(2)
                val threshold = if (wechatMode) baseThreshold * 2 else baseThreshold

                var archiveCount = 0
                var currentStart = startIdx

                while (currentStart < messages.size - 1) {
                    val currentEnd = (currentStart + threshold - 1).coerceAtMost(messages.size - 1)
                    if (currentEnd - currentStart < 1) break

                    val toSummarize = messages.subList(currentStart, currentEnd + 1)
                    val text = toSummarize.joinToString("\n") {
                        "${it.role}: ${it.toContentText().take(500)}"
                    }

                    val locale = Locale.getDefault().displayName
                    val prompt = DEFAULT_TEMP_SUMMARY_PROMPT
                        .replace("{{new_messages}}", text)
                        .replace("{{locale}}", locale)
                        .replace("{{char}}", currentAssistant.name)

                    // Correctly handle timeout vs exception to avoid misleading "timeout" message
                    val aiResponse = try {
                        val response = withTimeoutOrNull(15000) {
                            handler.generateText(
                                providerSetting,
                                listOf(UIMessage.user(prompt)),
                                TextGenerationParams(model = model, temperature = 0.3f, topP = 1.0f, thinkingBudget = 0)
                            ).choices.firstOrNull()?.message?.toContentText()
                        }
                        if (response == null) {
                            setSnackbarMessage(context.getString(R.string.manual_archive_l1_timeout))
                            null
                        } else if (response.isBlank()) {
                            Log.w(TAG, "AI returned blank response for L1 archive")
                            null
                        } else {
                            response
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "L1 Summarization error", e)
                        setSnackbarMessage("Error: ${e.localizedMessage ?: e.message ?: "Unknown error"}")
                        null
                    }

                    if (aiResponse == null) {
                        break
                    }

                    val backgroundRegex = Regex("""\[(?:Background|背景)\][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)
                    val keywordsRegex = Regex("""\[(?:Keywords|关键词)\][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)

                    val backgroundMatch = backgroundRegex.find(aiResponse)?.groupValues?.get(1)?.trim()
                    val keywordsMatch = keywordsRegex.find(aiResponse)?.groupValues?.get(1)?.trim()

                    val finalBackground = backgroundMatch ?: aiResponse.lines().firstOrNull { it.isNotBlank() && !it.startsWith("[") } ?: aiResponse
                    val aiKeywords = keywordsMatch ?: ""
                    val localKeywords = KeywordExtractor.extract(finalBackground)
                    val mergedKeywords = (aiKeywords.split(Regex("[,，、；;]")) + localKeywords.split(",")).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().joinToString(",")

                    val fullContextualContent = "[Background]: $finalBackground\n[Original Text]:\n$text"
                    val embeddingResult = try {
                        embeddingService.embedWithModelId(fullContextualContent, currentAssistant.id.toString())
                    } catch (e: Exception) { null }

                    val segment = ChatSegmentEntity(
                        assistantId = currentAssistant.id.toString(),
                        conversationId = lastConvId,
                        content = finalBackground,
                        keywords = mergedKeywords,
                        startMessageIndex = currentStart,
                        endMessageIndex = currentEnd,
                        embedding = embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) },
                        embeddingModelId = embeddingResult?.modelId
                    )
                    memoryRepository.saveSegment(segment)
                    archiveCount++
                    currentStart = currentEnd + 1

                    val updatedConv = conversation.copy(contextSummaryUpToIndex = currentEnd, lastRefreshTime = System.currentTimeMillis())
                    conversationRepository.updateConversation(updatedConv)
                }

                if (archiveCount > 0) {
                    setSnackbarMessage(context.getString(R.string.manual_archive_l1_success, archiveCount))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual L1 archive failed", e)
                setSnackbarMessage("Error: ${e.localizedMessage ?: e.message ?: "Unknown error"}")
            } finally {
                _isArchivingL1.value = false
            }
        }
    }

    fun optimizeMemories() {
        viewModelScope.launch {
            if (_isOptimizing.value) return@launch
            _isOptimizing.value = true
            try {
                val currentSettings = settings.value
                val currentAssistant = assistant.value
                val allMemories = memories.value

                val coreMemories = allMemories.filter { it.id > 0 }

                if (coreMemories.isEmpty()) {
                    setSnackbarMessage(context.getString(R.string.memory_optimize_no_change))
                    return@launch
                }

                val modelId = currentAssistant.memoryModelId ?: currentSettings.memoryModelId
                val model = currentSettings.findModelById(modelId) ?: error("No model")
                val providerSetting = model.findProvider(currentSettings.providers) ?: error("No provider")
                val handler = providerManager.getProviderByType(providerSetting)

                var totalUpdated = 0
                var totalDeleted = 0
                var totalAdded = 0

                if (coreMemories.isNotEmpty()) {
                    val coreGroups = findSimilarGroups(coreMemories, true)
                    for (group in coreGroups) {
                        val result = processOptimizationGroup(
                            handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>,
                            providerSetting,
                            model,
                            group,
                            null
                        )
                        totalUpdated += result.updated
                        totalDeleted += result.deleted
                        totalAdded += result.added
                    }
                }

                setSnackbarMessage(
                    context.getString(
                        R.string.memory_optimize_success,
                        totalUpdated,
                        totalDeleted,
                        totalAdded
                    )
                )

                _embeddingProgress.value = EmbeddingProgress(0, 1, true)
                memoryRepository.regenerateEmbeddings(assistantId.toString()) { current, total ->
                    _embeddingProgress.value = EmbeddingProgress(current, total, true)
                }
                _embeddingProgress.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Optimization failed", e)
                setSnackbarMessage("Error: ${e.message}")
            } finally {
                _isOptimizing.value = false
                _embeddingProgress.value = null
            }
        }
    }

    private suspend fun generateConversationSummary(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        assistantName: String,
        previousSummary: String?,
        messages: List<UIMessage>,
        temporarySummaries: List<String> = emptyList()
    ): String {
        val messagesText = messages.joinToString("\n") { "${it.role}: ${it.toContentText().take(1000)}" }
        val detailText = if (temporarySummaries.isNotEmpty()) {
            "\n### Tactical Details:\n" + temporarySummaries.joinToString("\n") { "- $it" }
        } else ""

        val prompt =
            DEFAULT_FULL_SUMMARY_PROMPT
                .applyPlaceholders(
                    "previous_summary" to previousSummary + detailText,
                    "new_messages" to messagesText,
                    "locale" to Locale.getDefault().displayName,
                    "char" to assistantName
                )

        val h = handler as Provider<ProviderSetting>
        val resp = h.generateText(
            providerSetting,
            listOf(UIMessage.user(prompt)),
            TextGenerationParams(model = model, temperature = 0.3f, topP = 1.0f, thinkingBudget = 0)
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }

    private suspend fun generateSegmentSummary(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        assistantName: String,
        messages: List<UIMessage>
    ): String {
        // Implement segment summarization if needed
        return ""
    }

    private suspend fun updateMasterMemory(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        assistantName: String,
        existingArchive: String,
        newContext: String,
        systemPrompt: String
    ): String {
        val locale = Locale.getDefault().displayName
        val finalSystemPrompt = systemPrompt.applyPlaceholders(
            "char" to assistantName,
            "locale" to locale
        )

        val inputPrompt =
            "Current Date: ${LocalDate.now()}\n\n# Existing Memory Archive:\n${existingArchive.ifBlank { "(Empty)" }}\n\n# New Conversation Context:\n$newContext\n\nPlease provide the fully updated Memory Archive incorporating all relevant new information."
        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(
            providerSetting,
            listOf(UIMessage.system(finalSystemPrompt), UIMessage.user(inputPrompt)),
            TextGenerationParams(model = model, temperature = 0.2f, topP = 1.0f)
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }

    private suspend fun findSimilarGroups(
        memList: List<AssistantMemory>,
        isCore: Boolean
    ): List<List<AssistantMemory>> {
        val groups = mutableListOf<List<AssistantMemory>>()
        val processedIds = mutableSetOf<Int>()

        for (memory in memList) {
            if (processedIds.contains(memory.id)) continue
            val similar = memoryRepository.retrieveRelevantMemoriesWithScores(
                assistantId = assistantId.toString(),
                query = memory.content,
                limit = assistant.value.ragLimit,
                similarityThreshold = 0.6f,
                includeCore = isCore,
                includeEpisodes = !isCore
            ).map { it.first }.filter { m ->
                val idMatch = if (isCore) m.id > 0 else m.id < 0
                idMatch && !processedIds.contains(m.id)
            }

            if (similar.size > 1) {
                groups.add(similar)
                processedIds.addAll(similar.map { it.id })
            }
        }
        return groups
    }

    private data class OptimizationResult(val updated: Int, val deleted: Int, val added: Int)

    private suspend fun processOptimizationGroup(
        handler: me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        group: List<AssistantMemory>,
        contextEpisodic: String?
    ): OptimizationResult {
        var updated = 0
        var deleted = 0
        var added = 0

        val groupIds = group.map { it.id }
        val groupText = group.joinToString("\n") { "(ID: ${it.id}): ${it.content}" }

        val prompt = DEFAULT_MEMORY_OPTIMIZATION_PROMPT
            .applyPlaceholders(
                "groupText" to groupText,
                "locale" to Locale.getDefault().displayName
            )

        try {
            val response =
                handler.generateText(providerSetting, listOf(UIMessage.user(prompt)), TextGenerationParams(model, 0.1f))
            val resultText = response.choices.firstOrNull()?.message?.toContentText() ?: ""

            var jsonString = if (resultText.contains("[") && resultText.contains("]")) {
                resultText.substring(resultText.indexOf("["), resultText.lastIndexOf("]") + 1)
            } else resultText

            jsonString = jsonString
                .replace(Regex("""("id":\s*)(-?\d+)\""""), "$1$2")
                .replace(Regex("""("id":\s*)"(-?\d+)""""), "$1$2")

            val json = Json { ignoreUnknownKeys = true; isLenient = true }

            val root = try {
                json.parseToJsonElement(jsonString)
            } catch (e: Exception) {
                return OptimizationResult(0, 0, 0)
            }

            if (root !is JsonArray) {
                return OptimizationResult(0, 0, 0)
            }

            root.forEach { element ->
                if (element !is JsonObject) return@forEach
                val op = element["op"]?.jsonPrimitive?.contentOrNull ?: ""
                val id = element["id"]?.jsonPrimitive?.intOrNull ?: element["id"]?.jsonPrimitive?.intOrNull
                val contentElement = element["content"]
                val contentString = when {
                    contentElement == null || contentElement is JsonNull -> null
                    contentElement is JsonPrimitive -> contentElement.contentOrNull
                    contentElement is JsonObject -> {
                        contentElement["content"]?.jsonPrimitive?.contentOrNull
                            ?: contentElement["Content"]?.jsonPrimitive?.contentOrNull
                            ?: contentElement.toString()
                    }

                    else -> contentElement.toString()
                }

                when (op) {
                    "update" -> if (id != null) {
                        if (id > 0) {
                            memoryRepository.updateContent(id, contentString ?: "")
                        } else {
                            // Map negative ID back to positive for Segment or Episode
                            val positiveId = kotlin.math.abs(id)
                            // How to know if it was a segment or episode from the AI?
                            // In this VM, we only display Segments as negative IDs now.
                            memoryRepository.updateSegmentContent(positiveId, contentString ?: "")
                        }
                        updated++
                    }

                    "delete" -> if (id != null) {
                        if (groupIds.contains(id)) {
                            deleteMemoryById(id)
                            deleted++
                            Log.i(TAG, "Executed [DELETE] on ID: $id")
                        } else {
                            Log.w(TAG, "!!! [PROTECTED] AI tried to delete ID $id NOT in group. Bypassing.")
                        }
                    }

                    "add" -> {
                        memoryRepository.addMemory(assistantId.toString(), contentString ?: "")
                        added++
                        Log.i(TAG, "Executed [ADD] new memory")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Group optimization failed", e)
        }

        return OptimizationResult(updated, deleted, added)
    }

    private suspend fun deleteMemoryById(id: Int) {
        if (id > 0) {
            memoryRepository.deleteMemory(id)
        } else {
            val segmentId = kotlin.math.abs(id)
            val segment = memoryRepository.getSegmentById(segmentId)
            if (segment != null) {
                val convId = segment.conversationId
                val startIndex = segment.startMessageIndex
                val endIndex = segment.endMessageIndex
                // 2. 执行物理删除
                memoryRepository.deleteSegment(segmentId)

                // 3. 更新会话的水位线，回退到该片段开始之前
                if (!convId.isNullOrBlank()) {
                    val conversation = conversationRepository.getConversationById(kotlin.uuid.Uuid.parse(convId))
                    if (conversation != null && conversation.contextSummaryUpToIndex == endIndex) {
                        val newIndex = (startIndex - 1).coerceAtLeast(-1)
                        val updatedConv = conversation.copy(contextSummaryUpToIndex = newIndex)
                        conversationRepository.updateConversation(updatedConv)
                        Log.i(TAG, "最新L1 片段已删除，会话 $convId 进度回退至: $newIndex")
                    }
                    else {
                        Log.i(TAG, "删除非末尾 L1 片段，水位线保持不变")
                    }
                }
            } else {
                memoryRepository.deleteSegment(segmentId)
            }
        }
    }

    val providers: StateFlow<List<me.rerere.ai.provider.ProviderSetting>> =
        settingsStore.settingsFlow.map { it.providers }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value

            // 强制互斥逻辑：如果当前被设为主智能体，将其余所有智能体设为非主智能体
            val updatedAssistants = if (assistant.isMain) {
                currentSettings.assistants.map {
                    if (it.id == assistant.id) assistant else it.copy(isMain = false)
                }
            } else {
                currentSettings.assistants.map {
                    if (it.id == assistant.id) assistant else it
                }
            }
            settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
        }
    }

    fun updateTags(tagIds: List<Uuid>, updatedTags: List<Tag>) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value;
            val currentAssistant =
                assistant.value; settingsStore.update(currentSettings.copy(assistants = currentSettings.assistants.map {
            if (it.id == currentAssistant.id) it.copy(
                tags = tagIds
            ) else it
        }, assistantTags = updatedTags))
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch { memoryRepository.addMemory(assistantId.toString(), memory.content) }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            if (memory.type == MemoryType.SEGMENT) {
                memoryRepository.updateSegmentContent(kotlin.math.abs(memory.id), memory.content)
            } else if (memory.id < 0) {
                // Fallback for negative IDs if type is not set correctly
                memoryRepository.updateSegmentContent(kotlin.math.abs(memory.id), memory.content)
            } else {
                memoryRepository.updateContent(memory.id, memory.content)
            }
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch { deleteMemoryById(memory.id) }
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()
    fun setSnackbarMessage(message: String?) {
        _snackbarMessage.value = message
    }

    fun clearSnackbarMessage() {
        _snackbarMessage.value = null
    }

    val needsEmbeddingRegeneration: StateFlow<Boolean> =
        memories.map { list -> list.any { !it.hasEmbedding } }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    private val _retrievalResults = MutableStateFlow<List<Pair<AssistantMemory, Float>>>(emptyList())
    val retrievalResults = _retrievalResults.asStateFlow()

    fun testRetrieval(query: String) {
        viewModelScope.launch {
            val currentAssistant = assistant.value
            val results = memoryRepository.retrieveRelevantMemoriesWithScores(
                assistantId = assistantId.toString(),
                query = query,
                limit = currentAssistant.ragLimit,
                similarityThreshold = currentAssistant.ragSimilarityThreshold,
                includeCore = currentAssistant.ragIncludeCore,
                includeEpisodes = currentAssistant.ragIncludeEpisodes,
                mode = currentAssistant.memoryRetrievalMode
            )
            _retrievalResults.value = results.map { it.first.copy(content = it.first.content) to it.second }
        }
    }

    fun regenerateEmbeddings() {
        viewModelScope.launch {
            _embeddingProgress.value = EmbeddingProgress(0, 1, true)
            memoryRepository.regenerateEmbeddings(assistantId.toString()) { c, t ->
                _embeddingProgress.value = EmbeddingProgress(c, t, true)
            }
            _embeddingProgress.value = null
        }
    }

    fun importConversation(title: String, messages: List<UIMessage>) {
        viewModelScope.launch {
            // 使用项目中已导入的 me.rerere.rikkahub.core.data.model.Conversation
            val conversation = Conversation(
                assistantId = assistantId,
                title = title,
                messageNodes = messages.map { it.toMessageNode() },
                createAt = Instant.now(),
                updateAt = Instant.now()
            )
            conversationRepository.insertConversation(conversation)
            setSnackbarMessage("会话导入成功")
        }
    }

    fun consolidateMemories(isFullScan: Boolean) {
        val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>()
            .setInputData(androidx.work.workDataOf("FULL_SCAN" to isFullScan, "ASSISTANT_ID" to assistantId.toString()))
            .build(); androidx.work.WorkManager.getInstance(context).enqueue(request)
    }

    fun estimateTokens(text: String): Int = text.length / 4
    val averageMemoryLength = memoryRepository.getAverageMemoryLength(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, 150)
    val estimatedMemoryCapacity =
        assistant.map { (it.maxTokenUsage / 50).coerceAtLeast(10) }.stateIn(viewModelScope, SharingStarted.Lazily, 10)
}


data class EmbeddingProgress(val current: Int, val total: Int, val isRunning: Boolean)
data class EpisodeStats(val totalEpisodes: Int, val averageSignificance: Double, val coreMemoryCount: Int)
