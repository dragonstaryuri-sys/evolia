package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.AssistantUnreadCount
import me.rerere.rikkahub.core.data.db.entity.ChatUnreadMessageEntity

@Dao
interface ChatUnreadDAO {
    /**
     * 批量写入未读节点。node_id 为主键 + IGNORE 冲突策略：
     * 同一节点重复处理（重复完成回调 / 与既存记录撞车）时静默跳过，天然幂等。
     * 注意：调用方通常在 saveConversation 的同一 Room 事务内调用（与消息落库原子生效）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnreadNodes(entities: List<ChatUnreadMessageEntity>)

    /** 用户进入会话即清零该会话全部未读记录（幂等）。 */
    @Query("DELETE FROM chat_unread_messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    /**
     * 按 Agent 汇总未读数量。assistant_id 由 JOIN conversationentity 实时获取，
     * 不在未读表冗余存储（Agent 删除时会话级联删除，计数自然归零）。
     */
    @Query(
        """
        SELECT c.assistant_id AS assistantId, COUNT(u.node_id) AS unreadCount
        FROM chat_unread_messages u
        INNER JOIN conversationentity c ON u.conversation_id = c.id
        GROUP BY c.assistant_id
        """
    )
    fun observeUnreadCountByAssistant(): Flow<List<AssistantUnreadCount>>

    /**
     * 清理指向不存在节点的孤儿未读记录（DatabaseSanitizer 恢复期间外键关闭，
     * 幽灵节点清理的级联不生效，恢复收尾前调用本方法兜底）。
     */
    @Query("DELETE FROM chat_unread_messages WHERE node_id NOT IN (SELECT id FROM chat_message_nodes)")
    suspend fun deleteOrphanUnread()
}
