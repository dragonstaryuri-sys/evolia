package me.rerere.rikkahub.core.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.core.data.db.dao.ConversationDAO
import me.rerere.rikkahub.core.data.db.dao.DailyActivityDAO
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.dao.ChatSegmentDAO
import me.rerere.rikkahub.core.data.db.entity.ConversationEntity
import me.rerere.rikkahub.core.data.db.entity.DailyActivityEntity
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.common.deleteChatFiles
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

class ConversationRepository(
    private val context: Context,
    private val conversationDAO: ConversationDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val dailyActivityDAO: DailyActivityDAO,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10, isVirtual: Boolean = false): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit,
            isVirtual = isVirtual
        ).map { conversationEntityToConversation(it) }
    }

    suspend fun getLatestConversations(assistantId: Uuid, limit: Int = 1, isVirtual: Boolean = false): List<Conversation> {
        return conversationDAO.getLatestConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit,
            isVirtual = isVirtual
        ).map { conversationEntityToConversation(it) }
    }

    suspend fun getLatestConversation(assistantId: Uuid): Conversation? {
        return conversationDAO.getRecentConversationsOfAssistantAnyMode(
            assistantId = assistantId.toString(),
            limit = 1
        ).firstOrNull()?.let { conversationEntityToConversation(it) }
    }

    suspend fun getPreviousConversation(assistantId: Uuid, currentConversationId: Uuid): Conversation? {
        return conversationDAO.getRecentConversationsOfAssistantExclude(
            assistantId = assistantId.toString(),
            excludeId = currentConversationId.toString(),
            limit = 1
        ).firstOrNull()?.let { conversationEntityToConversation(it) }
    }

    fun getConversationsOfAssistant(assistantId: Uuid, isVirtual: Boolean = false): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString(), isVirtual = isVirtual)
            .map { list ->
                list.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
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
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
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
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
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
        return if (entity != null) {
            conversationEntityToConversation(entity)
        } else null
    }

    suspend fun getConversationById(id: String): Conversation? {
        return runCatching { getConversationById(Uuid.parse(id)) }.getOrNull()
    }

    suspend fun insertConversation(conversation: Conversation) {
        conversationDAO.insert(
            conversationToConversationEntity(conversation)
        )
    }

    suspend fun updateConversation(conversation: Conversation) {
        if (conversation.isConsolidated) {
            // 只把状态改回 false，标记该会话内容已变动，需要下次重新滚动整合
            val updatedConversation = conversation.copy(isConsolidated = false)
            conversationDAO.update(
                conversationToConversationEntity(updatedConversation)
            )
            // 删除了之前所有的 deleteEpisode/Segment 调用，由 Worker 进行覆盖更新
        } else {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
        }
    }

    suspend fun deleteConversation(conversation: Conversation, deleteFiles: Boolean = true) {
        conversationDAO.delete(
            conversationToConversationEntity(conversation)
        )
        chatEpisodeDAO.deleteEpisodeByConversationId(conversation.id.toString())
        chatSegmentDAO.deleteSegmentsByConversation(conversation.id.toString())
        if (deleteFiles) {
            context.deleteChatFiles(conversation.files)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        // Delete both normal and virtual conversations
        conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = false).first().forEach { conversation ->
            deleteConversation(conversationEntityToConversation(conversation))
        }
        conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = true).first().forEach { conversation ->
            deleteConversation(conversationEntityToConversation(conversation))
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = JsonInstant.encodeToString(conversation.messageNodes),
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            truncateIndex = conversation.truncateIndex,
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            isConsolidated = conversation.isConsolidated,
            enabledModeIds = JsonInstant.encodeToString(conversation.enabledModeIds.map { it.toString() }),
            contextSummary = conversation.contextSummary ?: "",
            contextSummaryUpToIndex = conversation.contextSummaryUpToIndex,
            lastPruneTime = conversation.lastPruneTime,
            lastPruneMessageCount = conversation.lastPruneMessageCount,
            lastRefreshTime = conversation.lastRefreshTime,
            isVirtual = conversation.isVirtual
        )
    }

    fun conversationEntityToConversation(conversationEntity: ConversationEntity): Conversation {
        val messageNodes = JsonInstant
            .decodeFromString<List<MessageNode>>(conversationEntity.nodes)
            .filter { it.messages.isNotEmpty() }
        val enabledModeIds = try {
            JsonInstant.decodeFromString<List<String>>(conversationEntity.enabledModeIds)
                .map { Uuid.parse(it) }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes,
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            truncateIndex = conversationEntity.truncateIndex,
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            isConsolidated = conversationEntity.isConsolidated,
            enabledModeIds = enabledModeIds,
            contextSummary = conversationEntity.contextSummary.takeIf { it.isNotBlank() },
            contextSummaryUpToIndex = conversationEntity.contextSummaryUpToIndex,
            lastPruneTime = conversationEntity.lastPruneTime,
            lastPruneMessageCount = conversationEntity.lastPruneMessageCount,
            lastRefreshTime = conversationEntity.lastRefreshTime,
            isVirtual = conversationEntity.isVirtual
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    suspend fun markAsConsolidated(conversationId: Uuid) {
        conversationDAO.updateConsolidatedStatus(
            id = conversationId.toString(),
            isConsolidated = true
        )
    }

    suspend fun markAsNotConsolidated(conversationId: Uuid) {
        conversationDAO.updateConsolidatedStatus(
            id = conversationId.toString(),
            isConsolidated = false
        )
    }

    suspend fun getEpisodeCount(): Int {
        return chatEpisodeDAO.getCount()
    }

    fun getEpisodeCountFlow(): Flow<Int> {
        return chatEpisodeDAO.getCountFlow()
    }

    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDAO.getAll()
            .map { list ->
                list.map { conversationEntityToConversation(it) }
            }
    }

    fun getConversationCountFlow(): Flow<Int> = conversationDAO.getConversationCountFlow()

    fun getDistinctCreateDatesFlow(): Flow<List<String>> = conversationDAO.getDistinctCreateDatesFlow()

    fun getDistinctCreateDates(): List<String> {
        return emptyList() // Not used in this project
    }

    fun getMostActiveAssistantIdFlow(): Flow<String?> = conversationDAO.getMostActiveAssistantFlow()
        .map { it?.assistantId }

    fun getConversationHoursFlow(): Flow<List<Int>> = conversationDAO.getConversationHoursFlow()

    fun getDailyActivityDatesFlow(): Flow<List<String>> = dailyActivityDAO.getAllDatesFlow()

    fun getWeeklyActivityFlow(startDate: String): Flow<List<DailyActivityEntity>> = dailyActivityDAO.getWeeklyActivityFlow(startDate)

    fun getConversationCountByAssistantFlow(assistantId: String): Flow<Int> =
        conversationDAO.getConversationCountByAssistantFlow(assistantId)

    fun getMostUsedModelIdForAssistantFlow(assistantId: String): Flow<String?> =
        conversationDAO.getConversationsOfAssistant(assistantId, isVirtual = false)
            .map { conversations ->
                val modelCounts = mutableMapOf<String, Int>()

                for (conversation in conversations) {
                    try {
                        val nodesJson = JsonInstant.parseToJsonElement(conversation.nodes)
                        if (nodesJson is JsonArray) {
                            for (nodeElement in nodesJson) {
                                val node = nodeElement.jsonObject
                                val messages = node["messages"]?.jsonArray ?: continue
                                for (messageElement in messages) {
                                    val message = messageElement.jsonObject
                                    val modelId = message["modelId"]?.jsonPrimitive?.content ?: continue
                                    modelCounts[modelId] = (modelCounts[modelId] ?: 0) + 1
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors for individual conversations
                    }
                }

                modelCounts.maxByOrNull { it.value }?.key
            }

    private fun conversationSummaryToConversation(summary: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(summary.id),
            title = summary.title,
            assistantId = Uuid.parse(summary.assistantId),
            createAt = Instant.ofEpochMilli(summary.createAt),
            updateAt = Instant.ofEpochMilli(summary.updateAt),
            isPinned = summary.isPinned,
            isConsolidated = summary.isConsolidated,
            messageNodes = emptyList(), // Summary doesn't include nodes
            isVirtual = summary.isVirtual
        )
    }

    fun getAverageMessageLength(assistantId: Uuid): Flow<Int> {
        return conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = false)
            .map { list ->
                val recent = list.take(50)
                if (recent.isEmpty()) return@map 100 // Default estimate

                var totalLength = 0L
                var messageCount = 0

                recent.forEach { entity ->
                    try {
                        val nodes = JsonInstant.decodeFromString<List<MessageNode>>(entity.nodes)
                        nodes.forEach { node ->
                            node.messages.forEach { msg ->
                                totalLength += msg.toText().length
                                messageCount++
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (messageCount > 0) {
                    (totalLength / messageCount).toInt()
                } else {
                    100 // Default
                }
            }
    }

    suspend fun recordDailyActivity() {
        val date = LocalDate.now().toString()
        dailyActivityDAO.recordActivity(date)
    }

    fun getAllVirtualMessagesOfAssistant(assistantId: Uuid): Flow<List<MessageNode>> {
        return conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = true)
            .map { conversations ->
                // 按时间升序排序，然后打平所有的消息节点
                conversations.sortedBy { it.createAt }
                    .flatMap { entity ->
                        // 解码每个会话的消息节点
                        conversationEntityToConversation(entity).messageNodes
                    }
            }
    }


    fun getVirtualConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO.getConversationsOfAssistant(assistantId.toString(), isVirtual = true)
            .map { entities ->
                entities.map { conversationEntityToConversation(it) }
            }
    }
    suspend fun migrateConversationDatesToActivity() {
        val dates = conversationDAO.getDistinctCreateDates()
        dates.forEach { date ->
            dailyActivityDAO.insertDateIfNotExists(date, System.currentTimeMillis())
        }
    }
}
