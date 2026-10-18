package me.rerere.rikkahub.core.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ChatMessageEntity
import me.rerere.rikkahub.core.data.db.entity.ChatMessageNodeEntity

@Dao
interface ChatMessageDAO {
    // --- 节点操作 ---
    @Query("DELETE FROM chat_message_nodes WHERE id IN (:nodeIds)")
    suspend fun deleteNodesByIds(nodeIds: List<String>)

    @Query("DELETE FROM chat_messages WHERE node_id IN (:nodeIds)")
    suspend fun deleteMessagesByNodeIds(nodeIds: List<String>)

    @Query("DELETE FROM chat_messages WHERE id IN (:messageIds)")
    suspend fun deleteMessagesByIds(messageIds: List<String>)

    @Transaction
    suspend fun deleteNodesAndMessages(nodeIds: List<String>) {
        deleteMessagesByNodeIds(nodeIds)
        deleteNodesByIds(nodeIds)
    }
    @Upsert
    suspend fun insertNode(node: ChatMessageNodeEntity)

    @Upsert
    suspend fun insertNodes(nodes: List<ChatMessageNodeEntity>)

    @Transaction
    suspend fun syncConversationMessages(
        conversationId: String, nodes: List<ChatMessageNodeEntity>, messages: List<ChatMessageEntity>
    ) {
        insertNodes(nodes)
        insertMessages(messages)
    }

    @Query("SELECT * FROM chat_message_nodes WHERE conversation_id = :conversationId ORDER BY order_index ASC")
    suspend fun getNodesByConversationId(conversationId: String): List<ChatMessageNodeEntity>

    @Query("SELECT * FROM chat_message_nodes WHERE conversation_id IN (:conversationIds) ORDER BY order_index ASC")
    suspend fun getNodesByConversationIds(conversationIds: List<String>): List<ChatMessageNodeEntity>

    @Query("DELETE FROM chat_message_nodes WHERE conversation_id = :conversationId")
    suspend fun deleteNodesByConversationId(conversationId: String)

