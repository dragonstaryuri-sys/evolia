package me.rerere.rikkahub.core.data.ai

/**
 * Interface for generating embeddings.
 * Implemented in :app module to avoid circular dependencies.
 */
interface EmbeddingService {
    /**
     * Get the current embedding model ID for an assistant.
     */
    fun getEmbeddingModelId(assistantId: String? = null): String

    /**
     * Generate embedding for a single text.
     */
    suspend fun embed(text: String, assistantId: String? = null): List<Float>

    /**
     * Generate embedding and return both results and the model used.
     */
    suspend fun embedWithModelId(text: String, assistantId: String? = null): EmbeddingResult
}

data class EmbeddingResult(
    val embeddings: List<List<Float>>,
    val modelId: String
)
