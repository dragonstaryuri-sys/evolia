package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import me.rerere.ai.provider.Modality
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
import me.rerere.ai.ui.isCallDecisionRequest
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.truncate
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.data.planner.PlannerFacade
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.tools.TemporarySessionToolRules
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
import me.rerere.rikkahub.data.ai.transformers.AudioToTextTransformer
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

/**
 * 一次独立用户请求的动态上下文快照（上下文快照复用）。
 *
 * 在 [GenerationHandler.generateText] 的工具调用循环开始前构建一次，循环内所有模型请求复用：
 * - **固定**：系统提示（助手档案/用户档案/里程碑/日程/日记/世界书激活结果/工具提示词）、
 *   L1 近期片段、L2 当日跨会话记忆、RAG 记忆注入、TimeSense（当前时间/间隔）、正在进行的日程。
 * - **不固定**：消息历史（每轮基于最新 currentMessages 重新选取）、工具执行结果。
 *
 * 收益：工具循环内不再重复执行世界书 RAG 嵌入、记忆/日程/日记 DB 查询；同一轮请求内
 * 动态上下文完全一致（TimeSense 不随工具轮次漂移），system prompt 前缀稳定可命中 Prefix Caching。
 * 快照绑定本轮原始用户请求：dynamicContext 只注入 [anchorUserMessageId] 对应的消息，
 * 不随工具执行期间追加的额外 USER 消息（如拍照注入）漂移。
 */
data class GenerationContextSnapshot(
    val staticSystemPrompt: String,
    val summarySystemPrompt: String,
    val allContextAttachments: List<UIMessagePart>,
    /** 注入到锚点 USER 消息中的动态上下文全文（梗概/记忆/变量/时间/日程） */
    val dynamicContext: String,
    /** 动态上下文锚点：本轮原始用户请求消息 id */
    val anchorUserMessageId: Uuid?,
    val activatedLorebookEntries: List<UsedLorebookEntry>,
    val usedModes: List<UsedMode>,
    val usedMemories: List<UsedMemory>,
)

/**
 * 组装最后一条 USER 消息的请求 parts：注入动态上下文文本，并按模型音频输入能力决定
 * Audio Part 的去留（纯函数，不依赖 Android API，便于单元测试）。
 *
 * 音频处理策略（[supportsAudioInput] = model.inputModalities 含 Modality.AUDIO）：
 * - **支持音频输入**：Audio Part 保留原始音频随请求下发（当轮真正"听语音"）；
 *   转写文本**不**拼入 prompt（避免与音频内容重复）；如有 Quote 引用，仅输出引用结构文本。
 * - **不支持音频输入**：
 *   - 有转写（metadata.transcription）：沿用文本路径，转写拼入 prompt、剔除 Audio Part（原行为）；
 *   - 无转写（ASR 失败/未完成）：保留 Audio Part，交由 [AudioToTextTransformer] 输出失败
 *     降级文案，不伪造转写内容。
 *
 * 文本组装沿用既有规则：Quote 与紧随其后的 Text/Audio 配对，
 * 产出格式：`引用X刚才说的内容：\n> content\n用户回复：text`。
 * dynamicContext 与文本均为空时不再塞入空 Text Part（修复 ASR 失败时当轮语音消息
 * 被替换成空消息的问题）。
 *
 * @param parts 原始消息 parts
 * @param supportsAudioInput 当前模型是否支持音频输入（Modality.AUDIO）
 * @param dynamicContext 动态上下文（L2 记忆、变量、时间等），可为空
 * @return 重组后的 parts
 */
