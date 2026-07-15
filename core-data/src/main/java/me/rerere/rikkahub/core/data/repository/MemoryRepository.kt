package me.rerere.rikkahub.core.data.repository

import android.util.Log
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
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.ai.RerankService
import me.rerere.rikkahub.core.data.ai.rag.VectorEngine
import me.rerere.rikkahub.core.data.utils.KeywordExtractor
import me.rerere.rikkahub.core.data.utils.VectorUtils
import kotlin.uuid.Uuid

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val embeddingService: EmbeddingService,
    private val rerankService: RerankService,
    private val embeddingCacheDAO: EmbeddingCacheDAO,
    private val conversationRepository: ConversationRepository
) {
    private val TAG = "MemoryRepository"

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

    suspend fun getSegmentById(id: Int): ChatSegmentEntity? {
        return chatSegmentDAO.getSegmentById(id)
    }

    fun getSegmentsOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        chatSegmentDAO.getSegmentsByAssistantFlow(assistantId)
            .map { entities ->
                entities.map {
                    AssistantMemory(
                        id = it.id,
                        content = it.content,
                        keywords = it.keywords,
                        type = MemoryType.SEGMENT,
                        hasEmbedding = it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                        embeddingModelId = it.embeddingModelId,
                        timestamp = it.timestamp,
                        recallCount = it.recallCount
                    )
                }
            }

    suspend fun getLatestSegmentEndIndex(conversationId: String): Int? {
        return chatSegmentDAO.getLatestSegmentEndIndex(conversationId)
    }

    suspend fun getSegmentsByAssistantAndTimeRange(assistantId: String, startTime: Long, endTime: Long): List<ChatSegmentEntity> {
        return chatSegmentDAO.getSegmentsByAssistantAndTimeRange(assistantId, startTime, endTime)
    }

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

        // 显式声明为 FloatArray 避免类型模糊
        val queryEmbedding: FloatArray? = if (mode != MemoryRetrievalMode.KEYWORD) {
            try { embeddingService.embed(query, assistantId).toFloatArray() } catch (e: Exception) { null }
        } else null

        val conversation = conversationRepository.getConversationById(Uuid.parse(conversationId))
        val messages = conversation?.currentMessages ?: emptyList()

        val scoredSegments = segments.map { segment ->
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC) {
                calculateKeywordScore(query, segment.keywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                // 1. 提取或生成向量
                val segmentEmb: FloatArray? = if (segment.embedding != null) {
                    VectorUtils.fromByteArray(segment.embedding)
                } else {
                    val originalText = if (messages.isNotEmpty()) {
                        val start = segment.startMessageIndex.coerceIn(messages.indices)
                        val end = (segment.endMessageIndex + 1).coerceIn(messages.indices.first, messages.size)
                        if (start < end) {
                            messages.subList(start, end).joinToString("\n") { "${it.role}: ${it.toContentText()}" }
                        } else ""
                    } else ""

                    val effectiveContent = "[Background]: ${segment.content}\n[Original Text]:\n$originalText"
                    try {
                        val newEmb = embeddingService.embed(effectiveContent, assistantId)
                        val modelId = embeddingService.getEmbeddingModelId(assistantId)
                        val byteArray = VectorUtils.fromList(newEmb)
                        chatSegmentDAO.insertSegment(segment.copy(embedding = byteArray, embeddingModelId = modelId))
                        newEmb.toFloatArray()
                    } catch (e: Exception) {
                        null
                    }
                }

                // 2. 确保类型匹配调用 FloatArray 重载
                if (segmentEmb != null) {
                    VectorEngine.cosineSimilarity(segmentEmb, queryEmbedding)
                } else 0f
            } else 0f

            val score = when(mode) {
                MemoryRetrievalMode.SEMANTIC -> similarity
                MemoryRetrievalMode.KEYWORD -> keywordScore
                MemoryRetrievalMode.HYBRID -> {
                    if (queryEmbedding == null) keywordScore
                    else (keywordScore * 0.5f) + (similarity * 0.5f)
                }
                else -> 0f
            }

            segment to score
        }

        val topSegments = scoredSegments.sortedByDescending { it.second }.take(limit)

        topSegments.forEach { (segment, _) ->
            chatSegmentDAO.incrementRecallCount(segment.id)
        }

        return topSegments.map { (segment, _) ->
            val originalText = if (messages.isNotEmpty()) {
                val start = segment.startMessageIndex.coerceIn(messages.indices)
                val end = (segment.endMessageIndex + 1).coerceIn(messages.indices.first, messages.size)
                if (start < end) {
                    messages.subList(start, end).joinToString("\n") { "${it.role}: ${it.toContentText()}" }
                } else ""
            } else ""

            segment.copy(
                content = "[Background]: ${segment.content}\n[Original Text]:\n$originalText"
            )
        }
    }

    // --- Core Memory Methods ---

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map {
                    AssistantMemory(
                        it.id, it.content, it.keywords, it.type,
                        it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                        it.embeddingModelId, it.createdAt
                    )
                }
            }

    fun getCombinedMemoriesFlow(assistantId: String): Flow<List<AssistantMemory>> =
        kotlinx.coroutines.flow.combine(
            memoryDAO.getMemoriesOfAssistantFlow(assistantId),
            chatEpisodeDAO.getEpisodesOfAssistantFlow(assistantId)
        ) { memories, episodes ->
            val coreMemories = memories.map {
                AssistantMemory(
                    it.id, it.content, it.keywords, it.type,
                    it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                    it.embeddingModelId, it.createdAt
                )
            }
            val episodicMemories = episodes.map {
                AssistantMemory(
                    -it.id, it.content, it.keywords, MemoryType.EPISODIC,
                    it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                    it.embeddingModelId, it.endTime, it.significance
                )
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
            .map {
                AssistantMemory(
                    it.id, it.content, it.keywords, it.type,
                    it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                    it.embeddingModelId, it.createdAt
                )
            }
    }

    suspend fun getMemoryById(id: Int): AssistantMemory? {
        val memory = memoryDAO.getMemoryById(id) ?: return null
        return AssistantMemory(
            id = memory.id,
            content = memory.content,
            keywords = memory.keywords,
            type = memory.type,
            hasEmbedding = memory.embedding != null && !memory.embeddingModelId.isNullOrBlank(),
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

    suspend fun getEpisodesAfter(assistantId: String, startTime: Long, excludeConversationId: String? = null): List<AssistantMemory> {
        return chatEpisodeDAO.getEpisodesAfter(assistantId, startTime)
            .filter { it.conversationId != excludeConversationId }
            .map {
                AssistantMemory(
                    -it.id, it.content, it.keywords, MemoryType.EPISODIC,
                    it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                    it.embeddingModelId, it.endTime, it.significance
                )
            }
    }

    suspend fun getEpisodeByConversationId(conversationId: String): ChatEpisodeEntity? {
        return chatEpisodeDAO.getEpisodeByConversationId(conversationId)
    }

    suspend fun getMemoriesByTypeAndTime(assistantId: String, type: Int, startTime: Long): List<AssistantMemory> {
        return when (type) {
            MemoryType.EPISODIC -> getEpisodesAfter(assistantId, startTime)
            MemoryType.CORE -> {
                memoryDAO.getMemoriesOfAssistant(assistantId)
                    .filter { it.type == MemoryType.CORE && it.createdAt >= startTime }
                    .map {
                        AssistantMemory(
                            it.id, it.content, it.keywords, it.type,
                            it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                            it.embeddingModelId, it.createdAt
                        )
                    }
            }
            MemoryType.SEGMENT -> {
                chatSegmentDAO.getSegmentsByAssistant(assistantId)
                    .filter { it.timestamp >= startTime }
                    .map {
                        AssistantMemory(
                            it.id, it.content, it.keywords, MemoryType.SEGMENT,
                            it.embedding != null && !it.embeddingModelId.isNullOrBlank(),
                            it.embeddingModelId, it.timestamp,
                            recallCount = it.recallCount
                        )
                    }
            }
            else -> emptyList()
        }
    }

    suspend fun getFullMemoryContent(id: Int, type: Int): String? {
        return when (type) {
            MemoryType.CORE -> memoryDAO.getMemoryById(id)?.content
            MemoryType.EPISODIC -> chatEpisodeDAO.getEpisodeById(kotlin.math.abs(id))?.content
            MemoryType.SEGMENT -> chatSegmentDAO.getSegmentById(id)?.content
            else -> null
        }
    }

    private suspend fun getOrCreateEmbedding(
        memoryId: Int,
        memoryType: Int,
        content: String,
        keywords: String?,
        assistantId: String,
        existingEmbedding: ByteArray? = null,
        existingModelId: String? = null
    ): FloatArray? {
        if (content.trim().isBlank()) {
            return null
        }
        val modelId = embeddingService.getEmbeddingModelId(assistantId)
        val effectiveContent = if (!keywords.isNullOrBlank() && (memoryType == MemoryType.EPISODIC || memoryType == MemoryType.SEGMENT)) {
            "Keywords: $keywords\nContent: $content"
        } else {
            content
        }

        val cached = embeddingCacheDAO.getEmbedding(memoryId, memoryType, modelId)
        if (cached != null) {
            return VectorUtils.fromByteArray(cached.embedding)
        }

        if (existingEmbedding != null && existingModelId == modelId) {
            embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(memoryId = memoryId, memoryType = memoryType, modelId = modelId, embedding = existingEmbedding)
            )
            return VectorUtils.fromByteArray(existingEmbedding)
        }

        return try {
            val embedding = embeddingService.embed(effectiveContent, assistantId)
            val byteArray = VectorUtils.fromList(embedding)
            embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(memoryId = memoryId, memoryType = memoryType, modelId = modelId, embedding = byteArray)
            )
            embedding.toFloatArray()
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

    suspend fun saveEpisode(episode: ChatEpisodeEntity) {
        chatEpisodeDAO.insertEpisode(episode)
        chatEpisodeDAO.trimEpisodes(episode.assistantId, 30)
    }

    suspend fun updateEpisodeContent(id: Int, content: String): AssistantMemory {
        val episode = chatEpisodeDAO.getEpisodeById(id) ?: error("Episode not found")
        val keywords = KeywordExtractor.extract(content)
        val newEpisode = episode.copy(content = content, keywords = keywords, embedding = null)
        saveEpisode(newEpisode)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.EPISODIC)
        return AssistantMemory(-newEpisode.id, newEpisode.content, newEpisode.keywords, MemoryType.EPISODIC, false, null, newEpisode.endTime, newEpisode.significance)
    }

    suspend fun updateSegmentContent(id: Int, content: String): AssistantMemory {
        val segment = chatSegmentDAO.getSegmentById(id) ?: error("Segment not found")
        val keywords = KeywordExtractor.extract(content)
        val newSegment = segment.copy(content = content, keywords = keywords, embedding = null, embeddingModelId = null)
        chatSegmentDAO.insertSegment(newSegment)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.SEGMENT)
        return AssistantMemory(newSegment.id, newSegment.content, newSegment.keywords, MemoryType.SEGMENT, false, null, newSegment.timestamp, recallCount = newSegment.recallCount)
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
            embedding = embeddingResult?.embeddings?.firstOrNull()?.let { VectorUtils.fromList(it) },
            embeddingModelId = embeddingResult?.modelId,
            type = type,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        val id = memoryDAO.insertMemory(entity)

        if (embeddingResult != null && embeddingResult.embeddings.isNotEmpty()) {
            embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(memoryId = id.toInt(), memoryType = type, modelId = embeddingResult.modelId, embedding = VectorUtils.fromList(embeddingResult.embeddings.first()))
            )
        }

        return AssistantMemory(id.toInt(), content, finalKeywords, type, embeddingResult != null, embeddingResult?.modelId)
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
    }

    suspend fun deleteSegment(id: Int) {
        chatSegmentDAO.deleteSegmentById(id)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.SEGMENT)
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
        mode: MemoryRetrievalMode = MemoryRetrievalMode.HYBRID,
        excludeConversationId: String? = null,
        onEmbeddingFailure: (suspend (Exception) -> Unit)? = null
    ): List<Pair<AssistantMemory, Float>> {
        if (mode == MemoryRetrievalMode.OFF || query.trim().isBlank()) return emptyList()
        Log.v(TAG, "🔍 [RAG] 开始检索 | Query: '$query' | Mode: $mode")

        val rerankModelId = rerankService.getRerankModelId(assistantId)
        val hasRerank = !rerankModelId.isNullOrBlank()

        val queryEmbedding: FloatArray? = if (mode != MemoryRetrievalMode.KEYWORD) {
            try {
                embeddingService.embed(query, assistantId).toFloatArray()
            } catch (e: Exception) {
                Log.e(TAG, "Embedding failed: ${e.message}")
                onEmbeddingFailure?.invoke(e)
                null
            }
        } else null

        // 初排候选集大小：固定为 limit * 3
        val retrievalLimit = limit * 3
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)
        val startTime = System.currentTimeMillis()
        val memoryProjections = if (includeCore) memoryDAO.getMemoryProjections(assistantId) else emptyList()
        val segmentProjections = if (includeEpisodes) {
            chatSegmentDAO.getSegmentProjections(assistantId)
                .filter { it.conversationId != excludeConversationId }
        } else emptyList()

        val projectionTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "⏱️ [RAG-Perf] 加载 ${memoryProjections.size + segmentProjections.size} 条投影耗时: ${projectionTime}ms")

        // 初排：不进行阈值过滤
        val memoryScores = memoryProjections.map { proj ->
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC || queryEmbedding == null) {
                calculateKeywordScore(query, proj.keywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val emb = proj.embedding?.let { VectorUtils.fromByteArray(it) }
                if (emb != null && proj.embeddingModelId == currentModelId) {
                    VectorEngine.cosineSimilarity(queryEmbedding, emb)
                } else 0f
            } else 0f

            val score = when(mode) {
                MemoryRetrievalMode.SEMANTIC -> if (queryEmbedding == null) keywordScore else similarity * 1.05f
                MemoryRetrievalMode.KEYWORD -> keywordScore
                MemoryRetrievalMode.HYBRID -> {
                    if (queryEmbedding == null) keywordScore
                    else (keywordScore * 0.5f) + (similarity * 0.5f)
                }
                MemoryRetrievalMode.OFF -> 0f
            }
            Triple(proj.id, score, true)
        }

        val segmentScores = segmentProjections.map { proj ->
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC || queryEmbedding == null) {
                calculateKeywordScore(query, proj.keywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val emb = proj.embedding?.let { VectorUtils.fromByteArray(it) }
                if (emb != null && proj.embeddingModelId == currentModelId) {
                    VectorEngine.cosineSimilarity(queryEmbedding, emb)
                } else 0f
            } else 0f

            val ageInMillis = System.currentTimeMillis() - proj.timestamp
            val recency = (1.0 / (1.0 + (ageInMillis / (1000.0 * 60 * 60 * 24 * 7.0)))).toFloat()

            val score = when(mode) {
                MemoryRetrievalMode.SEMANTIC -> if (queryEmbedding == null) (keywordScore * 0.9f) + (recency * 0.1f) else (similarity * 0.9f) + (recency * 0.1f)
                MemoryRetrievalMode.KEYWORD -> (keywordScore * 0.9f) + (recency * 0.1f)
                MemoryRetrievalMode.HYBRID -> if (queryEmbedding == null) (keywordScore * 0.9f) + (recency * 0.1f) else (keywordScore * 0.45f) + (similarity * 0.45f) + (recency * 0.1f)
                MemoryRetrievalMode.OFF -> 0f
            }
            Triple(proj.id, score, false)
        }

        val allScored = (memoryScores + segmentScores).sortedByDescending { it.second }
        val scoringTime = System.currentTimeMillis() - startTime - projectionTime
        Log.d(TAG, "⏱️ [RAG-Perf] 内存评分排序耗时: ${scoringTime}ms")

        val topCandidates = allScored.take(retrievalLimit)
        val topMemoryIds = topCandidates.filter { it.third }.map { it.first }
        val topSegmentIds = topCandidates.filter { !it.third }.map { it.first }

        val fetchFullStart = System.currentTimeMillis()
        val fullMemories = if (topMemoryIds.isNotEmpty()) memoryDAO.getMemoriesByIds(topMemoryIds).associateBy { it.id } else emptyMap()
        val fullSegments = if (topSegmentIds.isNotEmpty()) chatSegmentDAO.getSegmentsByIds(topSegmentIds).associateBy { it.id } else emptyMap()
        val fetchFullTime = System.currentTimeMillis() - fetchFullStart
        Log.d(TAG, "⏱️ [RAG-Perf] 加载 ${topCandidates.size} 条全量文本耗时: ${fetchFullTime}ms")

        val initialResults = topCandidates.mapNotNull { (id, score, isMemory) ->
            if (isMemory) {
                fullMemories[id]?.let { m ->
                    AssistantMemory(m.id, m.content, m.keywords, m.type, true, m.embeddingModelId, m.createdAt, null, score) to score
                }
            } else {
                fullSegments[id]?.let { s ->
                    AssistantMemory(s.id, s.content, s.keywords, MemoryType.SEGMENT, true, s.embeddingModelId, s.timestamp, null, score, s.recallCount) to score
                }
            }
        }

        val finalResults = if (hasRerank && initialResults.isNotEmpty()) {
            try {
                Log.v(TAG, "🧠 [RAG] 启动 Rerank 精排 | 候选数量: ${initialResults.size} | 模型: $rerankModelId")
                val contents = initialResults.map { it.first.content }
                val rerankResults = rerankService.rerank(query, contents, assistantId)

                // Rerank 后过滤低于阈值的项
                val results = rerankResults
                    .filter { it.score >= similarityThreshold }
                    .map { r ->
                        val pair = initialResults[r.index]
                        pair.first.copy(score = r.score) to r.score
                    }
                    .sortedByDescending { it.second }
                    .take(limit)

                if (results.isEmpty()) {
                    Log.w(TAG, "⚠️ [RAG] Rerank 后无符合阈值的记忆")
                    emptyList()
                } else {
                    Log.v(TAG, "✅ [RAG] Rerank 完成 | 符合阈值的数量: ${results.size}")
                    results
                }
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ [RAG] Rerank 失败: ${e.message}")
                // 降级：手动过滤初排结果
                initialResults.filter { it.second >= similarityThreshold }.take(limit)
            }
        } else {
            // 无 Rerank 模型：直接过滤初排结果
            initialResults.filter { it.second >= similarityThreshold }.take(limit)
        }

        Log.v(TAG, "🏁 [RAG] 检索结束 | 最终召回: ${finalResults.size}条")
        finalResults.forEach { (memory, _) ->
            if (memory.type == MemoryType.SEGMENT) {
                chatSegmentDAO.incrementRecallCount(memory.id)
            }
        }

        return finalResults
    }

    suspend fun regenerateEmbeddings(assistantId: String, onProgress: (Int, Int) -> Unit): Pair<Int, Int> {
        val rawMemories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val rawSegments = chatSegmentDAO.getSegmentsByAssistant(assistantId)

        rawMemories.filter { it.content.trim().isBlank() }.forEach { deleteMemory(it.id) }
        rawSegments.filter { it.content.trim().isBlank() }.forEach {
            chatSegmentDAO.deleteSegmentById(it.id)
            embeddingCacheDAO.deleteByMemoryId(it.id, MemoryType.SEGMENT)
        }

        val allMemories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val allSegments = chatSegmentDAO.getSegmentsByAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)

        val memoriesNeedingEmbedding = allMemories.filter { it.embedding == null || it.embeddingModelId != currentModelId }
        val segmentsNeedingEmbedding = allSegments.filter { it.embedding == null || it.embeddingModelId != currentModelId }

        val total = memoriesNeedingEmbedding.size + segmentsNeedingEmbedding.size
        var current = 0
        var successCount = 0
        var failureCount = 0
        onProgress(0, total)
        if (total == 0) return 0 to 0

        memoriesNeedingEmbedding.forEach { memory ->
            current++
            try {
                val finalKeywords = if (memory.keywords.isNullOrBlank()) {
                    KeywordExtractor.extract(memory.content)
                } else memory.keywords
                val embedding = embeddingService.embed(memory.content, assistantId)
                val byteArray = VectorUtils.fromList(embedding)
                memoryDAO.updateMemory(memory.copy(
                    keywords = finalKeywords,
                    embedding = byteArray,
                    embeddingModelId = currentModelId
                ))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = memory.id, memoryType = memory.type, modelId = currentModelId, embedding = byteArray))
                successCount++
            } catch (e: Exception) { failureCount++ }
            onProgress(current, total)
        }

        segmentsNeedingEmbedding.forEach { segment ->
            current++
            try {
                val finalKeywords = if (segment.keywords.isNullOrBlank()) {
                    KeywordExtractor.extract(segment.content)
                } else segment.keywords
                val effectiveContent = if (!finalKeywords.isNullOrBlank()) "Keywords: $finalKeywords\nContent: ${segment.content}" else segment.content
                val embedding = embeddingService.embed(effectiveContent, assistantId)
                val byteArray = VectorUtils.fromList(embedding)
                chatSegmentDAO.insertSegment(segment.copy(embedding = byteArray, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = segment.id, memoryType = MemoryType.SEGMENT, modelId = currentModelId, embedding = byteArray))
                successCount++
            } catch (e: Exception) { failureCount++ }
            onProgress(current, total)
        }
        return successCount to failureCount
    }

    suspend fun embedMissingMemories(assistantId: String): Pair<Int, Int> {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val segments = chatSegmentDAO.getSegmentsByAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)

        var successCount = 0
        var failureCount = 0
        val memoriesNeedingEmbedding = memories.filter { it.embedding == null || it.embeddingModelId != currentModelId }

        memoriesNeedingEmbedding.forEach { memory ->
            try {
                val embedding = embeddingService.embed(memory.content, assistantId)
                val byteArray = VectorUtils.fromList(embedding)
                memoryDAO.updateMemory(memory.copy(embedding = byteArray, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = memory.id, memoryType = memory.type, modelId = currentModelId, embedding = byteArray))
                successCount++
            } catch (e: Exception) { failureCount++ }
        }

        segments.filter { it.embedding == null || it.embeddingModelId != currentModelId }.forEach { segment ->
            try {
                val effectiveContent = if (!segment.keywords.isNullOrBlank()) "Keywords: ${segment.keywords}\nContent: ${segment.content}" else segment.content
                val embedding = embeddingService.embed(effectiveContent, assistantId)
                val byteArray = VectorUtils.fromList(embedding)
                chatSegmentDAO.insertSegment(segment.copy(embedding = byteArray, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = segment.id, memoryType = MemoryType.SEGMENT, modelId = currentModelId, embedding = byteArray))
                successCount++
            } catch (e: Exception) { failureCount++ }
        }
        return successCount to failureCount
    }

    suspend fun countMemoriesNeedingEmbedding(assistantId: String): Int {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val segments = chatSegmentDAO.getSegmentsByAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)

        val memoriesNeedingEmbedding = memories.count {
            it.embedding == null || it.embeddingModelId != currentModelId
        }
        val segmentsNeedingEmbedding = segments.count {
            it.embedding == null || it.embeddingModelId != currentModelId
        }

        return memoriesNeedingEmbedding + segmentsNeedingEmbedding
    }
}
