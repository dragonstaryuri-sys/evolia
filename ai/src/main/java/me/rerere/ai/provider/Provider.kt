package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import java.math.BigDecimal

/**
 * 重排序结果项
 */
@Serializable
data class RerankResult(
    val index: Int,
    val score: Float
)

// 提供商实现
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String = "TODO"

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult

    suspend fun createEmbedding(
        providerSetting: T,
        input: List<String>,
        model: Model,
    ): List<List<Float>> = emptyList()

    /**
     * 对检索到的文档进行重排序
     */
    suspend fun rerank(
        providerSetting: T,
        query: String,
        documents: List<String>,
        model: Model,
    ): List<RerankResult> = emptyList()
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val thinkingBudget: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /**
     * 非流式调用的整体生成超时（分钟）。
     * 仅用于在 Provider 实现层派生 OkHttp readTimeout，使非流式调用的服务端响应时间
     * 与应用层 chatGenerationTimeoutMinutes 对齐，避免 OkHttp 默认 60s readTimeout
     * 在长生成场景下误触发。
     * 注意：此字段不参与请求体构造，仅作为客户端超时配置传递。
     */
    val generationTimeoutMinutes: Int? = null,
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val image: String? = null,
)

@Serializable
data class CustomHeader(val name: String, val value: String)

@Serializable
data class CustomBody(val key: String, val value: JsonElement)