    // --- 消息操作 ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_messages WHERE node_id = :nodeId ORDER BY order_index ASC")
    suspend fun getMessagesByNodeId(nodeId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE node_id IN (:nodeIds)")
    suspend fun getMessagesByNodeIds(nodeIds: List<String>): List<ChatMessageEntity>

    @Query("SELECT id FROM chat_message_nodes WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId) ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun getLatestNodeIdsOfAssistant(assistantId: String, limit: Int): List<String>

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId AND created_at >= :startTime AND created_at <= :endTime AND is_deleted = 0 ORDER BY created_at ASC")
    suspend fun getMessagesByTimeRange(conversationId: String, startTime: Long, endTime: Long): List<ChatMessageEntity>

    /**
     * 按 [startTime, endTime] 闭区间拉取指定会话的消息（按创建时间升序），
     * 仅取每个节点的当前选中版本（JOIN 节点表按 m.order_index = n.select_index 过滤）。
     *
     * 用于手动重新生成片段记忆 / 查看 segment 原始消息，避免同一节点下多个版本
     * （刷新追加版本场景）污染 segment 摘要，与 [getMessagesForSegmentSummary] 的
     * 版本过滤策略保持一致。
     */
    @Query("""
        SELECT m.* FROM chat_messages m
        INNER JOIN chat_message_nodes n ON m.node_id = n.id
        WHERE m.conversation_id = :conversationId
          AND m.is_deleted = 0
          AND m.order_index = n.select_index
          AND m.created_at >= :startTime
          AND m.created_at <= :endTime
        ORDER BY m.created_at ASC
    """)
    suspend fun getMessagesByTimeRangeForSegment(conversationId: String, startTime: Long, endTime: Long): List<ChatMessageEntity>

    @Query("SELECT n.order_index FROM chat_message_nodes n JOIN chat_messages m ON n.id = m.node_id WHERE m.id = :messageId")
    suspend fun getNodeOrderIndexByMessageId(messageId: String): Int?

    @Query("UPDATE chat_messages SET is_deleted = 1 WHERE id = :messageId")
    suspend fun markMessageAsDeleted(messageId: String)

    @Query("SELECT * FROM chat_messages WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId) AND content_json LIKE '%' || :query || '%' AND is_deleted = 0 ORDER BY created_at DESC")
    fun searchMessagesOfAssistant(assistantId: String, query: String): Flow<List<ChatMessageEntity>>

    /**
     * 按 callId 拉取某会话内归属于该次通话的消息（按创建时间升序）。
     *
     * content_json 序列化后包含 `"callId":"<id>"`，因此用 LIKE 粗筛，再由调用方按 callId +
     * messageSource == CALL 精确过滤（防止子串误命中）。callId 唯一，结果集规模有限。
     */
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId AND content_json LIKE '%' || :callId || '%' AND is_deleted = 0 ORDER BY created_at ASC")
    suspend fun getMessagesByCallId(conversationId: String, callId: String): List<ChatMessageEntity>

    @Query("SELECT node_id FROM chat_messages WHERE id = :messageId")
    suspend fun getNodeIdByMessageId(messageId: String): String?

    @Query("SELECT conversation_id FROM chat_messages WHERE id = :messageId")
    suspend fun getConversationIdByMessageId(messageId: String): String?

    @Query("SELECT conversation_id FROM chat_message_nodes WHERE id = :nodeId")
    suspend fun getConversationIdByNodeId(nodeId: String): String?

    @Query("SELECT COUNT(*) FROM chat_message_nodes n INNER JOIN conversationentity c ON n.conversation_id = c.id WHERE c.assistant_id = :assistantId AND (n.created_at > (SELECT n2.created_at FROM chat_message_nodes n2 WHERE n2.id = (SELECT node_id FROM chat_messages WHERE id = :messageId)) OR (n.created_at = (SELECT n2.created_at FROM chat_message_nodes n2 WHERE n2.id = (SELECT node_id FROM chat_messages WHERE id = :messageId)) AND n.id >= (SELECT node_id FROM chat_messages WHERE id = :messageId)))")
    suspend fun getMessageGlobalDepth(assistantId: String, messageId: String): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = :convId AND created_at > :lastTime AND is_deleted = 0")
    suspend fun countNewMessages(convId: String, lastTime: Long): Int

    /**
     * 按「(created_at, id)」复合游标查询待总结消息，并仅取每个节点的当前选中版本。
     *
     * - 当 [lastId] 为空时（老数据 / 未设置）：退化为仅按 created_at > :lastTime 比较；
     * - 当 [lastId] 不为空时：created_at > :lastTime 或 (created_at = :lastTime AND id > :lastId)，
     *   配合 ORDER BY created_at ASC, id ASC 彻底解决同毫秒时间戳组边界遗漏问题。
     * - JOIN chat_message_nodes 后按 m.order_index = n.select_index 过滤，确保同一节点下
     *   多个版本消息（刷新追加版本场景）只返回当前选中版本，避免历史版本污染 segment 摘要。
     *
     * @param upperTime 上界时间戳，0 表示不设上界；非 0 时排除 >= 上界的消息
     * @param upperId 上界消息 id，空字符串表示不设上界；非空时排除 (created_at = upperTime AND id >= upperId)
     */
    @Query("""
        SELECT m.* FROM chat_messages m
        INNER JOIN chat_message_nodes n ON m.node_id = n.id
        WHERE m.conversation_id = :convId
          AND m.is_deleted = 0
          AND m.order_index = n.select_index
          AND (
            (:lastId = '') AND m.created_at > :lastTime
            OR (:lastId <> '') AND (m.created_at > :lastTime OR (m.created_at = :lastTime AND m.id > :lastId))
          )
          AND (
            :upperTime = 0
            OR m.created_at < :upperTime
            OR (m.created_at = :upperTime AND m.id < :upperId)
          )
        ORDER BY m.created_at ASC, m.id ASC
        LIMIT :limit
    """)
    suspend fun getMessagesForSummary(
        convId: String,
        lastTime: Long,
        lastId: String,
        limit: Int,
        upperTime: Long = 0L,
        upperId: String = ""
    ): List<ChatMessageEntity>

    @Deprecated("Use getMessagesForSummary(convId, lastTime, lastId, limit) instead. 保留用于 L2 归档等纯时间窗口场景。")
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :convId AND created_at > :lastTime AND is_deleted = 0 ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun getMessagesForSummary(convId: String, lastTime: Long, limit: Int): List<ChatMessageEntity>

    /**
     * 获取会话内最后一条"当前选中版本"消息的 (created_at, id) 游标。
     * 同样 JOIN 节点表按 order_index = select_index 过滤，确保拿到的是用户实际看到
     * 的最新版本而非被刷新抛弃的旧版本。
     *
     * 返回 null 表示会话无可见消息。
     */
    @Query("""
        SELECT m.created_at, m.id FROM chat_messages m
        INNER JOIN chat_message_nodes n ON m.node_id = n.id
        WHERE m.conversation_id = :convId
          AND m.is_deleted = 0
          AND m.order_index = n.select_index
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT 1
    """)
    suspend fun getLatestSelectedMessageCursor(convId: String): me.rerere.rikkahub.core.data.db.entity.MessageCursor?

    // --- 滑动窗口分页查询 ---
    // 注意: 这些查询仅返回节点实体 (ChatMessageNodeEntity)，不再使用 @Relation 加载消息。
    // 消息内容由 ConversationRepository 通过 getMessagesByNodeIds 分批加载，
    // 避免 content_json 过大时 CursorWindow (2MB) 溢出导致闪退。

    /**
     * 获取最新 N 个节点。排序键为节点创建时间与节点 ID，二者均不可变。
     */
    @Query("""
        SELECT n.* FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        ORDER BY n.created_at DESC, n.id DESC
        LIMIT :limit
    """)
    suspend fun getLatestNodesWithMetadata(assistantId: String, limit: Int): List<ChatMessageNodeEntity>

    /**
     * 直接解析搜索结果所属节点。搜索结果传入的是 message id，而分页游标使用 node id。
     */
    @Query("""
        SELECT n.* FROM chat_message_nodes n
        INNER JOIN chat_messages m ON m.node_id = n.id
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
          AND m.id = :messageId
          AND m.is_deleted = 0
        LIMIT 1
    """)
    suspend fun getNodeContainingMessage(
        assistantId: String,
        messageId: String
    ): ChatMessageNodeEntity?

    /**
     * 加载比当前锚点更旧的节点（向上滚动历史）
     */
    @Query("""
        SELECT n.* FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        AND (
            n.created_at < :anchorCreatedAt
            OR (n.created_at = :anchorCreatedAt AND n.id < :anchorNodeId)
        )
        ORDER BY n.created_at DESC, n.id DESC
        LIMIT :limit
    """)
    suspend fun getNodesOlderThan(
        assistantId: String,
        anchorCreatedAt: Long,
        anchorNodeId: String,
        limit: Int
    ): List<ChatMessageNodeEntity>

    /**
     * 加载比当前锚点更新的节点（向下滚动回最新区域）
     */
    @Query("""
        SELECT n.* FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        AND (
            n.created_at > :anchorCreatedAt
            OR (n.created_at = :anchorCreatedAt AND n.id > :anchorNodeId)
        )
        ORDER BY n.created_at ASC, n.id ASC
        LIMIT :limit
    """)
    suspend fun getNodesNewerThan(
        assistantId: String,
        anchorCreatedAt: Long,
        anchorNodeId: String,
        limit: Int
    ): List<ChatMessageNodeEntity>

    /**
     * 获取指定会话最新的 N 个节点
     */
    @Query("SELECT * FROM chat_message_nodes WHERE conversation_id = :conversationId ORDER BY order_index DESC LIMIT :limit")
    suspend fun getNodesWithMessagesOfConversation(conversationId: String, limit: Int): List<ChatMessageNodeEntity>

    @Query("SELECT COUNT(*) FROM chat_message_nodes n INNER JOIN conversationentity c ON n.conversation_id = c.id WHERE c.assistant_id = :assistantId")
    suspend fun getTotalNodeCountByAssistant(assistantId: String): Int
}
