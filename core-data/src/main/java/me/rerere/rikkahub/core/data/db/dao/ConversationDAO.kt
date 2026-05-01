package me.rerere.rikkahub.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ConversationEntity
import me.rerere.rikkahub.core.data.repository.LightConversationEntity

data class AssistantCountResult(
    val assistantId: String,
    val count: Int
)

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated, is_virtual as isVirtual FROM conversationentity ORDER BY update_at DESC")
    fun getAllLight(): Flow<List<LightConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = :isVirtual ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String, isVirtual: Boolean = false): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated, is_virtual as isVirtual FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = :isVirtual ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String, isVirtual: Boolean = false): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = :isVirtual ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int, isVirtual: Boolean = false): List<ConversationEntity>

    // 获取最新会话（忽略置顶，仅按更新时间）
    // 增加 create_at 作为第二排序键，确保同一毫秒内创建的会话顺序稳定
    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = :isVirtual ORDER BY update_at DESC, create_at DESC LIMIT :limit")
    suspend fun getLatestConversationsOfAssistant(assistantId: String, limit: Int, isVirtual: Boolean = false): List<ConversationEntity>

    // 新增：获取助手最近的会话（不分模式）
    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY update_at DESC, create_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistantAnyMode(assistantId: String, limit: Int): List<ConversationEntity>

    // 新增：获取助手上一次会话（排除当前会话）
    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND id != :excludeId ORDER BY update_at DESC, create_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistantExclude(assistantId: String, excludeId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE is_virtual = :isVirtual AND (title LIKE '%' || :searchText || '%' OR nodes LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String, isVirtual: Boolean = false): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated, is_virtual as isVirtual FROM conversationentity WHERE is_virtual = :isVirtual AND (title LIKE '%' || :searchText || '%' OR nodes LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String, isVirtual: Boolean = false): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = :isVirtual AND (title LIKE '%' || :searchText || '%' OR nodes LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String, isVirtual: Boolean = false): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated, is_virtual as isVirtual FROM conversationentity WHERE assistant_id = :assistantId AND is_virtual = :isVirtual AND (title LIKE '%' || :searchText || '%' OR nodes LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String, isVirtual: Boolean = false): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET is_consolidated = :isConsolidated WHERE id = :id")
    suspend fun updateConsolidatedStatus(id: String, isConsolidated: Boolean)

    // Stats queries for MenuVM optimization
    @Query("SELECT COUNT(*) FROM conversationentity")
    fun getConversationCountFlow(): Flow<Int>

    @Query("SELECT DISTINCT date(create_at / 1000, 'unixepoch', 'localtime') as createDate FROM conversationentity ORDER BY createDate DESC")
    fun getDistinctCreateDatesFlow(): Flow<List<String>>

    @Query("SELECT DISTINCT date(create_at / 1000, 'unixepoch', 'localtime') as createDate FROM conversationentity")
    suspend fun getDistinctCreateDates(): List<String>

    @Query("SELECT assistant_id as assistantId, COUNT(*) as count FROM conversationentity GROUP BY assistant_id ORDER BY count DESC LIMIT 1")
    fun getMostActiveAssistantFlow(): Flow<AssistantCountResult?>

    // Get hour of day for each conversation's creation time (for time label calculation)
    @Query("SELECT CAST(strftime('%H', create_at / 1000, 'unixepoch', 'localtime') AS INTEGER) as hour FROM conversationentity")
    fun getConversationHoursFlow(): Flow<List<Int>>

    // Per-assistant chat count
    @Query("SELECT COUNT(*) FROM conversationentity WHERE assistant_id = :assistantId")
    fun getConversationCountByAssistantFlow(assistantId: String): Flow<Int>
}
