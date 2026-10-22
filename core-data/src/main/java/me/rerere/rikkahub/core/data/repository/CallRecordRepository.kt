package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.core.data.db.dao.CallRecordDao
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.core.data.db.entity.CallRecordStatus

/**
 * 通话记录仓储。
 *
 * 只负责数据库读写；录音文件的落盘/删除/过期由 app 层的通话服务负责，
 * 二者通过 [CallRecordEntity.audio] 中的相对路径关联。
 */
class CallRecordRepository(private val dao: CallRecordDao) {

    suspend fun insert(record: CallRecordEntity) = dao.upsert(record)

    suspend fun getById(callId: String): CallRecordEntity? = dao.getById(callId)

    fun getByIdFlow(callId: String): Flow<CallRecordEntity?> = dao.getByIdFlow(callId)

    fun getByConversation(conversationId: String): Flow<List<CallRecordEntity>> =
        dao.getByConversation(conversationId)

    suspend fun getByConversationOnce(conversationId: String): List<CallRecordEntity> =
        dao.getByConversationOnce(conversationId)

    /**
     * 批量会话的通话记录（含未接通），按 create_time 升序。
     * 专供聊天时间线合并使用：消息分页是滑动窗口，只加载窗口内出现过的会话，
     * 通话记录也只加载这些会话，避免全量加载占用内存，
     * 同时避免「会话消息未进入窗口、通话卡片却孤立显示」。
     */
    fun getByConversations(conversationIds: List<String>): Flow<List<CallRecordEntity>> =
        if (conversationIds.isEmpty()) flowOf(emptyList())
        else dao.getByConversations(conversationIds)

    /**
     * 某智能体的通话记录（仅已接通的），按 [sort] 排序。
     * 已挂断（未接通）的记录不在历史列表中展示，过滤在 DAO 层完成；
     * 排序字段统一使用 create_time / duration_ms。
     */
    fun getByAssistant(assistantId: String, sort: CallHistorySort): Flow<List<CallRecordEntity>> =
        when (sort) {
            CallHistorySort.LATEST -> dao.getByAssistant(assistantId)
            CallHistorySort.OLDEST -> dao.getByAssistantOldestFirst(assistantId)
            CallHistorySort.LONGEST -> dao.getByAssistantLongestFirst(assistantId)
            CallHistorySort.SHORTEST -> dao.getByAssistantShortestFirst(assistantId)
        }

    suspend fun updateStatus(callId: String, status: String) = dao.updateStatus(callId, status)

    suspend fun markConnected(callId: String, status: String, connectedAt: Long) =
        dao.markConnected(callId, status, connectedAt)

    suspend fun updateStatusWithReason(callId: String, status: String, endReason: String?) =
        dao.updateStatusWithReason(callId, status, endReason)

    suspend fun updateEnded(
        callId: String,
        status: String = CallRecordStatus.ENDED,
        connectedAt: Long?,
        disconnectedAt: Long?,
        durationMs: Long,
        endReason: String?
    ) = dao.updateEnded(callId, status, connectedAt, disconnectedAt, durationMs, endReason)

    suspend fun updateAudio(callId: String, audio: String?, audioExpiresAt: Long?) =
        dao.updateAudio(callId, audio, audioExpiresAt)

    suspend fun updateForever(callId: String, forever: Int) = dao.updateForever(callId, forever)

    suspend fun clearAudio(callId: String) = dao.clearAudio(callId)

    suspend fun updateStartTimeIfEarlier(callId: String, startTime: Long) =
        dao.updateStartTimeIfEarlier(callId, startTime)

    suspend fun updateEndTimeIfLater(callId: String, endTime: Long) =
        dao.updateEndTimeIfLater(callId, endTime)

    suspend fun getExpiredAudioRecords(now: Long = System.currentTimeMillis()): List<CallRecordEntity> =
        dao.getExpiredAudioRecords(now)

    suspend fun deleteByAssistant(assistantId: String) = dao.deleteByAssistant(assistantId)

    suspend fun deleteById(callId: String) = dao.deleteById(callId)
}

/** 历史通话列表排序方式。 */
enum class CallHistorySort {
    LATEST, OLDEST, LONGEST, SHORTEST
}
