package me.rerere.rikkahub.data.ai.rag

import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RerankResult
import me.rerere.rikkahub.core.data.ai.RerankService as IRerankService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

class RerankServiceImpl(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore
) : IRerankService {

    override fun getRerankModelId(assistantId: String?): String? {
        val settings = settingsStore.settingsFlow.value
        val modelId = settings.rerankModelId ?: return null
        val model = settings.findModelById(modelId)
        return if (model != null) modelId.toString() else null
    }

    override suspend fun rerank(
        query: String,
        documents: List<String>,
        assistantId: String?
    ): List<RerankResult> {
        if (documents.isEmpty()) return emptyList()

        val settings = settingsStore.settingsFlow.value
        val modelId = getRerankModelId(assistantId) ?: return emptyList()

        val model = settings.findModelById(kotlin.uuid.Uuid.parse(modelId)) ?: return emptyList()
        val providerSetting = model.findProvider(settings.providers) ?: return emptyList()
        val provider = providerManager.getProviderByType(providerSetting)

        // 调用 Provider 的 rerank 方法
        return provider.rerank(providerSetting, query, documents, model)
    }
}
