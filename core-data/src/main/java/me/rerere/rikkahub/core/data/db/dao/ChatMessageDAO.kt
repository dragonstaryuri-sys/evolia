package me.rerere.rikkahub.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ChatMessageEntity
import me.rerere.rikkahub.core.data.db.entity.ChatMessageNodeEntity

@Dao
interface ChatMessageDAO {
    // --- 节点操作 ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: ChatMessageNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<ChatMessageNodeEntity>)

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

    @Query("SELECT content_json FROM chat_messages WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = 0)")
    fun getAllMessagesContentByAssistant(assistantId: String): Flow<List<String>>
    @Query("""
    SELECT id FROM chat_message_nodes
    WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId)
    ORDER BY (SELECT update_at FROM conversationentity WHERE id = conversation_id) DESC, order_index DESC
    LIMIT :limit
""")
    suspend fun getLatestNodeIdsOfAssistant(assistantId: String, limit: Int): List<String>
    @Transaction
    suspend fun syncConversationMessages(
        conversationId: String, nodes: List<ChatMessageNodeEntity>, messages: List<ChatMessageEntity>
    ) {
        deleteNodesByConversationId(conversationId)
        insertNodes(nodes)
        insertMessages(messages)
    }
    @Query("""
    SELECT * FROM chat_messages
    WHERE conversation_id = :conversationId
    AND created_at >= :startTime
    AND created_at <= :endTime
    AND is_deleted = 0
    ORDER BY created_at ASC
""")
    suspend fun getMessagesByTimeRange(conversationId: String, startTime: Long, endTime: Long): List<ChatMessageEntity>

    @Query("SELECT n.order_index FROM chat_message_nodes n JOIN chat_messages m ON n.id = m.node_id WHERE m.id = :messageId")
    suspend fun getNodeOrderIndexByMessageId(messageId: String): Int?
    @Query("UPDATE chat_messages SET is_deleted = 1 WHERE id = :messageId")
    suspend fun markMessageAsDeleted(messageId: String)

    @Query("""
        SELECT * FROM chat_messages
        WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId)
        AND content_json LIKE '%' || :query || '%'
        AND is_deleted = 0
        ORDER BY created_at DESC
    """)
    fun searchMessagesOfAssistant(assistantId: String, query: String): Flow<List<ChatMessageEntity>>

    // ✨ 全局时间轴分页
    @Transaction
    @Query("""
        SELECT n.* FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        ORDER BY c.update_at DESC, n.order_index DESC
    """)
    fun getNodesWithMessagesOfAssistantPaging(assistantId: String): PagingSource<Int, MessageNodeWithMessages>

    // ✨ 获取除当前活跃话题外的全局历史消息 (用于填充 100 条 Limit)
    @Transaction
    @Query("""
        SELECT n.* FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId AND n.conversation_id != :excludeConvId
        ORDER BY c.update_at DESC, n.order_index DESC
        LIMIT :limit
    """)
    fun getLatestNodesOfAssistantExcludingFlow(assistantId: String, excludeConvId: String, limit: Int): Flow<List<MessageNodeWithMessages>>

    @Query("SELECT node_id FROM chat_messages WHERE id = :messageId")
    suspend fun getNodeIdByMessageId(messageId: String): String?

    @Query("SELECT conversation_id FROM chat_messages WHERE id = :messageId")
    suspend fun getConversationIdByMessageId(messageId: String): String?

    // ✨ 获取消息在全局时间轴中的“深度”（倒数第几个）
    @Query("""
        SELECT COUNT(*) FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        AND (
            c.update_at > (SELECT update_at FROM conversationentity WHERE id = (SELECT conversation_id FROM chat_messages WHERE id = :messageId))
            OR (
                c.update_at = (SELECT update_at FROM conversationentity WHERE id = (SELECT conversation_id FROM chat_messages WHERE id = :messageId))
                AND n.order_index >= (SELECT order_index FROM chat_message_nodes WHERE id = (SELECT node_id FROM chat_messages WHERE id = :messageId))
            )
        )
    """)
    suspend fun getMessageGlobalDepth(assistantId: String, messageId: String): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = :convId AND created_at > :lastTime AND is_deleted = 0")
    suspend fun countNewMessages(convId: String, lastTime: Long): Int

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :convId AND created_at > :lastTime AND is_deleted = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getMessagesForSummary(convId: String, lastTime: Long, limit: Int): List<ChatMessageEntity>

    // 1. 获取智能体全局最新的 N 个节点（包含消息），按时间倒序
    @Transaction
    @Query("""
    SELECT n.* FROM chat_message_nodes n
    INNER JOIN conversationentity c ON n.conversation_id = c.id
    WHERE c.assistant_id = :assistantId
    ORDER BY c.update_at DESC, n.order_index DESC
    LIMIT :limit
""")
    suspend fun getLatestNodesWithMessagesOfAssistant(assistantId: String, limit: Int): List<MessageNodeWithMessages>

    // 2. 获取智能体全局节点总数
    @Query("""
    SELECT COUNT(*) FROM chat_message_nodes n
    INNER JOIN conversationentity c ON n.conversation_id = c.id
    WHERE c.assistant_id = :assistantId
""")
    suspend fun getTotalNodeCountByAssistant(assistantId: String): Int


    // 4. 消息内容搜索分页
    @Query("""
    SELECT * FROM chat_messages
    WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId)
    AND content_json LIKE '%' || :query || '%'
    AND is_deleted = 0
    ORDER BY created_at DESC
""")
    fun searchMessagesOfAssistantPaging(assistantId: String, query: String): PagingSource<Int, ChatMessageEntity>
}

data class MessageNodeWithMessages(
    @Embedded val node: ChatMessageNodeEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "node_id"
    )
    val messages: List<ChatMessageEntity>
)
