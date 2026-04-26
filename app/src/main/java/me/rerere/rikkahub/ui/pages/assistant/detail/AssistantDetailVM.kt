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
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantMemory
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.model.Tag
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_OPTIMIZATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_EPISODIC_CONSOLIDATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MASTER_MEMORY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException

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
    private val agentTaskRepository: AgentTaskRepository
) : ViewModel() {
    private val assistantId = try { Uuid.parse(id) } catch (e: Exception) { Uuid.NIL }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = Assistant()
        )

    // Agent Tasks logic
    val agentTasks: StateFlow<List<AgentTaskEntity>> = agentTaskRepository
        .getTasksByAssistant(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun deleteAgentTask(task: AgentTaskEntity) {
        viewModelScope.launch {
            agentTaskRepository.deleteTask(task)
        }
    }

    val mcpServerConfigs: StateFlow<List<McpServerConfig>> = settingsStore.settingsFlow
        .map { it.mcpServers }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val tags: StateFlow<List<Tag>> = settingsStore.settingsFlow
        .map { it.assistantTags }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hasMemories: StateFlow<Boolean> = memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val hasLorebooks: StateFlow<Boolean> = assistant.map { it.enabledLorebookIds.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val episodes: StateFlow<List<ChatEpisodeEntity>> = chatEpisodeDAO.getEpisodesOfAssistantFlow(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val episodeStats: StateFlow<EpisodeStats> = combine(
        memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString()),
        episodes
    ) { core, episodic ->
        EpisodeStats(
            totalEpisodes = episodic.size,
            averageSignificance = episodic.map { it.significance ?: 0 }.average(),
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
        episodes,
        _memorySearchQuery
    ) { coreMemories, episodesList, query ->
        val core = coreMemories.map { it.copy(content = it.content) }
        val episodic = episodesList.map {
            AssistantMemory(
                id = -it.id,
                content = it.content,
                type = 1,
                hasEmbedding = it.embedding != null,
                embeddingModelId = it.embeddingModelId,
                timestamp = it.startTime,
                significance = it.significance
            )
        }
        val allMemories = core + episodic
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

    private val _isOptimizing = MutableStateFlow(false)
    val isOptimizing = _isOptimizing.asStateFlow()

    private val _isConsolidating = MutableStateFlow(false)
    val isConsolidating = _isConsolidating.asStateFlow()

    private val _embeddingProgress = MutableStateFlow<EmbeddingProgress?>(null)
    val embeddingProgress = _embeddingProgress.asStateFlow()

    private var consolidationJob: Job? = null

    fun runManualConsolidation(
        consolidateEpisodes: Boolean = true,
        updateMaster: Boolean = true
    ) {
        // 异步处理“更新记忆档案”（L3）
        if (updateMaster && !consolidateEpisodes) {
            val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>()
                .setInputData(androidx.work.workDataOf(
                    "ASSISTANT_ID" to assistantId.toString(),
                    "FORCE_MASTER" to true,
                    "IS_MANUAL" to true
                ))
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
            setSnackbarMessage(context.getString(R.string.master_memory_update_started))
            return
        }

        // L2 情节记忆整合逻辑
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
                            chatEpisodeDAO.insertEpisode(episode)
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
                            contextParts.add("Recent Messages:\n${conv.currentMessages.takeLast(20).joinToString("\n") { "${it.role}: ${it.toContentText().take(300)}" }}")
                        }
                    }
                    val recentContext = contextParts.joinToString("\n\n---\n\n")
                    if (recentContext.isNotBlank()) {
                        updatedMasterContent = updateMasterMemory(
                            handler, providerSetting, model,
                            currentAssistant.masterMemoryContent,
                            recentContext,
                            currentAssistant.masterMemoryPrompt.ifBlank { DEFAULT_MASTER_MEMORY_PROMPT }
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
                                lastConsolidationTime = now,
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
                        val result = processOptimizationGroup(handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>, providerSetting, model, group, null)
                        totalUpdated += result.updated
                        totalDeleted += result.deleted
                        totalAdded += result.added
                    }
                }

                setSnackbarMessage(context.getString(R.string.memory_optimize_success, totalUpdated, totalDeleted, totalAdded))

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

        val prompt = if (previousSummary != null) {
            DEFAULT_FULL_SUMMARY_PROMPT
                .replace("{{previous_summary}}", previousSummary + detailText)
                .replace("{{new_messages}}", messagesText)
                .replace("{{locale}}", Locale.getDefault().displayName)
                .replace("{{char}}", assistantName)
        } else {
            DEFAULT_EPISODIC_CONSOLIDATION_PROMPT
                .replace("{{text}}", detailText + "\n" + messagesText)
                .replace("{{locale}}", Locale.getDefault().displayName)
                .replace("{{char}}", assistantName)
        }

        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(providerSetting, listOf(UIMessage.user(prompt)), TextGenerationParams(model = model, temperature = 0.3f, topP = 1.0f))
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }

    private suspend fun updateMasterMemory(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        existingArchive: String,
        newContext: String,
        systemPrompt: String
    ): String {
        val inputPrompt = "Current Date: ${LocalDate.now()}\n\n# Existing Memory Archive:\n${existingArchive.ifBlank { "(Empty)" }}\n\n# New Conversation Context:\n$newContext\n\nPlease provide the fully updated Memory Archive incorporating all relevant new information."
        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(providerSetting, listOf(UIMessage.system(systemPrompt), UIMessage.user(inputPrompt)), TextGenerationParams(model = model, temperature = 0.2f, topP = 1.0f))
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
        Log.i(TAG, ">>> [Memory Optimization] Sending Similarity Group (IDs: $groupIds) to AI")

        val prompt = DEFAULT_MEMORY_OPTIMIZATION_PROMPT
            .replace("{{groupText}}", groupText)
            .replace("{{locale}}", Locale.getDefault().displayName)

        try {
            val response = handler.generateText(providerSetting, listOf(UIMessage.user(prompt)), TextGenerationParams(model, 0.1f))
            val resultText = response.choices.firstOrNull()?.message?.toContentText() ?: ""
            Log.i(TAG, "<<< [Memory Optimization] AI Raw Response:\n$resultText")

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
                Log.w(TAG, "!!! [Memory Optimization] 不能识别 AI 返回内容的格式 (可能存在语法错误)。跳过此组优化。错误: ${e.message}")
                return OptimizationResult(0, 0, 0)
            }

            if (root !is JsonArray) {
                Log.w(TAG, "!!! [Memory Optimization] AI 返回的不是有效的 JSON 数组。跳过。")
                return OptimizationResult(0, 0, 0)
            }

            root.forEach { element ->
                if (element !is JsonObject) return@forEach
                val op = element["op"]?.jsonPrimitive?.contentOrNull ?: ""
                val id = element["id"]?.jsonPrimitive?.intOrNull
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
                            memoryRepository.updateEpisodeContent(-id, contentString ?: "")
                        }
                        updated++
                        Log.i(TAG, "Executed [UPDATE] on ID: $id")
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
            chatEpisodeDAO.deleteEpisode(-id)
        }
    }

    val providers: StateFlow<List<me.rerere.ai.provider.ProviderSetting>> = settingsStore.settingsFlow.map { it.providers }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    fun updateTags(tagIds: List<Uuid>, updatedTags: List<Tag>) { viewModelScope.launch { val currentSettings = settingsStore.settingsFlow.value; val currentAssistant = assistant.value; settingsStore.update(currentSettings.copy(assistants = currentSettings.assistants.map { if (it.id == currentAssistant.id) it.copy(tags = tagIds) else it }, assistantTags = updatedTags)) } }
    fun addMemory(memory: AssistantMemory) { viewModelScope.launch { memoryRepository.addMemory(assistantId.toString(), memory.content) } }
    fun updateMemory(memory: AssistantMemory) { viewModelScope.launch { if (memory.id < 0) memoryRepository.updateEpisodeContent(-memory.id, memory.content) else memoryRepository.updateContent(memory.id, memory.content) } }
    fun deleteMemory(memory: AssistantMemory) { viewModelScope.launch { deleteMemoryById(memory.id) } }
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()
    fun setSnackbarMessage(message: String?) { _snackbarMessage.value = message }
    fun clearSnackbarMessage() { _snackbarMessage.value = null }
    val needsEmbeddingRegeneration: StateFlow<Boolean> = memories.map { list -> list.any { !it.hasEmbedding } }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    private val _retrievalResults = MutableStateFlow<List<Pair<AssistantMemory, Float>>>(emptyList())
    val retrievalResults = _retrievalResults.asStateFlow()
    fun testRetrieval(query: String) { viewModelScope.launch { val results = memoryRepository.retrieveRelevantMemoriesWithScores(assistantId.toString(), query); _retrievalResults.value = results.map { it.first.copy(content = it.first.content) to it.second } } }
    fun regenerateEmbeddings() { viewModelScope.launch { _embeddingProgress.value = EmbeddingProgress(0, 1, true); memoryRepository.regenerateEmbeddings(assistantId.toString()) { c, t -> _embeddingProgress.value = EmbeddingProgress(c, t, true) }; _embeddingProgress.value = null } }
    fun consolidateMemories(isFullScan: Boolean) { val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>().setInputData(androidx.work.workDataOf("FULL_SCAN" to isFullScan, "ASSISTANT_ID" to assistantId.toString())).build(); androidx.work.WorkManager.getInstance(context).enqueue(request) }
    fun estimateTokens(text: String): Int = text.length / 4
    val averageMemoryLength = memoryRepository.getAverageMemoryLength(assistantId.toString()).stateIn(viewModelScope, SharingStarted.Lazily, 150)
    val estimatedMemoryCapacity = assistant.map { (it.maxTokenUsage / 50).coerceAtLeast(10) }.stateIn(viewModelScope, SharingStarted.Lazily, 10)
}


data class EmbeddingProgress(val current: Int, val total: Int, val isRunning: Boolean)
data class EpisodeStats(val totalEpisodes: Int, val averageSignificance: Double, val coreMemoryCount: Int)
