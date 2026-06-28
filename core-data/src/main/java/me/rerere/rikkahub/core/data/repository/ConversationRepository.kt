package me.rerere.rikkahub.core.data.repository

import android.content.Context
import android.util.Log
import androidx.room.RoomDatabase
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.core.data.db.dao.*
import me.rerere.rikkahub.core.data.db.entity.*
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.common.deleteChatFiles
import me.rerere.ai.ui.UIMessage
import java.time.Instant
import java.time.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid
import kotlin.time.ExperimentalTime

class ConversationRepository(
    private val context: Context,
    private val db: RoomDatabase,
    private val conversationDAO: ConversationDAO,
    private val chatMessageDAO: ChatMessageDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val dailyActivityDAO: DailyActivityDAO,
    private val tokenUsageDAO: TokenUsageDAO,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
        private const val TAG = "ConversationRepo"
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10, isVirtual: Boolean = false): List<Conversation> {
        val entities = conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit,
            isVirtual = isVirtual
        )
        return fetchFullConversations(entities)
    }

    suspend fun getLatestConversations(assistantId: Uuid, limit: Int = 1, isVirtual: Boolean = false): List<Conversation> {
        val entities = conversationDAO.getLatestConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit,
            isVirtual = isVirtual
        )
        return fetchFullConversations(entities)
    }

    suspend fun getLatestConversation(assistantId: Uuid): Conversation? {
        return conversationDAO.getRecentConversationsOfAssistantAnyMode(
            assistantId = assistantId.toString(),
            limit = 1
        ).firstOrNull()?.let { fetchFullConversation(it) }
    }

    suspend fun getPreviousConversation(assistantId: Uuid, currentConversationId: Uuid): Conversation? {
        return conversationDAO.getRecentConversationsOfAssistantExclude(
            assistantId = assistantId.toString(),
            excludeId = currentConversationId.toString(),
            limit = 1
        ).firstOrNull()?.let { fetchFullConversation(it) }
    }

    fun getConversationsOfAssistant(assistantId: Uuid, isVirtual: Boolean = false): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString(), isVirtual = isVirtual)
            .map { list -> fetchFullConversations(list) }
    }

    fun getConversationsOfAssistantAnyMode(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistantAnyMode(assistantId.toString())
            .map { list -> fetchFullConversations(list) }
    }

    fun getAllLightConversations(): Flow<List<Conversation>> {
        return conversationDAO.getAllLight()
            .map { list ->
                list.map { conversationSummaryToConversation(it) }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid, isVirtual: Boolean = false): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString(), isVirtual = isVirtual) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversations(titleKeyword: String, isVirtual: Boolean = false): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword, isVirtual = isVirtual)
            .map { list -> fetchFullConversations(list) }
    }

    fun searchConversationsPaging(titleKeyword: String, isVirtual: Boolean = false): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword, isVirtual = isVirtual) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String, isVirtual: Boolean = false): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword, isVirtual = isVirtual)
            .map { list -> fetchFullConversations(list) }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String, isVirtual: Boolean = false): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsOfAssistantPaging(assistantId.toString(), titleKeyword, isVirtual) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return entity?.let { fetchFullConversation(it) }
    }

    suspend fun getConversationById(id: String): Conversation? {
        return runCatching { getConversationById(Uuid.parse(id)) }.getOrNull()
    }

    private suspend fun fetchFullConversation(entity: ConversationEntity): Conversation {
        return fetchFullConversations(listOf(entity)).first()
    }

    private suspend fun fetchFullConversations(entities: List<ConversationEntity>): List<Conversation> =
        withContext(Dispatchers.IO) {
            if (entities.isEmpty()) return@withContext emptyList()
            val convIds = entities.map { it.id }

            val allNodes = chatMessageDAO.getNodesByConversationIds(convIds).groupBy { it.conversationId }
            val allMessages = chatMessageDAO.getMessagesByConversationIds(convIds).groupBy { it.nodeId }

            entities.map { entity ->
                // 1. 获取新表中的消息
                val nodesFromDb = allNodes[entity.id] ?: emptyList()
                val messageNodes = nodesFromDb.map { nodeEntity ->
                    val messages = allMessages[nodeEntity.id]
                        ?.map { JsonInstant.decodeFromString<UIMessage>(it.contentJson) }
                        ?: emptyList()
                    MessageNode(
                        id = Uuid.parse(nodeEntity.id),
                        messages = messages,
                        selectIndex = nodeEntity.selectIndex
                    )
                }

                // 2. 获取旧字段中的消息
                val oldNodes = try {
                    if (entity.nodes.isNotBlank() && entity.nodes != "[]") {
                        JsonInstant.decodeFromString<List<MessageNode>>(entity.nodes)
                    } else emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "解析旧节点失败: ${entity.id}", e)
                    emptyList()
                }

                // 3. 【核心修复】：合并策略
                // 解决导入备份后“聊了几句”再导出的混合状态。
                // 我们以 ID 为准进行合并，新表的消息代表“现状”，旧字段代表“历史”。
                val finalNodes = if (oldNodes.isEmpty()) {
                    messageNodes
                } else if (messageNodes.isEmpty()) {
                    oldNodes
                } else {
                    val newIds = messageNodes.map { it.id }.toSet()
                    oldNodes.filter { it.id !in newIds } + messageNodes
                }

                conversationEntityToConversation(entity, finalNodes)
            }
        }

    /**
     * 强制迁移所有尚未拆表的会话数据。
     */
    suspend fun migrateAllOldConversations() = withContext(Dispatchers.IO) {
        try {
            val allEntities = conversationDAO.getAll().first()
            val entitiesToMigrate = allEntities.filter { it.nodes.isNotBlank() && it.nodes != "[]" }

            if (entitiesToMigrate.isEmpty()) return@withContext

            db.withTransaction {
                entitiesToMigrate.forEach { entity ->
                    try {
                        // 【核心修复】：基于合并后的全量视图进行同步，防止 syncMessages 的 delete 冲掉新聊的内容
                        val fullConversation = fetchFullConversation(entity)

                        // 同步到新表
                        syncMessages(fullConversation)

                        // 搬运成功后，清空旧字段
                        conversationDAO.update(entity.copy(nodes = ""))
                        Log.i(TAG, "迁移旧数据成功: ${entity.title} (ID: ${entity.id})")
                    } catch (e: Exception) {
                        Log.e(TAG, "迁移单个会话数据失败: ${entity.id}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行迁移任务全局失败", e)
        }
    }

    suspend fun insertConversation(conversation: Conversation) {
        db.withTransaction {
            conversationDAO.insert(conversationToConversationEntity(conversation))
            syncMessages(conversation)
        }
    }

    suspend fun updateConversation(conversation: Conversation) {
        val updatedConv = if (conversation.isConsolidated) conversation.copy(isConsolidated = false) else conversation
        db.withTransaction {
            conversationDAO.update(conversationToConversationEntity(updatedConv))
            syncMessages(updatedConv)
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun syncMessages(conversation: Conversation) {
        val convId = conversation.id.toString()
        val nodeEntities = conversation.messageNodes.mapIndexed { index, node ->
            ChatMessageNodeEntity(
                id = node.id.toString(),
                conversationId = convId,
                selectIndex = node.selectIndex,
                orderIndex = index
            )
        }
        val messageEntities = conversation.messageNodes.flatMap { node ->
            node.messages.mapIndexed { index, msg ->
                ChatMessageEntity(
                    id = msg.id.toString(),
                    nodeId = node.id.toString(),
                    conversationId = convId,
                    contentJson = JsonInstant.encodeToString(msg),
                    createdAt = msg.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
                    orderIndex = index
                )
            }
        }
        chatMessageDAO.syncConversationMessages(convId, nodeEntities, messageEntities)
    }

    suspend fun deleteConversation(conversation: Conversation, deleteFiles: Boolean = true) {
        conversationDAO.delete(conversationToConversationEntity(conversation))
        chatEpisodeDAO.deleteEpisodeByConversationId(conversation.id.toString())
        chatSegmentDAO.deleteSegmentsByConversation(conversation.id.toString())
        if (deleteFiles) {
            context.deleteChatFiles(conversation.files)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = false).first().forEach {
            deleteConversation(fetchFullConversation(it))
        }
        conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = true).first().forEach {
            deleteConversation(fetchFullConversation(it))
        }
    }

    private fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "",
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            truncateIndex = conversation.truncateIndex,
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            isConsolidated = conversation.isConsolidated,
            enabledModeIds = JsonInstant.encodeToString(conversation.enabledModeIds.map { it.toString() }),
            contextSummaryUpToIndex = conversation.contextSummaryUpToIndex,
            lastPruneTime = conversation.lastPruneTime,
            lastPruneMessageCount = conversation.lastPruneMessageCount,
            lastRefreshTime = conversation.lastRefreshTime,
            isVirtual = conversation.isVirtual
        )
    }

    private fun conversationEntityToConversation(entity: ConversationEntity, nodes: List<MessageNode>): Conversation {
        val enabledModeIds = try {
            JsonInstant.decodeFromString<List<String>>(entity.enabledModeIds)
                .map { Uuid.parse(it) }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        return Conversation(
            id = Uuid.parse(entity.id),
            title = entity.title,
            messageNodes = nodes,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            assistantId = Uuid.parse(entity.assistantId),
            truncateIndex = entity.truncateIndex,
            chatSuggestions = JsonInstant.decodeFromString(entity.chatSuggestions),
            isPinned = entity.isPinned,
            isConsolidated = entity.isConsolidated,
            enabledModeIds = enabledModeIds,
            contextSummaryUpToIndex = entity.contextSummaryUpToIndex,
            lastPruneTime = entity.lastPruneTime,
            lastPruneMessageCount = entity.lastPruneMessageCount,
            lastRefreshTime = entity.lastRefreshTime,
            isVirtual = entity.isVirtual
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO.getPinnedConversations()
            .map { list -> fetchFullConversations(list) }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    suspend fun markAsConsolidated(conversationId: Uuid) {
        conversationDAO.updateConsolidatedStatus(id = conversationId.toString(), isConsolidated = true)
    }

    suspend fun markAsNotConsolidated(conversationId: Uuid) {
        conversationDAO.updateConsolidatedStatus(id = conversationId.toString(), isConsolidated = false)
    }

    suspend fun getEpisodeCount(): Int = chatEpisodeDAO.getCount()

    fun getEpisodeCountFlow(): Flow<Int> = chatEpisodeDAO.getCountFlow()

    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDAO.getAll().map { list -> fetchFullConversations(list) }
    }

    fun getConversationCountFlow(): Flow<Int> = conversationDAO.getConversationCountFlow()

    fun getDistinctCreateDatesFlow(): Flow<List<String>> = conversationDAO.getDistinctCreateDatesFlow()

    suspend fun getDistinctCreateDates(): List<String> = conversationDAO.getDistinctCreateDates()

    fun getMostActiveAssistantIdFlow(): Flow<String?> = conversationDAO.getMostActiveAssistantFlow().map { it?.assistantId }

    fun getConversationHoursFlow(): Flow<List<Int>> = conversationDAO.getConversationHoursFlow()

    fun getDailyActivityDatesFlow(): Flow<List<String>> = dailyActivityDAO.getAllDatesFlow()

    fun getWeeklyActivityFlow(startDate: String): Flow<List<DailyActivityEntity>> = dailyActivityDAO.getWeeklyActivityFlow(startDate)

    fun getConversationCountByAssistantFlow(assistantId: String): Flow<Int> =
        conversationDAO.getConversationCountByAssistantFlow(assistantId)

    fun getMostUsedModelIdForAssistantFlow(assistantId: String): Flow<String?> =
        chatMessageDAO.getAllMessagesContentByAssistant(assistantId)
            .map { jsonList ->
                jsonList.asSequence()
                    .map { JsonInstant.decodeFromString<UIMessage>(it) }
                    .mapNotNull { it.modelId?.toString() }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }?.key
            }
            .flowOn(Dispatchers.IO)

    private fun conversationSummaryToConversation(summary: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(summary.id),
            title = summary.title,
            assistantId = Uuid.parse(summary.assistantId),
            createAt = Instant.ofEpochMilli(summary.createAt),
            updateAt = Instant.ofEpochMilli(summary.updateAt),
            isPinned = summary.isPinned,
            isConsolidated = summary.isConsolidated,
            messageNodes = emptyList(),
            isVirtual = summary.isVirtual
        )
    }

    fun getAverageMessageLength(assistantId: Uuid): Flow<Int> {
        return chatMessageDAO.getAllMessagesContentByAssistant(assistantId.toString())
            .map { jsonList ->
                if (jsonList.isEmpty()) return@map 100
                val totalLength = jsonList.sumOf {
                    JsonInstant.decodeFromString<UIMessage>(it).toText().length.toLong()
                }
                (totalLength / jsonList.size).toInt()
            }
            .flowOn(Dispatchers.IO)
    }

    suspend fun recordDailyActivity() {
        val date = LocalDate.now().toString()
        dailyActivityDAO.recordActivity(date)
    }

    fun getAllVirtualMessagesOfAssistant(assistantId: Uuid): Flow<List<MessageNode>> {
        return conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = true)
            .map { conversations ->
                conversations.sortedBy { it.createAt }
                    .flatMap { entity -> fetchFullConversation(entity).messageNodes }
            }
    }

    fun getVirtualConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = true)
            .map { entities -> entities.map { fetchFullConversation(it) } }
    }

    suspend fun migrateConversationDatesToActivity() {
        val dates = conversationDAO.getDistinctCreateDates()
        dates.forEach { date ->
            dailyActivityDAO.insertDateIfNotExists(date, System.currentTimeMillis())
        }
    }

    suspend fun recordTokenUsage(assistantId: String, usage: TokenUsage) {
        val date = LocalDate.now().toString()
        tokenUsageDAO.incrementUsage(
            assistantId = assistantId,
            date = date,
            prompt = usage.promptTokens,
            completion = usage.completionTokens,
            cached = usage.cachedTokens
        )
        val thirtyDaysAgo = LocalDate.now().minusDays(30).toString()
        tokenUsageDAO.deleteOldUsage(thirtyDaysAgo)
    }

    fun getRecentTokenUsageFlow(assistantId: String, days: Int = 7): Flow<List<TokenUsageEntity>> {
        return tokenUsageDAO.getRecentUsageFlow(assistantId, days)
    }

    fun getAllRecentTokenUsageFlow(days: Int = 7): Flow<List<TokenUsageEntity>> {
        val startDate = LocalDate.now().minusDays(days.toLong()).toString()
        return tokenUsageDAO.getAllRecentUsageFlow(startDate)
    }

    fun getDailyTotalUsageFlow(days: Int = 7): Flow<List<DailyUsageSummary>> {
        val startDate = LocalDate.now().minusDays(days.toLong()).toString()
        return tokenUsageDAO.getDailyTotalUsageFlow(startDate)
    }
}
