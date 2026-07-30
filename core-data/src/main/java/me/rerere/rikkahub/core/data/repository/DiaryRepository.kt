package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.AgentDiaryDAO
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryCommentEntity

class DiaryRepository(
    private val agentDiaryDao: AgentDiaryDAO
) {
    // --- Diary Operations ---
    suspend fun insertDiary(diary: AgentDiaryEntity) {
        agentDiaryDao.insertDiary(diary)
    }

    fun getDiariesByAssistant(assistantId: String): Flow<List<AgentDiaryEntity>> {
        return agentDiaryDao.getDiariesByAssistant(assistantId)
    }

    fun getAllDiaries(): Flow<List<AgentDiaryEntity>> {
        return agentDiaryDao.getAllDiaries()
    }

    suspend fun getDiaryById(id: String): AgentDiaryEntity? {
        return agentDiaryDao.getDiaryById(id)
    }

    suspend fun getDiaryByDate(assistantId: String, date: String): AgentDiaryEntity? {
        return agentDiaryDao.getDiaryByDate(assistantId, date)
    }

    suspend fun deleteDiaryById(id: String) {
        agentDiaryDao.deleteDiaryById(id)
    }

    suspend fun deleteDiariesOfAssistant(assistantId: String) {
        agentDiaryDao.deleteDiariesByAssistant(assistantId)
    }

    // 核心修复：确保这个方法存在且为 suspend
    suspend fun getLastDiaryOfAssistant(assistantId: String): AgentDiaryEntity? {
        return agentDiaryDao.getLastDiaryOfAssistant(assistantId)
    }

    // --- Personnel Filtering ---
    fun getDiariesByAssistants(assistantIds: List<String>): Flow<List<AgentDiaryEntity>> {
        return agentDiaryDao.getDiariesByAssistants(assistantIds)
    }

    suspend fun getDiariesByAssistantsPaged(assistantIds: List<String>, limit: Int, offset: Int): List<AgentDiaryEntity> {
        return agentDiaryDao.getDiariesByAssistantsPaged(assistantIds, limit, offset)
    }

    // --- Calendar Support ---
    fun getDatesWithDiaries(assistantIds: List<String>): Flow<List<String>> {
        return agentDiaryDao.getDatesWithDiaries(assistantIds)
    }

    fun getDiariesByDateAndAssistants(date: String, assistantIds: List<String>): Flow<List<AgentDiaryEntity>> {
        return agentDiaryDao.getDiariesByDateAndAssistants(date, assistantIds)
    }

    // --- Search ---
    fun searchDiaries(query: String): Flow<List<AgentDiaryEntity>> {
        return agentDiaryDao.searchDiaries(query)
    }

    // --- Comment Operations ---
    suspend fun insertComment(comment: DiaryCommentEntity) {
        agentDiaryDao.insertComment(comment)
    }

    fun getCommentsForDiary(diaryId: String): Flow<List<DiaryCommentEntity>> {
        return agentDiaryDao.getCommentsForDiary(diaryId)
    }

    suspend fun getCommentById(commentId: String): DiaryCommentEntity? {
        return agentDiaryDao.getCommentById(commentId)
    }

    suspend fun deleteComment(commentId: String) {
        agentDiaryDao.deleteComment(commentId)
    }
}
