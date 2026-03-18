package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

@Serializable
data class MemoryOp(
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

    // --- 补全 AssistantPromptSubPage 需要的属性 ---
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
        val allMemories = coreMemories + episodic
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

    fun optimizeMemories() {
        viewModelScope.launch {
            if (_isOptimizing.value) return@launch
            _isOptimizing.value = true
            try {
                val currentSettings = settings.value
                val currentAssistant = assistant.value
                val coreMemories = memories.value.filter { it.id > 0 }

                if (coreMemories.isEmpty()) {
                    setSnackbarMessage(context.getString(R.string.memory_optimize_no_change))
                    return@launch
                }

                val groups = mutableListOf<List<AssistantMemory>>()
                val processedIds = mutableSetOf<Int>()

                for (memory in coreMemories) {
                    if (processedIds.contains(memory.id)) continue
                    val similar = memoryRepository.retrieveRelevantMemoriesWithScores(
                        assistantId = assistantId.toString(),
                        query = memory.content,
                        limit = 10,
                        similarityThreshold = 0.7f,
                        includeCore = true,
                        includeEpisodes = false
                    ).map { it.first }.filter { !processedIds.contains(it.id) }

                    if (similar.size > 1) {
                        groups.add(similar)
                        processedIds.addAll(similar.map { it.id })
                    }
                }

                if (groups.isEmpty()) {
                    setSnackbarMessage(context.getString(R.string.memory_optimize_no_change))
                    return@launch
                }

                val modelId = currentAssistant.summarizerModelId ?: currentAssistant.chatModelId ?: currentSettings.chatModelId
                val model = currentSettings.findModelById(modelId) ?: error("No model")
                val providerSetting = model.findProvider(currentSettings.providers) ?: error("No provider")
                val handler = providerManager.getProviderByType(providerSetting)

                var totalMerged = 0
                var totalConflicts = 0

                for (group in groups) {
                    val groupText = group.joinToString("\n") { "(ID: ${it.id}): ${it.content}" }
                    val contextEpisodic = memoryRepository.retrieveRelevantMemories(
                        assistantId = assistantId.toString(),
                        query = group.first().content,
                        limit = 3,
                        includeCore = false,
                        includeEpisodes = true
                    ).joinToString("\n") { "Context: ${it.content}" }

                    val prompt = """
                        You are a memory manager. Optimize this group of related memories:
                        $groupText
                        Relevant Context:
                        $contextEpisodic
                        Goals:
                        1. MERGE: Combine highly similar ones.
                        2. CONFLICT: Keep only latest/most accurate.
                        Return JSON array of operations:
                        [{"op": "update", "id": 1, "content": "..."}, {"op": "delete", "id": 2}, {"op": "add", "content": "..."}]
                    """.trimIndent()

                    val response = handler.generateText(providerSetting, listOf(UIMessage.user(prompt)), TextGenerationParams(model, 0.1f))
                    val resultText = response.choices.firstOrNull()?.message?.toContentText() ?: ""
                    val ops = Json { ignoreUnknownKeys = true }.decodeFromString<List<MemoryOp>>(resultText.substringAfter("[").substringBeforeLast("]").let { "[$it]" })

                    ops.forEach { op ->
                        when (op.op) {
                            "update" -> if (op.id != null && op.id > 0) { memoryRepository.updateContent(op.id, op.content ?: ""); totalConflicts++ }
                            "delete" -> if (op.id != null && op.id > 0) { memoryRepository.deleteMemory(op.id); totalMerged++ }
                            "add" -> memoryRepository.addMemory(assistantId.toString(), op.content ?: "")
                        }
                    }
                }
                setSnackbarMessage(context.getString(R.string.memory_optimize_success, totalMerged, totalConflicts))
            } catch (e: Exception) {
                Log.e(TAG, "Optimization failed", e)
                setSnackbarMessage("Error: ${e.message}")
            } finally {
                _isOptimizing.value = false
            }
        }
    }

    val providers: StateFlow<List<me.rerere.ai.provider.ProviderSetting>> = settingsStore.settingsFlow.map { it.providers }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    fun update(assistant: Assistant) { viewModelScope.launch { val currentSettings = settingsStore.settingsFlow.value; settingsStore.update(currentSettings.copy(assistants = currentSettings.assistants.map { if (it.id == assistant.id) assistant else it })) } }
    fun updateTags(tagIds: List<Uuid>, updatedTags: List<Tag>) { viewModelScope.launch { val currentSettings = settingsStore.settingsFlow.value; val currentAssistant = assistant.value; settingsStore.update(currentSettings.copy(assistants = currentSettings.assistants.map { if (it.id == currentAssistant.id) it.copy(tags = tagIds) else it }, assistantTags = updatedTags)) } }
    fun addMemory(memory: AssistantMemory) { viewModelScope.launch { memoryRepository.addMemory(assistantId.toString(), memory.content) } }
    fun updateMemory(memory: AssistantMemory) { viewModelScope.launch { if (memory.id < 0) memoryRepository.updateEpisodeContent(-memory.id, memory.content) else memoryRepository.updateContent(memory.id, memory.content) } }
    fun deleteMemory(memory: AssistantMemory) { viewModelScope.launch { if (memory.id > 0) memoryRepository.deleteMemory(memory.id) } }
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()
    fun setSnackbarMessage(message: String?) { _snackbarMessage.value = message }
    fun clearSnackbarMessage() { _snackbarMessage.value = null }
    private val _embeddingProgress = MutableStateFlow<EmbeddingProgress?>(null)
    val embeddingProgress = _embeddingProgress.asStateFlow()
    val needsEmbeddingRegeneration: StateFlow<Boolean> = memories.map { list -> list.any { !it.hasEmbedding } }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    private val _retrievalResults = MutableStateFlow<List<Pair<AssistantMemory, Float>>>(emptyList())
    val retrievalResults = _retrievalResults.asStateFlow()
    fun testRetrieval(query: String) { viewModelScope.launch { val results = memoryRepository.retrieveRelevantMemoriesWithScores(assistantId.toString(), query); _retrievalResults.value = results } }
    fun regenerateEmbeddings() { viewModelScope.launch { _embeddingProgress.value = EmbeddingProgress(0, 1, true); memoryRepository.regenerateEmbeddings(assistantId.toString()) { c, t -> _embeddingProgress.value = EmbeddingProgress(c, t, true) }; _embeddingProgress.value = null } }
    fun consolidateMemories(isFullScan: Boolean) { val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>().setInputData(androidx.work.workDataOf("FULL_SCAN" to isFullScan, "ASSISTANT_ID" to assistantId.toString())).build(); androidx.work.WorkManager.getInstance(context).enqueue(request) }
    fun estimateTokens(text: String): Int = text.length / 4
    val averageMemoryLength = memoryRepository.getAverageMemoryLength(assistantId.toString()).stateIn(viewModelScope, SharingStarted.Lazily, 150)
    val estimatedMemoryCapacity = assistant.map { (it.maxTokenUsage / 50).coerceAtLeast(10) }.stateIn(viewModelScope, SharingStarted.Lazily, 10)
}

data class EmbeddingProgress(val current: Int, val total: Int, val isRunning: Boolean)
data class EpisodeStats(val totalEpisodes: Int, val averageSignificance: Double, val coreMemoryCount: Int)
