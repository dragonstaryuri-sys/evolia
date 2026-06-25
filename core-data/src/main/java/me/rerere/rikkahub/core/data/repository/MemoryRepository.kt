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
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.ai.RerankService
import me.rerere.rikkahub.core.data.ai.rag.VectorEngine
import me.rerere.rikkahub.core.data.utils.KeywordExtractor
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
                        // 必须同时拥有 embedding 和 modelId 才认为已嵌入
                        hasEmbedding = !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
                        embeddingModelId = it.embeddingModelId,
                        timestamp = it.timestamp,
                        recallCount = it.recallCount
                    )
                }
            }

    suspend fun getLatestSegmentEndIndex(conversationId: String): Int? {
        return chatSegmentDAO.getLatestSegmentEndIndex(conversationId)
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

        val queryEmbedding = if (mode != MemoryRetrievalMode.KEYWORD) {
            try { embeddingService.embed(query, assistantId) } catch (e: Exception) { null }
        } else null

        val conversation = conversationRepository.getConversationById(Uuid.parse(conversationId))
        val messages = conversation?.currentMessages ?: emptyList()

        val scoredSegments = segments.map { segment ->
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC) {
                calculateKeywordScore(query, segment.keywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val segmentEmbedding = segment.embedding?.let {
                    runCatching { JsonInstant.decodeFromString<List<Float>>(it) }.getOrNull()
                } ?: run {
                    val originalText = if (messages.isNotEmpty()) {
                        messages.subList(
                            segment.startMessageIndex.coerceIn(messages.indices),
                            (segment.endMessageIndex + 1).coerceIn(messages.indices.first, messages.size)
                        ).joinToString("\n") { "${it.role}: ${it.toContentText()}" }
                    } else ""

                    val effectiveContent = "[Background]: ${segment.content}\n[Original Text]:\n$originalText"
                    val newEmb = try { embeddingService.embed(effectiveContent, assistantId) } catch (e: Exception) { null }
                    if (newEmb != null) {
                        val modelId = embeddingService.getEmbeddingModelId(assistantId)
                        chatSegmentDAO.insertSegment(segment.copy(embedding = JsonInstant.encodeToString(newEmb), embeddingModelId = modelId))
                    }
                    newEmb
                }
                segmentEmbedding?.let { VectorEngine.cosineSimilarity(it, queryEmbedding) } ?: 0f
            } else 0f

            val recallScore = calculateRecallScore(segment.recallCount)

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

        // 增加召回计数
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
                        !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
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
                    !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
                    it.embeddingModelId, it.createdAt
                )
            }
            val episodicMemories = episodes.map {
                AssistantMemory(
                    -it.id, it.content, it.keywords, MemoryType.EPISODIC,
                    !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
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
                    !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
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
            hasEmbedding = !memory.embedding.isNullOrBlank() && !memory.embeddingModelId.isNullOrBlank(),
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
                    !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
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
                            !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
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
                            !it.embedding.isNullOrBlank() && !it.embeddingModelId.isNullOrBlank(),
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
        existingEmbedding: String? = null,
        existingModelId: String? = null
    ): List<Float>? {
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

    // 新增：召回得分计算
    private fun calculateRecallScore(count: Int): Float {
        // 使用饱和函数：1 - 1/(1 + 0.2*x)
        // 0次 -> 0
        // 5次 -> 0.5
        // 20次 -> 0.8
        return (1.0f - 1.0f / (1.0f + count.toFloat() * 0.2f))
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

        val queryEmbedding = if (mode != MemoryRetrievalMode.KEYWORD) {
            try {
                embeddingService.embed(query, assistantId)
            } catch (e: Exception) {
                Log.e(TAG, "Embedding failed: ${e.message}")
                onEmbeddingFailure?.invoke(e)
                null
            }
        } else null


        val retrievalLimit = if (hasRerank) limit * 3 else limit

        val memories = if (includeCore) memoryDAO.getMemoriesOfAssistant(assistantId) else emptyList()
        val segments = if (includeEpisodes) {
            chatSegmentDAO.getSegmentsByAssistant(assistantId)
                .filter { it.conversationId != excludeConversationId }
        } else emptyList()
        Log.v(TAG, "📦 [RAG] 候选池大小: Memories=${memories.size}, Segments=${segments.size}")
        val memoryScores = memories.mapNotNull { memory ->
            val effectiveKeywords = if (memory.keywords.isNullOrBlank()) {
                val local = KeywordExtractor.extract(memory.content)
                memoryDAO.updateMemory(memory.copy(keywords = local))
                local
            } else memory.keywords
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC || queryEmbedding == null) {
                calculateKeywordScore(query, effectiveKeywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val embedding = getOrCreateEmbedding(memory.id, memory.type, memory.content, memory.keywords, assistantId, memory.embedding, memory.embeddingModelId)
                embedding?.let { VectorEngine.cosineSimilarity(queryEmbedding, it) } ?: 0f
            } else 0f

            val score = when(mode) {
                MemoryRetrievalMode.SEMANTIC -> {
                    if (queryEmbedding == null) keywordScore else similarity * 1.05f
                }
                MemoryRetrievalMode.KEYWORD -> keywordScore
                MemoryRetrievalMode.HYBRID -> {
                    if (queryEmbedding == null) keywordScore
                    else (keywordScore * 0.5f) + (similarity * 0.5f)
                }
                MemoryRetrievalMode.OFF -> 0f
            }

            if (score >= similarityThreshold) Triple(memory, score, true) else null
        }

        val segmentScores = segments.mapNotNull { segment ->
            val keywordScore = if (mode != MemoryRetrievalMode.SEMANTIC || queryEmbedding == null) {
                calculateKeywordScore(query, segment.keywords)
            } else 0f

            val similarity = if (mode != MemoryRetrievalMode.KEYWORD && queryEmbedding != null) {
                val embedding = getOrCreateEmbedding(segment.id, MemoryType.SEGMENT, segment.content, segment.keywords, assistantId, segment.embedding, segment.embeddingModelId)
                embedding?.let { VectorEngine.cosineSimilarity(queryEmbedding, it) } ?: 0f
            } else 0f

            val ageInMillis = System.currentTimeMillis() - segment.timestamp
            val ageInDays = ageInMillis / (1000.0 * 60 * 60 * 24)
            val recency = (1.0 / (1.0 + (ageInDays / 7.0))).toFloat()

            // 召回率得分
            val recallScore = calculateRecallScore(segment.recallCount)

            val score = when(mode) {
                // 相似度/关键词 70%, 新鲜度 20%, 召回率 10%
                MemoryRetrievalMode.SEMANTIC -> {
                    if (queryEmbedding == null) (keywordScore * 0.8f) + (recency * 0.2f)
                    else (similarity * 0.8f) + (recency * 0.2f)
                }
                MemoryRetrievalMode.KEYWORD -> (keywordScore * 0.8f) + (recency * 0.2f)
                MemoryRetrievalMode.HYBRID -> {
                    if (queryEmbedding == null) (keywordScore * 0.8f) + (recency * 0.2f)
                    else (keywordScore * 0.4f) + (similarity * 0.4f) + (recency * 0.2f)
                }
                MemoryRetrievalMode.OFF -> 0f
            }
            if (score >= similarityThreshold) Triple(segment, score, false) else null
        }

        val allScored = (memoryScores + segmentScores).sortedByDescending { it.second }
        Log.v(TAG, "🎯 [RAG] 阈值筛选后匹配数: ${allScored.size}")

        val initialResults = allScored.take(retrievalLimit).mapNotNull { triple ->
            val item = triple.first
            val score = triple.second
            if (triple.third) {
                val m = item as MemoryEntity
                AssistantMemory(m.id, m.content, m.keywords, m.type, true, m.embeddingModelId, m.createdAt, null, score) to score
            } else {
                val s = item as ChatSegmentEntity
                AssistantMemory(s.id, s.content, s.keywords, MemoryType.SEGMENT, true, s.embeddingModelId, s.timestamp, null, score, s.recallCount) to score
            }
        }

        val finalResults = if (hasRerank && initialResults.isNotEmpty()) {
            try {
                Log.v(TAG, "🧠 [RAG] 启动 Rerank 精排 | 候选数量: ${initialResults.size} | 模型: $rerankModelId")
                val contents = initialResults.map { it.first.content }
                val rerankResults = rerankService.rerank(query, contents, assistantId)
                val results = rerankResults.map { r ->
                    val pair = initialResults[r.index]
                    pair.first.copy(score = r.score) to r.score
                }.sortedByDescending { it.second }.take(limit)
                if (results.isEmpty()) {
                    Log.w(TAG, "⚠️ [RAG] Rerank 结果为空，使用初始检索结果兜底")
                    initialResults.take(limit)
                } else {
                    Log.v(TAG, "✅ [RAG] Rerank 完成 | 耗时: ${System.currentTimeMillis()}ms")
                }
                results
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ [RAG] Rerank 失败: ${e.message}")
                initialResults.take(limit)
            }
        } else {
            initialResults.take(limit)
        }
        Log.v(TAG, "🏁 [RAG] 检索结束 | 最终召回: ${finalResults.size}条")
        // 异步更新被召回片段的计数
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

        segmentsNeedingEmbedding.forEach { segment ->
            current++
            try {
                val finalKeywords = if (segment.keywords.isNullOrBlank()) {
                    KeywordExtractor.extract(segment.content)
                } else segment.keywords
                val effectiveContent = if (!finalKeywords.isNullOrBlank()) "Keywords: $finalKeywords\nContent: ${segment.content}" else segment.content
                val embedding = embeddingService.embed(effectiveContent, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chatSegmentDAO.insertSegment(segment.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = segment.id, memoryType = MemoryType.SEGMENT, modelId = currentModelId, embedding = embeddingJson))
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
        val segmentsNeedingEmbedding = segments.count { it.embedding == null || it.embeddingModelId != currentModelId }

        memoriesNeedingEmbedding.forEach { memory ->
            try {
                val embedding = embeddingService.embed(memory.content, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                memoryDAO.updateMemory(memory.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = memory.id, memoryType = memory.type, modelId = currentModelId, embedding = embeddingJson))
                successCount++
            } catch (e: Exception) { failureCount++ }
        }

        // Fix segmentsNeedingEmbedding type error
        segments.filter { it.embedding == null || it.embeddingModelId != currentModelId }.forEach { segment ->
            try {
                val effectiveContent = if (!segment.keywords.isNullOrBlank()) "Keywords: ${segment.keywords}\nContent: ${segment.content}" else segment.content
                val embedding = embeddingService.embed(effectiveContent, assistantId)
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chatSegmentDAO.insertSegment(segment.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                embeddingCacheDAO.insertEmbedding(EmbeddingCacheEntity(memoryId = segment.id, memoryType = MemoryType.SEGMENT, modelId = currentModelId, embedding = embeddingJson))
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
