package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity

@Dao
interface CallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: CallRecordEntity)

    @Query("SELECT * FROM call_records WHERE id = :callId LIMIT 1")
    suspend fun getById(callId: String): CallRecordEntity?

    @Query("SELECT * FROM call_records WHERE id = :callId LIMIT 1")
    fun getByIdFlow(callId: String): Flow<CallRecordEntity?>

    /** 某会话的通话记录（用于聊天时间线合并渲染）。 */
    @Query("SELECT * FROM call_records WHERE conversation_id = :conversationId ORDER BY create_time ASC")
    fun getByConversation(conversationId: String): Flow<List<CallRecordEntity>>

    /** 某会话的通话记录一次性查询。 */
    @Query("SELECT * FROM call_records WHERE conversation_id = :conversationId ORDER BY create_time ASC")
    suspend fun getByConversationOnce(conversationId: String): List<CallRecordEntity>

    /** 某智能体的通话记录（仅已接通的），默认最新优先。
     * 已挂断（未接通）的记录不展示在历史通话列表，故统一过滤 connected_at IS NOT NULL。
     */
    @Query(
        "SELECT * FROM call_records WHERE assistant_id = :assistantId AND connected_at IS NOT NULL " +
            "ORDER BY create_time DESC"
    )
    fun getByAssistant(assistantId: String): Flow<List<CallRecordEntity>>

    /** 批量会话的通话记录（含未接通），按创建时间升序。
     *  聊天时间线采用滑动窗口（只加载当前可见范围内的消息节点），
     *  通话记录也只加载窗口内出现过的会话，避免全量加载占用内存、
     *  以及出现「会话消息未加载、通话卡片却孤立显示」的情况。 */
    @Query("SELECT * FROM call_records WHERE conversation_id IN (:conversationIds) ORDER BY create_time ASC")
    fun getByConversations(conversationIds: List<String>): Flow<List<CallRecordEntity>>

    /** 获取每个智能体最新的一条通话记录（用于首页预览）。 */
    @Query(
        "SELECT * FROM call_records WHERE (assistant_id, create_time) IN " +
            "(SELECT assistant_id, MAX(create_time) FROM call_records GROUP BY assistant_id)"
    )
    fun getLatestByAllAssistants(): Flow<List<CallRecordEntity>>

    @Query(
        "SELECT * FROM call_records WHERE assistant_id = :assistantId AND connected_at IS NOT NULL " +
            "ORDER BY create_time ASC"
    )
    fun getByAssistantOldestFirst(assistantId: String): Flow<List<CallRecordEntity>>

    @Query(
        "SELECT * FROM call_records WHERE assistant_id = :assistantId AND connected_at IS NOT NULL " +
            "ORDER BY duration_ms DESC, create_time DESC"
    )
    fun getByAssistantLongestFirst(assistantId: String): Flow<List<CallRecordEntity>>

    @Query(
        "SELECT * FROM call_records WHERE assistant_id = :assistantId AND connected_at IS NOT NULL " +
            "ORDER BY duration_ms ASC, create_time DESC"
    )
    fun getByAssistantShortestFirst(assistantId: String): Flow<List<CallRecordEntity>>

    /** 需要自动清理录音的记录：未永久保存且已过期。 */
    @Query("SELECT * FROM call_records WHERE forever = 0 AND audio IS NOT NULL AND audio_expires_at IS NOT NULL AND audio_expires_at <= :now")
    suspend fun getExpiredAudioRecords(now: Long): List<CallRecordEntity>

    /** 未永久保存且仍有音频的记录（清理缓存时只清音频、保留通话记录用）。 */
    @Query("SELECT * FROM call_records WHERE forever = 0 AND audio IS NOT NULL")
    suspend fun getNonForeverAudioRecords(): List<CallRecordEntity>

    /** 批量清空未永久保存录音的 audio 引用（通话记录与转写保留）。 */
    @Query("UPDATE call_records SET audio = NULL, audio_expires_at = NULL WHERE forever = 0 AND audio IS NOT NULL")
    suspend fun clearAudioForTransient()

    @Query("UPDATE call_records SET status = :status WHERE id = :callId")
    suspend fun updateStatus(callId: String, status: String)

    /** 接通：置为 CONNECTED 并记录 connected_at（用于时长计算）。 */
    @Query("UPDATE call_records SET status = :status, connected_at = :connectedAt WHERE id = :callId")
    suspend fun markConnected(callId: String, status: String, connectedAt: Long)

    @Query("UPDATE call_records SET status = :status, end_reason = :endReason WHERE id = :callId")
    suspend fun updateStatusWithReason(callId: String, status: String, endReason: String?)

    @Query(
        "UPDATE call_records SET status = :status, connected_at = :connectedAt, disconnected_at = :disconnectedAt, " +
            "duration_ms = :durationMs, end_reason = :endReason WHERE id = :callId"
    )
    suspend fun updateEnded(
        callId: String,
        status: String,
        connectedAt: Long?,
        disconnectedAt: Long?,
        durationMs: Long,
        endReason: String?
    )

    @Query(
        "UPDATE call_records SET audio = :audio, audio_expires_at = :audioExpiresAt WHERE id = :callId"
    )
    suspend fun updateAudio(callId: String, audio: String?, audioExpiresAt: Long?)

    @Query("UPDATE call_records SET forever = :forever WHERE id = :callId")
    suspend fun updateForever(callId: String, forever: Int)

    @Query("UPDATE call_records SET audio = NULL, audio_expires_at = NULL, forever = 0 WHERE id = :callId")
    suspend fun clearAudio(callId: String)

    @Query("UPDATE call_records SET start_time = :startTime WHERE id = :callId AND (start_time IS NULL OR start_time > :startTime)")
    suspend fun updateStartTimeIfEarlier(callId: String, startTime: Long)

    @Query("UPDATE call_records SET end_time = :endTime WHERE id = :callId AND (end_time IS NULL OR end_time < :endTime)")
    suspend fun updateEndTimeIfLater(callId: String, endTime: Long)

    @Query("DELETE FROM call_records WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistant(assistantId: String)

    @Query("DELETE FROM call_records WHERE id = :callId")
    suspend fun deleteById(callId: String)
}
