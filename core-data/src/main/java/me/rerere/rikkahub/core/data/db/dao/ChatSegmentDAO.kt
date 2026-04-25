package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity

@Dao
interface ChatSegmentDAO {
    @Query("SELECT * FROM chat_segments WHERE conversation_id = :conversationId ORDER BY start_index ASC")
    suspend fun getSegmentsByConversation(conversationId: String): List<ChatSegmentEntity>

    @Query("SELECT * FROM chat_segments WHERE conversation_id = :conversationId ORDER BY start_index ASC")
    fun getSegmentsByConversationFlow(conversationId: String): Flow<List<ChatSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: ChatSegmentEntity): Long

    @Query("DELETE FROM chat_segments WHERE conversation_id = :conversationId")
    suspend fun deleteSegmentsByConversation(conversationId: String)

    @Query("DELETE FROM chat_segments WHERE assistant_id = :assistantId")
    suspend fun deleteSegmentsByAssistant(assistantId: String)

    @Query("SELECT * FROM chat_segments WHERE id = :id")
    suspend fun getSegmentById(id: Int): ChatSegmentEntity?
}
