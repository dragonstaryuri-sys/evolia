package me.rerere.rikkahub.core.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import me.rerere.rikkahub.core.data.model.Assistant
import kotlin.uuid.Uuid
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConversationRepository(
    private val context: Context,
    private val db: RoomDatabase,
    private val conversationDAO: ConversationDAO,
    val chatMessageDAO: ChatMessageDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val chatSegmentDAO: ChatSegmentDAO,
    private val dailyActivityDAO: DailyActivityDAO,
    private val tokenUsageDAO: TokenUsageDAO,
    private val scheduleDAO: ScheduleDAO,
) {
    companion object {
        private const val PAGE_SIZE = 30
        private const val INITIAL_LOAD_SIZE = 100
        private const val TAG = "ConversationRepo"
    }

    /**
     * 获取所有智能体的最后一条消息内容（高效版）
     */
    fun getAssistantsLastMessagesFlow(): Flow<Map<Uuid, String>> {
        return conversationDAO.getAssistantsLastMessagesFlow()
            .map { list ->
                list.associate { result ->
                    val content = result.content?.let { json ->
                        try {
                            JsonInstant.decodeFromString<UIMessage>(json).toContentText()
                        } catch (e: Exception) {
                            ""
                        }
                    } ?: ""
                    Uuid.parse(result.assistantId) to content
                }
            }
            .flowOn(Dispatchers.IO)
    }


    suspend fun getLatestConversation(assistantId: Uuid): Conversation? {
        return conversationDAO.getRecentConversationsOfAssistantAnyMode(
            assistantId = assistantId.toString(),
            limit = 1
        ).firstOrNull()?.let { fetchFullConversation(it) }
    }


    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { list -> fetchFullConversations(list) }
    }

    fun getConversationsOfAssistantAnyMode(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistantAnyMode(assistantId.toString())
            .map { list -> fetchFullConversations(list) }
    }


    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { list -> fetchFullConversations(list) }
    }


    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsOfAssistantPaging(assistantId.toString(), titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationById(uuid: Uuid, targetMessageId: String? = null): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return entity?.let { fetchFullConversation(it, targetMessageId) }
    }


    suspend fun getConversationById(id: String, targetMessageId: String? = null): Conversation? {
        return runCatching { getConversationById(Uuid.parse(id), targetMessageId) }.getOrNull()
    }

    private suspend fun fetchFullConversation(entity: ConversationEntity, targetMessageId: String? = null): Conversation {
        return fetchFullConversations(listOf(entity), targetMessageId).first()
    }

    suspend fun getLatestNodesByAssistant(assistantId: Uuid, limit: Int): List<MessageNode> = withContext(Dispatchers.IO) {
        // 将 getLatestNodesWithMessagesOfAssistant 修正为 getLatestNodesWithMetadata
        chatMessageDAO.getLatestNodesWithMetadata(assistantId.toString(), limit).map { wrapper ->
            mapMetadataToNode(wrapper)
        }
    }

    // ✨ 修复：添加获取指定会话节点的方法
    suspend fun getNodesOfConversation(conversationId: Uuid, limit: Int): List<MessageNode> = withContext(Dispatchers.IO) {
        chatMessageDAO.getNodesWithMessagesOfConversation(conversationId.toString(), limit).map { wrapper ->
            val uiMessages = wrapper.messages
                .filter { !it.isDeleted }
                .sortedBy { it.orderIndex }
                .map { JsonInstant.decodeFromString<UIMessage>(it.contentJson) }

            MessageNode(
                id = Uuid.parse(wrapper.node.id),
                messages = uiMessages,
                selectIndex = wrapper.node.selectIndex,
                conversationId = Uuid.parse(wrapper.node.conversationId)
            )
        }
    }

    private suspend fun fetchFullConversations(
        entities: List<ConversationEntity>,
        targetMessageId: String? = null // ✨ 新增参数
    ): List<Conversation> = withContext(Dispatchers.IO) {
        if (entities.isEmpty()) return@withContext emptyList()
        // 1. 确定我们要加载哪个智能体
        val firstAssistantId = entities.first().assistantId
        // 2. ✨ 核心变更：计算出“全局最新 N 条”或“包含目标消息”的消息 ID 集合
        val nodeIdsToLoad = if (!targetMessageId.isNullOrBlank()) {
            // 如果是搜索跳转，查出该消息的深度，确保加载范围能覆盖到它
            val depth = chatMessageDAO.getMessageGlobalDepth(firstAssistantId, targetMessageId)
            chatMessageDAO.getLatestNodeIdsOfAssistant(firstAssistantId, (depth + 20).coerceAtLeast(100))
        } else {
            // ✨ 动态加载：根据 truncateIndex 调整加载深度，确保 AI 截断逻辑有足够上下文
            val maxTruncate = entities.maxOfOrNull { it.truncateIndex } ?: 0
            val loadLimit = (maxTruncate + 100).coerceAtLeast(200)
            chatMessageDAO.getLatestNodeIdsOfAssistant(firstAssistantId, loadLimit)
        }.toSet()
        // 3. 批量获取这些节点的实际消息内容 (contentJson)
        val allMessages = if (nodeIdsToLoad.isNotEmpty()) {
            nodeIdsToLoad.chunked(900).flatMap { batch ->
                chatMessageDAO.getMessagesByNodeIds(batch)
            }.groupBy { it.nodeId }
        } else emptyMap()
        // 4. 获取所有涉及的节点占位符（用于 UI 排序和分页定位）
        val convIds = entities.map { it.id }
        val allNodesRaw = convIds.chunked(900).flatMap { batch ->
            chatMessageDAO.getNodesByConversationIds(batch)
        }.groupBy { it.conversationId }
        // 5. 组装数据
        entities.map { entity ->
            val nodesFromDb = allNodesRaw[entity.id] ?: emptyList()
            val messageNodes = nodesFromDb.sortedBy { it.orderIndex }.map { nodeEntity ->
                // ✨ 关键点：只有在这个节点属于“待加载范围”时，才解析它的内容
                val messages = if (nodeEntity.id in nodeIdsToLoad) {
                    allMessages[nodeEntity.id]
                        ?.filter { !it.isDeleted }
                        ?.map { JsonInstant.decodeFromString<UIMessage>(it.contentJson) }
                        ?: emptyList()
                } else {
                    emptyList() // 否则保持为空消息列表，作为占位符
                }

                MessageNode(
                    id = Uuid.parse(nodeEntity.id),
                    messages = messages,
                    selectIndex = nodeEntity.selectIndex,
                    conversationId = Uuid.parse(nodeEntity.conversationId)
                )
            }

            // 兼容旧版本字段
            val oldNodes = try {
                if (entity.nodes.isNotBlank() && entity.nodes != "[]") {
                    JsonInstant.decodeFromString<List<MessageNode>>(entity.nodes).takeLast(200)
                } else emptyList()
            } catch (e: Exception) { emptyList() }

            val finalNodes = if (oldNodes.isEmpty()) messageNodes
            else if (messageNodes.isEmpty()) oldNodes
            else {
                val newIds = messageNodes.map { it.id }.toSet()
                oldNodes.filter { it.id !in newIds } + messageNodes
            }

            conversationEntityToConversation(entity, finalNodes)
        }
    }

    /**
     * 加载更多历史消息
     * @param conversationId 会话 ID
     * @param alreadyLoadedNodeIds 已经加载过消息内容的节点 ID 集合
     */
    suspend fun loadMoreMessages(conversationId: Uuid, alreadyLoadedNodeIds: Set<Uuid>): List<MessageNode> = withContext(Dispatchers.IO) {
        val allNodes = chatMessageDAO.getNodesByConversationId(conversationId.toString())
        val pendingNodes = allNodes.filter { Uuid.parse(it.id) !in alreadyLoadedNodeIds }
            .sortedByDescending { it.orderIndex }
            .takeLast(50)

        if (pendingNodes.isEmpty()) return@withContext emptyList()

        val messagesMap = pendingNodes.map { it.id }.chunked(900).flatMap { batch ->
            chatMessageDAO.getMessagesByNodeIds(batch)
        }.groupBy { it.nodeId }

        pendingNodes.map { nodeEntity ->
            val messages = messagesMap[nodeEntity.id]
                ?.filter { !it.isDeleted }
                ?.map { JsonInstant.decodeFromString<UIMessage>(it.contentJson) }
                ?: emptyList()

            MessageNode(
                id = Uuid.parse(nodeEntity.id),
                messages = messages,
                selectIndex = nodeEntity.selectIndex,
                conversationId = Uuid.parse(nodeEntity.conversationId)
            )
        }
    }

    /**
     * 强制迁移所有尚未拆表的会话数据。
     */
    suspend fun migrateAllOldConversations() = withContext(Dispatchers.IO) {
        try {
            val allEntities = conversationDAO.getAll().first()
            val entitiesToMigrate = allEntities.filter { it.nodes.isNotBlank() && it.nodes != "[]" }

            Log.i(TAG, "检测到 ${entitiesToMigrate.size} 个待拆表迁移的会话")

            if (entitiesToMigrate.isEmpty()) return@withContext

            db.withTransaction {
                entitiesToMigrate.forEach { entity ->
                    try {
                        val fullConversation = fetchFullConversation(entity)
                        syncMessages(fullConversation)
                        conversationDAO.update(entity.copy(nodes = ""))
                    } catch (e: Exception) {
                        Log.e(TAG, "迁移单个会话数据失败: ${entity.id}", e)
                    }
                }
            }
            Log.i(TAG, "所有旧会话数据拆表迁移完成")
        } catch (e: Exception) {
            Log.e(TAG, "执行迁移任务全局失败", e)
        }
    }

    /**
     * 从智能体主记忆 (L3) 中提取待办事项并迁移到 schedules 表。
     * 返回处理后的 Assistant 列表（已清除主智能体中的冗余待办内容）
     */
    suspend fun extractSchedulesFromAssistants(assistants: List<Assistant>): List<Assistant> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始从智能体提取 L3 待办...")
            val writableDb = db.openHelper.writableDatabase
            assistants.map { assistant ->
                if (assistant.isMain && assistant.masterMemoryContent.contains("约定与待办")) {
                    val masterContent = assistant.masterMemoryContent
                    val section = masterContent.substringAfter("约定与待办")
                        .substringBefore("##")
                        .trim()

                    val lines = section.split("\n")
                        .map { it.trim().trimStart('-', '*', ' ', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ':', '：') }
                        .filter { it.isNotBlank() && !it.startsWith("#") }

                    lines.forEach { line ->
                        val values = ContentValues().apply {
                            put("title", line)
                            put("content", "来自原记忆档案提取")
                            put("start_time", System.currentTimeMillis())
                            put("is_completed", 0) // 标为未完成
                            put("category", "assistant")
                            put("created_at", System.currentTimeMillis())
                            put("updated_at", System.currentTimeMillis())
                            put("priority", 1)
                            put("urgency", 1)
                            put("difficulty", 1)
                        }
                        writableDb.insert("schedules", SQLiteDatabase.CONFLICT_IGNORE, values)
                    }

                    // 【新逻辑】：删除已提取的内容和标题
                    val textIndex = masterContent.indexOf("约定与待办")
                    val hashIndex = masterContent.lastIndexOf("##", textIndex)
                    // 寻找标题起点，兼容 "## 1. 约定与待办"
                    val startIndex = if (hashIndex != -1 && masterContent.substring(hashIndex, textIndex).length < 20) hashIndex else textIndex
                    val nextHashIndex = masterContent.indexOf("##", textIndex + 5)

                    val newContent = if (nextHashIndex != -1) {
                        (masterContent.substring(0, startIndex).trimEnd() + "\n\n" + masterContent.substring(nextHashIndex).trimStart()).trim()
                    } else {
                        masterContent.substring(0, startIndex).trim()
                    }
                    assistant.copy(masterMemoryContent = newContent)
                } else {
                    assistant
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取 L3 待办失败", e)
            assistants
        } finally {
            Log.i(TAG, "L3 待办提取完成")
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
            val nodeCreatedAt = node.messages
                .minOfOrNull { it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() }
                ?: System.currentTimeMillis()
            ChatMessageNodeEntity(
                id = node.id.toString(),
                conversationId = convId,
                selectIndex = node.selectIndex,
                orderIndex = index,
                createdAt = nodeCreatedAt
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
                    orderIndex = index,
                    isDeleted = false
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
        conversationDAO.getConversationsOfAssistant(assistantId.toString()).first().forEach {
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
            lastSummarizedMessageTime = conversation.lastSummarizedMessageTime,
            lastPruneTime = conversation.lastPruneTime,
            lastPruneMessageCount = conversation.lastPruneMessageCount,
            lastRefreshTime = conversation.lastRefreshTime,
            isVirtual = false
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
            lastSummarizedMessageTime = entity.lastSummarizedMessageTime,
            lastPruneTime = entity.lastPruneTime,
            lastPruneMessageCount = entity.lastPruneMessageCount,
            lastRefreshTime = entity.lastRefreshTime
        )
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

    suspend fun updateTruncateIndex(conversationId: Uuid, truncateIndex: Int) {
        conversationDAO.updateTruncateIndex(id = conversationId.toString(), truncateIndex = truncateIndex)
    }

    suspend fun deleteNodes(nodeIds: List<Uuid>) = withContext(Dispatchers.IO) {
        chatMessageDAO.deleteNodesAndMessages(nodeIds.map { it.toString() })
    }

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
            messageNodes = emptyList()
        )
    }

    fun getAverageMessageLength(assistantId: Uuid): Flow<Int> {
        return chatMessageDAO.getAllMessagesContentByAssistant(assistantId.toString())
            .map { jsonList ->
                if (jsonList.isEmpty()) return@map 100
                val totalLength = jsonList.sumOf {
                    JsonInstant.decodeFromString<UIMessage>(it).toContentText().length.toLong()
                }
                (totalLength / jsonList.size).toInt()
            }
            .flowOn(Dispatchers.IO)
    }

    // ==================================================================================
    // ✨ 新增：游标双向滑动窗口分页 (Cursor-based Bidirectional Pagination)
    // ==================================================================================

    /**
     * 游标锚点
     */
    data class NodeCursor(val createdAt: Long, val nodeId: String)

    enum class PageLoadDirection {
        OLDER,
        NEWER
    }

    /**
     * 分页状态机
     */
    sealed class ChatPaginationState {
        data object Idle : ChatPaginationState()
        data object Loading : ChatPaginationState()
        data class Success(
            val nodes: List<MessageNode>,
            val hasOlder: Boolean,
            val hasNewer: Boolean,
            val loadingDirection: PageLoadDirection? = null,
            val errorDirection: PageLoadDirection? = null,
            val error: Throwable? = null
        ) : ChatPaginationState()
        data class Error(val cause: Throwable) : ChatPaginationState()
    }

    /**
     * 创建消息分页管理器工厂方法
     */
    fun createPaginationManager(assistantId: Uuid): MessagePaginationManager {
        return MessagePaginationManager(assistantId)
    }

    /**
     * 双向滑动窗口管理器
     *
     * 节点按不可变的 (createdAt, nodeId) 排序。会话更新、输入法布局变化和窗口
     * 裁剪都不会改变游标的相对顺序。
     */
    inner class MessagePaginationManager(private val assistantId: Uuid) {
        private val WINDOW_LIMIT = 1000
        private val BATCH_SIZE = 100

        private val mutex = Mutex()
        private val _currentNodes = mutableListOf<MessageNode>()

        // 内部标记：hasNewer 仅代表窗口曾经裁剪过头部消息，不代表数据库绝对存在新消息
        private var hasOlder = false
        private var hasNewer = false
        private val _state = MutableStateFlow<ChatPaginationState>(ChatPaginationState.Idle)
        // ✨ 新增：对外暴露的只读流
        val state: StateFlow<ChatPaginationState> = _state.asStateFlow()

        private fun publish(
            loadingDirection: PageLoadDirection? = null,
            errorDirection: PageLoadDirection? = null,
            error: Throwable? = null
        ): ChatPaginationState.Success {
            return ChatPaginationState.Success(
                nodes = _currentNodes.toList(),
                hasOlder = hasOlder,
                hasNewer = hasNewer,
                loadingDirection = loadingDirection,
                errorDirection = errorDirection,
                error = error
            ).also { _state.value = it }
        }

        /**
         * 加载初始窗口（最新的 BATCH_SIZE 条）
         */
        suspend fun loadInitial(): ChatPaginationState = mutex.withLock {
            _state.value = ChatPaginationState.Loading
            return@withLock try {
                val results = withContext(Dispatchers.IO) {
                    chatMessageDAO.getLatestNodesWithMetadata(assistantId.toString(), BATCH_SIZE + 1)
                }
                hasOlder = results.size > BATCH_SIZE
                hasNewer = false

                val nodes = results.take(BATCH_SIZE).map { mapMetadataToNode(it) }.reversed()
                _currentNodes.clear()
                _currentNodes.addAll(nodes)

                Log.d(TAG, "pagination initial: nodes=${nodes.size}, hasOlder=$hasOlder")

                // ✨ 3. 更新状态流并返回
                publish()
            } catch (e: Exception) {
                val errorState = ChatPaginationState.Error(e)
                _state.value = errorState
                errorState
            }
        }

        /**
         * 以搜索命中的消息节点为中心建立窗口，避免从最新消息逐页扫描到目标。
         */
        suspend fun loadAroundMessage(messageId: String): ChatPaginationState = mutex.withLock {
            _state.value = ChatPaginationState.Loading
            return@withLock try {
                val assistantIdValue = assistantId.toString()
                val sideSize = BATCH_SIZE / 2
                val target = withContext(Dispatchers.IO) {
                    chatMessageDAO.getNodeContainingMessage(assistantIdValue, messageId)
                } ?: return@withLock loadInitialLocked()

                val olderResults = withContext(Dispatchers.IO) {
                    chatMessageDAO.getNodesOlderThan(
                        assistantIdValue,
                        target.node.createdAt,
                        target.node.id,
                        sideSize + 1
                    )
                }
                val newerResults = withContext(Dispatchers.IO) {
                    chatMessageDAO.getNodesNewerThan(
                        assistantIdValue,
                        target.node.createdAt,
                        target.node.id,
                        sideSize + 1
                    )
                }

                val olderNodes = olderResults.take(sideSize).map(::mapMetadataToNode).reversed()
                val targetNode = mapMetadataToNode(
                    wrapper = target,
                    selectedMessageId = messageId
                )
                val newerNodes = newerResults.take(sideSize).map(::mapMetadataToNode)

                _currentNodes.clear()
                _currentNodes.addAll(olderNodes)
                _currentNodes.add(targetNode)
                _currentNodes.addAll(newerNodes)
                hasOlder = olderResults.size > sideSize
                hasNewer = newerResults.size > sideSize

                Log.d(
                    TAG,
                    "pagination target: message=$messageId, node=${target.node.id}, " +
                        "window=${_currentNodes.size}, hasOlder=$hasOlder, hasNewer=$hasNewer"
                )
                publish()
            } catch (e: Exception) {
                Log.w(TAG, "pagination target failed: message=$messageId", e)
                val errorState = ChatPaginationState.Error(e)
                _state.value = errorState
                errorState
            }
        }

        /**
         * mutex 已持有时使用，避免目标不存在时递归获取同一把锁。
         */
        private suspend fun loadInitialLocked(): ChatPaginationState {
            return try {
                val results = withContext(Dispatchers.IO) {
                    chatMessageDAO.getLatestNodesWithMetadata(assistantId.toString(), BATCH_SIZE + 1)
                }
                hasOlder = results.size > BATCH_SIZE
                hasNewer = false
                val nodes = results.take(BATCH_SIZE).map(::mapMetadataToNode).reversed()
                _currentNodes.clear()
                _currentNodes.addAll(nodes)
                Log.w(TAG, "pagination target missing; fallback to latest window")
                publish()
            } catch (e: Exception) {
                val errorState = ChatPaginationState.Error(e)
                _state.value = errorState
                errorState
            }
        }

        suspend fun loadOlder(): ChatPaginationState = mutex.withLock {
            if (!hasOlder) return@withLock _state.value

            return@withLock try {
                publish(loadingDirection = PageLoadDirection.OLDER)
                val anchorNode = _currentNodes.firstOrNull() ?: return@withLock ChatPaginationState.Idle

                val results = withContext(Dispatchers.IO) {
                    chatMessageDAO.getNodesOlderThan(
                        assistantId.toString(),
                        anchorNode.timelineCreatedAt,
                        anchorNode.id.toString(),
                        BATCH_SIZE + 1
                    )
                }

                val newOldNodes = results.take(BATCH_SIZE).map { mapMetadataToNode(it) }.reversed()
                _currentNodes.addAll(0, newOldNodes)
                hasOlder = results.size > BATCH_SIZE

                if (_currentNodes.size > WINDOW_LIMIT) {
                    val excessCount = _currentNodes.size - WINDOW_LIMIT
                    // ✨ 修正：加载历史时，删掉最底端最新的消息
                    repeat(excessCount) { _currentNodes.removeAt(_currentNodes.size - 1) }
                    hasNewer = true
                }

                Log.d(TAG, "pagination older: cursor=${anchorNode.timelineCreatedAt}/${anchorNode.id}, added=${newOldNodes.size}, window=${_currentNodes.size}, hasOlder=$hasOlder")

                publish()
            } catch (e: Exception) {
                Log.w(TAG, "pagination older failed: window=${_currentNodes.size}", e)
                publish(errorDirection = PageLoadDirection.OLDER, error = e)
            }
        }

        suspend fun loadNewer(): ChatPaginationState = mutex.withLock {
            if (!hasNewer && _currentNodes.isNotEmpty()) return@withLock _state.value

            return@withLock try {
                publish(loadingDirection = PageLoadDirection.NEWER)
                val anchorNode = _currentNodes.lastOrNull() ?: return@withLock ChatPaginationState.Idle

                val results = withContext(Dispatchers.IO) {
                    chatMessageDAO.getNodesNewerThan(
                        assistantId.toString(),
                        anchorNode.timelineCreatedAt,
                        anchorNode.id.toString(),
                        BATCH_SIZE + 1
                    )
                }

                val newNewerNodes = results.take(BATCH_SIZE).map { mapMetadataToNode(it) }
                _currentNodes.addAll(newNewerNodes)
                hasNewer = results.size > BATCH_SIZE

                if (_currentNodes.size > WINDOW_LIMIT) {
                    val excessCount = _currentNodes.size - WINDOW_LIMIT
                    // ✨ 修正：加载最新时，删掉最顶端最旧的消息
                    repeat(excessCount) { _currentNodes.removeAt(0) }
                    hasOlder = true
                }

                Log.d(TAG, "pagination newer: cursor=${anchorNode.timelineCreatedAt}/${anchorNode.id}, added=${newNewerNodes.size}, window=${_currentNodes.size}, hasNewer=$hasNewer")

                publish()
            } catch (e: Exception) {
                Log.w(TAG, "pagination newer failed: window=${_currentNodes.size}", e)
                publish(errorDirection = PageLoadDirection.NEWER, error = e)
            }
        }

        suspend fun retry(): ChatPaginationState {
            val currentState = _state.value
            return when (currentState) {
                is ChatPaginationState.Error -> loadInitial()
                is ChatPaginationState.Success -> when (currentState.errorDirection) {
                    PageLoadDirection.OLDER -> loadOlder()
                    PageLoadDirection.NEWER -> loadNewer()
                    null -> currentState
                }
                else -> currentState
            }
        }

        suspend fun injectNewNode(node: MessageNode) = mutex.withLock {
            if (!hasNewer) {
                val normalizedNode = if (node.timelineCreatedAt > 0L) {
                    node
                } else {
                    val createdAt = node.messages
                        .minOfOrNull { it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() }
                        ?: System.currentTimeMillis()
                    node.copy(timelineCreatedAt = createdAt)
                }
                val index = _currentNodes.indexOfFirst { it.id == normalizedNode.id }
                if (index != -1) {
                    if (_currentNodes[index] != normalizedNode) {
                        _currentNodes[index] = normalizedNode
                        publish()
                    }
                }  else {
                _currentNodes.add(normalizedNode)
                    if (_currentNodes.size > WINDOW_LIMIT) {
                    _currentNodes.removeAt(0)
                    hasOlder = true
                }
                publish()
            }
            }
        }

        /**
         * 清空分页缓存
         */
        suspend fun clear() = mutex.withLock {
            _currentNodes.clear()
            hasOlder = false
            hasNewer = false
            _state.value = ChatPaginationState.Idle
        }
    }

    /**
     * 辅助方法：将 DAO 返回的带元数据节点转换为 UI 节点模型
     */
    private fun mapMetadataToNode(
        wrapper: MessageNodeWithMetadata,
        selectedMessageId: String? = null
    ): MessageNode {
        val uiMessages = wrapper.messages
            .filter { !it.isDeleted }
            .sortedBy { it.orderIndex }
            .map { JsonInstant.decodeFromString<UIMessage>(it.contentJson) }
        val targetMessageIndex = selectedMessageId?.let { targetId ->
            uiMessages.indexOfFirst { message -> message.id.toString() == targetId }
                .takeIf { it >= 0 }
        }

        return MessageNode(
            id = Uuid.parse(wrapper.node.id),
            messages = uiMessages,
            selectIndex = targetMessageIndex ?: wrapper.node.selectIndex,
            conversationId = Uuid.parse(wrapper.node.conversationId),
            orderIndex = wrapper.node.orderIndex,
            timelineCreatedAt = wrapper.node.createdAt
        )
    }

    // ==================================================================================
    // 保留原有业务函数
    // ==================================================================================

    suspend fun recordDailyActivity() {
        val date = LocalDate.now().toString()
        dailyActivityDAO.recordActivity(date)
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

    /**
     * 逻辑删除单条消息
     */
    suspend fun markMessageAsDeleted(messageId: Uuid) {
        chatMessageDAO.markMessageAsDeleted(messageId.toString())
    }

    /**
     * 统计指定时间戳之后的新消息数量
     */
    suspend fun countNewMessages(convId: String, lastTime: Long): Int {
        return chatMessageDAO.countNewMessages(convId, lastTime)
    }

    /**
     * 获取指定时间戳之后的消息列表用于总结
     */
    suspend fun getMessagesForSummary(convId: String, lastTime: Long, limit: Int = 100): List<ChatMessageEntity> {
        return chatMessageDAO.getMessagesForSummary(convId, lastTime, limit)
    }

    /**
     * 重新计算所有消息节点的创建时间（基于其包含的消息中最早的一条）。
     * 用于修复还原备份后可能存在的时间戳缺失，或修复因旧版本 Bug 导致的创建时间乱序。
     */
    suspend fun recomputeNodeTimestamps() = withContext(Dispatchers.IO) {
        try {
            // 使用原生 SQL 更新，因为 Room 本身不支持跨表 Update 的简单映射
            db.openHelper.writableDatabase.execSQL(
                """
            UPDATE `chat_message_nodes`
            SET `created_at` = COALESCE(
                (SELECT MIN(`created_at`) FROM `chat_messages`
                 WHERE `chat_messages`.`node_id` = `chat_message_nodes`.`id`),
                `created_at`
            )
            WHERE `created_at` <= 0
               OR `created_at` > ${System.currentTimeMillis() - 5000}
            """.trimIndent()
            )
            Log.i(TAG, "已完成消息节点时间戳重新校准")
        } catch (e: Exception) {
            Log.e(TAG, "校准消息节点时间戳失败", e)
        }
    }

}
