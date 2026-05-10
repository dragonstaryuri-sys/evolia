package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole as CoreMessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UsedLorebookEntry
import me.rerere.ai.ui.UsedMemory
import me.rerere.ai.ui.UsedMode
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.truncate
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.VIRTUAL_WORLD_PROMPT
import me.rerere.rikkahub.data.ai.prompts.VIRTUAL_TRANSITION_TO_NORMAL
import me.rerere.rikkahub.data.ai.prompts.VIRTUAL_TRANSITION_TO_VIRTUAL
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantMemory
import me.rerere.rikkahub.core.data.model.ContextPriority
import me.rerere.rikkahub.core.data.model.InjectionPosition
import me.rerere.rikkahub.core.data.model.Lorebook
import me.rerere.rikkahub.core.data.model.LorebookActivationType
import me.rerere.rikkahub.core.data.model.LorebookEntry
import me.rerere.rikkahub.core.data.model.ModeAttachmentType
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.core.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.db.dao.ChatSegmentDAO
import me.rerere.rikkahub.data.ai.prompts.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Duration
import java.time.Instant
import java.time.DayOfWeek
import java.time.format.TextStyle


/**
 * Result of building messages, includes both the messages and info about activated context sources.
 */
data class BuildMessagesResult(
    val messages: List<UIMessage>,
    val activatedLorebookEntries: List<UsedLorebookEntry>,
    val usedModes: List<UsedMode> = emptyList(),
    val usedMemories: List<UsedMemory> = emptyList()
)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val TAG: String = "GenerationHandler",
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val embeddingService: EmbeddingService,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val appScope: AppScope,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        truncateIndex: Int = -1,
        maxSteps: Int = 256,
        enabledModeIds: Set<Uuid> = emptySet(),
        contextSummary: String? = null,
        temporarySummaries: List<String> = emptyList(),
        skipContextForResponse: Boolean = false,
        conversationId: Uuid? = null
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var currentMessages: List<UIMessage> = messages
        var searchCount = 0
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF) {
                    buildMemoryTools(
                        assistantId = assistant.id.toString(),
                        onCreation = { content ->
                            val relevant = memoryRepo.retrieveRelevantMemoriesWithScores(
                                assistantId = assistant.id.toString(),
                                query = content,
                                limit = 1,
                                similarityThreshold = 0.8f,
                                includeCore = true,
                                includeEpisodes = false,
                                mode = assistant.memoryRetrievalMode
                            )

                            val existing = relevant.firstOrNull()
                            if (existing != null) {
                                val score = existing.second
                                val memory = existing.first
                                if (score > 0.98f) {
                                    Log.i(TAG, "Near-identical memory (score: $score), skipping creation.")
                                    memory
                                } else {
                                    Log.i(TAG, "High-similarity memory (score: $score), updating existing entry.")
                                    memoryRepo.updateContent(memory.id, content)
                                }
                            } else {
                                memoryRepo.addMemory(assistant.id.toString(), content)
                            }
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)

                    add(
                        Tool(
                            name = "retrieve_memory_details",
                            description = "Retrieve high-resolution tactical details or specific segments for a given episodic memory ID using semantic search. Use this when the summary is too vague.",
                            parameters = {
                                InputSchema.Obj(
                                    properties = buildJsonObject {
                                        put("episode_id", buildJsonObject {
                                            put("type", "integer")
                                            put("description", "The ID of the episodic memory.")
                                        })
                                        put("query", buildJsonObject {
                                            put("type", "string")
                                            put("description", "The specific topic or question to search for within this memory's details.")
                                        })
                                    },
                                    required = listOf("episode_id", "query")
                                )
                            },
                            execute = { params ->
                                val id = kotlin.math.abs(params.jsonObject["episode_id"]?.jsonPrimitive?.intOrNull ?: 0)
                                val query = params.jsonObject["query"]?.jsonPrimitive?.contentOrNull ?: ""

                                val episode = memoryRepo.getEpisodeEntitiesOfAssistant(assistant.id.toString()).find { it.id == id }
                                val convId = episode?.conversationId ?: return@Tool buildJsonObject { put("error", JsonPrimitive("No detailed segments found.")) }

                                val resultSegments = memoryRepo.retrieveRelevantSegments(
                                    assistantId = assistant.id.toString(),
                                    conversationId = convId,
                                    query = query,
                                    limit = 2,
                                    mode = assistant.memoryRetrievalMode
                                )

                                if (resultSegments.isEmpty()) {
                                    return@Tool buildJsonObject { put("error", JsonPrimitive("No detailed segments found.")) }
                                }

                                buildJsonObject {
                                    put("episode_id", JsonPrimitive(id))
                                    put("query_used", JsonPrimitive(query))
                                    put("segments_found", JsonPrimitive(resultSegments.size))
                                    put("details", JsonPrimitive(resultSegments.joinToString("\n---\n") {
                                        "Segment ID [${it.id}]: ${it.content}"
                                    }))
                                }
                            }
                        )
                    )
                }
                addAll(tools)
            }

            generateInternal(
                assistant = assistant,
                settings = settings,
                messages = currentMessages,
                onUpdateMessages = {updatedFromChunk ->
                    currentMessages = updatedFromChunk.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant
                    )
                    emit(
                        GenerationChunk.Messages(
                            currentMessages.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant
                            )
                        )
                    )
                },
                transformers = inputTransformers,
                model = model,
                providerImpl = providerImpl,
                provider = provider,
                tools = toolsInternal,
                memories = memories ?: emptyList(),
                truncateIndex = truncateIndex,
                stream = assistant.streamOutput,
                enabledModeIds = enabledModeIds,
                contextSummary = contextSummary,
                temporarySummaries = temporarySummaries,
                includeSkipContextMessages = skipContextForResponse,
                conversationId = conversationId
            )


            currentMessages = currentMessages.visualTransforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant
            ).onGenerationFinish(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant
            )
            emit(GenerationChunk.Messages(currentMessages))

            val toolCalls = currentMessages.last().getToolCalls()
            if (toolCalls.isEmpty()) {
                break
            }
            if (toolCalls.any { it.toolName == "search_web" }) {
                searchCount++
                Log.d(TAG, "generateText: current search count: $searchCount")
            }

            val results = arrayListOf<UIMessagePart.ToolResult>()
            toolCalls.forEach { toolCall ->
                runCatching {
                    if (toolCall.toolName == "search_web" && searchCount > 3) {
                        results += UIMessagePart.ToolResult(
                            toolName = toolCall.toolName,
                            toolCallId = toolCall.toolCallId,
                            content = buildJsonObject {
                                put("error", JsonPrimitive("已达到搜索次数上限（3次）。如果仍然没有找到相关信息，请直接告知用户在当前搜索中未找到匹配内容。"))
                            },
                            arguments = runCatching {
                                json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                            }.getOrElse { buildJsonObject {} },
                            metadata = toolCall.metadata
                        )
                        return@forEach
                    }

                    val tool = toolsInternal.find { tool -> tool.name == toolCall.toolName }
                        ?: error("Tool ${toolCall.toolName} not found")

                    val args = runCatching {
                        json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                    }.getOrElse {
                        Log.w(TAG, "Failed to parse tool arguments, attempting sanitization: ${it.message}")
                        val sanitized = sanitizeToolCallArguments(toolCall.arguments)
                        json.parseToJsonElement(sanitized)
                    }
                    Log.i(TAG, "generateText: executing tool ${tool.name} with args: $args")
                    val result = tool.execute(args)
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        content = result,
                        arguments = args,
                        metadata = toolCall.metadata
                    )
                }.onFailure {
                    it.printStackTrace()
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        metadata = toolCall.metadata,
                        content = buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive(buildString {
                                    append("[${it.javaClass.name}] ${it.message}")
                                    append("\n${it.stackTraceToString()}")
                                })
                            )
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments)
                        }.getOrElse { buildJsonObject {} }
                    )
                }
            }

            currentMessages = currentMessages + UIMessage(
                role = CoreMessageRole.TOOL,
                parts = results,
                skipContext = skipContextForResponse
            )

            emit(
                GenerationChunk.Messages(
                    currentMessages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    suspend fun buildMessages(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        model: Model,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        truncateIndex: Int,
        enabledModeIds: Set<Uuid> = emptySet(),
        contextSummary: String? = null,
        temporarySummaries: List<String> = emptyList(),
        includeSkipContextMessages: Boolean = false,
        conversationId: Uuid? = null
    ): BuildMessagesResult {
        fun estimateTokens(text: String) = text.length / 4
        fun estimateTokens(message: UIMessage) = estimateTokens(message.toText())

        val maxTokens = assistant.maxTokenUsage
        var currentTokens = 0

        fun getLorebookEntryActivationReason(entry: LorebookEntry, recentMessages: List<String>, queryEmbedding: List<Float>? = null): String? {
            if (!entry.enabled) return null
            return when (entry.activationType) {
                LorebookActivationType.ALWAYS -> context.getString(R.string.activation_always)
                LorebookActivationType.KEYWORDS -> {
                    val searchText = recentMessages.joinToString(" ")
                    val matchingKeyword = entry.keywords.firstOrNull { keyword ->
                        if (entry.useRegex) {
                            try {
                                val regex = if (entry.caseSensitive) Regex(keyword) else Regex(keyword, RegexOption.IGNORE_CASE)
                                regex.containsMatchIn(searchText)
                            } catch (e: Exception) { false }
                        } else {
                            if (entry.caseSensitive) searchText.contains(keyword) else searchText.contains(keyword, ignoreCase = true)
                        }
                    }
                    if (matchingKeyword != null) context.getString(R.string.context_source_keyword_match, matchingKeyword) else null
                }
                LorebookActivationType.RAG -> {
                    val entryEmbedding = entry.embedding
                    if (entryEmbedding.isNullOrEmpty()) {
                        Log.d(TAG, "RAG entry '${entry.name}' has no embedding, skipping")
                        null
                    } else if (queryEmbedding == null) {
                        Log.d(TAG, "No query embedding available for RAG matching")
                        null
                    } else {
                        val similarity = cosineSimilarity(entryEmbedding, queryEmbedding)
                        val threshold = 0.7f
                        val activated = similarity >= threshold
                        if (activated) {
                            val scoreStr = try {
                                "%.2f".format(similarity)
                            } catch (e: Exception) {
                                similarity.toString().take(4)
                            }
                            Log.d(TAG, "RAG entry '${entry.name}' activated with similarity $similarity")
                            context.getString(R.string.context_source_rag_match, scoreStr)
                        } else null
                    }
                }
            }
        }

        val recentMessagesForScan = messages.takeLast(10).map { it.toText() }
        val enabledModes = if (enabledModeIds.isNotEmpty()) settings.modes.filter { enabledModeIds.contains(it.id) } else settings.modes.filter { it.defaultEnabled }

        val usedModesList = enabledModes.mapIndexed { index, mode ->
            val reason = if (enabledModeIds.contains(mode.id)) {
                context.getString(R.string.context_source_activated_by_user)
            } else {
                context.getString(R.string.context_source_default_enabled)
            }
            UsedMode(
                modeId = mode.id.toString(),
                modeName = mode.name,
                modeIcon = mode.icon,
                priority = enabledModes.size - index,
                activationReason = reason
            )
        }

        val lorebooksForAssistant = settings.lorebooks.filter { it.enabled && assistant.enabledLorebookIds.contains(it.id) }
        val hasRagEntries = lorebooksForAssistant.any { lorebook -> lorebook.entries.any { it.activationType == LorebookActivationType.RAG && it.enabled } }

        val queryEmbedding: List<Float>? = if (hasRagEntries) {
            try {
                val queryText = recentMessagesForScan.takeLast(3).joinToString("\n")
                if (queryText.isNotBlank()) {
                    embeddingService.embed(queryText, assistant.id.toString())
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute query embedding for RAG", e)
                null
            }
        } else null

        data class ActivatedEntryWithLorebook(val lorebook: Lorebook, val entry: LorebookEntry, val entryIndex: Int, val reason: String)
        val activatedEntriesWithLorebook = lorebooksForAssistant
            .flatMap { lorebook ->
                lorebook.entries.mapIndexedNotNull { index, entry ->
                    val reason = getLorebookEntryActivationReason(entry, recentMessagesForScan, queryEmbedding)
                    if (reason != null) {
                        ActivatedEntryWithLorebook(lorebook, entry, index, reason)
                    } else null
                }
            }
        val activatedEntries = activatedEntriesWithLorebook.map { it.entry }

        val usedLorebookEntriesList = activatedEntriesWithLorebook.mapIndexed { priority, activated ->
            val coverJson = activated.lorebook.cover?.let { cover ->
                try {
                    json.encodeToString(Avatar.serializer(), cover)
                } catch (e: Exception) {
                    null
                }
            }
            UsedLorebookEntry(
                lorebookId = activated.lorebook.id.toString(),
                lorebookName = activated.lorebook.name,
                lorebookCover = coverJson,
                entryId = activated.entry.id.toString(),
                entryName = activated.entry.name,
                entryIndex = activated.entryIndex,
                priority = activatedEntriesWithLorebook.size - priority,
                activationReason = activated.reason
            )
        }

        val beforeSystemModes = enabledModes.filter { it.injectionPosition == InjectionPosition.BEFORE_SYSTEM }
        val afterSystemModes = enabledModes.filter { it.injectionPosition == InjectionPosition.AFTER_SYSTEM }
        val beforeSystemEntries = activatedEntries.filter { it.injectionPosition == InjectionPosition.BEFORE_SYSTEM }
        val afterSystemEntries = activatedEntries.filter { it.injectionPosition == InjectionPosition.AFTER_SYSTEM }

        val baseSystemPromptBuilder = StringBuilder()
        baseSystemPromptBuilder.append("The default user gender is female.\n")

        if (assistant.systemPrompt.isNotBlank()) {
            baseSystemPromptBuilder.append(
                assistant.systemPrompt.applyPlaceholders(
                    "char" to assistant.name,
                    "locale" to Locale.getDefault().displayName
                )
            )
            baseSystemPromptBuilder.appendLine("\n")
        }

        val styleExamples = if (assistant.isMain && assistant.isVirtualWorldMode) {
            assistant.virtualLanguageStyleExamples
        } else {
            assistant.languageStyleExamples
        }

        if (styleExamples.isNotEmpty()) {
            baseSystemPromptBuilder.append("## Language Style Examples\n")
            styleExamples.forEach { example ->
                baseSystemPromptBuilder.append("- $example\n")
            }
            baseSystemPromptBuilder.appendLine()
        }

        if (assistant.isVirtualWorldMode) {
             baseSystemPromptBuilder.append(VIRTUAL_WORLD_PROMPT)
             baseSystemPromptBuilder.appendLine("\n")
        }

        if (assistant.learningMode) {
            baseSystemPromptBuilder.append(
                settings.learningModePrompt.ifEmpty { DEFAULT_LEARNING_MODE_PROMPT }
                    .applyPlaceholders(
                        "char" to assistant.name,
                        "locale" to Locale.getDefault().displayName
                    )
            )
            baseSystemPromptBuilder.appendLine("\n")
        }

        beforeSystemModes.forEach { mode ->
            baseSystemPromptBuilder.append(mode.prompt)
            baseSystemPromptBuilder.appendLine()
        }
        beforeSystemEntries.forEach { entry ->
            baseSystemPromptBuilder.append(entry.prompt)
            baseSystemPromptBuilder.appendLine()
        }

        afterSystemModes.forEach { mode ->
            baseSystemPromptBuilder.appendLine()
            baseSystemPromptBuilder.append(mode.prompt)
        }
        afterSystemEntries.forEach { entry ->
            baseSystemPromptBuilder.appendLine()
            baseSystemPromptBuilder.append(entry.prompt)
        }

        tools.forEach { baseSystemPromptBuilder.appendLine().append(it.systemPrompt(model, messages)) }

        if (model.abilities.contains(ModelAbility.TOOL) && assistant.enableMemory) {
            baseSystemPromptBuilder.appendLine().append(
                """

                        ## Memory Tool
                        You are a stateless large language model; you **cannot store memories** internally. To remember information, you must use **memory tools**.
                        Memory tools allow you (the assistant) to store multiple pieces of information (records) to recall details across conversations.

                        ### Person Specification (IMPORTANT)
                        To ensure clarity and avoid identity confusion when retrieving memories in the future, please strictly adhere to the following specifications:
                        1. **"User"**: Refers to the person you are chatting with. Always use "User" to refer to them in all records.
                        2. **"I"**: Refers to yourself (the AI Assistant).

                        **Record Format Guidelines:**
                        - **STRICTLY PROHIBITED**: Using the first-person pronoun "I" or "me".
                        - **CORRECT EXAMPLE**: "User completed a PPT with me", "User plans to go to Shanghai tomorrow".
                        - **INCORRECT EXAMPLE**: "You completed a PPT with the user", "I am going to Shanghai tomorrow" (This will mislead you into thinking YOU are the one performing the action when you read this later).

                        ### Tool Usage
                        You can use the `create_memory`, `edit_memory`, `delete_memory` ,`retrieve_memory_details` tools to create, update, delete or \"deep dive\" into that specific conversation's  memories.
                        - If there is no relevant information in memory, call `create_memory` to create a new record.
                        - If a relevant record already exists, call `edit_memory` to update it.
                        - If a memory is outdated or no longer useful, call `delete_memory` to remove it.
                        - `retrieve_memory_details` for Episodic Memories: Call this when a summary is insufficient and you need to \"deep dive\" into that specific conversation's segments."
                        **Note:** You can only edit or delete **Core Memories** (which have an ID). Episodic Memories are read-only context.

                        **Do not store sensitive information.** Sensitive information includes: ethnicity, religious beliefs, sexual orientation, political views, sexual life, criminal records, etc.
                        During chats, act like a personal secretary and **proactively** record user-related information, including but not limited to:
                        - Name/Nickname
                        - Age/Gender/Hobbies
                        - Plans
                        - User's target
                    """.trimIndent()
            )
        }

        if (assistant.enableMasterMemory && assistant.masterMemoryContent.isNotBlank()) {
            baseSystemPromptBuilder.append("## Memory Archive\n")
            baseSystemPromptBuilder.append(assistant.masterMemoryContent)
            baseSystemPromptBuilder.append("\n\n")
        }

        // --- Semi-stable Section: Context Summary (L1) ---
        // 1. 全局总结始终尝试注入 (L1 Global Summary)
        if (!contextSummary.isNullOrBlank()) {
            baseSystemPromptBuilder.append("\n## Overall Conversation Summary\n").append(contextSummary).appendLine()
        }

        // 2. 片段总结受 enableContextRefresh 开关控制 (L1 Segments)
        if (assistant.enableContextRefresh) {
            val finalSegments = if (conversationId != null) {
                val limit = assistant.maxTemporarySummariesToInclude
                if (limit > 0) {
                    chatSegmentDAO.getSegmentsByConversation(conversationId.toString())
                        .takeLast(limit)
                        .map { it.content }
                } else emptyList()
            } else {
                temporarySummaries.takeLast(assistant.maxTemporarySummariesToInclude)
            }

            if (finalSegments.isNotEmpty()) {
                baseSystemPromptBuilder.append("\n## Recent Context Highlights\n")
                finalSegments.forEachIndexed { index, s ->
                    baseSystemPromptBuilder.append("${index + 1}. $s\n")
                }
            }
        }

        val baseSystemPrompt = baseSystemPromptBuilder.toString()
        currentTokens += estimateTokens(baseSystemPrompt)

        val contextCandidates = if (includeSkipContextMessages) {
            messages
        } else {
            messages.filter { !it.skipContext }
        }

        val chatHistoryCandidates = contextCandidates
            .truncate(truncateIndex)
            .let { truncated ->
                assistant.maxHistoryMessages?.let { limit ->
                    if (limit > 0) truncated.limitContext(limit) else truncated
                } ?: truncated
            }
            .reversed()

        val searchPrunedMessages = assistant.maxSearchResultsRetained?.let { maxSearches ->
            if (maxSearches > 0) {
                val searchResultIndices = chatHistoryCandidates.mapIndexedNotNull { index, msg ->
                    val hasSearchResult = msg.parts.any { part ->
                        part is UIMessagePart.ToolResult && part.toolName == "search_web"
                    }
                    if (hasSearchResult) index else null
                }

                val indicesToPrune = searchResultIndices.dropLast(maxSearches).toSet()
                if (indicesToPrune.isNotEmpty()) {
                    chatHistoryCandidates.mapIndexed { index, msg ->
                        if (index in indicesToPrune) {
                            msg.copy(parts = msg.parts.map { part ->
                                if (part is UIMessagePart.ToolResult && part.toolName == "search_web") {
                                    part.copy(content = buildJsonObject {
                                        put("note", JsonPrimitive("Earlier search results pruned to save context"))
                                    })
                                } else part
                            })
                        } else msg
                    }
                } else chatHistoryCandidates
            } else chatHistoryCandidates
        } ?: chatHistoryCandidates


        val effectiveMemoriesCandidates = if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF) {
            val recentChatMemories = if (assistant.enableRecentChatsReference) {
                val today = java.time.LocalDate.now()
                val zoneId = ZoneId.systemDefault()

                val lastConv = assistant.lastConversationId?.let { lastId ->
                    if (lastId != conversationId?.toString()) {
                        conversationRepo.getConversationById(lastId)
                    } else null
                }

                val isFromToday = lastConv?.let {
                    it.updateAt.atZone(zoneId).toLocalDate() == today
                } ?: false

                if (isFromToday && lastConv != null) {
                    val currentIsVirtual = assistant.isVirtualWorldMode
                    val lastIsVirtual = lastConv.isVirtual
                    val memoriesToInject = mutableListOf<AssistantMemory>()

                    if (messages.size <= 2) {
                        val episode = memoryRepo.getEpisodeByConversationId(lastConv.id.toString())
                        if (episode != null) {
                            Log.i(TAG, "Injecting context summary for new conversation.")
                            memoriesToInject.add(
                                AssistantMemory(
                                    id = 0,
                                    content = "Summary of your last conversation today: ${episode.content}",
                                    type = 2,
                                    timestamp = episode.endTime
                                )
                            )
                        }
                    }

                    if (currentIsVirtual != lastIsVirtual) {
                        val transitionPrompt = if (currentIsVirtual) {
                            VIRTUAL_TRANSITION_TO_VIRTUAL
                        } else {
                            VIRTUAL_TRANSITION_TO_NORMAL
                        }

                        val lastRawHistory = lastConv.currentMessages
                            .filter { !it.skipContext }
                            .takeLast(6)
                            .joinToString("\n") { msg ->
                                "${msg.role.name}: ${msg.toContentText()}"
                            }

                        if (lastRawHistory.isNotBlank()) {
                            Log.i(TAG, "Injecting mode transition raw messages.")
                            memoriesToInject.add(
                                AssistantMemory(
                                    id = 0,
                                    content = "$transitionPrompt\n\nRecent messages from previous mode:\n$lastRawHistory",
                                    type = 2,
                                    timestamp = lastConv.updateAt.toEpochMilli()
                                )
                            )
                        }
                    }
                    memoriesToInject
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            (memories + recentChatMemories).distinctBy { it.content }
        } else {
            emptyList()
        }

        val selectedMessages = mutableListOf<UIMessage>()
        val selectedMemories = mutableListOf<AssistantMemory>()
        val remainingTokens = maxTokens - currentTokens
        if (remainingTokens <= 0) {
            Log.w(TAG, "buildMessages: System prompt exceeds max tokens!")
        }

        val minChatHistory = 4.coerceAtMost(searchPrunedMessages.size)
        val minMemories = if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF) 1.coerceAtMost(effectiveMemoriesCandidates.size) else 0

        var usedTokens = 0

        searchPrunedMessages.take(minChatHistory).forEach {
            selectedMessages.add(it)
            usedTokens += estimateTokens(it)
        }

        effectiveMemoriesCandidates.take(minMemories).forEach {
            selectedMemories.add(it)
            usedTokens += estimateTokens(it.content)
        }

        var availableTokens = remainingTokens - usedTokens
        if (availableTokens > 0) {
            val remainingChatHistory = searchPrunedMessages.drop(minChatHistory)
            val remainingMemories = effectiveMemoriesCandidates.drop(minMemories)
            when (assistant.contextPriority) {
                ContextPriority.CHAT_HISTORY -> {
                    for (msg in remainingChatHistory) {
                        val cost = estimateTokens(msg)
                        if (availableTokens >= cost) {
                            selectedMessages.add(msg)
                            availableTokens -= cost
                        } else break
                    }
                    for (mem in remainingMemories) {
                        val cost = estimateTokens(mem.content)
                        if (availableTokens >= cost) {
                            selectedMemories.add(mem)
                            availableTokens -= cost
                        }
                    }
                }
                ContextPriority.MEMORIES -> {
                    for (mem in remainingMemories) {
                        val cost = estimateTokens(mem.content)
                        if (availableTokens >= cost) {
                            selectedMemories.add(mem)
                            availableTokens -= cost
                        }
                    }
                    for (msg in searchPrunedMessages.drop(minChatHistory)) {
                        val cost = estimateTokens(msg)
                        if (availableTokens >= cost) {
                            selectedMessages.add(msg)
                            availableTokens -= cost
                        } else break
                    }
                }
                ContextPriority.BALANCED -> {
                    var msgIndex = 0; var memIndex = 0; var addedSomething = true
                    while (addedSomething && availableTokens > 0) {
                        addedSomething = false
                        if (msgIndex < remainingChatHistory.size) {
                            val msg = remainingChatHistory[msgIndex]
                            val cost = estimateTokens(msg)
                            if (availableTokens >= cost) {
                                selectedMessages.add(msg)
                                availableTokens -= cost
                                msgIndex++
                                addedSomething = true
                            }
                        }
                        if (memIndex < remainingMemories.size) {
                            val mem = remainingMemories[memIndex]
                            val cost = estimateTokens(mem.content)
                            if (availableTokens >= cost) {
                                selectedMemories.add(mem)
                                availableTokens -= cost
                                memIndex++
                                addedSomething = true
                            }
                        }
                    }
                }
            }
        }

        val modeAttachmentParts = enabledModes.flatMap { mode ->
            mode.attachments.map { attachment ->
                when (attachment.type) {
                    ModeAttachmentType.IMAGE -> UIMessagePart.Image(url = attachment.url)
                    ModeAttachmentType.VIDEO -> UIMessagePart.Video(url = attachment.url)
                    ModeAttachmentType.AUDIO -> UIMessagePart.Audio(url = attachment.url)
                    ModeAttachmentType.DOCUMENT -> UIMessagePart.Document(
                        url = attachment.url,
                        fileName = attachment.fileName,
                        mime = attachment.mime
                    )
                }
            }
        }

        val lorebookAttachmentParts = activatedEntries.flatMap { entry ->
            entry.attachments.map { attachment ->
                when (attachment.type) {
                    ModeAttachmentType.IMAGE -> UIMessagePart.Image(url = attachment.url)
                    ModeAttachmentType.VIDEO -> UIMessagePart.Video(url = attachment.url)
                    ModeAttachmentType.AUDIO -> UIMessagePart.Audio(url = attachment.url)
                    ModeAttachmentType.DOCUMENT -> UIMessagePart.Document(
                        url = attachment.url,
                        fileName = attachment.fileName,
                        mime = attachment.mime
                    )
                }
            }
        }

        val allContextAttachments = modeAttachmentParts + lorebookAttachmentParts

        val builtMessages = buildList {
            // 1. Stable & Semi-stable System Prompt
            // Includes personality, tools, Master Memory (L3), and Global Summary (L1).
            if (baseSystemPrompt.isNotBlank()) {
                add(UIMessage.system(baseSystemPrompt))
            }

            // 2. Attachments
            if (allContextAttachments.isNotEmpty()) {
                add(UIMessage(
                    role = CoreMessageRole.USER,
                    parts = allContextAttachments
                ))
            }

            // 3. Chat History (L0)
            addAll(selectedMessages.sortedBy { messages.indexOf(it) })

            // 4. Instant Dynamic System Information
            // Only contains high-frequency changes: RAG Memories (L2), Variables and Time.
            val dynamicSystemPrompt = buildString {
                // A. L2: Memories (RAG retrieved facts)
                if (selectedMemories.isNotEmpty()) {
                    appendLine()
                    append(buildMemoryPrompt(selectedMemories))
                }

                // B. Reference Variables
                if (assistant.referenceVariables.isNotBlank()) {
                    appendLine()
                    append(
                        assistant.referenceVariables.applyPlaceholders(
                            "char" to assistant.name,
                            "locale" to Locale.getDefault().displayName
                        )
                    )
                }

                // C. Time Information
                if (assistant.localTools.any { it is LocalToolOption.TimeSense }) {
                    val now = LocalDateTime.now()
                    val month = now.monthValue
                    val day = now.dayOfMonth
                    val holiday = when {
                        month == 1 && day == 1 -> "New Year's Day"
                        month == 3 && day == 8 -> "Women's Day"
                        month == 3 && day == 12 -> "Arbor Day"
                        month == 4 && (day in 4..6) -> "Qingming Festival"
                        month == 5 && (day == 1 ||day == 2 ||day == 3 || day == 5) -> "legal holiday for Labour Day"
                        month == 5 && day == 4 -> "legal holiday for Labour Day&Youth Day"
                        month == 6 && day == 1 -> "Children's Day"
                        month == 7 && day == 1 -> "CPC Founding Day"
                        month == 8 && day == 1 -> "Army Day"
                        month == 9 && day == 10 -> "Teachers' Day"
                        month == 11 && day == 8 -> "Journalists' Day"
                        month == 12 && day == 25 -> "Christmas"
                        else -> null
                    }
                    val dayOfWeek = now.dayOfWeek
                    val dayName = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    val dayType = holiday ?: if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) "restday" else "workday"
                    val formattedTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val timeStr = "$dayName.($dayType), $formattedTime"

                    val lastAiMessage = messages.lastOrNull { it.role == CoreMessageRole.ASSISTANT }
                    val intervalInfo = lastAiMessage?.let {
                        runCatching {
                            @Suppress("DEPRECATION")
                            val prevJavaDateTime = LocalDateTime.of(
                                it.createdAt.year,
                                it.createdAt.monthNumber,
                                it.createdAt.dayOfMonth,
                                it.createdAt.hour,
                                it.createdAt.minute,
                                it.createdAt.second,
                                it.createdAt.nanosecond
                            )
                            val prevInstant = prevJavaDateTime.atZone(ZoneId.systemDefault()).toInstant()
                            val currentInstant = Instant.now()
                            val duration = Duration.between(prevInstant, currentInstant)
                            val seconds = duration.seconds
                            val absSeconds = kotlin.math.abs(seconds)
                            val sign = if (seconds >= 0) "+" else "-"
                            val formatted = when {
                                absSeconds < 60 -> "${sign}${absSeconds}s"
                                absSeconds < 3600 -> "${sign}${absSeconds / 60}m"
                                absSeconds < 86400 -> "${sign}${absSeconds / 3600}h"
                                else -> "${sign}${absSeconds / 86400}d"
                            }
                            ", Interval since your last reply: $formatted"
                        }.getOrNull()
                    } ?: ""

                    appendLine()
                    append("\n## Current Time Information\n- Current Time: $timeStr$intervalInfo\n\n Fabricating time will result in punishment.")
                }
            }

            if (dynamicSystemPrompt.isNotBlank()) {
                add(UIMessage.system(dynamicSystemPrompt))
            }
        }

        Log.d(TAG, "buildMessages: summaries info - hasContextSummary=${!contextSummary.isNullOrBlank()}, rawMessagesCount=${selectedMessages.size}")

        val usedMemoriesList = selectedMemories.mapIndexed { index, memory ->
            val isBoost = memory.type == 2
            val reason = when {
                isBoost -> context.getString(R.string.context_source_recent_episode_boost)
                assistant.useRagMemoryRetrieval -> context.getString(R.string.context_source_contextually_relevant)
                else -> context.getString(R.string.context_source_always_included)
            }
            UsedMemory(
                memoryId = memory.id,
                memoryContent = if (isBoost) memory.content else {
                    buildString {
                        append(memory.content.take(50))
                        if (memory.content.length > 50) append("...")
                    }
                },
                memoryType = memory.type,
                priority = selectedMemories.size - index,
                activationReason = reason
            )
        }

        return BuildMessagesResult(
            messages = builtMessages,
            activatedLorebookEntries = usedLorebookEntriesList,
            usedModes = usedModesList,
            usedMemories = usedMemoriesList
        )
    }

    private fun buildMemoryTools(
        assistantId: String,
        onCreation: suspend (String) -> AssistantMemory,
        onUpdate: suspend (Int, String) -> AssistantMemory,
        onDelete: suspend (Int) -> Unit
    ) = listOf(
        Tool(
            name = "create_memory",
            description = "Create a new memory record.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Content of the memory.")
                        })
                    },
                    required = listOf("content")
                )
            },
            execute = {
                val params = it.jsonObject
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
            }
        ),
        Tool(
            name = "edit_memory",
            description = "Update an existing memory record.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "ID of the memory to update.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "New content for the memory.")
                        })
                    },
                    required = listOf("id", "content"),
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                val before = memoryRepo.getMemoryById(id)
                val updated = onUpdate(id, content)
                buildJsonObject {
                    put("id", JsonPrimitive(updated.id))
                    put("content", JsonPrimitive(updated.content))
                    put("type", JsonPrimitive(updated.type))
                    put("hasEmbedding", JsonPrimitive(updated.hasEmbedding))
                    updated.embeddingModelId?.let { put("embeddingModelId", JsonPrimitive(it)) }
                    put("timestamp", JsonPrimitive(updated.timestamp))
                    updated.significance?.let { put("significance", JsonPrimitive(it)) }
                    before?.let { previous ->
                        put("before_content", JsonPrimitive(previous.content))
                        put("before_timestamp", JsonPrimitive(previous.timestamp))
                    }
                }
            }
        ),
        Tool(
            name = "delete_memory",
            description = "Delete a memory record.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "ID of the memory to delete.")
                        })
                    },
                    required = listOf("id")
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                val before = memoryRepo.getMemoryById(id)
                onDelete(id)
                buildJsonObject {
                    put("deleted", JsonPrimitive(true))
                    before?.let { memory ->
                        put("id", JsonPrimitive(memory.id))
                        put("content", JsonPrimitive(memory.content))
                        put("type", JsonPrimitive(memory.type))
                        put("hasEmbedding", JsonPrimitive(memory.hasEmbedding))
                        memory.embeddingModelId?.let { put("embeddingModelId", JsonPrimitive(it)) }
                        put("timestamp", JsonPrimitive(memory.timestamp))
                        memory.significance?.let { put("significance", JsonPrimitive(it)) }
                    }
                }
            }
        )
    )

    private fun buildMemoryPrompt(memories: List<AssistantMemory>): String {
        Log.d(TAG, "buildMemoryPrompt: Injecting ${memories.size} memories into prompt")
        if (memories.isEmpty()) {
            return ""
        }

        val coreMemories = memories.filter { it.type == 0 }
        val episodicMemories = memories.filter { it.type == 1 }
        val boostedMemories = memories.filter { it.type == 2 }

        fun formatMemoryDate(timestamp: Long): String {
            if (timestamp <= 0) return "Unknown Date"
            return Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }

        return buildString {
            append("## Memories\n").append("These are memories that you can reference. If a memory summary is too brief and you need specific tactical details (like code blocks, exact quotes, or step-by-step logic), please call `retrieve_memory_details(episode_id, query)`.\n")

            if (boostedMemories.isNotEmpty()) {
                append("### Recent Interaction Reference\n")
                boostedMemories.forEach { memory ->
                    append("- ${memory.content}\n")
                }
            }

            if (coreMemories.isNotEmpty()) {
                append("### Core Memories\n")
                coreMemories.forEach { memory ->
                    val dateStr = formatMemoryDate(memory.timestamp)
                    append("- [Date: $dateStr] ${memory.content}\n")
                }
            }

            if (episodicMemories.isNotEmpty()) {
                append("### Episodic Memories\n")

                val now = java.time.LocalDate.now()
                val yesterday = now.minusDays(1)
                val lastWeek = now.minusWeeks(1)

                val groupedEpisodes = episodicMemories.groupBy { memory ->
                    val date = Instant.ofEpochMilli(memory.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()

                    when {
                        date.isEqual(now) -> "Today"
                        date.isEqual(yesterday) -> "Yesterday"
                        date.isAfter(lastWeek) -> "This Week"
                        else -> "Older"
                    }
                }

                listOf("Today", "Yesterday", "This Week", "Older").forEach { group ->
                    val memoriesInGroup = groupedEpisodes[group]
                    if (!memoriesInGroup.isNullOrEmpty()) {
                        append("#### $group\n")
                        memoriesInGroup.sortedByDescending { it.timestamp }.forEach { memory ->
                            val dateStr = formatMemoryDate(memory.timestamp)
                            append("- [ID: ${memory.id}, Date: $dateStr] ${memory.content}\n")
                        }
                    }
                }
            }
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        truncateIndex: Int,
        stream: Boolean,
        enabledModeIds: Set<Uuid> = emptySet(),
        contextSummary: String? = null,
        temporarySummaries: List<String> = emptyList(),
        includeSkipContextMessages: Boolean = false,
        conversationId: Uuid? = null
    ) {
        val buildResult = buildMessages(
            assistant = assistant,
            settings = settings,
            messages = messages,
            model = model,
            tools = tools,
            memories = memories,
            truncateIndex = truncateIndex,
            enabledModeIds = enabledModeIds,
            contextSummary = contextSummary,
            temporarySummaries = temporarySummaries,
            includeSkipContextMessages = includeSkipContextMessages,
            conversationId = conversationId
        )
        val internalMessages = buildResult.messages.transforms(transformers, context, model, assistant)

        Log.i(TAG, ">>> START LLM PAYLOAD [${internalMessages.size} messages] <<<")
        Log.i(TAG, "  Available Tools: ${tools.joinToString { it.name }}")
        internalMessages.forEach { msg ->
             val preview = msg.toContentText().take(60).replace("\n", " ")
             Log.i(TAG, "  [${msg.role}] -> $preview...")
             if (msg.role == CoreMessageRole.SYSTEM) {
                 Log.v(TAG, "  Full System Content: \n${msg.toText()}")
             }
        }
        Log.i(TAG, ">>> END LLM PAYLOAD <<<")

        val usedLorebookEntries = buildResult.activatedLorebookEntries
        val usedModes = buildResult.usedModes
        val usedMemories = buildResult.usedMemories
        val hasContextSources = usedLorebookEntries.isNotEmpty() || usedModes.isNotEmpty() || usedMemories.isNotEmpty()

        var currentMessages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            thinkingBudget = assistant.thinkingBudget,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(AILogging.Generation(
                params = params,
                messages = currentMessages,
                providerSetting = provider,
                stream = true
            ))
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                currentMessages = currentMessages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    currentMessages = currentMessages.mapIndexed { index, message ->
                        if (index == currentMessages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(currentMessages)
            }
            if (hasContextSources) {
                currentMessages = currentMessages.mapIndexed { index, message ->
                    if (index == currentMessages.lastIndex && message.role == CoreMessageRole.ASSISTANT) {
                        message.copy(
                            usedLorebookEntries = usedLorebookEntries.ifEmpty { null },
                            usedModes = usedModes.ifEmpty { null },
                            usedMemories = usedMemories.ifEmpty { null }
                        )
                    } else {
                        message
                    }
                }
                onUpdateMessages(currentMessages)
            }
        } else {
            aiLoggingManager.addLog(AILogging.Generation(
                params = params,
                messages = currentMessages,
                providerSetting = provider,
                stream = false
            ))
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            currentMessages = currentMessages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                currentMessages = currentMessages.size.let { _ ->
                    currentMessages.mapIndexed { index, message ->
                        if (index == currentMessages.lastIndex) {
                            message.copy(
                                usage = message.usage.merge(usage)
                            )
                        } else {
                            message
                        }
                    }
                }
            }
            if (hasContextSources) {
                currentMessages = currentMessages.mapIndexed { index, message ->
                    if (index == currentMessages.lastIndex && message.role == CoreMessageRole.ASSISTANT) {
                        message.copy(
                            usedLorebookEntries = usedLorebookEntries.ifEmpty { null },
                            usedModes = usedModes.ifEmpty { null },
                            usedMemories = usedMemories.ifEmpty { null }
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(currentMessages)
        }
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        modelIdOverride: Uuid? = null,
        onStreamUpdate: (suspend (String) -> Unit)? = null
    ): Flow<String> = flow {
        val modelId = modelIdOverride ?: settings.translateModeId
        val model = settings.providers.findModelById(modelId) ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers) ?: error("Translation provider not found")
        val providerHandler = providerManager.getProviderByType(provider)
        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var currentMessages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = currentMessages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                ),
            ).collect { chunk ->
                currentMessages = currentMessages.handleMessageChunk(chunk)
                translatedText = currentMessages.lastOrNull()?.toContentText() ?: ""
                if (translatedText.isNotBlank()) { onStreamUpdate?.invoke(translatedText); emit(translatedText) }
            }
        } else {
            val currentMessages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = currentMessages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toContentText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun sanitizeToolCallArguments(arguments: String): String {
        if (arguments.isBlank()) return "{}"
        val trimmed = arguments.trim()
        var braceCount = 0; var inString = false; var escape = false
        for ((index, char) in trimmed.withIndex()) {
            if (escape) {
                escape = false
                continue
            }
            when (char) {
                '\\' -> if (inString) escape = true
                '"' -> inString = !inString
                '{' -> if (!inString) braceCount++
                '}' -> if (!inString) {
                    braceCount--
                    if (braceCount == 0) {
                        return trimmed.substring(0, index + 1)
                    }
                }
            }
        }
        Log.w(TAG, "Could not extract valid JSON object from: $trimmed")
        return "{}"
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }
}
