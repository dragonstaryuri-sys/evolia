package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.openaiAudioFormat
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import kotlin.time.Clock

private const val TAG = "ChatCompletionsAPI"

/**
 * Model-ID substrings that identify "strict schema following" models.
 * These models ONLY pass parameters that are explicitly declared in the tool's
 * parameters JSON schema, ignoring parameters that are mentioned in tool
 * descriptions or MCP tool documentation but omitted from the schema.
 *
 * Detection is case-insensitive and matches any substring of the model ID.
 */
private val STRICT_TOOL_SCHEMA_MODEL_KEYWORDS = listOf(
    "glm",      // 智谱 AI / GLM 系列 (glm-4, glm-4-plus, glm-4.5, cogview 等)
    "claude",   // Anthropic Claude (claude-3, claude-3.5 等)
)

/**
 * One-shot instruction injected at the END of the system message.
 * Only injected when: (a) model is a strict-schema model AND (b) the current
 * tool list contains at least one MCP tool.
 */
private const val MCP_FLEXIBLE_PARAMS_GUIDANCE =
    "\n\n【MCP 工具参数灵活度原则】\n" +
        "本对话中名称以 \"mcp_\" 开头的工具来自 MCP 生态。这些工具的实际后端接受的参数通常比它们暴露的 JSON Schema 列表更丰富：如果某工具的描述、其配套文档或对话上下文暗示了其他可用的字段，请**务必将这些字段一并传入**，不要因为该字段没有出现在 parameters 的 properties 列表里就省略。MCP 工具后端会正确识别并处理额外的参数。"

/**
 * Returns true when the modelId matches any of the known strict-schema keywords.
 */
private fun isStrictSchemaModel(modelId: String): Boolean {
    val lowerId = modelId.lowercase()
    return STRICT_TOOL_SCHEMA_MODEL_KEYWORDS.any { keyword ->
        keyword in lowerId
    }
}

/**
 * Returns true if the tool name starts with the MCP prefix ("mcp_").
 * MCP tools are the only tools that typically have incomplete parameter schemas
 * (the MCP server author often skips fields to save tokens, expecting the model
 * to read the free-text description for the rest of the parameters).
 */
private fun isMcpTool(toolName: String): Boolean = toolName.startsWith("mcp_")

/**
 * Model-ID substrings (case-insensitive) that identify reasoning-enabled deployments
 * on aggregator providers (currently SiliconFlow). Aggregators host many models and
 * only the explicitly-marked reasoning variants accept `enable_thinking` / `thinking_*`
 * parameters; standard chat variants (e.g. `Qwen/Qwen2.5-72B-Instruct`) reject the
 * parameter outright with `ValueError: current model does not support parameter enable thinking`.
 *
 * We conservatively gate thinking-parameter emission on these vendors by requiring a
 * known reasoning marker in the model ID, rather than trusting only the model-family
 * `REASONING_MODELS` registry (which is designed for first-party / vendor-native APIs).
 */
private val SILICONFLOW_REASONING_MODEL_MARKERS = listOf(
    "-thinking",          // Qwen / Qwen2.5 / Qwen3 官方推理变体，如 Qwen/Qwen2.5-72B-Instruct-Thinking
    "deepseek",        // DeepSeek系列
    "deepseek-reasoner",  // DeepSeek-Reasoner 别名
    "intern-s1",          // 书生浦语 Intern-S1 推理模型
    "kimi",            // 月之暗面 Kimi K2
    "step-3",             // 阶跃星辰 Step-3
    "glm",
    "minimax"
)

/**
 * Returns true only when the modelId contains a known reasoning-deployment marker for
 * aggregator platforms (SiliconFlow). A `REASONING` ability at the model-family level is
 * necessary but NOT sufficient on SiliconFlow — only specific model deployments accept
 * the `enable_thinking` parameter, and sending it to standard chat models triggers a
 * server-side ValueError.
 */
