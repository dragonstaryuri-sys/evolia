package me.rerere.ai.provider.providers

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
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ClaudeProvider"

/**
 * Model-ID substrings that identify "strict schema following" models.
 * (Keep in sync with ChatCompletionsAPI & ResponseAPI.)
 */
private val STRICT_TOOL_SCHEMA_MODEL_KEYWORDS_CLAUDE = listOf(
    "glm",      // 智谱 AI / GLM 系列 (即使走中转站也能识别)
    "claude",   // Anthropic Claude (本 Provider 的本地 Claude 也可能有此问题)
)

private const val MCP_FLEXIBLE_PARAMS_GUIDANCE_CLAUDE =
    "\n\n【MCP 工具参数灵活度原则】\n" +
        "本对话中名称以 \"mcp_\" 开头的工具来自 MCP 生态。这些工具的实际后端接受的参数通常比它们暴露的 JSON Schema 列表更丰富：如果某工具的描述、其配套文档或对话上下文暗示了其他可用的字段，请**务必将这些字段一并传入**，不要因为该字段没有出现在 parameters 的 properties 列表里就省略。MCP 工具后端会正确识别并处理额外的参数。"

private fun isStrictSchemaModelClaude(modelId: String): Boolean {
    val lowerId = modelId.lowercase()
    return STRICT_TOOL_SCHEMA_MODEL_KEYWORDS_CLAUDE.any { keyword ->
        keyword in lowerId
    }
}

private fun isMcpToolClaude(toolName: String): Boolean = toolName.startsWith("mcp_")

private fun enhanceMcpToolParametersClaude(rawParameters: JsonElement): JsonObject {
    val paramsObj = (rawParameters as? JsonObject) ?: buildJsonObject { }
    if (paramsObj.containsKey("additionalProperties")) return paramsObj
    return buildJsonObject {
        paramsObj.forEach { (k, v) -> put(k, v) }
        put("additionalProperties", JsonPrimitive(true))
    }
}
private const val ANTHROPIC_VERSION = "2023-06-01"

