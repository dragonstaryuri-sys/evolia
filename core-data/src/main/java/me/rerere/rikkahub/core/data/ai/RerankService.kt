package me.rerere.rikkahub.core.data.ai

import me.rerere.ai.provider.RerankResult

/**
 * Interface for re-ranking search results.
 */
interface RerankService {
    /**
     * Get the current rerank model ID for an assistant.
     */
    fun getRerankModelId(assistantId: String? = null): String?

    /**
     * Re-rank the retrieved documents.
     */
    suspend fun rerank(
        query: String,
        documents: List<String>,
        assistantId: String? = null
    ): List<RerankResult>
}
