package me.rerere.rikkahub.core.data.db.dao

import androidx.paging.PagingSource
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

    @Query("SELECT content_json FROM chat_messages WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = 0)")
    fun getAllMessagesContentByAssistant(assistantId: String): Flow<List<String>>

    @Query("SELECT id FROM chat_message_nodes WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId) ORDER BY (SELECT update_at FROM conversationentity WHERE id = conversation_id) DESC, order_index DESC LIMIT :limit")
    suspend fun getLatestNodeIdsOfAssistant(assistantId: String, limit: Int): List<String>

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId AND created_at >= :startTime AND created_at <= :endTime AND is_deleted = 0 ORDER BY created_at ASC")
    suspend fun getMessagesByTimeRange(conversationId: String, startTime: Long, endTime: Long): List<ChatMessageEntity>

    @Query("SELECT n.order_index FROM chat_message_nodes n JOIN chat_messages m ON n.id = m.node_id WHERE m.id = :messageId")
    suspend fun getNodeOrderIndexByMessageId(messageId: String): Int?

    @Query("UPDATE chat_messages SET is_deleted = 1 WHERE id = :messageId")
    suspend fun markMessageAsDeleted(messageId: String)

    @Query("SELECT * FROM chat_messages WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId) AND content_json LIKE '%' || :query || '%' AND is_deleted = 0 ORDER BY created_at DESC")
    fun searchMessagesOfAssistant(assistantId: String, query: String): Flow<List<ChatMessageEntity>>

    @Transaction
    @Query("SELECT n.* FROM chat_message_nodes n INNER JOIN conversationentity c ON n.conversation_id = c.id WHERE c.assistant_id = :assistantId ORDER BY c.update_at DESC, n.order_index DESC")
    fun getNodesWithMessagesOfAssistantPaging(assistantId: String): PagingSource<Int, MessageNodeWithMessages>

    @Transaction
    @Query("SELECT n.* FROM chat_message_nodes n INNER JOIN conversationentity c ON n.conversation_id = c.id WHERE c.assistant_id = :assistantId AND n.conversation_id != :excludeConvId ORDER BY c.update_at DESC, n.order_index DESC LIMIT :limit")
    fun getLatestNodesOfAssistantExcludingFlow(assistantId: String, excludeConvId: String, limit: Int): Flow<List<MessageNodeWithMessages>>

    @Query("SELECT node_id FROM chat_messages WHERE id = :messageId")
    suspend fun getNodeIdByMessageId(messageId: String): String?

    @Query("SELECT conversation_id FROM chat_messages WHERE id = :messageId")
    suspend fun getConversationIdByMessageId(messageId: String): String?

    @Query("SELECT conversation_id FROM chat_message_nodes WHERE id = :nodeId")
    suspend fun getConversationIdByNodeId(nodeId: String): String?

    @Query("SELECT COUNT(*) FROM chat_message_nodes n INNER JOIN conversationentity c ON n.conversation_id = c.id WHERE c.assistant_id = :assistantId AND (c.update_at > (SELECT update_at FROM conversationentity WHERE id = (SELECT conversation_id FROM chat_messages WHERE id = :messageId)) OR (c.update_at = (SELECT update_at FROM conversationentity WHERE id = (SELECT conversation_id FROM chat_messages WHERE id = :messageId)) AND n.order_index >= (SELECT order_index FROM chat_message_nodes WHERE id = (SELECT node_id FROM chat_messages WHERE id = :messageId))))")
    suspend fun getMessageGlobalDepth(assistantId: String, messageId: String): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = :convId AND created_at > :lastTime AND is_deleted = 0")
    suspend fun countNewMessages(convId: String, lastTime: Long): Int

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :convId AND created_at > :lastTime AND is_deleted = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getMessagesForSummary(convId: String, lastTime: Long, limit: Int): List<ChatMessageEntity>

    // --- 滑动窗口分页查询 ---

    /**
     * 获取最新 N 个节点，包含会话更新时间
     */
    @Transaction
    @Query("""
        SELECT n.*, c.update_at as conv_update_at FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        ORDER BY c.update_at DESC, n.order_index DESC
        LIMIT :limit
    """)
    suspend fun getLatestNodesWithMetadata(assistantId: String, limit: Int): List<MessageNodeWithMetadata>

    /**
     * 加载比当前锚点更旧的节点（向上滚动历史）
     */
    @Transaction
    @Query("""
        SELECT n.*, c.update_at as conv_update_at FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        AND (
            c.update_at < :anchorUpdateAt
            OR (c.update_at = :anchorUpdateAt AND n.order_index < :anchorOrderIndex)
        )
        ORDER BY c.update_at DESC, n.order_index DESC
        LIMIT :limit
    """)
    suspend fun getNodesOlderThan(
        assistantId: String,
        anchorUpdateAt: Long,
        anchorOrderIndex: Int,
        limit: Int
    ): List<MessageNodeWithMetadata>

    /**
     * 加载比当前锚点更新的节点（向下滚动回最新区域）
     */
    @Transaction
    @Query("""
        SELECT n.*, c.update_at as conv_update_at FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        AND (
            c.update_at > :anchorUpdateAt
            OR (c.update_at = :anchorUpdateAt AND n.order_index > :anchorOrderIndex)
        )
        ORDER BY c.update_at ASC, n.order_index ASC
        LIMIT :limit
    """)
    suspend fun getNodesNewerThan(
        assistantId: String,
        anchorUpdateAt: Long,
        anchorOrderIndex: Int,
        limit: Int
    ): List<MessageNodeWithMetadata>

    /**
     * 获取指定消息所属节点的元数据
     */
    @Transaction
    @Query("""
        SELECT n.*, c.update_at as conv_update_at FROM chat_message_nodes n
        INNER JOIN conversationentity c ON n.conversation_id = c.id
        INNER JOIN chat_messages m ON n.id = m.node_id
        WHERE m.id = :messageId
    """)
    suspend fun getNodeWithMetadataByMessageId(messageId: String): MessageNodeWithMetadata?

    // ✨ 新增：获取指定会话最新的 N 个节点
    @Transaction
    @Query("SELECT * FROM chat_message_nodes WHERE conversation_id = :conversationId ORDER BY order_index DESC LIMIT :limit")
    suspend fun getNodesWithMessagesOfConversation(conversationId: String, limit: Int): List<MessageNodeWithMessages>

    @Query("SELECT COUNT(*) FROM chat_message_nodes n INNER JOIN conversationentity c ON n.conversation_id = c.id WHERE c.assistant_id = :assistantId")
    suspend fun getTotalNodeCountByAssistant(assistantId: String): Int

    @Query("SELECT * FROM chat_messages WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId) AND content_json LIKE '%' || :query || '%' AND is_deleted = 0 ORDER BY created_at DESC")
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

/**
 * 带有定位元数据的节点包装
 */
data class MessageNodeWithMetadata(
    @Embedded val node: ChatMessageNodeEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "node_id"
    )
    val messages: List<ChatMessageEntity>,
    @ColumnInfo(name = "conv_update_at") val convUpdateAt: Long
)