class ClaudeProvider(private val client: OkHttpClient) : Provider<ProviderSetting.Claude> {
    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("x-api-key", providerSetting.apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response =
                client.configureClientWithProxy(providerSetting.proxy).newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildMessageRequest(messages, params)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", providerSetting.apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(content),
                    finishReason = stopReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildMessageRequest(messages, params, stream = true)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", providerSetting.apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        requestBody["messages"]!!.jsonArray.forEach {
            Log.i(TAG, "streamText: $it")
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: type=$type, data=$data")

                val dataJson = json.parseToJsonElement(data).jsonObject
                val deltaMessage = parseMessage(buildJsonArray {
                    val contentBlockObj = dataJson["content_block"]?.jsonObject
                    val deltaObj = dataJson["delta"]?.jsonObject
                    if (contentBlockObj != null) {
                        add(contentBlockObj)
                    }
                    if (deltaObj != null) {
                        add(deltaObj)
                    }
                })
                val tokenUsage = parseTokenUsage(
                    dataJson["usage"]?.jsonObject ?: dataJson["message"]?.jsonObject?.get("usage")?.jsonObject
                )
                val messageChunk = MessageChunk(
                    id = id ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = deltaMessage,
                            message = null,
                            finishReason = null
                        )
                    ),
                    usage = tokenUsage
                )

                when (type) {
                    "message_stop" -> {
                        Log.d(TAG, "Stream ended")
                        close()
                    }

                    "error" -> {
                        val eventData = json.parseToJsonElement(data).jsonObject
                        val error = eventData["error"]?.parseErrorDetail()
                        close(error)
                    }
                }

                trySend(messageChunk)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.i(TAG, "Error response: $bodyElement")
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource =
            EventSources.createFactory(client.configureClientWithProxy(providerSetting.proxy))
                .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            eventSource.cancel()
        }
    }

    private fun buildMessageRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        // --- Strict model + MCP tool pre-computation ---
        val modelId = params.model.modelId
        val strictModel = isStrictSchemaModelClaude(modelId)
        val hasMcpTool = params.tools.any { tool -> isMcpToolClaude(tool.name) }
        val injectMcpGuidance = strictModel && hasMcpTool

        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages))
            put("max_tokens", params.maxTokens ?: 64_000)

            if (params.temperature != null && (params.thinkingBudget ?: 0) == 0) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt (append one-shot MCP guidance when strict model + has MCP tools)
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            if (systemMessage != null) {
                put("system", buildJsonArray {
                    systemMessage.parts.filterIsInstance<UIMessagePart.Text>().forEachIndexed { idx, part ->
                        val text = if (injectMcpGuidance && idx == systemMessage.parts.filterIsInstance<UIMessagePart.Text>().lastIndex) {
                            part.text + MCP_FLEXIBLE_PARAMS_GUIDANCE_CLAUDE
                        } else {
                            part.text
                        }
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                })
            } else if (injectMcpGuidance) {
                // No system message, but we still need to inform the model
                put("system", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", MCP_FLEXIBLE_PARAMS_GUIDANCE_CLAUDE.removePrefix("\n\n"))
                    })
                })
            }

            // 处理 thinking budget
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = ReasoningLevel.fromBudgetTokens(params.thinkingBudget ?: 0)
                put("thinking", buildJsonObject {
                    if (level == ReasoningLevel.OFF) {
                        put("type", "disabled")
                    } else {
                        put("type", "enabled")
                        if (level != ReasoningLevel.AUTO) put("budget_tokens", params.thinkingBudget ?: 0)
                    }
                })
            }

            // 处理工具
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        val rawSchema = json.encodeToJsonElement(tool.parameters())
                        // Only MCP tools get additionalProperties=true
                        val finalSchema = if (isMcpToolClaude(tool.name)) {
                            enhanceMcpToolParametersClaude(rawSchema)
                        } else {
                            rawSchema as? JsonObject ?: buildJsonObject {}
                        }
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", finalSchema)
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.TOOL) {
                    message.getToolResults().forEach { result ->
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", result.toolCallId)
                                    put("content", json.encodeToString(result.content))
                                })
                            }
                        })
                    }
                    return@forEach
                }

                add(buildJsonObject {
                    // role
                    put("role", JsonPrimitive(message.role.name.lowercase()))

                    // content
                    putJsonArray("content") {
                        message.parts.forEach { part ->
                            when (part) {
                                is UIMessagePart.Text -> {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", part.text)
                                    })
                                }

                                is UIMessagePart.Image -> {
                                    add(buildJsonObject {
                                        part.encodeBase64().onSuccess { base64Data ->
                                            put("type", "image")
                                            put("source", buildJsonObject {
                                                put("type", "base64")
                                                put(
                                                    "media_type",
                                                    "image/jpeg"
                                                ) // 默认为 jpeg，可能需要根据实际情况调整
                                                put(
                                                    "data",
                                                    base64Data.substringAfter(",")
                                                ) // 移除 data:image/jpeg;base64, 前缀
                                            })
                                        }.onFailure {
                                            it.printStackTrace()
                                            Log.w(TAG, "encode image failed: ${part.url}")
                                            // 如果图片编码失败，添加一个空文本块
                                            put("type", "text")
                                            put("text", "")
                                        }
                                    })
                                }

                                is UIMessagePart.ToolCall -> {
                                    add(buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", part.toolCallId)
                                        put("name", part.toolName)
                                        put("input", json.parseToJsonElement(part.arguments))
                                    })
                                }

                                is UIMessagePart.Reasoning -> {
                                    add(buildJsonObject {
                                        put("type", "thinking")
                                        put("thinking", part.reasoning)
                                        part.metadata?.let {
                                            it.forEach { entry ->
                                                put(entry.key, entry.value)
                                            }
                                        }
                                    })
                                }

                                else -> {
                                    Log.w(TAG, "buildMessages: message part not supported: $part")
                                    // DO NOTHING
                                }
                            }
                        }
                    }
                })
            }
    }

    private fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()

        content.forEach { contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    parts.add(UIMessagePart.Text(text))
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    val reasoning = UIMessagePart.Reasoning(
                        reasoning = thinking,
                        createdAt = Clock.System.now(),
                    )
                    if (signature != null) {
                        reasoning.metadata = buildJsonObject {
                            put("signature", signature)
                        }
                    }
                    parts.add(reasoning)
                }

                "redacted_thinking" -> {
                    error("redacted_thinking detected, not support yet!")
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = id,
                            toolName = name,
                            arguments = if (input.isEmpty()) "" else json.encodeToString(input)
                        )
                    )
                }

                "input_json_delta" -> {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = "",
                            toolName = "",
                            arguments = input ?: ""
                        )
                    )
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = (jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0) +
                (jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0)
        )
    }
}
