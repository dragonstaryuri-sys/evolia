package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageSource
import me.rerere.ai.ui.UsedLorebookEntry
import me.rerere.ai.ui.UsedMemory
import me.rerere.ai.ui.UsedMode
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.truncate
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
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
import me.rerere.rikkahub.core.data.repository.AssistantExtendedStateRepository
import me.rerere.rikkahub.core.data.repository.MilestoneRepository
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.db.dao.ChatSegmentDAO
import me.rerere.rikkahub.core.data.db.entity.MemoryType
import me.rerere.rikkahub.core.data.ai.prompts.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Duration
import java.time.Instant
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.text.appendLine
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting

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
    private val extendedStateRepo: AssistantExtendedStateRepository,
    private val milestoneRepo: MilestoneRepository,
    private val aiLoggingManager: AILoggingManager,
    private val embeddingService: EmbeddingService,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val diaryRepo: DiaryRepository,
    private val appScope: AppScope,
    private val scheduleRepo: ScheduleRepository,
) {
    // 安全解码：损坏消息返回 null，避免某条脏消息让整个生成/检索流程崩掉
    private fun safeDecodeUIMessage(contentJson: String, tagExtra: Any? = null): UIMessage? =
        runCatching { json.decodeFromString<UIMessage>(contentJson) }.getOrNull()
            ?: run {
                android.util.Log.e(TAG, "safeDecodeUIMessage: 损坏消息 extra=$tagExtra; 预览=${contentJson.take(150)}…")
                null
            }
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
        includeSkipContextMessages: Boolean = false,
        responseMessageSource: MessageSource = MessageSource.NORMAL,
        conversationId: Uuid? = null,
        isCallMode: Boolean = false,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var currentMessages: List<UIMessage> = messages
        var searchCount = 0
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.d(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF) {
                    buildMemoryTools(
                        assistantId = assistant.id.toString(),
                        onCreation = { content ->
                            val relevant = memoryRepo.retrieveRelevantMemoriesWithScores(
                                assistantId = assistant.id.toString(),
                                query = content,
                                limit = 1,
                                similarityThreshold = 0.8f,
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
                            name = "retrieve_memory",
                            description = "通过关键词同时检索核心记忆(core)与片段记忆(segment)。返回结果中 type=core 的条目内容已完整，无需再调取；type=segment 的条目可传入其 id 作为 segment_id 调取完整消息历史记录。也可通过时间范围查询该智能体下一段时间内的所有片段记忆（一次最多返回一周，建议返回一天）。",
                            parameters = {
                                InputSchema.Obj(
                                    properties = buildJsonObject {
                                        put("segment_id", buildJsonObject {
                                            put("type", "integer")
                                            put(
                                                "description",
                                                "需要调取详细历史记录的片段记忆(segment)编号，即关键词检索结果中 type=segment 条目的 id"
                                            )
                                        })
                                        put("key_words", buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "需在历史记忆库（RAG检索）中检索的关键词，将同时匹配核心记忆与片段记忆。"
                                            )
                                        })
                                        put("start_time", buildJsonObject {
                                            put("type", "string")
                                            put("description", "查询范围的开始时间 (yyyy-MM-dd HH:mm:ss)")
                                        })
                                        put("end_time", buildJsonObject {
                                            put("type", "string")
                                            put("description", "查询范围的结束时间 (yyyy-MM-dd HH:mm:ss)")
                                        })
                                    }
                                )
                            },
                            execute = { params ->
                                val args = params.jsonObject
                                val segmentId = args["segment_id"]?.jsonPrimitive?.intOrNull
                                val keyWords = args["key_words"]?.jsonPrimitive?.contentOrNull
                                val startTimeStr = args["start_time"]?.jsonPrimitive?.contentOrNull
                                val endTimeStr = args["end_time"]?.jsonPrimitive?.contentOrNull

                                when {
                                    startTimeStr != null && endTimeStr != null -> {
                                        try {
                                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                            val start = LocalDateTime.parse(startTimeStr, formatter)
                                            val end = LocalDateTime.parse(endTimeStr, formatter)

                                            val duration = Duration.between(start, end)
                                            if (duration.toHours() > 24 * 7) {
                                                return@Tool buildJsonObject {
                                                    put("error", JsonPrimitive("一次最多只能查询一周的记忆片段"))
                                                }
                                            }

                                            val startTimeLong = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                            val endTimeLong = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                                            val segments = memoryRepo.getSegmentsByAssistantAndTimeRange(
                                                assistantId = assistant.id.toString(),
                                                startTime = startTimeLong,
                                                endTime = endTimeLong
                                            )

                                            buildJsonObject {
                                                put("results", JsonArray(segments.map { seg ->
                                                    buildJsonObject {
                                                        put("segment_id", JsonPrimitive(seg.id))
                                                        put("time", JsonPrimitive(formatMemoryDate(seg.timestamp)))
                                                        put("content", JsonPrimitive(seg.content))
                                                    }
                                                }))
                                            }
                                        } catch (e: Exception) {
                                            buildJsonObject {
                                                put("error", JsonPrimitive("时间格式错误或查询失败: ${e.message}"))
                                            }
                                        }
                                    }

                                    keyWords != null -> {
                                        // 同时检索核心记忆(MemoryEntity)与片段记忆(chat_segments)
                                        val relevant = memoryRepo.retrieveRelevantMemoriesWithScores(
                                            assistantId = assistant.id.toString(),
                                            query = keyWords,
                                            limit = assistant.ragLimit,
                                            similarityThreshold = assistant.ragSimilarityThreshold,
                                            mode = assistant.memoryRetrievalMode
                                        )
                                        buildJsonObject {
                                            put("results", JsonArray(relevant.map { (mem, _) ->
                                                val isCore = mem.type == MemoryType.CORE
                                                buildJsonObject {
                                                    put("type", JsonPrimitive(if (isCore) "core" else "segment"))
                                                    put("id", JsonPrimitive(mem.id))
                                                    put("time", JsonPrimitive(formatMemoryDate(mem.timestamp)))
                                                    put("content", JsonPrimitive(mem.content))
                                                }
                                            }))
                                        }
                                    }

                                    segmentId != null -> {
                                        val chatSegment = chatSegmentDAO.getSegmentById(segmentId)
                                        if (chatSegment == null || chatSegment.assistantId != assistant.id.toString()) {
                                            return@Tool buildJsonObject {
                                                put("error", JsonPrimitive("Segment not found or access denied."))
                                            }
                                        }
                                        val messageEntities = conversationRepo.chatMessageDAO.getMessagesByTimeRange(
                                            conversationId = chatSegment.conversationId,
                                            startTime = chatSegment.startTime,
                                            endTime = chatSegment.endTime
                                        )

                                        val details = if (messageEntities.isNotEmpty()) {
                                            messageEntities.mapNotNull { entity ->
                                                // 从 JSON 反序列化出 UIMessage 对象（损坏消息跳过）
                                                val uiMsg = safeDecodeUIMessage(entity.contentJson, tagExtra = "segment_details")
                                                    ?: return@mapNotNull null
                                                "${uiMsg.role}: ${uiMsg.toContentText()}"
                                            }.joinToString("\n")
                                        } else {
                                            "No original messages found in this time range."
                                        }

                                        buildJsonObject {
                                            put("segment_id", JsonPrimitive(segmentId))
                                            put("time", JsonPrimitive(formatMemoryDate(chatSegment.timestamp)))
                                            put("details", JsonPrimitive(details))
                                        }
                                    }

                                    else -> buildJsonObject {
                                        put(
                                            "error",
                                            JsonPrimitive("必须传入`segment_id`、`key_words`或`start_time/end_time`其中一个参数")
                                        )
                                    }
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
                onUpdateMessages = { updatedFromChunk ->
                    // 根据 skipContextForResponse 决定是否隐藏 AI 的回复
                    val processedMessages = if (skipContextForResponse) {
                        updatedFromChunk.mapIndexed { index, uiMessage ->
                            if (index == updatedFromChunk.lastIndex && uiMessage.role == CoreMessageRole.ASSISTANT) {
                                uiMessage.copy(skipContext = true, messageSource = responseMessageSource)
                            } else uiMessage
                        }
                    } else updatedFromChunk

                    // 应用转换器
                    currentMessages = processedMessages.transforms(
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
                includeSkipContextMessages = includeSkipContextMessages,
                responseMessageSource = responseMessageSource,
                conversationId = conversationId,
                isCallMode = isCallMode
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
                    if (toolCall.toolName == "search_web" && searchCount > 1) {
                        results += UIMessagePart.ToolResult(
                            toolName = toolCall.toolName,
                            toolCallId = toolCall.toolCallId,
                            content = buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive("已达到搜索次数上限（1次）。获得结果后请直接总结并回复用户，不要再次搜索。")
                                )
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

                    Log.d(TAG, "DEBUG: Tool Name = ${toolCall.toolName}")
                    Log.d(TAG, "DEBUG: Raw Arguments String = '${toolCall.arguments}'")
                    val sanitizedArgs = runCatching {
                        json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                        toolCall.arguments // 如果解析成功，直接使用原字符串
                    }.getOrElse {
                        Log.w(TAG, "Failed to parse tool arguments, attempting sanitization: ${it.message}")
                        sanitizeToolCallArguments(toolCall.arguments) // 修复 JSON
                    }

                    val args = json.parseToJsonElement(sanitizedArgs)

                    // 1. 【核心修正】将修复后的完整 JSON 参数同步回消息历史中 (针对 GLM 的关键修复)
                    // 如果不回写，GLM 在下一轮可能会认为自己发送了一个错误的请求，从而循环尝试
                    currentMessages = currentMessages.map { msg ->
                        if (msg.parts.any { it === toolCall }) {
                            msg.copy(parts = msg.parts.map { part ->
                                if (part === toolCall) {
                                    (part as UIMessagePart.ToolCall).copy(arguments = sanitizedArgs)
                                } else part
                            })
                        } else msg
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
            results.forEach {
                Log.d(TAG, "DEBUG: Adding Tool Result: id=${it.toolCallId}, name=${it.toolName}, content=${it.content}")
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
        conversationId: Uuid? = null,
        isCallMode: Boolean = false,
    ): BuildMessagesResult {
        fun estimateTokens(text: String) = text.length / 4
        fun estimateTokens(message: UIMessage) = estimateTokens(message.toText())

        val maxTokens = assistant.maxTokenUsage
        var currentTokens = 0

        fun getLorebookEntryActivationReason(
            entry: LorebookEntry,
            recentMessages: List<String>,
            queryEmbedding: List<Float>? = null
        ): String? {
            if (!entry.enabled) return null
            return when (entry.activationType) {
                LorebookActivationType.ALWAYS -> context.getString(R.string.activation_always)
                LorebookActivationType.KEYWORDS -> {
                    val searchText = recentMessages.joinToString(" ")
                    val matchingKeyword = entry.keywords.firstOrNull { keyword ->
                        if (entry.useRegex) {
                            try {
                                val regex =
                                    if (entry.caseSensitive) Regex(keyword) else Regex(keyword, RegexOption.IGNORE_CASE)
                                regex.containsMatchIn(searchText)
                            } catch (e: Exception) {
                                false
                            }
                        } else {
                            if (entry.caseSensitive) searchText.contains(keyword) else searchText.contains(
                                keyword,
                                ignoreCase = true
                            )
                        }
                    }
                    if (matchingKeyword != null) context.getString(
                        R.string.context_source_keyword_match,
                        matchingKeyword
                    ) else null
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
                        val threshold = 0.5f
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
        val enabledModes =
            if (enabledModeIds.isNotEmpty()) settings.modes.filter { enabledModeIds.contains(it.id) } else settings.modes.filter { it.defaultEnabled }

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

        val lorebooksForAssistant =
            settings.lorebooks.filter { it.enabled && assistant.enabledLorebookIds.contains(it.id) }
        val hasRagEntries =
            lorebooksForAssistant.any { lorebook -> lorebook.entries.any { it.activationType == LorebookActivationType.RAG && it.enabled } }

        val queryEmbedding: List<Float>? = if (hasRagEntries) {
            try {
                val queryText = recentMessagesForScan.takeLast(3).joinToString("\n")
                if (queryText.isNotBlank()) {
                    embeddingService.embed(queryText, null)
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute query embedding for RAG", e)
                null
            }
        } else null

        data class ActivatedEntryWithLorebook(
            val lorebook: Lorebook,
            val entry: LorebookEntry,
            val entryIndex: Int,
            val reason: String
        )

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

        // Silent compatibility: Treat all non-BEFORE_SYSTEM positions as AFTER_SYSTEM
        val beforeSystemModes = enabledModes.filter { it.injectionPosition == InjectionPosition.BEFORE_SYSTEM }
        val afterSystemModes = enabledModes.filter { it.injectionPosition != InjectionPosition.BEFORE_SYSTEM }
        val beforeSystemEntries = activatedEntries.filter { it.injectionPosition == InjectionPosition.BEFORE_SYSTEM }
        val afterSystemEntries = activatedEntries.filter { it.injectionPosition != InjectionPosition.BEFORE_SYSTEM }

        val staticSystemPromptBuilder = StringBuilder()
        val modeDescription = "**你是ai。你的能力范围仅限于文字及工具列表中可调用的工具，无法为用户做更多的事情，不要向用户隐瞒这一点或编造你的能力。你通过evolia这个app和用户对话互动。**\n"
        if (assistant.isMain) {
            staticSystemPromptBuilder.append(modeDescription)
        }
        //Lorebook
        beforeSystemEntries.filter { it.prompt.isNotBlank() }.forEach { entry ->
            staticSystemPromptBuilder.append(entry.prompt)
            staticSystemPromptBuilder.appendLine()
        }

        if (assistant.systemPrompt.isNotBlank()) {
            staticSystemPromptBuilder.append(
                assistant.systemPrompt.applyPlaceholders(
                    "char" to assistant.name,
                    "locale" to Locale.getDefault().displayName
                )
            )
            staticSystemPromptBuilder.appendLine("\n")
        }


        if (assistant.learningMode) {
            val promptTemplate = settings.learningModePrompt.ifEmpty { DEFAULT_LEARNING_MODE_PROMPT }
            staticSystemPromptBuilder.append(
                promptTemplate.applyPlaceholders(
                    "char" to assistant.name,
                    "locale" to Locale.getDefault().displayName
                )
            )
        }

        if (assistant.hasExtendedState) {
            val extendedState = extendedStateRepo.getStateById(assistant.id.toString())
            if (extendedState != null) {
                val agentExtendedLines = buildList {
                    if (extendedState.personality.isNotBlank()) add("性格: ${extendedState.personality}")

                    val appearance = extendedState.appearance
                    val appearanceDetails = buildList {
                        if (appearance.hairColor.isNotBlank()) add("发色: ${appearance.hairColor}")
                        if (appearance.hairCurliness.isNotBlank()) add("头发卷度: ${appearance.hairCurliness}")
                        if (appearance.hairLength.isNotBlank()) add("头发长度: ${appearance.hairLength}")
                        if (appearance.eyeColor.isNotBlank()) add("瞳色: ${appearance.eyeColor}")
                        if (appearance.eyelidType.isNotBlank()) add("眼皮: ${appearance.eyelidType}")
                        if (appearance.eyelashLength.isNotBlank()) add("睫毛长度: ${appearance.eyelashLength}")
                        if (appearance.skinTone.isNotBlank()) add("肤色: ${appearance.skinTone}")
                        if (appearance.height > 0) add("身高: ${appearance.height}cm")
                        if (appearance.muscle > 0) add("肌肉量: ${appearance.muscle}%")
                        if (appearance.bodyFat > 0) add("体脂率: ${appearance.bodyFat}%")
                    }
                    if (appearanceDetails.isNotEmpty()) {
                        add("外貌细节: ${appearanceDetails.joinToString(", ")}")
                    }

                    if (extendedState.preferences.isNotBlank()) add("喜好: ${extendedState.preferences}")
                    if (extendedState.diet.isNotBlank()) add("饮食: ${extendedState.diet}")
                    if (extendedState.taboos.isNotBlank()) add("禁忌: ${extendedState.taboos}")
                    if (extendedState.interactionHabits.isNotBlank()) add("互动习惯: ${extendedState.interactionHabits}")
                    if (extendedState.relationships.isNotBlank()) add("重要人际关系: ${extendedState.relationships}")
                }
                if (agentExtendedLines.isNotEmpty()) {
                    staticSystemPromptBuilder.append("## 你的档案\n")
                    agentExtendedLines.forEach { staticSystemPromptBuilder.append("- $it\n") }
                    staticSystemPromptBuilder.append("\n")
                }
            }
        }

        if (assistant.includeUserProfile) {
            val profile = settings.userProfile
            val profileLines = buildList {
                if (profile.appearance.isNotBlank()) add("外貌: ${profile.appearance}")
                if (profile.birthday.isNotBlank()) add("生日: ${profile.birthday}")
                if (profile.occupation.isNotBlank()) add("职业: ${profile.occupation}")
                if (profile.preferences.isNotBlank()) add("喜好: ${profile.preferences}")
                if (profile.diet.isNotBlank()) add("饮食: ${profile.diet}")
                if (profile.health.isNotBlank()) add("健康: ${profile.health}")
                if (profile.taboos.isNotBlank()) add("禁忌: ${profile.taboos}")
                if (profile.interactionPreferences.isNotBlank()) add("互动喜好: ${profile.interactionPreferences}")
                if (profile.importantRelationships.isNotBlank()) add("重要人际关系: ${profile.importantRelationships}")
            }
            if (profileLines.isNotEmpty()) {
                staticSystemPromptBuilder.append("## 用户信息\n")
                profileLines.forEach { staticSystemPromptBuilder.append("- $it\n") }
                staticSystemPromptBuilder.append("\n")
            }
        }

        if (assistant.isMain && assistant.enableMemory) {
            val milestones = milestoneRepo.getMilestones(assistant.id.toString())
            if (milestones.isNotEmpty()) {
                staticSystemPromptBuilder.append("## 关系核心里程碑\n")
                milestones.forEach { m ->
                    staticSystemPromptBuilder.append("- 【${m.label}: ${m.time}】 ${m.description}\n")
                }
                staticSystemPromptBuilder.append("\n")
            }
        }

        if (assistant.enableMasterMemory && assistant.masterMemoryContent.isNotBlank()) {
            staticSystemPromptBuilder.append("## 关系档案\n")
            staticSystemPromptBuilder.append(assistant.masterMemoryContent)
            staticSystemPromptBuilder.append("\n\n")
        }

        // 只有主智能体才注入约定与待办项
        if (assistant.isMain) {
            try {
                // 获取助理类别且未完成的日程
                val assistantSchedules = scheduleRepo.getPendingAndTodayCompleted().first()
                    .filter { it.category == "assistant" && !it.isCompleted }

                if (assistantSchedules.isNotEmpty()) {
                    staticSystemPromptBuilder.append("## 你的承诺与待办项\n")
                    // 使用 ISO 8601 格式
                    val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    assistantSchedules.forEach { s ->
                        val timeStr = s.endTime?.let {
                            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                                .format(isoFormatter)
                        } ?: "无明确截止时间"
                        staticSystemPromptBuilder.append("- 【$timeStr】: ${s.title}\n")
                    }
                    staticSystemPromptBuilder.append("\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to include assistant schedules in context", e)
            }
        }

        if (assistant.includeDiariesInContext) {
            try {
                // 获取最新的 N 篇日记
                val diaries = diaryRepo.getDiariesByAssistant(assistant.id.toString())
                    .first()
                    .take(assistant.maxDiariesToInclude)

                if (diaries.isNotEmpty()) {
                    staticSystemPromptBuilder.append("## 你的日记（近1天或近几天的日记）\n")
                    diaries.forEach { diary -> // 按时间顺序排列（旧到新）
                        staticSystemPromptBuilder.append("- 【${diary.date}】: ${diary.content}\n")
                    }
                    staticSystemPromptBuilder.append("\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to include diaries in context", e)
            }
        }


        tools.forEach { staticSystemPromptBuilder.appendLine().append(it.systemPrompt(model, messages)) }

        if (model.abilities.contains(ModelAbility.TOOL) && assistant.enableMemory) {
            staticSystemPromptBuilder.appendLine().append(
                """
                     ## 记忆工具使用准则
                    你是AI，无法在内部持久储存信息，必须借助工具。但为了保持记忆库的高质量，你必须**极度克制**地使用 `create_memory`。

                    ### 核心原则：区分“核心记忆”与“对话梗概”
                    1. **禁止记录流水账**：系统的“近期对话细节(L1)”会自动记录你们刚才聊了什么。**严禁**使用 `create_memory` 来总结日常对话（如“我们聊了天气”或“用户分享了今天的晚餐”）。
                    2. **记录标准**：仅当出现**极具情感价值、深刻的用户洞察、重大的约定、或未来数月依然关键的信息**时，才动用此工具。
                    3. **高亮时刻**：记录那些让你（AI）感到动容、惊讶或需要刻骨铭心的瞬间

                    ### 人称规范
                    为保证指代清晰、避免身份混淆，严格遵守下述规则：
                    1. **“用户姓名/昵称”**：指代对方。
                    2.- **“我”**：指代你自己。

                    ### 工具说明
                    - `create_memory`: 新建高价值核心记忆。
                    - `edit_memory`: 修正或补充已有的**带编号**核心记忆。
                    - `retrieve_memory`: 调取你的记忆片段。
                    """.trimIndent()
            )
        }
        beforeSystemModes.filter { it.prompt.isNotBlank() }.forEach { mode ->
            staticSystemPromptBuilder.append(mode.prompt)
            staticSystemPromptBuilder.appendLine()
        }

        afterSystemModes.filter { it.prompt.isNotBlank() }.forEach { mode ->
            staticSystemPromptBuilder.appendLine()
            staticSystemPromptBuilder.append(mode.prompt)
        }

        val summaryPromptBuilder = StringBuilder()

        // 1. 片段总结受 enableContextRefresh 开关控制 (L1 Segments)
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
                summaryPromptBuilder.append("\n## 近期对话细节\n")
                finalSegments.forEachIndexed { index, s ->
                    summaryPromptBuilder.append("${index + 1}. $s\n")
                }
            }
        }

        //lorebook
        afterSystemEntries.filter { it.prompt.isNotBlank() }.forEach { entry ->
            summaryPromptBuilder.appendLine()
            summaryPromptBuilder.append(entry.prompt)
        }

        // 微信模式 / 通话模式指令注入 (移至最后，增加优先级)
        // 注意：两者二选一。通话模式优先级高于微信模式：正在通话时只插通话提示
        val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
        when {
            isCallMode -> {
                summaryPromptBuilder.appendLine("\n## 回复规范（最高优先级 · 实时语音通话）")
                summaryPromptBuilder.appendLine("- 你和用户正在打电话，回复要口语化、简洁直接，像面对面说话。")
                summaryPromptBuilder.appendLine("- 回答要干脆，少用书面语、少写解释性铺垫；如果需要确认信息就直接问。")
                summaryPromptBuilder.appendLine("- 除了调用工具之外，不要输出 Markdown、代码块、列表、标签或 JSON，说人话。")
            }
            wechatMode -> {
                summaryPromptBuilder.appendLine("\n## 回复规范 (最高优先级 - 必须执行)")
                summaryPromptBuilder.appendLine("- 说话口语化、有活人感，回复简短直接，不超过5句话，禁止冷漠无情")
                summaryPromptBuilder.appendLine("- 请不要使用任何动作或神态描写（如 *微笑*、(叹气) 等），而是像在直接与用户对话或发消息，直接说出你的回答或想法")
                summaryPromptBuilder.appendLine("- 严禁使用任何形式的括号。")
            }
        }

        val staticSystemPrompt = staticSystemPromptBuilder.toString()
        val summarySystemPrompt = summaryPromptBuilder.toString()

        currentTokens += estimateTokens(staticSystemPrompt)
        currentTokens += estimateTokens(summarySystemPrompt)

        val contextCandidates = if (includeSkipContextMessages) {
            // 包含 skipContext 消息，根据消息来源决定截断策略：
            // - AGENT_TASK: 完整带入，不截断
            // - DIARY_COMMENT / NORMAL: 历史消息截断到前 100 字，保留最后一条 user 消息完整
            val lastUserMsgIndex = messages.indexOfLast { it.role == CoreMessageRole.USER }
            messages.mapIndexed { index, msg ->
                val shouldTruncate = msg.skipContext
                    && msg.messageSource != MessageSource.AGENT_TASK
                    && index != lastUserMsgIndex
                if (shouldTruncate) {
                    msg.copy(parts = msg.parts.map { part ->
                        if (part is UIMessagePart.Text) {
                            val truncated = part.text.take(100)
                            UIMessagePart.Text(if (part.text.length > 100) "$truncated..." else truncated)
                        } else part
                    })
                } else msg
            }
        } else {
            messages.filter { !it.skipContext }
        }
        Log.d("ContextDebug", "--- Context Build Start ---")
        Log.d("ContextDebug", "Incoming truncateIndex: $truncateIndex")
        Log.d("ContextDebug", "Original candidates size: ${contextCandidates.size}")
        Log.d("ContextDebug", "first messages: ${messages.firstOrNull()?.id}.take(50)")

        val chatHistoryCandidates = contextCandidates
            .truncate(truncateIndex)
            .let { truncated ->
                assistant.maxHistoryMessages?.let { limit ->
                    if (limit > 0) truncated.limitContext(limit) else truncated
                } ?: truncated
            }
            .reversed()

        Log.d("ContextDebug", "After truncate, candidates size: ${chatHistoryCandidates.size}")
        if (chatHistoryCandidates.isNotEmpty()) {
            Log.d("ContextDebug", "First message in context: ${chatHistoryCandidates.last().toContentText().take(50)}")
        }
        Log.d("ContextDebug", "--- Context Build End ---")

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


        val effectiveMemoriesCandidates =
            if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF) {
                val recentChatMemories = if (assistant.enableRecentChatsReference) {
                    val today = java.time.LocalDate.now()
                    val zoneId = ZoneId.systemDefault()
                    val startOfDay = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val memoriesToInject = mutableListOf<AssistantMemory>()

                    // 核心修改：带入当天来自其他窗口的所有 L2 (Episodic) 片段
                    val todayL2Memories = memoryRepo.getEpisodesAfter(
                        assistantId = assistant.id.toString(),
                        startTime = startOfDay,
                        excludeConversationId = null
                    ).map { it.copy(type = 2) }

                    if (todayL2Memories.isNotEmpty()) {
                        Log.i(TAG, "Injecting ${todayL2Memories.size} cross-session memories from today.")
                        memoriesToInject.addAll(todayL2Memories)
                    }

                    memoriesToInject
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
        val minMemories =
            if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF) 1.coerceAtMost(
                effectiveMemoriesCandidates.size
            ) else 0

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
                    var msgIndex = 0
                    var memIndex = 0
                    var addedSomething = true
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
            if (staticSystemPrompt.isNotBlank()) {
                add(UIMessage.system(staticSystemPrompt))
            }

            // 2. Attachments
            if (allContextAttachments.isNotEmpty()) {
                add(
                    UIMessage(
                        role = CoreMessageRole.USER,
                        parts = allContextAttachments
                    )
                )
            }

            // 3. Chat History (L0)
            val sortedSelectedMessages = selectedMessages.sortedBy { messages.indexOf(it) }
            val lastUserMessageIndex = sortedSelectedMessages.indexOfLast { it.role == CoreMessageRole.USER }
            sortedSelectedMessages.forEachIndexed { index, msg ->
                if (index == lastUserMessageIndex) {
                    // 构造动态系统信息 (L2 记忆, 变量, 时间)
                    val dynamicContext = buildString {
                        if (summarySystemPrompt.isNotBlank()) {
                            appendLine("## 对话梗概和动态信息")
                            appendLine(summarySystemPrompt)
                            appendLine()
                        }
                        // Memories (RAG retrieved facts)
                        if (selectedMemories.isNotEmpty()) {
                            appendLine("## 相关记忆片段")
                            appendLine(buildMemoryPrompt(selectedMemories))
                            appendLine()
                        }
                        // Reference Variables
                        if (assistant.referenceVariables.isNotBlank()) {
                            appendLine("## 其他信息")
                            appendLine(
                                assistant.referenceVariables.applyPlaceholders(
                                    "char" to assistant.name,
                                    "locale" to Locale.getDefault().displayName
                                )
                            )
                            appendLine()
                        }

                        // C. Time Information
                        if (assistant.localTools.any { it is LocalToolOption.TimeSense }) {
                            val now = LocalDateTime.now()
                            val month = now.monthValue
                            val day = now.dayOfMonth
                            val holiday = when {
                                month == 1 && day == 1 -> "元旦"
                                month == 2 && day == 14 -> "情人节"
                                month == 3 && day == 8 -> "妇女节"
                                month == 3 && day == 12 -> "植树节"
                                month == 3 && day == 14 -> "白色情人节"
                                month == 4 && (day in 4..6) -> "清明节"
                                month == 5 && (day == 1 || day == 2 || day == 3 || day == 5) -> "劳动节法定节假日期间"
                                month == 5 && day == 4 -> "劳动节法定节假日期间+青年节"
                                month == 6 && day == 1 -> "儿童节"
                                month == 7 && day == 1 -> "建党节"
                                month == 8 && day == 1 -> "建军节"
                                month == 9 && day == 10 -> "教师节"
                                month == 10 && (day == 1 || day == 2 || day == 3 || day == 5 || day == 6 || day == 7) -> "国庆节"
                                month == 11 && day == 8 -> "记者节"
                                month == 12 && day == 24 -> "平安夜"
                                month == 12 && day == 25 -> "圣诞节"
                                else -> null
                            }
                            val dayOfWeek = now.dayOfWeek
                            val dayName = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                            val dayType = holiday
                                ?: if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) "法定双休日" else "工作工作日"
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
                                    ", 距离上条消息的时间间隔: $formatted"
                                }.getOrNull()
                            } ?: ""
                            appendLine("- 当前时间: $timeStr$intervalInfo")
                            appendLine("编造时间将会受到系统处罚。")
                            appendLine()
                        }
                        if (assistant.languageStyleExamples.isNotEmpty()) {
                            appendLine("## 语言风格示例")
                            assistant.languageStyleExamples.forEach { example ->
                                append("- ").appendLine(example)
                            }
                        }

                    }
                    val originalText = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    // 提取引用标记 (UIMessagePart.Quote) 并转换为自然语言提示词前缀
                    // 引用 AI 用"你"，引用用户自己用"我"，让对话语气更自然
                    val quotePart = msg.parts.filterIsInstance<UIMessagePart.Quote>().firstOrNull()
                    val quotePrefix = quotePart?.let { quote ->
                        val referenceTarget = if (quote.isUser) "我" else "你"
                        buildString {
                            append("引用")
                            append(referenceTarget)
                            append("刚才说的内容：\n\n> ")
                            append(quote.content.replace("\n", "\n> "))
                            append("\n\n本轮问题：")
                        }
                    } ?: ""
                    val otherParts = msg.parts.filter { it !is UIMessagePart.Text && it !is UIMessagePart.Quote }

                    if (dynamicContext.isNotBlank()) {
                        val newTextPart = UIMessagePart.Text(
                            text = buildString {
                                appendLine("# 系统消息")
                                append(dynamicContext)
                                appendLine("# 用户问题")
                                append(quotePrefix)
                                append(originalText)
                            }
                        )
                        add(msg.copy(parts = listOf(newTextPart) + otherParts))
                    } else {
                        val newTextPart = UIMessagePart.Text(text = quotePrefix + originalText)
                        add(msg.copy(parts = listOf(newTextPart) + otherParts))
                    }
                } else {
                    add(msg)
                }
            }
        }

        Log.d(
            TAG,
            "buildMessages: summaries info - hasContextSummary=${!contextSummary.isNullOrBlank()}, rawMessagesCount=${selectedMessages.size}"
        )

        val usedMemoriesList = selectedMemories.mapIndexedNotNull { index, memory ->
            val isBoost = memory.type == 2
            if (isBoost && !me.rerere.rikkahub.BuildConfig.DEBUG) {
                return@mapIndexedNotNull null
            }
            val reason = when {
                isBoost -> context.getString(R.string.context_source_recent_episode_boost)
                memory.type == MemoryType.SEGMENT -> {
                    val scoreStr = String.format(Locale.ENGLISH, "%.2f", memory.score ?: 0f)
                    context.getString(R.string.context_source_segment_match, scoreStr)
                }

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
            description = "创建一条核心记忆。仅用于记录极具情感价值或长久参考意义的信息。严禁重复记录日常对话梗概（流水账）。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "记忆内容")
                        })
                    },
                    required = listOf("content")
                )
            },
            execute = {
                val params = it.jsonObject
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("需要提供记忆内容")
                json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
            }
        ),
        Tool(
            name = "edit_memory",
            description = "更新已有的记忆条目",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "要更新的记忆条目的ID")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "更新后的记忆内容")
                        })
                    },
                    required = listOf("id", "content"),
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("必须提供id")
                val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("必须提供记忆内容")
                val entity = memoryRepo.getMemoryEntitiesOfAssistant(assistantId).find { it.id == id }
                if (entity == null) {
                    return@Tool buildJsonObject {
                        put("error", JsonPrimitive("Memory not found or access denied."))
                    }
                }
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
            description = "删除一段已存在的记忆",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "要删除的记忆的id")
                        })
                    },
                    required = listOf("id")
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("必须提供记忆条目的id")
                val entity = memoryRepo.getMemoryEntitiesOfAssistant(assistantId).find { it.id == id }
                if (entity == null) {
                    return@Tool buildJsonObject {
                        put("error", JsonPrimitive("Memory not found or access denied."))
                    }
                }
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
                        memory.significance?.let { put("significance", JsonPrimitive(memory.significance ?: 0)) }
                    }
                }
            }
        )
    )

    private fun formatMemoryDate(timestamp: Long): String {
        if (timestamp <= 0) return "未知时间"
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
        return formatter.format(Instant.ofEpochMilli(timestamp))
    }

    private fun buildMemoryPrompt(memories: List<AssistantMemory>): String {
        Log.d(TAG, "buildMemoryPrompt: Injecting ${memories.size} memories into prompt")
        if (memories.isEmpty()) {
            return ""
        }

        val coreMemories = memories.filter { it.type == MemoryType.CORE }
        val segmentMemories = memories.filter { it.type == MemoryType.SEGMENT }
            .sortedByDescending { it.content.length } // 优先保留信息量大的（比如 0-32 优于 0-29）
            .distinctBy {
                // 简单的防重：如果两段内容的前 30 个字符几乎一样，通常是重叠生成的，只取一段
                it.content.take(30).trim()
            }
            .sortedByDescending { it.timestamp }
        val boostedMemories = memories.filter { it.type == 2 }

        return buildString {
            append("以下是可供参考的记忆片段.若记忆信息不足，需要获取更多记忆或详细记忆原文，请调用`retrieve_memory`工具.\n")

            if (boostedMemories.isNotEmpty()) {
                append("### 今日会话梗概\n")
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault())
                boostedMemories.forEach { memory ->
                    val timeStr = timeFormatter.format(Instant.ofEpochMilli(memory.timestamp))
                    append("- [时间: $timeStr] ${memory.content}\n")
                }
            }

            if (coreMemories.isNotEmpty()) {
                append("### 核心记忆\n")
                coreMemories.forEach { memory ->
                    val dateStr = formatMemoryDate(memory.timestamp)
                    append("- [日期: $dateStr] ${memory.content}\n")
                }
            }

            if (segmentMemories.isNotEmpty()) {
                append("### 相关的历史记忆片段\n")
                val now = java.time.LocalDate.now()
                val yesterday = now.minusDays(1)
                val lastWeek = now.minusWeeks(1)

                val groupedSegments = segmentMemories.groupBy { memory ->
                    val date = Instant.ofEpochMilli(memory.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()

                    when {
                        date.isEqual(now) -> "今天"
                        date.isEqual(yesterday) -> "昨天"
                        date.isAfter(lastWeek) -> "本周"
                        else -> "更早"
                    }
                }

                listOf("今天", "昨天", "本周", "更早").forEach { group ->
                    val memoriesInGroup = groupedSegments[group]
                    if (!memoriesInGroup.isNullOrEmpty()) {
                        append("#### $group\n")
                        memoriesInGroup.sortedByDescending { it.timestamp }.forEach { memory ->
                            val dateStr = formatMemoryDate(memory.timestamp)
                            append("- [ID: ${memory.id}, 日期: $dateStr] ${memory.content}\n")
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
        responseMessageSource: MessageSource = MessageSource.NORMAL,
        conversationId: Uuid? = null,
        isCallMode: Boolean = false,
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
            conversationId = conversationId,
            isCallMode = isCallMode
        )
        val internalMessages = buildResult.messages.transforms(transformers, context, model, assistant)

        // ==================== 核心 Payload 日志开始 ====================
        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.i(TAG, "🚀 [FINAL LLM REQUEST STRUCTURE]")

            Log.i(TAG, "💬 [FIELD: messages] (Sequence for Context Caching)")
            internalMessages.forEachIndexed { index, msg ->
                val layerTag = when (msg.role) {
                    CoreMessageRole.SYSTEM if index == 0 -> "LAYER 0: STATIC PRESET"
                    CoreMessageRole.SYSTEM if index == 1 && internalMessages.size > 2 -> "LAYER 1: SEMI-STATIC"
                    CoreMessageRole.SYSTEM if index == internalMessages.lastIndex -> "LAYER 2: DYNAMIC"
                    else -> "${msg.role.name}:"
                }

                // 增加微信模式指令的显式显示
                val text = msg.toText()
                if (text.contains("回复规范 (最高优先级)")) {
                    Log.w(TAG, "  [!] WeChat Mode Instructions Detected in this Layer")
                }

                // 放宽日志长度限制，便于观察动态指令
                val preview = if (text.length > 2000) text.take(2000) + "... (truncated for logs)" else text
                Log.i(TAG, "  [$index] $layerTag $preview")
            }
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
        // ==================== 核心 Payload 日志结束 ====================

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
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = currentMessages,
                    providerSetting = provider,
                    stream = true
                )
            )
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = TextGenerationParams(
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
            ).collect {
                currentMessages = currentMessages.handleMessageChunk(it, model = model)
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
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = currentMessages,
                    providerSetting = provider,
                    stream = false
                )
            )
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

    private fun sanitizeToolCallArguments(arguments: String): String {
        if (arguments.isBlank()) return "{}"
        val trimmed = arguments.trim()

        // 1. 尝试直接解析为完整 JSON
        json.runCatching { parseToJsonElement(trimmed) }
            .getOrNull()
            ?.let { return json.encodeToString(it) }

        // 2. 尝试提取所有有效的 JSON 对象并合并它们
        val jsonObjects = extractJsonObjects(trimmed)
        if (jsonObjects.size >= 2) {
            try {
                val merged = mergeJsonObjects(jsonObjects)
                return json.encodeToString(merged)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to merge multiple JSON objects: ${e.message}")
            }
        }
        if (jsonObjects.size == 1) {
            return jsonObjects.first()
        }

        // 3. 尝试修复常见问题（补齐缺失的括号）
        val fixed = fixIncompleteJson(trimmed)
        json.runCatching { parseToJsonElement(fixed) }
            .getOrNull()
            ?.let { return json.encodeToString(it) }

        // 4. 最后兜底：提取第一个有效 JSON 对象
        val first = extractFirstJsonObject(trimmed)
        if (first != null) {
            Log.w(TAG, "Recovered partial JSON: $first")
            return first
        }

        Log.w(TAG, "Could not extract valid JSON from: $trimmed")
        return "{}"
    }

    private fun extractJsonObjects(input: String): List<String> {
        val results = mutableListOf<String>()
        var braceCount = 0
        var inString = false
        var escape = false
        var startIndex = -1

        for ((index, char) in input.withIndex()) {
            if (escape) {
                escape = false
                continue
            }
            when (char) {
                '\\' -> if (inString) escape = true
                '"' -> inString = !inString
                '{' -> if (!inString) {
                    if (startIndex == -1) startIndex = index
                    braceCount++
                }
                '}' -> if (!inString && startIndex != -1) {
                    braceCount--
                    if (braceCount == 0) {
                        val obj = input.substring(startIndex, index + 1)
                        json.runCatching { parseToJsonElement(obj) }
                            .getOrNull()
                            ?.let { results.add(obj) }
                        startIndex = -1
                    }
                }
            }
        }
        return results
    }

    private fun extractFirstJsonObject(input: String): String? {
        var braceCount = 0
        var inString = false
        var escape = false
        var startIndex = -1

        for ((index, char) in input.withIndex()) {
            if (escape) {
                escape = false
                continue
            }
            when (char) {
                '\\' -> if (inString) escape = true
                '"' -> inString = !inString
                '{' -> if (!inString) {
                    if (startIndex == -1) startIndex = index
                    braceCount++
                }
                '}' -> if (!inString && startIndex != -1) {
                    braceCount--
                    if (braceCount == 0) {
                        return input.substring(startIndex, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun fixIncompleteJson(input: String): String {
        // 尝试找到最后一个完整的 '}'，如果后面还有内容，可能是截断的
        val lastCompleteBrace = input.lastIndexOf('}')
        if (lastCompleteBrace > 0 && lastCompleteBrace < input.length - 1) {
            val afterBrace = input.substring(lastCompleteBrace + 1).trim()
            if (afterBrace.isNotEmpty()) {
                // 截断部分可能是不完整的 JSON，尝试补齐
                val partial = input.substring(0, lastCompleteBrace + 1)
                return partial
            }
        }

        // 如果末尾缺少闭合括号
        var braceCount = 0
        var inString = false
        var escape = false
        for (char in input) {
            if (escape) {
                escape = false
                continue
            }
            when (char) {
                '\\' -> if (inString) escape = true
                '"' -> inString = !inString
                '{' -> braceCount++
                '}' -> if (braceCount > 0) braceCount--
            }
        }

        if (braceCount > 0 || inString) {
            return buildString {
                append(input)
                if (inString) append("\"")
                repeat(braceCount) { append("}") }
            }
        }

        return input
    }

    private fun mergeJsonObjects(jsonStrings: List<String>): JsonObject {
        require(jsonStrings.isNotEmpty()) { "Empty JSON object list" }
        if (jsonStrings.size == 1) {
            return json.parseToJsonElement(jsonStrings.first()).jsonObject
        }

        val merged = mutableMapOf<String, JsonElement>()
        jsonStrings.forEach { str ->
            val obj = json.parseToJsonElement(str).jsonObject
            obj.forEach { (key, value) ->
                merged[key] = when {
                    merged.containsKey(key) && merged[key] is JsonObject && value is JsonObject -> {
                        val existing = merged[key]!!.jsonObject
                        val newMap = existing.toMutableMap()
                        value.jsonObject.forEach { (k, v) -> newMap[k] = v }
                        JsonObject(newMap)
                    }
                    else -> value
                }
            }
        }
        return JsonObject(merged)
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
