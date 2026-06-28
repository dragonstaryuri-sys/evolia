package me.rerere.rikkahub.core.data.db.dao

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

    // 补齐这个方法：批量获取多个会话的所有节点
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

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId")
    suspend fun getAllMessagesByConversationId(conversationId: String): List<ChatMessageEntity>

    // 批量获取指定会话列表的所有消息
    @Query("SELECT * FROM chat_messages WHERE conversation_id IN (:conversationIds)")
    suspend fun getMessagesByConversationIds(conversationIds: List<String>): List<ChatMessageEntity>

    // 流式获取某个助手的全部消息内容 (用于统计)
    @Query("""
        SELECT content_json FROM chat_messages
        WHERE conversation_id IN (SELECT id FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = 0)
    """)
    fun getAllMessagesContentByAssistant(assistantId: String): Flow<List<String>>

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Transaction
    suspend fun syncConversationMessages(
        conversationId: String,
        nodes: List<ChatMessageNodeEntity>,
        messages: List<ChatMessageEntity>
    ) {
        deleteNodesByConversationId(conversationId)
        insertNodes(nodes)
        insertMessages(messages)
    }
}