private fun isSiliconFlowReasoningDeployment(modelId: String): Boolean {
    val lowerId = modelId.lowercase()
    return SILICONFLOW_REASONING_MODEL_MARKERS.any { marker ->
        marker in lowerId
    }
}

/**
 * Adds `additionalProperties: true` to an MCP tool's JSON schema,
 * signaling both the model and the intermediate layers that extra properties
 * are allowed. Returns the parameters object unchanged when it's an MCP tool
 * that already has the flag, or when the tool is a local tool with a complete schema.
 */
private fun enhanceMcpToolParameters(rawParameters: JsonElement): JsonObject {
    val paramsObj = (rawParameters as? JsonObject) ?: buildJsonObject { }
    if (paramsObj.containsKey("additionalProperties")) return paramsObj
    return buildJsonObject {
        paramsObj.forEach { (k, v) -> put(k, v) }
        put("additionalProperties", JsonPrimitive(true))
    }
}

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val proxyClient = client.configureClientWithProxy(providerSetting.proxy)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey)}")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = proxyClient.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choices = bodyJson["choices"]?.jsonArrayOrNull
        val choice = choices?.getOrNull(0)?.jsonObject ?: error("choices is null or empty")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(message),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val startTime = System.currentTimeMillis()
        var firstTokenReceived = false

        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val proxyClient = client.configureClientWithProxy(providerSetting.proxy)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey)}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: start request, body size: ${json.encodeToString(requestBody).length}")

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "onOpen: Connection established in ${System.currentTimeMillis() - startTime}ms")
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (!firstTokenReceived) {
                    firstTokenReceived = true
                    Log.d(TAG, "onEvent: First chunk received in ${System.currentTimeMillis() - startTime}ms")
                }

                if (data == "[DONE]") {
                    close()
                    return
                }

                try {
                    data
                        .trim()
                        .split("\n")
                        .filter { it.isNotBlank() }
                        .map { json.parseToJsonElement(it).jsonObject }
                        .forEach {
                            if (it["error"] != null) {
                                val error = it["error"]!!.parseErrorDetail()
                                throw error
                            }
                            val id = it["id"]?.jsonPrimitive?.contentOrNull ?: ""
                            val model = it["model"]?.jsonPrimitive?.contentOrNull ?: ""

                            val choices = it["choices"]?.jsonArrayOrNull ?: JsonArray(emptyList())
                            val choiceList = buildList {
                                if (choices.isNotEmpty()) {
                                    val choice = choices[0].jsonObject
                                    val message =
                                        choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                                        ?: throw Exception("delta/message is null")
                                    val finishReason =
                                        choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                            ?: "unknown"
                                    add(
                                        UIMessageChoice(
                                            index = 0,
                                            delta = parseMessage(message),
                                            message = null,
                                            finishReason = finishReason,
                                        )
                                    )
                                }
                            }
                            val usage = parseTokenUsage(it["usage"] as? JsonObject)

                            val messageChunk = MessageChunk(
                                id = id,
                                model = model,
                                choices = choiceList,
                                usage = usage
                            )
                            trySend(messageChunk)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing SSE event: ${e.message}")
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (t is IOException && t.message == "canceled") {
                    return
                }

                Log.e(TAG, "streamText onFailure: t=${t?.message}, code=${response?.code}")

                var exception: Throwable? = t
                val bodyRaw = response?.body?.stringSafe()

                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    } else if (response != null && !response.isSuccessful) {
                        exception = IOException("HTTP ${response.code}: ${response.message}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse error body")
                    if (exception == null) exception = e
                }

                // 确保即使所有解析都失败，也要抛出一个异常防止 Flow 挂死
                close(exception ?: IOException("Unknown stream error"))
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "onClosed: SSE connection closed")
                close()
            }
        }

        val eventSource = EventSources.createFactory(proxyClient).newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "awaitClose: cancelling eventSource")
            eventSource.cancel()
        }
    }


    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host

        // --- Strict model + MCP tool pre-computation ---
        val modelId = params.model.modelId
        val strictModel = isStrictSchemaModel(modelId)
        val hasMcpTool = params.tools.any { tool -> isMcpTool(tool.name) }
        val injectMcpGuidance = strictModel && hasMcpTool

        // Decorate SYSTEM message with the one-shot MCP guidance when needed
        val effectiveMessages = if (injectMcpGuidance) {
            messages.map { uiMessage ->
                if (uiMessage.role != MessageRole.SYSTEM) return@map uiMessage
                val existingText = uiMessage.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                val restParts = uiMessage.parts.filter { it !is UIMessagePart.Text }
                uiMessage.copy(
                    parts = buildList {
                        add(UIMessagePart.Text(text = existingText + MCP_FLEXIBLE_PARAMS_GUIDANCE))
                        addAll(restParts)
                    }
                )
            }
        } else {
            messages
        }

        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(effectiveMessages, host))

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) {
                    // 智谱 AI 强制要求范围在 (0.0, 1.0) 之间
                    val safeTemperature = if (host == "open.bigmodel.cn") {
                        params.temperature.coerceIn(0.01f, 0.99f)
                    } else {
                        params.temperature
                    }
                    put("temperature", safeTemperature)
                }
                if (params.topP != null) {
                    // 智谱 AI 强制要求范围在 (0.0, 1.0) 之间
                    val safeTopP = if (host == "open.bigmodel.cn") {
                        params.topP.coerceIn(0.01f, 0.99f)
                    } else {
                        params.topP
                    }
                    put("top_p", safeTopP)
                }
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                // Some providers don't support stream_options
                if (host != "api.mistral.ai" && host != "open.bigmodel.cn") {
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if (host == "openrouter.ai") {
                if (params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = ReasoningLevel.fromBudgetTokens(params.thinkingBudget)
                when (host) {
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            if (level.isEnabled && level != ReasoningLevel.AUTO) {
                                put("max_tokens", params.thinkingBudget ?: 0)
                            }
                            if (!level.isEnabled) {
                                put("enabled", false)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            put("thinking_budget", params.thinkingBudget ?: 0)
                        }
                    }

                    "open.bigmodel.cn" -> {
                        // 智谱 (GLM)：部分"始终思考"模型强制开启推理，拒绝 type=disabled (错误码 1210)
                        // 错误提示："该模型始终思考，不支持关闭思考；请使用 low、high 或 max。"
                        // 因此统一发送 type=enabled，将 OFF 级别降级为 reasoning_effort=low
                        put("thinking", buildJsonObject {
                            put("type", "enabled")
                        })
                        val effort = when (level) {
                            ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
                            ReasoningLevel.MEDIUM -> "medium"
                            ReasoningLevel.HIGH -> "high"
                            ReasoningLevel.AUTO -> null
                        }
                        if (effort != null) {
                            put("reasoning_effort", effort)
                        }
                    }

                    "ark.cn-beijing.volces.com", "api.deepseek.com", "api.moonshot.cn" -> {
                        // 豆包 (火山) / DeepSeek / Kimi
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        // 如果开启了思考且指定了强度
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            val effort = if (host == "api.deepseek.com") {
                                when (level) {
                                    ReasoningLevel.LOW, ReasoningLevel.MEDIUM -> "high"
                                    ReasoningLevel.HIGH -> "max"
                                    else -> level.effort
                                }
                            } else {
                                level.effort
                            }
                            put("reasoning_effort", effort)
                        }
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        // 注意：SiliconFlow 是聚合平台，并非所有被家族级 REASONING 标签命中的模型部署
                        // 都实际接受 enable_thinking 参数。典型反例：Qwen/Qwen2.5-72B-Instruct
                        // （普通聊天版）会抛 ValueError: current model does not support parameter enable thinking。
                        // 因此除了家族级标签外，必须再检查 modelId 里是否有推理部署专属标识，
                        // 并且仅在用户显式开启（LOW/MEDIUM/HIGH，非 AUTO/OFF）时才发送参数。
                        val reasoningDeployment = isSiliconFlowReasoningDeployment(params.model.modelId)
                        val explicitlyEnabled = level.isEnabled && level != ReasoningLevel.AUTO
                        if (reasoningDeployment && explicitlyEnabled) {
                            put("enable_thinking", true)
                            put("thinking_budget", params.thinkingBudget ?: 0)
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", if (level.effort == "minimal") "low" else level.effort)
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        val rawParameters = json.encodeToJsonElement(tool.parameters())
                        // Schema enhancement: ONLY MCP tools get additionalProperties=true
                        // Local tools have complete schemas and do not need the flexibility flag
                        val finalParameters = if (isMcpTool(tool.name)) {
                            enhanceMcpToolParameters(rawParameters)
                        } else {
                            rawParameters as? JsonObject ?: buildJsonObject {}
                        }
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                // Tool description is never modified here
                                // The guidance is injected ONCE in the SYSTEM prompt
                                put("description", tool.description)
                                put("parameters", finalParameters)
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
    }

    private fun buildMessages(messages: List<UIMessage>, host: String) = buildJsonArray {
        val rawMessages = messages.filter { it.isValidToUpload() }
        val filteredMessages = mutableListOf<UIMessage>()
        var i = 0
        while (i < rawMessages.size) {
            val message = rawMessages[i]

            when (message.role) {
                MessageRole.TOOL -> {
                    // 1. 拦截掉没有合法前置 assistant 的孤立 Tool 消息
                    Log.w(TAG, "拦截到孤立的 TOOL 消息 (Index: $i)，已丢弃")
                    i++
                }
                MessageRole.ASSISTANT -> {
                    val toolCalls = message.getToolCalls()
                    if (toolCalls.isNotEmpty()) {
                        // 2. 这是一个带工具调用的助手消息，我们需要看后面有没有配套的 Tool 结果
                        val toolResults = mutableListOf<UIMessage>()
                        var j = i + 1
                        while (j < rawMessages.size && rawMessages[j].role == MessageRole.TOOL) {
                            toolResults.add(rawMessages[j])
                            j++
                        }

                        // 检查是否所有的 tool_call_id 都有对应结果
                        val calledIds = toolCalls.map { it.toolCallId }.toSet()
                        val resultIds = toolResults.flatMap { m -> m.getToolResults().map { it.toolCallId } }.toSet()

                        if (calledIds.isNotEmpty() && calledIds.all { resultIds.contains(it) }) {
                            // 完整匹配：全部添加
                            filteredMessages.add(message)
                            filteredMessages.addAll(toolResults)
                            i = j // 跳过已处理的 tool 消息
                        } else {
                            // 不匹配（可能被截断了）：
                            // 为了防止 DeepSeek 报错，我们必须移除这个 assistant 消息中的工具调用属性
                            Log.w(TAG, "检测到工具调用序列不完整 (可能被截断)，正在清洗 Assistant 消息以防止报错")
                            val cleanedParts = message.parts.filter { it !is UIMessagePart.ToolCall }
                            // 如果移除工具调用后还有文本内容，则保留这条消息，否则丢弃
                            if (cleanedParts.any { it is UIMessagePart.Text && it.text.isNotBlank() }) {
                                filteredMessages.add(message.copy(parts = cleanedParts))
                            }
                            i = j // 跳过那些无意义的 tool 消息
                        }
                    } else {
                        // 普通助手消息
                        filteredMessages.add(message)
                        i++
                    }
                }
                else -> {
                    // User, System 等消息直接添加
                    filteredMessages.add(message)
                    i++
                }
            }
        }
        filteredMessages.forEach { message ->
                if (message.role == MessageRole.TOOL) {
                    message.getToolResults().forEach { result ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", result.toolName)
                            put("tool_call_id", result.toolCallId)
                            put("content", json.encodeToString(result.content))
                        })
                    }
                    return@forEach
                }
                add(buildJsonObject {
                    // role
                    put("role", JsonPrimitive(message.role.name.lowercase()))
                    val toolCalls = message.getToolCalls()

                    // reasoning
                    val reasoningParts = message.parts.filter { it is UIMessagePart.Reasoning || it is UIMessagePart.Thinking }
                    if (reasoningParts.isNotEmpty()) {
                        val reasoningText = reasoningParts.joinToString("\n") {
                            when (it) {
                                is UIMessagePart.Reasoning -> it.reasoning
                                is UIMessagePart.Thinking -> it.thinking
                                else -> ""
                            }
                        }
                        if (reasoningText.isNotBlank()) {
                            put("reasoning_content", reasoningText)
                        }
                    }

                    // content
                    val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
                        .filter { it.text.isNotBlank() }
                    val imageParts = message.parts.filterIsInstance<UIMessagePart.Image>()
                    val audioParts = message.parts.filterIsInstance<UIMessagePart.Audio>()

                    when {
                        // 1. 只有单文本，直接发字符串 (最通用)
                        textParts.size == 1 && imageParts.isEmpty() && audioParts.isEmpty() -> {
                            put("content", textParts.first().text)
                        }
                        // 2. 无可见内容 (例如只有思维链或工具调用)
                        textParts.isEmpty() && imageParts.isEmpty() && audioParts.isEmpty() -> {
                            // 重要：DeepSeek 规范，有 tool_calls 时 content 必须为 null
                            // 智谱 (open.bigmodel.cn) 在有 tool_calls 时，content 建议为 "" 而不是 null，否则可能导致模型忽略该 turn
                            if (toolCalls.isNotEmpty()) {
                                if (host == "open.bigmodel.cn") {
                                    put("content", "")
                                } else {
                                    put("content", JsonPrimitive(null as String?))
                                }
                            } else {
                                put("content", "")
                            }
                        }
                        // 3. 多模态或复杂内容，发数组
                        else -> {
                            putJsonArray("content") {
                                message.parts.forEach { part ->
                                    when (part) {
                                        is UIMessagePart.Text -> {
                                            if (part.text.isNotBlank()) {
                                                add(buildJsonObject {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is UIMessagePart.Image -> {
                                            part.encodeBase64().onSuccess {
                                                add(buildJsonObject {
                                                    put("type", "image_url")
                                                    put("image_url", buildJsonObject { put("url", it) })
                                                })
                                            }.onFailure {
                                                add(buildJsonObject {
                                                    put("type", "text")
                                                    put("text", "")
                                                })
                                            }
                                        }
                                        is UIMessagePart.Audio -> {
                                            // OpenAI 规范：input_audio + data(base64) + format
                                            part.encodeBase64(false).onSuccess { base64Data ->
                                                add(buildJsonObject {
                                                    put("type", "input_audio")
                                                    put("input_audio", buildJsonObject {
                                                        put("data", base64Data)
                                                        put("format", part.openaiAudioFormat())
                                                    })
                                                })
                                            }.onFailure {
                                                add(buildJsonObject {
                                                    put("type", "text")
                                                    put("text", "[音频读取失败]")
                                                })
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }

                    // tool_calls
                    if (toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            toolCalls.forEach { toolCall ->
                                add(buildJsonObject {
                                    put("id", toolCall.toolCallId)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", toolCall.toolName)
                                        put("arguments", toolCall.arguments)
                                    })
                                })
                            }
                        })
                    }
                })
            }
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态 of 输出content? 暂时只支持文本吧
        val content = jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitive?.contentOrNull
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments = toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")
                    val argumentsStr = when (arguments) {
                        is JsonPrimitive -> arguments.contentOrNull ?: ""
                        is JsonObject, is JsonArray -> json.encodeToString(arguments)
                        else -> ""
                    }
                    add(
                        UIMessagePart.ToolCall(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            arguments = argumentsStr
                        )
                    )
                }
                add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}
