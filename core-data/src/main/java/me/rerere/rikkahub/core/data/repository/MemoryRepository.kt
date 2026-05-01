package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
import me.rerere.rikkahub.core.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.ai.rag.VectorEngine
import me.rerere.rikkahub.core.data.utils.KeywordExtractor

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val embeddingService: EmbeddingService,
    private val embeddingCacheDAO: EmbeddingCacheDAO
) {
    // --- L1 Segment Support ---

    suspend fun saveSegment(segment: ChatSegmentEntity) {
        val finalSegment = if (segment.keywords.isNullOrBlank()) {
            segment.copy(keywords = KeywordExtractor.extract(segment.content))
        } else segment
        chatSegmentDAO.insertSegment(finalSegment)
    }

    suspend fun getSegmentsForConversation(conversationId: String): List<ChatSegmentEntity> {
        return chatSegmentDAO.getSegmentsByConversation(conversationId)
    }

    /**
     * 针对 L1 Segment 的混合检索
     */
    suspend fun retrieveRelevantSegments(
        assistantId: String,
        conversationId: String,
        query: String,
        limit: Int = 2,
        mode: MemoryRetrievalMode = MemoryRetrievalMode.HYBRID
    ): List<ChatSegmentEntity> {
        if (mode == MemoryRetrievalMode.OFF) return emptyList()
        val segments = chatSegmentDAO.getSegmentsByConversation(conversationId)
        if (segments.isEmpty()) return emptyList()

        val queryEmbedding = if (mode != MemoryRetrievalMode.KEYWORD) {
            try { embeddingService.embed(query, assistantId) } catch (e: Exception) { null }
        } else null

        val scoredSegments = segments.map { segment ->
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC) {
                calculateKeywordScore(query, segment.keywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val segmentEmbedding = segment.embedding?.let {
                    runCatching { JsonInstant.decodeFromString<List<Float>>(it) }.getOrNull()
                } ?: run {
                    val newEmb = try { embeddingService.embed(segment.content, assistantId) } catch (e: Exception) { null }
                    if (newEmb != null) {
                        chatSegmentDAO.insertSegment(segment.copy(embedding = JsonInstant.encodeToString(newEmb)))
                    }
                    newEmb
                }
                segmentEmbedding?.let { VectorEngine.cosineSimilarity(it, queryEmbedding) } ?: 0f
            } else 0f

            val score = when(mode) {
                MemoryRetrievalMode.SEMANTIC -> similarity
                MemoryRetrievalMode.KEYWORD -> keywordScore
                MemoryRetrievalMode.HYBRID -> {
                    // 如果向量不可用，降级为纯关键词
                    if (queryEmbedding == null) keywordScore
                    else (keywordScore * 0.6f) + (similarity * 0.4f)
                }
                else -> 0f
            }

            segment to score
        }

        return scoredSegments.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // --- Core Memory Methods ---

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content, it.keywords, it.type, it.embedding != null, it.embeddingModelId, it.createdAt) }
            }

    fun getCombinedMemoriesFlow(assistantId: String): Flow<List<AssistantMemory>> =
        kotlinx.coroutines.flow.combine(
            memoryDAO.getMemoriesOfAssistantFlow(assistantId),
            chatEpisodeDAO.getEpisodesOfAssistantFlow(assistantId)
        ) { memories, episodes ->
            val coreMemories = memories.map {
                AssistantMemory(it.id, it.content, it.keywords, it.type, it.embedding != null, it.embeddingModelId, it.createdAt)
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
            .map { AssistantMemory(it.id, it.content, it.keywords, it.type, it.embedding != null, it.embeddingModelId, it.createdAt) }
    }

    suspend fun getMemoryById(id: Int): AssistantMemory? {
        val memory = memoryDAO.getMemoryById(id) ?: return null
        return AssistantMemory(
            id = memory.id,
            content = memory.content,
            keywords = memory.keywords,
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

    suspend fun getEpisodeByConversationId(conversationId: String): ChatEpisodeEntity? {
        return chatEpisodeDAO.getEpisodeByConversationId(conversationId)
    }

    /**
     * 根据 ID 和类型获取完整记忆内容
     */
    suspend fun getFullMemoryContent(id: Int, type: Int): String? {
        return if (type == 0) { // CORE
            val memory = memoryDAO.getMemoryById(id)
            memory?.content
        } else { // EPISODIC (L2)
            val absoluteId = kotlin.math.abs(id)
            val episode = chatEpisodeDAO.getEpisodeById(absoluteId)
            episode?.content
        }
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
        chatSegmentDAO.deleteSegmentsByAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val memory = memoryDAO.getMemoryById(id) ?: error("Memory not found")
        val keywords = KeywordExtractor.extract(content)
        val newMemory = memory.copy(content = content, keywords = keywords, embedding = null)
        memoryDAO.updateMemory(newMemory)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
        return AssistantMemory(newMemory.id, newMemory.content, newMemory.keywords, newMemory.type, false, null, newMemory.createdAt)
    }

    suspend fun updateEpisodeContent(id: Int, content: String): AssistantMemory {
        val episode = chatEpisodeDAO.getEpisodeById(id) ?: error("Episode not found")
        val keywords = KeywordExtractor.extract(content)
        val newEpisode = episode.copy(content = content, keywords = keywords, embedding = null)
        chatEpisodeDAO.insertEpisode(newEpisode)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.EPISODIC)
        return AssistantMemory(-newEpisode.id, newEpisode.content, newEpisode.keywords, MemoryType.EPISODIC, false, null, newEpisode.startTime, newEpisode.significance)
    }

    suspend fun addMemory(assistantId: String, content: String, type: Int = MemoryType.CORE, keywords: String? = null): AssistantMemory {
        val finalKeywords = keywords ?: KeywordExtractor.extract(content)

        val embeddingResult = if (type == MemoryType.CORE) {
             try {
                embeddingService.embedWithModelId(content, assistantId)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else null

        val entity = MemoryEntity(
            assistantId = assistantId,
            content = content,
            keywords = finalKeywords,
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

        return AssistantMemory(id.toInt(), content, finalKeywords, type, embeddingResult != null, embeddingResult?.modelId)
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
    }

    private fun calculateKeywordScore(query: String, keywords: String?): Float {
        if (keywords.isNullOrBlank()) return 0f
        val queryLower = query.lowercase()
        val keywordsList = keywords.split(Regex("[,，、\\s]")).map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (keywordsList.isEmpty()) return 0f
        val matchCount = keywordsList.count { queryLower.contains(it) || it.contains(queryLower) }
        if (matchCount == 0) return 0f
        val baseScore = 0.2f
        val bonusScore = (matchCount.toFloat() / keywordsList.size) * 0.8f
        return (baseScore + bonusScore).coerceAtMost(1.0f)
    }

    suspend fun retrieveRelevantMemoriesWithScores(
        assistantId: String,
        query: String,
        limit: Int = 5,
        similarityThreshold: Float = 0.5f,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true,
        mode: MemoryRetrievalMode = MemoryRetrievalMode.HYBRID
    ): List<Pair<AssistantMemory, Float>> {
        if (mode == MemoryRetrievalMode.OFF) return emptyList()
        val queryEmbedding = if (mode != MemoryRetrievalMode.KEYWORD) {
            try { embeddingService.embed(query, assistantId) } catch (e: Exception) { null }
        } else null

        val memories = if (includeCore) memoryDAO.getMemoriesOfAssistant(assistantId) else emptyList()
        val episodes = if (includeEpisodes) chatEpisodeDAO.getEpisodesOfAssistant(assistantId) else emptyList()

        // Core Memory Scoring
        val memoryScores = memories.mapNotNull { memory ->
            val effectiveKeywords = if (memory.keywords.isNullOrBlank()) {
                val local = KeywordExtractor.extract(memory.content)
                // 顺便更新数据库，下次就不用现场分词了
                memoryDAO.updateMemory(memory.copy(keywords = local))
                local
            } else memory.keywords
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC) {
                calculateKeywordScore(query, effectiveKeywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val embedding = getOrCreateEmbedding(memory.id, memory.type, memory.content, memory.keywords, assistantId, memory.embedding, memory.embeddingModelId)
                embedding?.let { VectorEngine.cosineSimilarity(queryEmbedding, it) } ?: 0f
            } else 0f

            val score = when(mode) {
                MemoryRetrievalMode.SEMANTIC -> similarity * 1.05f
                MemoryRetrievalMode.KEYWORD -> keywordScore
                MemoryRetrievalMode.HYBRID -> {
                    if (queryEmbedding == null) keywordScore // 降级
                    else (keywordScore * 0.5f) + (similarity * 0.5f)
                }
                MemoryRetrievalMode.OFF -> 0f
            }

            if (score >= similarityThreshold) Triple(memory, score, true) else null
        }

        // Episodic Memory Scoring
        val episodeScores = episodes.mapNotNull { episode ->
            val effectiveKeywords = if (episode.keywords.isNullOrBlank()) {
                val local = KeywordExtractor.extract(episode.content)
                chatEpisodeDAO.insertEpisode(episode.copy(keywords = local))
                local
            } else episode.keywords
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC) {
                calculateKeywordScore(query, effectiveKeywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val embedding = getOrCreateEmbedding(episode.id, MemoryType.EPISODIC, episode.content, episode.keywords, assistantId, episode.embedding, episode.embeddingModelId)
                embedding?.let { VectorEngine.cosineSimilarity(queryEmbedding, it) } ?: 0f
            } else 0f

            // Recency Score
            val ageInMillis = System.currentTimeMillis() - episode.startTime
            val ageInDays = ageInMillis / (1000.0 * 60 * 60 * 24)
            val recency = (1.0 / (1.0 + (ageInDays / 7.0))).toFloat()

            val score = when(mode) {
                MemoryRetrievalMode.SEMANTIC -> (similarity * 0.8f) + (recency * 0.2f)
                MemoryRetrievalMode.KEYWORD -> (keywordScore * 0.8f) + (recency * 0.2f)
                MemoryRetrievalMode.HYBRID -> {
                    if (queryEmbedding == null) (keywordScore * 0.8f) + (recency * 0.2f) // 降级
                    else (keywordScore * 0.4f) + (similarity * 0.4f) + (recency * 0.2f) // 40/40/20 比例
                }
                MemoryRetrievalMode.OFF -> 0f
            }

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
                AssistantMemory(m.id, m.content, m.keywords, m.type, true, m.embeddingModelId, m.createdAt) to score
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
                // 【新增】补全关键词逻辑
                val finalKeywords = if (memory.keywords.isNullOrBlank()) {
                    KeywordExtractor.extract(memory.content)
                } else memory.keywords
                val embedding = embeddingService.embed(memory.content, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                memoryDAO.updateMemory(memory.copy(
                    keywords = finalKeywords,
                    embedding = embeddingJson,
                    embeddingModelId = currentModelId
                ))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = memory.id, memoryType = memory.type, modelId = currentModelId, embedding = embeddingJson))
                successCount++
            } catch (e: Exception) { failureCount++ }
            onProgress(current, total)
        }

        episodesNeedingEmbedding.forEach { episode ->
            current++
            try {
                // 【新增】先确定关键词
                val finalKeywords = if (episode.keywords.isNullOrBlank()) {
                    KeywordExtractor.extract(episode.content)
                } else episode.keywords
                val effectiveContent = if (!finalKeywords.isNullOrBlank()) "Keywords: $finalKeywords\nContent: ${episode.content}" else episode.content
                val embedding = embeddingService.embed(effectiveContent, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chatEpisodeDAO.insertEpisode(episode.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = episode.id, memoryType = MemoryType.EPISODIC, modelId = currentModelId, embedding = JsonInstant.encodeToString(embedding))
            )
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
