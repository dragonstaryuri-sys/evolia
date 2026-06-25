package me.rerere.ai.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.RerankResult
import me.rerere.ai.provider.ModelType
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

            val architecture = modelObj["architecture"]?.jsonObject
            val modality = architecture?.get("modality")?.jsonPrimitive?.contentOrNull
            val outputModalities = architecture?.get("output_modalities")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

            // 识别逻辑：包含 "embed" 为嵌入模型，包含 "rerank" 为重排序模型
            val isEmbedding = forceEmbeddingType ||
                id.contains("embed", ignoreCase = true) ||
                modality?.contains("embedding", ignoreCase = true) == true ||
                outputModalities.any { it.contains("embedding", ignoreCase = true) }

            val isRerank = id.contains("rerank", ignoreCase = true)

            val iconUrl = modelObj["icon"]?.jsonPrimitive?.contentOrNull
                ?: architecture?.get("icon")?.jsonPrimitive?.contentOrNull

            val providerSlug = if (id.contains("/")) id.substringBefore("/") else null

            Model(
                modelId = id,
                displayName = modelObj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                type = when {
                    isEmbedding -> ModelType.EMBEDDING
                    isRerank -> ModelType.RERANK
                    else -> ModelType.CHAT
                },
                outputModalities = listOf(me.rerere.ai.provider.Modality.TEXT),
                iconUrl = iconUrl,
                providerSlug = providerSlug
            )
        }
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = if (providerSetting.balanceOption.authorizeKey.isNotBlank()) {
            providerSetting.balanceOption.authorizeKey
        } else {
            keyRoulette.next(providerSetting.apiKey)
        }

        val is4sApi = providerSetting.baseUrl.contains("4sapi.com", ignoreCase = true)

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

        val url = if (apiPath.startsWith("http")) apiPath else "${providerSetting.baseUrl}$apiPath"

        val requestBuilder = Request.Builder().url(url).get()

        val finalAuthValue = if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
        requestBuilder.addHeader("Authorization", finalAuthValue)

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
        if (digitalValue != null) {
            val finalValue = if (is4sApi && resultPath.contains("quota")) digitalValue * 0.000002 else digitalValue
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
        responseAPI.streamText(providerSetting, messages, params)
    } else {
        chatCompletionsAPI.streamText(providerSetting, messages, params)
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(providerSetting, messages, params)
    } else {
        chatCompletionsAPI.generateText(providerSetting, messages, params)
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting is ProviderSetting.OpenAI) { "Expected OpenAI provider setting" }
        val key = keyRoulette.next(providerSetting.apiKey)
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                val isDalle3 = params.model.modelId.contains("dall-e-3", ignoreCase = true)
                put("n", if (isDalle3) 1 else params.numOfImages.coerceIn(1, 10))
                put("response_format", "b64_json")
                put(
                    "size", when {
                        isDalle3 -> when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1024x1024"
                            ImageAspectRatio.LANDSCAPE -> "1792x1024"
                            ImageAspectRatio.PORTRAIT -> "1024x1792"
                        }

                        else -> "1024x1024"
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
        if (!response.isSuccessful) error("Failed to generate image: ${response.code}")

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
        // 分批处理，防止输入过多导致请求失败（如 413 Payload Too Large 或 超过模型限制）
        // 大多数 Provider 至少支持 16-32 个批次，这里保守取 16
        input.chunked(16).flatMap { batch ->
            val key = keyRoulette.next(providerSetting.apiKey)
            val requestBody = json.encodeToString(
                buildJsonObject {
                    put("model", model.modelId)
                    // 优化点：单条输入发送字符串，多条输入发送数组，提高对 SiliconFlow 等供应商的兼容性
                    if (batch.size == 1) {
                        put("input", batch[0])
                    } else {
                        put(
                            "input",
                            kotlinx.serialization.json.JsonArray(batch.map { kotlinx.serialization.json.JsonPrimitive(it) })
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

            val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
            if (!response.isSuccessful) error("Failed to create embedding: ${response.code} ${response.body?.string()}")

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: emptyList()
            data.map { item ->
                item.jsonObject["embedding"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.floatOrNull
                } ?: emptyList()
            }
        }
    }

    override suspend fun rerank(
        providerSetting: ProviderSetting.OpenAI,
        query: String,
        documents: List<String>,
        model: Model
    ): List<RerankResult> = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey)
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", model.modelId)
                put("query", query)
                put(
                    "documents",
                    kotlinx.serialization.json.JsonArray(documents.map { kotlinx.serialization.json.JsonPrimitive(it) })
                )
            }
        )

        val normalizedBaseUrl = providerSetting.baseUrl.removeSuffix("/")
        val url = if (normalizedBaseUrl.endsWith("/v1")) {
            "$normalizedBaseUrl/rerank"
        } else {
            // 兼容 SiliconFlow 等 provider 常见的 /v1/rerank 路径
            "$normalizedBaseUrl/v1/rerank".takeIf { !normalizedBaseUrl.contains("/v1") }
                ?: "$normalizedBaseUrl/rerank"
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to rerank: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val results = bodyJson["results"]?.jsonArray ?: return@withContext emptyList()
        results.map {
            val obj = it.jsonObject
            RerankResult(
                index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
                score = (obj["relevance_score"] ?: obj["score"])?.jsonPrimitive?.floatOrNull ?: 0f
            )
        }
    }
}