internal fun buildLastUserMessageParts(
    parts: List<UIMessagePart>,
    supportsAudioInput: Boolean,
    dynamicContext: String,
): List<UIMessagePart> {
    fun audioTranscriptionOf(part: UIMessagePart.Audio): String? =
        part.metadata
            ?.get(AudioToTextTransformer.METADATA_TRANSCRIPTION)
            ?.let { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.isNotBlank() }

    // Audio Part 去留判定（非保留的 Text/Quote 已转为 builtText，其余类型原样透传）
    val otherParts = parts.filter { part ->
        when (part) {
            is UIMessagePart.Text, is UIMessagePart.Quote -> false
            is UIMessagePart.Audio -> {
                val transcription = audioTranscriptionOf(part)
                supportsAudioInput || transcription == null
            }
            else -> true
        }
    }

    val builtText = buildString {
        var pendingQuote: UIMessagePart.Quote? = null
        var first = true
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Quote -> {
                    pendingQuote = part
                }
                is UIMessagePart.Text -> {
                    val quote = pendingQuote
                    if (quote != null) {
                        if (!first) append("\n")
                        val referenceTarget = if (quote.isUser) "我" else "你"
                        append("引用")
                        append(referenceTarget)
                        append("刚才说的内容：\n> ")
                        append(quote.content.replace("\n", "\n> "))
                        append("\n用户回复：")
                        append(part.text)
                        pendingQuote = null
                    } else {
                        if (!first) append("\n")
                        append(part.text)
                    }
                    first = false
                }
                is UIMessagePart.Audio -> {
                    val quote = pendingQuote
                    val audioTranscription = audioTranscriptionOf(part)
                    // 仅"模型不支持音频 && 有转写"时走文本路径
                    val convertToText = !supportsAudioInput && audioTranscription != null
                    if (convertToText) {
                        if (quote != null) {
                            if (!first) append("\n")
                            val referenceTarget = if (quote.isUser) "我" else "你"
                            append("引用")
                            append(referenceTarget)
                            append("刚才说的内容：\n> ")
                            append(quote.content.replace("\n", "\n> "))
                            append("\n用户回复：")
                            append(audioTranscription)
                            pendingQuote = null
                        } else {
                            // 无引用的语音消息：直接使用转写文本
                            if (!first) append("\n")
                            append(audioTranscription)
                        }
                    } else if (quote != null) {
                        // Audio 保留原始音频：仅输出引用结构文本，不拼转写（避免与音频内容重复）
                        if (!first) append("\n")
                        val referenceTarget = if (quote.isUser) "我" else "你"
                        append("引用")
                        append(referenceTarget)
                        append("刚才说的内容：\n> ")
                        append(quote.content.replace("\n", "\n> "))
                        pendingQuote = null
                    }
                    first = false
                }
                else -> {}
            }
        }
    }

    val newTextPart = if (dynamicContext.isNotBlank()) {
        UIMessagePart.Text(
            text = buildString {
                //尝试给system消息放后面看影不影响回复
                append(builtText)
                appendLine("System:")
                append("[")
                append(dynamicContext)
                append("]")

            }
        )
    } else {
        UIMessagePart.Text(text = builtText)
    }
    return if (newTextPart.text.isNotBlank()) {
        listOf(newTextPart) + otherParts
    } else {
        otherParts
    }
}

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
    private val plannerFacade: PlannerFacade,
) {
    companion object {
        /**
         * 语音通话期间禁止执行的工具：
         * 图片生成 / Python / JS 引擎产出的内容（图片、文件、控制台输出）无法在通话中呈现，
         * 拦截后以错误 ToolResult 提示 AI 改为口头告知用户，通话结束后再使用。
         */
        private val CALL_BLOCKED_TOOLS = setOf("generate_image", "eval_python", "eval_javascript")

        /**
         * 临时会话禁止执行的工具名黑名单（TemporarySessionToolRules 唯一事实源）：
         * 装配层已按白名单过滤不注册这些工具，此处为执行层兜底拦截，
         * 防止模型幻觉调用已下线工具产生正式数据或现实副作用（含记忆写入类）。
         */
        private val TEMPORARY_BLOCKED_TOOLS = TemporarySessionToolRules.blockedToolNames

        /** 瞬态断线自动重试前的等待间隔，给网络栈（如 Wi-Fi 重连）留出恢复时间 */
        private const val TRANSIENT_NETWORK_RETRY_DELAY_MS = 1000L
    }

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
        lastArchivedMessageTime: Long = 0L,
        maxSteps: Int = 256,
        enabledModeIds: Set<Uuid> = emptySet(),
        contextSummary: String? = null,
        temporarySummaries: List<String> = emptyList(),
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = false,
        responseMessageSource: MessageSource = MessageSource.NORMAL,
        conversationId: Uuid? = null,
        isCallMode: () -> Boolean = { false },
        isTemporarySession: () -> Boolean = { false },
        callId: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        // 请求级标识：关联同一次用户请求内的多个工具调用轮次（日志用，不含用户内容）
        val requestTag = "req-" + Uuid.random().toString().take(8)

        // ===== 上下文快照（Part 1）：一次独立用户请求只构建一次动态上下文 =====
        // 世界书激活（含 RAG 嵌入）、L1/L2 记忆、日程、日记、TimeSense 等全部在此计算一次，
        // 工具调用循环内所有模型请求复用该快照；消息历史与工具结果每轮仍基于最新数据组装。
        val contextSnapshot = computeContextSnapshot(
            assistant = assistant,
            settings = settings,
            messages = messages,
            model = model,
            tools = tools,
            memories = memories ?: emptyList(),
            lastArchivedMessageTime = lastArchivedMessageTime,
            enabledModeIds = enabledModeIds,
            contextSummary = contextSummary,
            temporarySummaries = temporarySummaries,
            includeSkipContextMessages = includeSkipContextMessages,
            conversationId = conversationId,
            isCallMode = isCallMode(),
        )
        Log.i(
            TAG,
            "[$requestTag] context snapshot built: dynamicContext=${contextSnapshot.dynamicContext.length} chars, " +
                "staticPrompt=${contextSnapshot.staticSystemPrompt.length} chars, anchorUser=${contextSnapshot.anchorUserMessageId}"
        )

        var currentMessages: List<UIMessage> = messages

        // ===== 工具定义本轮固定（Part 2.6）=====
        // 工具列表/Schema/描述在整轮请求内不变；执行闭包内部仍按需读取最新业务状态
        // （DB/Settings 查询发生在 execute 时，不在构建时）。
        val toolsInternal = buildList {
                Log.d(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF) {
                    // 临时会话记忆只读：不注册 create/edit/delete_memory 写工具，
                    // 即使模型幻觉调用也会被下方 TEMPORARY_BLOCKED_TOOLS 执行层拦截
                    if (!isTemporarySession()) {
                        buildMemoryTools(
                            assistantId = assistant.id.toString(),
                            onCreation = { content ->
                                val relevant = memoryRepo.retrieveRelevantMemoriesWithScores(
                                    assistantId = assistant.id.toString(),
                                    query = content,
                                    limit = 3,
                                    similarityThreshold = 0.8f,
                                    mode = assistant.memoryRetrievalMode
                                )

                                // 检索候选同时包含 core 记忆与 L1 segment，只有 core 记忆可被
                                // create_memory 的相似合并路径更新；直接拿 segment id 查 memories 表
                                // 会得到 null（IllegalStateException: Memory not found），
                                // 或与 core 记忆 id 撞号时静默覆盖错误记忆
                                val existing = relevant.firstOrNull { it.first.type == MemoryType.CORE }
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
                    }

                    add(
                        Tool(
                            name = "retrieve_memory",
                            description = "通过关键词同时检索核心记忆(core)与片段记忆(segment)。返回结果中 type=core 的条目内容已完整，无需再调取；type=segment 的条目可传入其 id 作为 segment_id 调取完整消息历史记录。也可通过时间范围查询该智能体下一段时间内的所有片段记忆（一次最多返回一周，建议返回一天）。支持组合查询：同时传入 start_time/end_time 与 key_words 时，会先按时间范围圈定 segment，再用关键词对结果做本地过滤，仅返回时间范围内命中关键词的片段。",
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
                                        put("key_words_only", buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "纯关键词匹配检索（不走向量/语义/Rerank）：提供需要检索的关键词，多个关键词用英文逗号分隔。只要记忆的 content 或 keywords 字段包含其中任一关键词即视为命中（OR 逻辑）。可与 start_time/end_time 组合使用，检索某时间段内包含关键词的记忆片段。当只需简单关键词命中而无需语义召回时，优先使用本参数而非 key_words。"
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
                                val keyWordsOnly = args["key_words_only"]?.jsonPrimitive?.contentOrNull
                                val startTimeStr = args["start_time"]?.jsonPrimitive?.contentOrNull
                                val endTimeStr = args["end_time"]?.jsonPrimitive?.contentOrNull

                                when {
                                    // 纯关键词匹配检索（可选叠加时间范围）：不走 RAG，仅做 contains 命中
                                    keyWordsOnly != null -> {
                                        try {
                                            val start: LocalDateTime? = startTimeStr?.let { parseFlexibleDate(it) }
                                            val end: LocalDateTime? = endTimeStr?.let { parseFlexibleDate(it) }

                                            // 校验：若提供时间则必须成对，且跨度不超过一周
                                            if (start != null && end != null) {
                                                val duration = Duration.between(start, end)
                                                if (duration.toHours() > 24 * 7) {
                                                    return@Tool buildJsonObject {
                                                        put("error", JsonPrimitive("一次最多只能查询一周的记忆片段"))
                                                    }
                                                }
                                            } else if (start != null || end != null) {
                                                return@Tool buildJsonObject {
                                                    put("error", JsonPrimitive("使用时间范围时必须同时提供 start_time 和 end_time"))
                                                }
                                            }

                                            val startTimeLong = start?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                                            val endTimeLong = end?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

                                            val relevant = memoryRepo.retrieveMemoriesByKeywordsOnly(
                                                assistantId = assistant.id.toString(),
                                                keyWords = keyWordsOnly,
                                                limit = assistant.ragLimit,
                                                startTime = startTimeLong,
                                                endTime = endTimeLong
                                            )
                                            buildJsonObject {
                                                put("results", JsonArray(relevant.map { mem ->
                                                    val isCore = mem.type == MemoryType.CORE
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive(if (isCore) "core" else "segment"))
                                                        put("id", JsonPrimitive(mem.id))
                                                        put("time", JsonPrimitive(formatMemoryDate(mem.timestamp)))
                                                        put("content", JsonPrimitive(mem.content))
                                                    }
                                                }))
                                                put("total", JsonPrimitive(relevant.size))
                                            }
                                        } catch (e: Exception) {
                                            buildJsonObject {
                                                put("error", JsonPrimitive("纯关键词检索失败: ${e.message}"))
                                            }
                                        }
                                    }

                                    // 组合检索:时间范围 + 关键词,先按时间范围取 segment,再用关键词对结果做本地过滤
                                    startTimeStr != null && endTimeStr != null && keyWords != null -> {
                                        try {
                                            val start = parseFlexibleDate(startTimeStr)
                                            val end = parseFlexibleDate(endTimeStr)
                                            Log.d(TAG, "DEBUG: start=$start, end=$end, keywords=$keyWords")

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

                                            // 拆分关键词,与 calculateKeywordScore 一致使用同一分隔符集
                                            val keywordsList = keyWords
                                                .split(Regex("[,，、\\s]"))
                                                .map { it.trim().lowercase() }
                                                .filter { it.isNotBlank() }

                                            // 本地过滤:对 segment 的 content 和 keywords 字段做双向 contains 匹配(OR 命中即保留)
                                            val filtered = if (keywordsList.isEmpty()) {
                                                segments
                                            } else {
                                                segments.filter { seg ->
                                                    val contentLower = seg.content.lowercase()
                                                    val keywordsFieldLower = seg.keywords?.lowercase().orEmpty()
                                                    keywordsList.any { kw ->
                                                        contentLower.contains(kw) || keywordsFieldLower.contains(kw)
                                                    }
                                                }
                                            }

                                            buildJsonObject {
                                                put("results", JsonArray(filtered.map { seg ->
                                                    buildJsonObject {
                                                        put("segment_id", JsonPrimitive(seg.id))
                                                        val timeRangeStr = "${formatMemoryDate(seg.startTime)} ~ ${formatMemoryDate(seg.endTime)}"
                                                        put("time_range", JsonPrimitive(timeRangeStr))
                                                        put("content", JsonPrimitive(seg.content))
                                                    }
                                                }))
                                                put("total_before_filter", JsonPrimitive(segments.size))
                                                put("total_after_filter", JsonPrimitive(filtered.size))
                                            }
                                        } catch (e: Exception) {
                                            buildJsonObject {
                                                put("error", JsonPrimitive("时间格式错误或查询失败: ${e.message}"))
                                            }
                                        }
                                    }

                                    startTimeStr != null && endTimeStr != null -> {
                                        try {
                                            val start = parseFlexibleDate(startTimeStr)
                                            val end = parseFlexibleDate(endTimeStr)
                                            Log.d(TAG, "DEBUG: start=$start, end=$end")

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
                                                        val timeRangeStr = "${formatMemoryDate(seg.startTime)} ~ ${formatMemoryDate(seg.endTime)}"
                                                        put("time_range", JsonPrimitive(timeRangeStr))
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
                                        // 【版本污染修复】改用 getMessagesByTimeRangeForSegment，
                                        // JOIN 节点表按 order_index = select_index 只取当前选中版本，
                                        // 避免同一节点多个版本污染 segment 详情展示，与
                                        // AssistantDetailVM.regenerateSegment 及 ChatService 自动
                                        // summarize 的版本过滤策略保持一致。
                                        val messageEntities = conversationRepo.getMessagesByTimeRangeForSegment(
                                            convId = chatSegment.conversationId,
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
                                            JsonPrimitive("必须传入`segment_id`、`key_words`、`key_words_only`或`start_time/end_time`其中一个参数")
                                        )
                                    }
                                }
                            }
                        )
                    )
                }
                addAll(tools)
        }

        // ===== 工具调用循环保护（Part 3.4）=====
        // 轮次上限 + 相同指纹连续重复检测；拦截时以引导性错误 ToolResult 反馈模型，
        // 保持 Assistant 工具调用与 Tool 消息配对，循环自然结束。
        // 轮次上限取助手配置（用户可在「工具与搜索」页调整），历史/导入数据越界时收敛进合法区间。
        val configuredMaxToolRounds = assistant.maxToolRounds.coerceIn(
            ToolLoopGuard.MIN_TOOL_ROUNDS,
            ToolLoopGuard.MAX_TOOL_ROUNDS
        )
        val toolLoopGuard = ToolLoopGuard(maxToolRounds = configuredMaxToolRounds)
        var toolRound = 0

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "[$requestTag] streamText: start step #$stepIndex (${model.id})")

            generateInternal(
                assistant = assistant,
                settings = settings,
                messages = currentMessages,
                onUpdateMessages = { updatedFromChunk ->
                    // 根据 skipContextForResponse 决定是否隐藏 AI 的回复
                    val processedMessages = if (skipContextForResponse) {
                        updatedFromChunk.mapIndexed { index, uiMessage ->
                            if (index == updatedFromChunk.lastIndex && uiMessage.role == CoreMessageRole.ASSISTANT) {
                                uiMessage.copy(skipContext = true, messageSource = responseMessageSource, callId = callId)
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
                lastArchivedMessageTime = lastArchivedMessageTime,
                stream = assistant.streamOutput,
                includeSkipContextMessages = includeSkipContextMessages,
                responseMessageSource = responseMessageSource,
                conversationId = conversationId,
                isCallMode = isCallMode,
                callId = callId,
                contextSnapshot = contextSnapshot,
                requestTag = requestTag,
                round = stepIndex
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

            toolRound++
            // ===== 循环保护决策（Part 3.4）：轮次上限 + 相同指纹连续重复检测 =====
            // 不同参数的连续调用（如换关键词的再次搜索）不受影响；仅拦截轮次超限与
            // 「相同工具+相同参数」的连续异常重复。
            val callFingerprints = toolCalls.map { toolCall ->
                val normalizedArgs = runCatching {
                    sanitizeToolCallArguments(toolCall.arguments.ifBlank { "{}" })
                }.getOrDefault("{}").trim()
                ToolLoopGuard.ToolCallFingerprint(
                    toolName = toolCall.toolName,
                    normalizedArguments = normalizedArgs
                )
            }
            val callDecisions = toolLoopGuard.evaluateRound(callFingerprints)

            val results = arrayListOf<UIMessagePart.ToolResult>()
            toolCalls.forEachIndexed { callIndex, toolCall ->
                val decision = callDecisions[callIndex]
                if (decision is ToolLoopGuard.CallDecision.Block) {
                    Log.w(
                        TAG,
                        "[$requestTag] ToolCall round=$toolRound tool=${toolCall.toolName} " +
                            "id=${toolCall.toolCallId} blocked by loop guard"
                    )
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        content = buildJsonObject {
                            put("error", JsonPrimitive(decision.reason))
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                        }.getOrElse { buildJsonObject {} },
                        metadata = toolCall.metadata
                    )
                    return@forEachIndexed
                }

                // 临时会话拦截记忆写入类工具（执行层兜底）：临时会话记忆只读，
                // 返回明确错误让 AI 改为口头说明，绝不产生正式记忆数据。
                if (isTemporarySession() && toolCall.toolName in TEMPORARY_BLOCKED_TOOLS) {
                    Log.i(TAG, "[$requestTag] ToolCall round=$toolRound blocked memory-write tool '${toolCall.toolName}' in temporary session")
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        content = buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive("当前为临时会话（记忆只读），不能创建/修改/删除记忆。本次对话内容不会保存，请直接用文字回复用户。")
                            )
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                        }.getOrElse { buildJsonObject {} },
                        metadata = toolCall.metadata
                    )
                    return@forEachIndexed
                }

                // 通话中拦截重计算/产媒体类工具：产物无法在通话中呈现，
                // 直接返回错误结果让 AI 改为口头告知用户，避免执行后输出无处消费。
                if (isCallMode() && toolCall.toolName in CALL_BLOCKED_TOOLS) {
                    Log.i(TAG, "[$requestTag] ToolCall round=$toolRound blocked tool '${toolCall.toolName}' during call")
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        content = buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive("通话过程中不能调用此工具，请结束通话后再使用。请用语音简要告知用户。")
                            )
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                        }.getOrElse { buildJsonObject {} },
                        metadata = toolCall.metadata
                    )
                    return@forEachIndexed
                }

                runCatching {
                    val tool = toolsInternal.find { t -> t.name == toolCall.toolName }
                    if (tool == null) {
                        // 工具不存在：以明确的结构化错误反馈，让模型可自行纠正（而非异常堆栈）
                        Log.w(
                            TAG,
                            "[$requestTag] ToolCall round=$toolRound tool not found: " +
                                "${toolCall.toolName} id=${toolCall.toolCallId}"
                        )
                        results += UIMessagePart.ToolResult(
                            toolName = toolCall.toolName,
                            toolCallId = toolCall.toolCallId,
                            content = buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive("工具 ${toolCall.toolName} 不存在或当前不可用。请检查工具名称是否正确，或改用其他方式完成任务。")
                                )
                            },
                            arguments = runCatching {
                                json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                            }.getOrElse { buildJsonObject {} },
                            metadata = toolCall.metadata
                        )
                        return@runCatching
                    }

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

                    val toolStartAt = System.currentTimeMillis()
                    val result = tool.execute(args)
                    val toolCostMs = System.currentTimeMillis() - toolStartAt
                    val resultText = result.toString()
                    // 空结果判定：null/空白字符串视为"执行成功但无内容"，规范化为明确说明；
                    // 结构化空对象/空数组原样保留（可能表达"无结果"的业务语义），由模型自行解读
                    val isEmptyResult = result is JsonNull ||
                        (result is JsonPrimitive && result.isString && result.content.isBlank())

                    Log.i(
                        TAG,
                        "[$requestTag] ToolCall round=$toolRound tool=${tool.name} id=${toolCall.toolCallId} " +
                            "duration=${toolCostMs}ms resultLength=${resultText.length} empty=$isEmptyResult"
                    )

                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        content = if (isEmptyResult) {
                            buildJsonObject {
                                put("note", JsonPrimitive("工具执行成功，但没有返回任何内容。"))
                            }
                        } else {
                            result
                        },
                        arguments = args,
                        metadata = toolCall.metadata
                    )
                }.onFailure {
                    it.printStackTrace()
                    Log.e(
                        TAG,
                        "[$requestTag] ToolCall round=$toolRound tool=${toolCall.toolName} " +
                            "id=${toolCall.toolCallId} failed: ${it.message}"
                    )
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        metadata = toolCall.metadata,
                        content = buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive(buildString {
                                    append("[${it.javaClass.name}] ${it.message}")
                                    append("\n${it.stackTraceToString().take(1200)}")
                                })
                            )
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                        }.getOrElse { buildJsonObject {} }
                    )
                }
            }

            // ===== 工具结果追加核验日志（Part 3.3）：确认所有调用都拿到了结果 =====
            Log.i(
                TAG,
                "[$requestTag] round=$toolRound appended ${results.size}/${toolCalls.size} tool results " +
                    "ids=[${results.joinToString(",") { it.toolCallId }}]"
            )
            if (results.isEmpty()) {
                // 防御：每条工具调用都应有对应结果（执行/拦截/失败路径均会追加）。
                // 空结果消息会造成 role=tool 无内容的协议违规，此处直接结束循环并提示。
                Log.e(TAG, "[$requestTag] round=$toolRound no tool results produced, aborting tool loop")
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
                break
            }
            currentMessages = currentMessages + UIMessage(
                role = CoreMessageRole.TOOL,
                parts = results,
                // ★ 工具结果必须跟随 responseMessageSource：
                //   通话中 skipContextForResponse=true 会让本条 skipContext=true，
                //   若 messageSource 仍为默认 NORMAL，会被「只放行 CALL/CALL_STATE」的
                //   上下文白名单过滤掉，导致 AI 看不到工具结果而反复调用同一工具。
                skipContext = skipContextForResponse,
                messageSource = responseMessageSource
            )

            // camera_capture 工具特殊处理：把拍下的照片作为一条额外 user 图片消息注入，
            // 因为 OpenAI provider 的 role=tool 消息 content 只输出纯字符串，无法直接携带图片。
            // 这样下一轮 AI 生成时既能看到 tool result JSON，又能直接看到照片本身。
            // 注意：这条消息的 Text part 带 camera_capture_injected 标记，UI 层会跳过渲染，
            // 照片本身会在 AI 的工具结果区域（assistant 气泡）展示，避免在用户侧重复显示。
            val cameraResult = results.firstOrNull { it.toolName == "camera_capture" }
            if (cameraResult != null) {
                val contentObj = cameraResult.content as? JsonObject
                val success = contentObj?.get("success")?.jsonPrimitive?.contentOrNull == "true"
                val imagePath = contentObj?.get("image_path")?.jsonPrimitive?.contentOrNull
                val facing = contentObj?.get("facing")?.jsonPrimitive?.contentOrNull ?: "back"
                if (success && !imagePath.isNullOrBlank()) {
                    val imageFile = java.io.File(imagePath)
                    if (imageFile.exists()) {
                        val facingText = if (facing == "front") "前置" else "后置"
                        currentMessages = currentMessages + UIMessage(
                            role = CoreMessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Text(
                                    text = "（这是你刚才调用user的${facingText}摄像头拍摄的照片）",
                                    metadata = buildJsonObject { put("camera_capture_injected", true) }
                                ),
                                UIMessagePart.Image(url = "file://$imagePath")
                            )
                        )
                        Log.i(TAG, "camera_capture: injected photo as user image message: $imagePath")
                    } else {
                        Log.w(TAG, "camera_capture: image file not found, skip injection: $imagePath")
                    }
                }
            }

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

    /**
     * 组装单次模型请求的 messages（工具调用循环内每轮调用）。
     *
     * 动态上下文（系统提示/记忆/世界书/TimeSense 等）来自 [snapshot]，本轮内固定复用；
     * 本函数每轮仍基于**最新**消息历史重新执行：
     * - skipContext 过滤、maxHistoryMessages 裁剪（limitContext 保持工具配对）、旧搜索结果修剪；
     * - Token 预算分配（仅消息；记忆已冻结进快照的动态上下文）；
     * - 冻结的动态上下文注入到本轮锚点 USER 消息（[GenerationContextSnapshot.anchorUserMessageId]，
     *   即本轮原始用户请求，不随工具执行期间追加的额外 USER 消息漂移）。
     */
    suspend fun buildMessages(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        model: Model,
        lastArchivedMessageTime: Long,
        includeSkipContextMessages: Boolean = false,
        isCallMode: Boolean = false,
        snapshot: GenerationContextSnapshot,
    ): BuildMessagesResult {
        fun estimateTokens(text: String) = text.length / 4
        fun estimateTokens(message: UIMessage) = estimateTokens(message.toText())

        val maxTokens = assistant.maxTokenUsage
        val currentTokens = estimateTokens(snapshot.staticSystemPrompt) + estimateTokens(snapshot.summarySystemPrompt)

        // ===== 历史消息选取（每轮基于最新 messages 重新执行）=====
        val searchPrunedMessages = prepareHistoryCandidates(
            assistant = assistant,
            messages = messages,
            lastArchivedMessageTime = lastArchivedMessageTime,
            includeSkipContextMessages = includeSkipContextMessages,
            isCallMode = isCallMode,
        )

        // ===== Token 预算分配（仅消息；记忆已冻结进快照动态上下文，不再参与每轮分配）=====
        val selectedMessages = mutableListOf<UIMessage>()
        val remainingTokens = maxTokens - currentTokens
        if (remainingTokens <= 0) {
            Log.w(TAG, "buildMessages: System prompt exceeds max tokens!")
        }

        val minChatHistory = 4.coerceAtMost(searchPrunedMessages.size)
        var usedTokens = 0
        searchPrunedMessages.take(minChatHistory).forEach {
            selectedMessages.add(it)
            usedTokens += estimateTokens(it)
        }

        var availableTokens = remainingTokens - usedTokens
        if (availableTokens > 0) {
            // 记忆不参与每轮预算，消息按时间序尽量多带（等价于原 CHAT_HISTORY 优先策略）
            for (msg in searchPrunedMessages.drop(minChatHistory)) {
                val cost = estimateTokens(msg)
                if (availableTokens >= cost) {
                    selectedMessages.add(msg)
                    availableTokens -= cost
                } else break
            }
        }

        // ===== 组装 =====
        val builtMessages = buildList {
            // 1. Stable & Semi-stable System Prompt（快照）
            if (snapshot.staticSystemPrompt.isNotBlank()) {
                add(UIMessage.system(snapshot.staticSystemPrompt))
            }

            // 2. Attachments（快照：模式附件 + 世界书附件）
            if (snapshot.allContextAttachments.isNotEmpty()) {
                add(
                    UIMessage(
                        role = CoreMessageRole.USER,
                        parts = snapshot.allContextAttachments
                    )
                )
            }

            // 3. Chat History (L0)：动态上下文注入锚点消息（本轮原始用户请求）
            val sortedSelectedMessages = selectedMessages.sortedBy { messages.indexOf(it) }
            if (snapshot.anchorUserMessageId == null) {
                Log.w(TAG, "buildMessages: no anchor user message in snapshot, dynamic context skipped")
            }
            var anchorInjected = false
            sortedSelectedMessages.forEach { msg ->
                if (msg.id == snapshot.anchorUserMessageId) {
                    anchorInjected = true
                    // 按模型音频输入能力重组锚点消息 parts，并注入冻结的动态上下文
                    val supportsAudioInput = model.inputModalities.contains(Modality.AUDIO)
                    val newParts = buildLastUserMessageParts(
                        parts = msg.parts,
                        supportsAudioInput = supportsAudioInput,
                        dynamicContext = snapshot.dynamicContext,
                    )
                    add(msg.copy(parts = newParts))
                } else {
                    add(msg)
                }
            }
            if (snapshot.anchorUserMessageId != null && !anchorInjected) {
                Log.w(
                    TAG,
                    "buildMessages: anchor user message ${snapshot.anchorUserMessageId} not selected by budget, " +
                        "dynamic context not injected this round"
                )
            }
        }

        Log.d(
            TAG,
            "buildMessages: snapshot reused - selectedMessages=${selectedMessages.size}/${searchPrunedMessages.size}, " +
                "dynamicContext=${snapshot.dynamicContext.length} chars"
        )

        return BuildMessagesResult(
            messages = builtMessages,
            activatedLorebookEntries = snapshot.activatedLorebookEntries,
            usedModes = snapshot.usedModes,
            usedMemories = snapshot.usedMemories
        )
    }

    /**
     * 历史消息候选管线（每次模型请求都要基于最新消息重新执行，不进快照）：
     * skipContext 过滤（来源白名单）→ maxHistoryMessages 裁剪（limitContext 保持
     * Assistant 工具调用与 Tool 结果配对）→ 旧搜索结果修剪，最后反转为"最新在前"。
     */
    private fun prepareHistoryCandidates(
        assistant: Assistant,
        messages: List<UIMessage>,
        lastArchivedMessageTime: Long,
        includeSkipContextMessages: Boolean,
        isCallMode: Boolean,
    ): List<UIMessage> {
        // ===== skipContext 过滤（按消息来源白名单决定截断/剔除策略）=====
        val contextCandidates = if (includeSkipContextMessages) {
            // 包含 skipContext 消息，根据消息来源决定截断策略：
            // - AGENT_TASK: 完整带入，不截断
            // - DIARY_COMMENT: 完整带入，不截断（用户评论+日记内容是后续对话的重要上下文）
            // - CALL / CALL_STATE: 完整带入，不截断（通话真实转写与通话状态标记必须进入后续上下文）
            // - NORMAL: 历史消息截断到前 100 字，保留最后一条 user 消息完整
            // - CALL_REQUEST: 通话决策指令仅在发起当轮（最后一条 user 消息）可见，
            //   后续对话轮次完全剔除，防止 AI 读到旧指令误回 0/1
            //   （isCallDecisionRequest 同时覆盖旧版本遗留的无 callId 决策消息）
            val lastUserMsgId = messages.lastOrNull { it.role == CoreMessageRole.USER }?.id
            messages
                .filterIndexed { _, msg ->
                    !msg.isCallDecisionRequest() || msg.id == lastUserMsgId
                }
                .map { msg ->
                    val isContextWhitelistedSource = msg.messageSource == MessageSource.AGENT_TASK
                        || msg.messageSource == MessageSource.DIARY_COMMENT
                        || msg.messageSource == MessageSource.CALL
                        || msg.messageSource == MessageSource.CALL_STATE
                    val shouldTruncate = msg.skipContext
                        && !isContextWhitelistedSource
                        && msg.id != lastUserMsgId
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
            // 不包含（大多数）skipContext 消息，但 CALL 真实转写与 CALL_STATE 状态标记
            // 必须按来源白名单保留，纳入后续上下文；普通 skipContext 指令维持原有语义。
            val isCallVisibleSource: (UIMessage) -> Boolean = { msg ->
                msg.messageSource == MessageSource.CALL || msg.messageSource == MessageSource.CALL_STATE
            }
            val filtered = messages.filter { !it.skipContext || isCallVisibleSource(it) }
            // 诊断日志：通话中工具结果若 messageSource!=CALL 会被过滤，导致 AI 看不到结果而反复调用工具
            if (isCallMode) {
                val droppedToolResults = messages.count {
                    it.role == CoreMessageRole.TOOL && it.skipContext && !isCallVisibleSource(it)
                }
                if (droppedToolResults > 0) {
                    Log.w(TAG, "ContextDebug: $droppedToolResults 条 TOOL 结果因 skipContext+非CALL来源被过滤，AI 将看不到这些工具结果！")
                }
                Log.i(TAG, "ContextDebug: isCallMode=$isCallMode, 输入${messages.size}条 → 过滤后${filtered.size}条, dropToolResults=$droppedToolResults")
            }
            filtered
        }
        Log.d("ContextDebug", "--- Context Build Start ---")
        Log.d("ContextDebug", "Incoming lastArchivedMessageTime: $lastArchivedMessageTime")
        Log.d("ContextDebug", "Original candidates size: ${contextCandidates.size}")
        Log.d("ContextDebug", "first messages: ${messages.firstOrNull()?.id}.take(50)")

        // selectMessagesForGeneration 已在 ChatService 层按 lastArchivedMessageTime 过滤，
        // 这里保留二次过滤防御位但实际 no-op，避免破坏既有链路。
        val chatHistoryCandidates = contextCandidates
            .let { list -> if (lastArchivedMessageTime == 0L) list else list }
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

        return searchPrunedMessages
    }

    /**
     * 构建一次独立用户请求的动态上下文快照（上下文快照复用，工具调用循环开始前调用一次）。
     *
     * 包含所有「本轮固定」的上下文计算：世界书激活（含 RAG 嵌入查询）、系统提示
     * （助手档案/用户档案/里程碑/关系档案/日程/日记/工具提示词）、L1 近期片段、
     * L2 当日跨会话记忆、RAG 记忆注入、TimeSense、正在进行的日程、模式与世界书附件。
     * 消息历史只用于确定本轮记忆预算分配（以首轮历史为准），不随工具轮次重新计算。
     */
    private suspend fun computeContextSnapshot(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        model: Model,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        lastArchivedMessageTime: Long,
        enabledModeIds: Set<Uuid> = emptySet(),
        contextSummary: String? = null,
        temporarySummaries: List<String> = emptyList(),
        includeSkipContextMessages: Boolean = false,
        conversationId: Uuid? = null,
        isCallMode: Boolean = false,
    ): GenerationContextSnapshot {
        fun estimateTokens(text: String) = text.length / 4
        fun estimateTokens(message: UIMessage) = estimateTokens(message.toText())

        val maxTokens = assistant.maxTokenUsage
        var currentTokens = 0

        fun getLorebookEntryActivationReason(
            entry: LorebookEntry,
            messageTexts: List<String>,
            queryEmbedding: List<Float>? = null
        ): String? {
            if (!entry.enabled) return null
            // Each entry scans its own scanDepth window of recent messages
            val scanWindow = messageTexts.takeLast(entry.scanDepth.coerceAtLeast(1))
            return when (entry.activationType) {
                LorebookActivationType.ALWAYS -> context.getString(R.string.activation_always)
                LorebookActivationType.KEYWORDS -> {
                    val searchText = scanWindow.joinToString(" ")
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
                        val threshold = entry.ragSimilarityThreshold.coerceIn(0f, 1f)
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

        val messageTextsForScan = messages.map { it.toText() }
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
        val ragEntries = lorebooksForAssistant
            .flatMap { lorebook -> lorebook.entries }
            .filter { it.enabled && it.activationType == LorebookActivationType.RAG }

        // RAG query window = the last min(scanDepth, 3) messages within each entry's scan window.
        // Deduplicate by window size so each distinct window only costs one embedding call.
        val queryEmbeddingsByWindowSize = mutableMapOf<Int, List<Float>>()
        if (ragEntries.isNotEmpty()) {
            val windowSizes = ragEntries.map { minOf(it.scanDepth.coerceAtLeast(1), 3) }.distinct()
            for (windowSize in windowSizes) {
                val queryText = messageTextsForScan.takeLast(windowSize).joinToString("\n")
                if (queryText.isBlank()) continue
                try {
                    queryEmbeddingsByWindowSize[windowSize] = embeddingService.embed(queryText, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to compute query embedding for RAG (windowSize=$windowSize)", e)
                }
            }
        }

        data class ActivatedEntryWithLorebook(
            val lorebook: Lorebook,
            val entry: LorebookEntry,
            val entryIndex: Int,
            val reason: String
        )

        val activatedEntriesWithLorebook = lorebooksForAssistant
            .flatMap { lorebook ->
                lorebook.entries.mapIndexedNotNull { index, entry ->
                    val queryEmbedding = if (entry.activationType == LorebookActivationType.RAG) {
                        queryEmbeddingsByWindowSize[minOf(entry.scanDepth.coerceAtLeast(1), 3)]
                    } else null
                    val reason = getLorebookEntryActivationReason(entry, messageTextsForScan, queryEmbedding)
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
                    if (extendedState.appearance.isNotBlank()) add("外貌: ${extendedState.appearance}")
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
                val voiceEnabled = settings.getEffectiveDisplaySetting(assistant).aiVoiceMessageEnabled
                summaryPromptBuilder.appendLine("\n## 回复规范 (最高优先级 - 必须执行)")
                summaryPromptBuilder.appendLine("- 说话口语化、有活人感，回复简短直接，不超过5句话，禁止冷漠无情")
                summaryPromptBuilder.appendLine("- 请不要使用任何动作或神态描写（如 *微笑*、(叹气) 等），而是像在直接与用户对话或发消息，直接说出你的回答或想法")
                if (voiceEnabled) {
                    // 有语音消息版本：保留括号例外说明 + 语音消息标记用法
                    summaryPromptBuilder.appendLine("- 严禁使用任何形式的括号（唯一例外见下条语音消息）。")
                    summaryPromptBuilder.appendLine("- 当你想给用户发语音消息时，在回复最开头加上(语音消息)标记即可，请不要频繁使用，这是唯一允许的括号用法。除此之外不要使用括号。")
                } else {
                    // 无语音消息版本：直接禁止所有括号，不提语音标记
                    summaryPromptBuilder.appendLine("- 严禁使用任何形式的括号。")
                }
            }
        }

        val staticSystemPrompt = staticSystemPromptBuilder.toString()
        val summarySystemPrompt = summaryPromptBuilder.toString()

        currentTokens += estimateTokens(staticSystemPrompt)
        currentTokens += estimateTokens(summarySystemPrompt)

        // 历史候选管线（与 buildMessages 每轮执行的管线一致，此处用于确定本轮记忆预算分配）
        val searchPrunedMessages = prepareHistoryCandidates(
            assistant = assistant,
            messages = messages,
            lastArchivedMessageTime = lastArchivedMessageTime,
            includeSkipContextMessages = includeSkipContextMessages,
            isCallMode = isCallMode,
        )


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

        // ===== 正在进行的日程（快照内只查询一次）=====
        val ongoingScheduleText = if (
            assistant.localTools.any { it is LocalToolOption.TimeSense } &&
            assistant.localTools.any { it is LocalToolOption.ScheduleManagement }
        ) {
            runCatching { plannerFacade.currentOngoingEvents() }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- 「${it.taskTitle}」${it.date} ${it.timeLabel()}" }
        } else {
            null
        }

        // ===== 冻结的动态上下文（梗概/记忆/变量/时间/日程，整轮请求复用）=====
        val dynamicContext = buildDynamicContext(
            assistant = assistant,
            summarySystemPrompt = summarySystemPrompt,
            selectedMemories = selectedMemories,
            ongoingScheduleText = ongoingScheduleText,
            lastAiMessage = messages.lastOrNull { it.role == CoreMessageRole.ASSISTANT },
        )

        // 动态上下文锚点：本轮原始用户请求（最后一条 USER 消息）。
        // 工具执行期间追加的额外 USER 消息（如拍照注入）不会改变锚点。
        val anchorUserMessageId = messages.lastOrNull { it.role == CoreMessageRole.USER }?.id

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

        return GenerationContextSnapshot(
            staticSystemPrompt = staticSystemPrompt,
            summarySystemPrompt = summarySystemPrompt,
            allContextAttachments = allContextAttachments,
            dynamicContext = dynamicContext,
            anchorUserMessageId = anchorUserMessageId,
            activatedLorebookEntries = usedLorebookEntriesList,
            usedModes = usedModesList,
            usedMemories = usedMemoriesList,
        )
    }

    /**
     * 构建注入锚点 USER 消息的动态上下文文本（梗概/记忆/参考变量/时间/日程/语言风格）。
     * 仅在快照构建时调用一次：TimeSense 与"距上条消息间隔"固定为请求开始时刻的值，
     * 工具循环内所有轮次复用同一份文本（不再随轮次漂移，稳定命中 Prefix Caching）。
     */
    private fun buildDynamicContext(
        assistant: Assistant,
        summarySystemPrompt: String,
        selectedMemories: List<AssistantMemory>,
        ongoingScheduleText: String?,
        lastAiMessage: UIMessage?,
    ): String = buildString {
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
        // 正在进行的日程：仅当存在符合条件（今天 && 开始<=当前<结束）的日程时注入
        if (ongoingScheduleText != null) {
            appendLine("## 正在进行的日程")
            appendLine(ongoingScheduleText)
            appendLine()
        }
        if (assistant.languageStyleExamples.isNotEmpty()) {
            appendLine("## 语言风格示例")
            assistant.languageStyleExamples.forEach { example ->
                append("- ").appendLine(example)
            }
        }
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
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return formatter.format(Instant.ofEpochMilli(timestamp))
    }
    fun parseFlexibleDate(dateStr: String): LocalDateTime {
        // 1. 先尝试处理常见的“空格丢失”：如果长度是18位且中间没空格，强行在第10位补个空格
        var sanitized = dateStr.trim()
        if (sanitized.length == 18 && sanitized[10].isDigit()) {
            // 比如 2023-10-2710:00:00 -> 2023-10-27 10:00:00
            sanitized = sanitized.substring(0, 10) + " " + sanitized.substring(10)
        }

        // 2. 兼容 ISO 格式中的 'T'
        sanitized = sanitized.replace("T", " ")

        // 3. 使用通用的 formatter
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return LocalDateTime.parse(sanitized, formatter)
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
        lastArchivedMessageTime: Long,
        stream: Boolean,
        includeSkipContextMessages: Boolean = false,
        responseMessageSource: MessageSource = MessageSource.NORMAL,
        conversationId: Uuid? = null,
        isCallMode: () -> Boolean = { false },
        callId: String? = null,
        contextSnapshot: GenerationContextSnapshot,
        requestTag: String,
        round: Int,
    ) {
        val buildResult = buildMessages(
            assistant = assistant,
            settings = settings,
            messages = messages,
            model = model,
            lastArchivedMessageTime = lastArchivedMessageTime,
            includeSkipContextMessages = includeSkipContextMessages,
            isCallMode = isCallMode(),
            snapshot = contextSnapshot
        )
        val internalMessages = buildResult.messages.transforms(transformers, context, model, assistant)

        // ===== 工具调用链路核验日志（Part 3.3）：每次模型请求发出前检查调用/结果配对 =====
        logToolCallChainSummary(requestTag, round, internalMessages)

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
            },
            // 供 Provider 层派生 OkHttp readTimeout：思考阶段 SSE 可能长时间不下发任何字节，
            // 沿用全局 60s readTimeout 会误断连接；与应用层 chatGenerationTimeoutMinutes 兜底对齐。
            generationTimeoutMinutes = settings.chatGenerationTimeoutMinutes,
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
            // 快照流式开始前的消息状态：瞬态断线重试前回滚，避免半截内容与重新生成的内容拼接
            val preStreamMessages = currentMessages
            // 本步是否已收到过任何 chunk；通话模式下已产出内容（可能已被 TTS 朗读）时不重试，
            // 避免回滚后重新生成的节点被二次朗读
            var receivedAnyChunk = false
            withTransientNetworkRetry(
                canRetry = { !isCallMode() || !receivedAnyChunk },
                onRetry = {
                    currentMessages = preStreamMessages
                    onUpdateMessages(currentMessages)
                },
            ) {
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params,
                ).collect {
                    receivedAnyChunk = true
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
            // 非流式一次性返回，期间无部分产出，直接整请求重试即可
            val chunk = withTransientNetworkRetry {
                providerImpl.generateText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params,
                )
            }
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

    /**
     * 工具调用链路核验日志（每次模型请求发出前）。
     * 只输出状态/数量/ID/内容长度，不输出工具结果内容本身（避免泄漏用户隐私数据）。
     * 用于定位"模型反复调用同一工具"类问题：若 unmatchedCallIds > 0，说明该请求中
     * 某些 Assistant 工具调用没有对应的 Tool 结果，模型将大概率重复发起调用。
     */
    private fun logToolCallChainSummary(requestTag: String, round: Int, internalMessages: List<UIMessage>) {
        if (!me.rerere.rikkahub.BuildConfig.DEBUG) return
        val assistantToolCallIds = internalMessages.flatMap { it.getToolCalls() }.map { it.toolCallId }
        val toolResults = internalMessages.flatMap { it.getToolResults() }
        val resultIds = toolResults.map { it.toolCallId }.toSet()
        val unmatchedCallIds = assistantToolCallIds.filter { it !in resultIds }
        val toolResultContentLength = toolResults.sumOf { it.content.toString().length }
        Log.i(
            TAG,
            "[ToolCallDebug] $requestTag round=$round messages=${internalMessages.size} " +
                "assistantToolCalls=${assistantToolCallIds.size} toolResults=${toolResults.size} " +
                "matchedToolCallIds=${unmatchedCallIds.isEmpty()} " +
                "toolResultContentLength=$toolResultContentLength"
        )
        if (unmatchedCallIds.isNotEmpty()) {
            Log.w(
                TAG,
                "[ToolCallDebug] $requestTag round=$round 以下工具调用缺少对应 Tool 结果，模型可能重复调用: $unmatchedCallIds"
            )
        }
    }

    /**
     * 网络层瞬态中断（连接被系统/代理掐断、重置、DNS 失败、建连超时等）自动重试一次。
     * 仅重试 [isTransientNetworkError] 判定的瞬态错误且 [canRetry] 放行时重试；
     * 第二次仍失败则原样抛出，由上层 translateError 转为友好提示。
     * 读超时不在重试之列（等待已远超预期），交给应用层看门狗统一取消。
     */
    private suspend fun <T> withTransientNetworkRetry(
        canRetry: () -> Boolean = { true },
        onRetry: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        try {
            return block()
        } catch (e: Exception) {
            if (!isTransientNetworkError(e) || !canRetry()) throw e
            Log.w(
                TAG,
                "Transient network error (${e.message}), retrying once in $TRANSIENT_NETWORK_RETRY_DELAY_MS ms"
            )
            onRetry()
            delay(TRANSIENT_NETWORK_RETRY_DELAY_MS)
            return block()
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
