package me.rerere.rikkahub.data.ai.rag

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.core.data.ai.EmbeddingService as IEmbeddingService
import me.rerere.rikkahub.core.data.ai.EmbeddingResult

class EmbeddingService(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore
) : IEmbeddingService {
    /**
     * Get the current embedding model ID for an assistant (or global if not set).
     * Resolves to an actually-existing EMBEDDING model: falls back to the first
     * available embedding model if the configured ID is missing.
     */
    override fun getEmbeddingModelId(assistantId: String?): String {
        val settings = settingsStore.settingsFlow.value
        val assistant = if (assistantId != null) settings.assistants.find { it.id.toString() == assistantId } else null
        val configuredModelId = assistant?.embeddingModelId ?: settings.embeddingModelId
        val resolvedModel = settings.findModelById(configuredModelId)
            ?: settings.providers
                .flatMap { it.models }
                .firstOrNull { it.type == ModelType.EMBEDDING }
        return (resolvedModel?.id ?: configuredModelId).toString()
    }

    override suspend fun embed(text: String, assistantId: String?): List<Float> {
        return embedBatch(listOf(text), assistantId).embeddings.first()
    }

    override suspend fun embedWithModelId(text: String, assistantId: String?): EmbeddingResult {
        val result = embedBatch(listOf(text), assistantId)
        return EmbeddingResult(result.embeddings, result.modelId)
    }

    suspend fun embedBatch(texts: List<String>, assistantId: String? = null): EmbeddingResult {
        val settings = settingsStore.settingsFlow.value

        // Use assistant embedding model if available, otherwise use global
        val configuredModelId = if (assistantId != null) {
            val assistant = settings.assistants.find { it.id.toString() == assistantId }
            assistant?.embeddingModelId ?: settings.embeddingModelId
        } else {
            settings.embeddingModelId
        }

        // 1. 首先尝试直接用配置的 modelId 查找
        // 2. 若找不到，自动降级：找 providers 中第一个 type == EMBEDDING 的模型
        val model: Model = settings.findModelById(configuredModelId)
            ?: settings.providers
                .flatMap { it.models }
                .firstOrNull { it.type == ModelType.EMBEDDING }
            ?: error(
                "Embedding model not found: $configuredModelId.\n" +
                    "请在设置中添加一个「嵌入模型(EMBEDDING 类型)」并将其选为全局嵌入模型。\n" +
                    "当前已配置的模型总数：${settings.providers.sumOf { it.models.size }}，" +
                    "其中嵌入模型数量：${settings.providers.flatMap { it.models }.count { it.type == ModelType.EMBEDDING }}"
            )

        val resolvedModelId = model.id

        // Check if provider supports embeddings
        val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found for embedding model")
        val provider = providerManager.getProviderByType(providerSetting)

        // Check if provider supports embeddings (OpenAI does, others may not)
        val embeddingResult = provider.createEmbedding(providerSetting, texts, model)
        if (embeddingResult.isEmpty() && texts.isNotEmpty()) {
            error("Provider ${providerSetting::class.simpleName} does not support embeddings or returned empty result")
        }

        return EmbeddingResult(embeddingResult, resolvedModelId.toString())
    }
}
