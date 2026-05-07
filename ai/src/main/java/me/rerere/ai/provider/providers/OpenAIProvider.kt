package me.rerere.ai.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal

class OpenAIProvider(
    private val client: OkHttpClient
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey)

            // Fetch regular models
            val regularModels = fetchModelsFromUrl(
                url = "${providerSetting.baseUrl}/models",
                key = key,
                providerSetting = providerSetting
            )

            // For OpenRouter, also fetch embedding models using output_modalities filter
            // OpenRouter's /models endpoint doesn't return embedding models by default
            val isOpenRouter = providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)
            val embeddingModels = if (isOpenRouter) {
                fetchModelsFromUrl(
                    url = "${providerSetting.baseUrl}/models?output_modalities=embeddings",
                    key = key,
                    providerSetting = providerSetting,
                    forceEmbeddingType = true
                )
            } else {
                emptyList()
            }

            // Combine and deduplicate by model ID
            val allModels = (regularModels + embeddingModels)
                .distinctBy { it.modelId }

            allModels
        }

    private suspend fun fetchModelsFromUrl(
        url: String,
        key: String,
        providerSetting: ProviderSetting.OpenAI,
        forceEmbeddingType: Boolean = false
    ): List<Model> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            // Don't fail completely if embedding endpoint fails, just return empty
            if (forceEmbeddingType) {
                return emptyList()
            }
            error("Failed to get models: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: return emptyList()

        return data.mapNotNull { modelJson ->
            val modelObj = modelJson.jsonObject
            val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            // Check if model is embedding type via:
            // 1. Model ID contains "embed"
            // 2. architecture.modality contains "embedding" (OpenRouter format)
            // 3. architecture.output_modalities contains "embedding" (OpenRouter array format)
            // 4. Forced by forceEmbeddingType parameter (for OpenRouter embedding endpoint)
            val architecture = modelObj["architecture"]?.jsonObject
            val modality = architecture?.get("modality")?.jsonPrimitive?.contentOrNull
            val outputModalities = architecture?.get("output_modalities")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

            val isEmbedding = forceEmbeddingType ||
                id.contains("embed", ignoreCase = true) ||
                modality?.contains("embedding", ignoreCase = true) == true ||
                outputModalities.any { it.contains("embedding", ignoreCase = true) }

            // Extract icon URL if available (some APIs provide this)
            val iconUrl = modelObj["icon"]?.jsonPrimitive?.contentOrNull
                ?: architecture?.get("icon")?.jsonPrimitive?.contentOrNull

            // Extract provider slug from model ID (e.g., "anthropic/claude-3.5" -> "anthropic")
            // Used for LobeHub CDN icon lookup
            val providerSlug = if (id.contains("/")) id.substringBefore("/") else null

            Model(
                modelId = id,
                displayName = modelObj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                type = if (isEmbedding) me.rerere.ai.provider.ModelType.EMBEDDING else me.rerere.ai.provider.ModelType.CHAT,
                outputModalities = listOf(me.rerere.ai.provider.Modality.TEXT),
                iconUrl = iconUrl,
                providerSlug = providerSlug
            )
        }
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        // 1. 确定授权 Key
        val key = if (providerSetting.balanceOption.authorizeKey.isNotBlank()) {
            providerSetting.balanceOption.authorizeKey
        } else {
            keyRoulette.next(providerSetting.apiKey)
        }

        // 识别是否为 4sapi
        val is4sApi = providerSetting.baseUrl.contains("4sapi.com", ignoreCase = true)

        // 智能默认值：如果是 4sapi 且用户没有修改过默认路径，则自动使用 4sapi 的地址和解析规则
        val apiPath = if (is4sApi && providerSetting.balanceOption.apiPath == "/credits") {
            "https://4sapi.com/api/user/self"
        } else {
            providerSetting.balanceOption.apiPath
        }

        val resultPath = if (is4sApi && providerSetting.balanceOption.resultPath == "data.total_usage") {
            "data.quota"
        } else {
            providerSetting.balanceOption.resultPath
        }

        val url = if (apiPath.startsWith("http")) {
            apiPath
        } else {
            "${providerSetting.baseUrl}$apiPath"
        }

        val requestBuilder = Request.Builder().url(url).get()

        // 2. 处理 Authorization Header (始终带有 Bearer 前缀)
        val finalAuthValue = if (key.startsWith("Bearer ", ignoreCase = true)) {
            key
        } else {
            "Bearer $key"
        }
        requestBuilder.addHeader("Authorization", finalAuthValue)

        // 3. 处理 New-Api-User Header (User ID)
        if (providerSetting.balanceOption.userId.isNotBlank()) {
            requestBuilder.addHeader("New-Api-User", providerSetting.balanceOption.userId)
        }

        val request = requestBuilder.build()
        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(resultPath)

        val digitalValue = value.toDoubleOrNull()
        if(digitalValue != null) {
            val finalValue = if (is4sApi && resultPath.contains("quota")) {
                digitalValue * 0.000002
            } else {
                digitalValue
            }
            "%.2f".format(finalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey)

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                // DALL-E 3 only supports n=1, DALL-E 2 supports up to 10
                val isDalle3 = params.model.modelId.contains("dall-e-3", ignoreCase = true)
                put("n", if (isDalle3) 1 else params.numOfImages.coerceIn(1, 10))
                put("response_format", "b64_json")
                // DALL-E 3: 1024x1024, 1792x1024, 1024x1792
                // DALL-E 2: 256x256, 512x512, 1024x1024
                put(
                    "size", when {
                        isDalle3 -> when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1024x1024"
                            ImageAspectRatio.LANDSCAPE -> "1792x1024"
                            ImageAspectRatio.PORTRAIT -> "1024x1792"
                        }
                        else -> "1024x1024" // DALL-E 2 only supports square
                    }
                )
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")

        val items = data.map { imageJson ->
            val imageObj = imageJson.jsonObject
            val b64Json = imageObj["b64_json"]?.jsonPrimitive?.contentOrNull
                ?: error("No b64_json in response")

            ImageGenerationItem(
                data = b64Json,
                mimeType = "image/png"
            )
        }

        ImageGenerationResult(items = items)
    }
    override suspend fun createEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        input: List<String>,
        model: Model
    ): List<List<Float>> = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey)
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", model.modelId)
                // 优化点：单条输入发送字符串，多条输入发送数组，提高对 SiliconFlow 等供应商的兼容性
                if (input.size == 1) {
                    put("input", input[0])
                } else {
                    put(
                        "input",
                        kotlinx.serialization.json.JsonArray(input.map { kotlinx.serialization.json.JsonPrimitive(it) })
                    )
                }
            }
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response =
            client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to create embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")

        data.map { item ->
            item.jsonObject["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toFloat() }
                ?: error("No embedding in response")
        }
    }
}
