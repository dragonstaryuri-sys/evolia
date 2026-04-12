package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.core.data.db.dao.MemoryDAO
import me.rerere.rikkahub.core.data.db.dao.ChatSegmentDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.EmbeddingCacheEntity
import me.rerere.rikkahub.core.data.db.entity.MemoryEntity
import me.rerere.rikkahub.core.data.db.entity.MemoryType
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity
import me.rerere.rikkahub.core.data.model.AssistantMemory
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.ai.rag.VectorEngine
import kotlin.math.sqrt

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val embeddingService: EmbeddingService,
    private val embeddingCacheDAO: EmbeddingCacheDAO
) {
    // --- L1 Segment Support ---

    suspend fun saveSegment(segment: ChatSegmentEntity) {
        chatSegmentDAO.insertSegment(segment)
    }

    suspend fun getSegmentsForConversation(conversationId: String): List<ChatSegmentEntity> {
        return chatSegmentDAO.getSegmentsByConversation(conversationId)
    }

    // --- Core Memory Methods ---

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content, null, it.type, it.embedding != null, it.embeddingModelId, it.createdAt) }
            }

    fun getCombinedMemoriesFlow(assistantId: String): Flow<List<AssistantMemory>> =
        kotlinx.coroutines.flow.combine(
            memoryDAO.getMemoriesOfAssistantFlow(assistantId),
            chatEpisodeDAO.getEpisodesOfAssistantFlow(assistantId)
        ) { memories, episodes ->
            val coreMemories = memories.map {
                AssistantMemory(it.id, it.content, null, it.type, it.embedding != null, it.embeddingModelId, it.createdAt)
            }
            val episodicMemories = episodes.map {
                AssistantMemory(-it.id, it.content, it.keywords, MemoryType.EPISODIC, it.embedding != null, it.embeddingModelId, it.startTime, it.significance)
            }
            coreMemories + episodicMemories
        }

    fun getAverageMemoryLength(assistantId: String): Flow<Int> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                if (entities.isEmpty()) return@map 150
                val totalLength = entities.sumOf { it.content.length.toLong() }
                (totalLength / entities.size).toInt()
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content, null, it.type, it.embedding != null, it.embeddingModelId, it.createdAt) }
    }

    suspend fun getMemoryById(id: Int): AssistantMemory? {
        val memory = memoryDAO.getMemoryById(id) ?: return null
        return AssistantMemory(
            id = memory.id,
            content = memory.content,
            keywords = null,
            type = memory.type,
            hasEmbedding = memory.embedding != null,
            embeddingModelId = memory.embeddingModelId,
            timestamp = memory.createdAt
        )
    }

    suspend fun getMemoryEntitiesOfAssistant(assistantId: String): List<MemoryEntity> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
    }

    suspend fun getEpisodeEntitiesOfAssistant(assistantId: String): List<ChatEpisodeEntity> {
        return chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
    }

    private suspend fun getOrCreateEmbedding(
        memoryId: Int,
        memoryType: Int,
        content: String,
        keywords: String?,
        assistantId: String,
        existingEmbedding: String? = null,
        existingModelId: String? = null
    ): List<Float>? {
        val modelId = embeddingService.getEmbeddingModelId(assistantId)
        val effectiveContent = if (!keywords.isNullOrBlank() && memoryType == MemoryType.EPISODIC) {
            "Keywords: $keywords\nContent: $content"
        } else {
            content
        }

        val cached = embeddingCacheDAO.getEmbedding(memoryId, memoryType, modelId)
        if (cached != null) {
            return try {
                JsonInstant.decodeFromString<List<Float>>(cached.embedding)
            } catch (e: Exception) {
                null
            }
        }

        if (existingEmbedding != null && existingModelId == modelId) {
            try {
                val emb = JsonInstant.decodeFromString<List<Float>>(existingEmbedding)
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(memoryId = memoryId, memoryType = memoryType, modelId = modelId, embedding = existingEmbedding)
                )
                return emb
            } catch (e: Exception) { e.printStackTrace() }
        }

        return try {
            val embedding = embeddingService.embed(effectiveContent, assistantId)
            embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(memoryId = memoryId, memoryType = memoryType, modelId = modelId, embedding = JsonInstant.encodeToString(embedding))
            )
            embedding
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun hasEmbeddingForCurrentModel(memoryId: Int, memoryType: Int, assistantId: String): Boolean {
        val modelId = embeddingService.getEmbeddingModelId(assistantId)
        return embeddingCacheDAO.hasEmbedding(memoryId, memoryType, modelId)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        chatEpisodeDAO.deleteEpisodesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val memory = memoryDAO.getMemoryById(id) ?: error("Memory not found")
        val newMemory = memory.copy(content = content, embedding = null)
        memoryDAO.updateMemory(newMemory)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
        return AssistantMemory(newMemory.id, newMemory.content, null, newMemory.type, false, null, newMemory.createdAt)
    }

    suspend fun updateEpisodeContent(id: Int, content: String): AssistantMemory {
        val episode = chatEpisodeDAO.getEpisodeById(id) ?: error("Episode not found")
        val newEpisode = episode.copy(content = content, embedding = null)
        chatEpisodeDAO.insertEpisode(newEpisode)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.EPISODIC)
        return AssistantMemory(-newEpisode.id, newEpisode.content, newEpisode.keywords, MemoryType.EPISODIC, false, null, newEpisode.startTime, newEpisode.significance)
    }

    suspend fun addMemory(assistantId: String, content: String, type: Int = MemoryType.CORE): AssistantMemory {
        val embeddingResult = try {
            embeddingService.embedWithModelId(content, assistantId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        val entity = MemoryEntity(
            assistantId = assistantId,
            content = content,
            embedding = embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) },
            embeddingModelId = embeddingResult?.modelId,
            type = type,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        val id = memoryDAO.insertMemory(entity)

        if (embeddingResult != null && embeddingResult.embeddings.isNotEmpty()) {
            embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(memoryId = id.toInt(), memoryType = type, modelId = embeddingResult.modelId, embedding = JsonInstant.encodeToString(embeddingResult.embeddings.first()))
            )
        }

        return AssistantMemory(id.toInt(), content, null, type, embeddingResult != null, embeddingResult?.modelId)
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
    }

    suspend fun retrieveRelevantMemoriesWithScores(assistantId: String, query: String, limit: Int = 5, similarityThreshold: Float = 0.5f): List<Pair<AssistantMemory, Float>> {
        return retrieveRelevantMemoriesWithScores(assistantId, query, limit, similarityThreshold, true, true)
    }

    suspend fun retrieveRelevantMemories(assistantId: String, query: String, limit: Int = 5, similarityThreshold: Float = 0.5f, includeCore: Boolean = true, includeEpisodes: Boolean = true): List<AssistantMemory> {
        return retrieveRelevantMemoriesWithScores(assistantId, query, limit, similarityThreshold, includeCore, includeEpisodes).map { it.first }
    }

    suspend fun retrieveRelevantMemoriesWithScores(assistantId: String, query: String, limit: Int = 5, similarityThreshold: Float = 0.5f, includeCore: Boolean = true, includeEpisodes: Boolean = true): List<Pair<AssistantMemory, Float>> {
        val queryEmbedding = try { embeddingService.embed(query, assistantId) } catch (e: Exception) { return emptyList() }
        val memories = if (includeCore) memoryDAO.getMemoriesOfAssistant(assistantId) else emptyList()
        val episodes = if (includeEpisodes) chatEpisodeDAO.getEpisodesOfAssistant(assistantId) else emptyList()

        // Core Memory Scoring (Normal Vector Search)
        val memoryScores = memories.mapNotNull { memory ->
            val embedding = getOrCreateEmbedding(memory.id, memory.type, memory.content, null, assistantId, memory.embedding, memory.embeddingModelId) ?: return@mapNotNull null
            val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
            val score = similarity * 1.05f // Slight boost for core memories
            if (score >= similarityThreshold) Triple(memory, score, true) else null
        }

        // Episodic Memory Scoring (Hybrid Search: Keywords + Vector + Recency)
        val queryLower = query.lowercase()
        val episodeScores = episodes.mapNotNull { episode ->
            // 1. Vector Similarity
            val embedding = getOrCreateEmbedding(episode.id, MemoryType.EPISODIC, episode.content, episode.keywords, assistantId, episode.embedding, episode.embeddingModelId) ?: return@mapNotNull null
            val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)

            // 2. Keyword Match Score (High Priority)
            var keywordMatchScore = 0f
            if (!episode.keywords.isNullOrBlank()) {
                val keywordsList = episode.keywords.split(",").map { it.trim().lowercase() }
                val matchCount = keywordsList.count { it.isNotEmpty() && (queryLower.contains(it) || it.contains(queryLower)) }
                if (matchCount > 0) {
                    keywordMatchScore = (matchCount.toFloat() / keywordsList.size).coerceAtMost(1.0f)
                }
            }

            // 3. Recency Score
            val ageInMillis = System.currentTimeMillis() - episode.startTime
            val ageInDays = ageInMillis / (1000.0 * 60 * 60 * 24)
            val recency = (1.0 / (1.0 + (ageInDays / 7.0))).toFloat()

            // Hybrid Weighted Score: 50% Keywords, 30% Similarity, 20% Recency
            val score = (keywordMatchScore * 0.5f) + (similarity * 0.3f) + (recency * 0.2f)

            if (score >= similarityThreshold) Triple(episode, score, false) else null
        }

        val allScored = (memoryScores + episodeScores).sortedByDescending { it.second }
        allScored.take(limit).forEach { (item, _, isMemory) ->
            if (isMemory) memoryDAO.updateMemory((item as MemoryEntity).copy(lastAccessedAt = System.currentTimeMillis()))
            else chatEpisodeDAO.insertEpisode((item as ChatEpisodeEntity).copy(lastAccessedAt = System.currentTimeMillis()))
        }

        return allScored.take(limit).mapNotNull { triple ->
            val item = triple.first
            val score = triple.second
            if (triple.third) {
                val m = item as MemoryEntity
                AssistantMemory(m.id, m.content, null, m.type, true, m.embeddingModelId, m.createdAt) to score
            } else {
                val e = item as ChatEpisodeEntity
                AssistantMemory(-e.id, e.content, e.keywords, MemoryType.EPISODIC, true, e.embeddingModelId, e.startTime, e.significance) to score
            }
        }
    }

    suspend fun regenerateEmbeddings(assistantId: String, onProgress: (Int, Int) -> Unit): Pair<Int, Int> {
        val allMemories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val allEpisodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)

        val memoriesNeedingEmbedding = allMemories.filter { it.embedding == null || it.embeddingModelId != currentModelId }
        val episodesNeedingEmbedding = allEpisodes.filter { it.embedding == null || it.embeddingModelId != currentModelId }

        val total = memoriesNeedingEmbedding.size + episodesNeedingEmbedding.size
        var current = 0
        var successCount = 0
        var failureCount = 0
        onProgress(0, total)
        if (total == 0) return 0 to 0

        memoriesNeedingEmbedding.forEach { memory ->
            current++
            try {
                val embedding = embeddingService.embed(memory.content, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                memoryDAO.updateMemory(memory.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = memory.id, memoryType = memory.type, modelId = currentModelId, embedding = embeddingJson))
                successCount++
            } catch (e: Exception) { failureCount++ }
            onProgress(current, total)
        }

        episodesNeedingEmbedding.forEach { episode ->
            current++
            try {
                val effectiveContent = if (!episode.keywords.isNullOrBlank()) "Keywords: ${episode.keywords}\nContent: ${episode.content}" else episode.content
                val embedding = embeddingService.embed(effectiveContent, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chatEpisodeDAO.insertEpisode(episode.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = episode.id, memoryType = MemoryType.EPISODIC, modelId = currentModelId, embedding = embeddingJson))
                successCount++
            } catch (e: Exception) { failureCount++ }
            onProgress(current, total)
        }
        return successCount to failureCount
    }

    suspend fun embedMissingMemories(assistantId: String): Pair<Int, Int> {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val episodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)

        var successCount = 0
        var failureCount = 0

        val memoriesNeedingEmbedding = memories.filter { it.embedding == null || it.embeddingModelId != currentModelId }
        val episodesNeedingEmbedding = episodes.filter { it.embedding == null || it.embeddingModelId != currentModelId }

        memoriesNeedingEmbedding.forEach { memory ->
            try {
                val embedding = embeddingService.embed(memory.content, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                memoryDAO.updateMemory(memory.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = memory.id, memoryType = memory.type, modelId = currentModelId, embedding = embeddingJson))
                successCount++
            } catch (e: Exception) { failureCount++ }
        }

        episodesNeedingEmbedding.forEach { episode ->
            try {
                val effectiveContent = if (!episode.keywords.isNullOrBlank()) "Keywords: ${episode.keywords}\nContent: ${episode.content}" else episode.content
                val embedding = embeddingService.embed(effectiveContent, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chatEpisodeDAO.insertEpisode(episode.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = episode.id, memoryType = MemoryType.EPISODIC, modelId = currentModelId, embedding = embeddingJson))
                successCount++
            } catch (e: Exception) { failureCount++ }
        }
        return successCount to failureCount
    }

    suspend fun countMemoriesNeedingEmbedding(assistantId: String): Int {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val episodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)

        val memoriesNeedingEmbedding = memories.count {
            it.embedding == null || it.embeddingModelId != currentModelId
        }
        val episodesNeedingEmbedding = episodes.count {
            it.embedding == null || it.embeddingModelId != currentModelId
        }

        return memoriesNeedingEmbedding + episodesNeedingEmbedding
    }
}
