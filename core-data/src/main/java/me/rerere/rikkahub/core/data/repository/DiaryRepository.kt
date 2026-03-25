package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.AgentDiaryDAO
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity

class DiaryRepository(
    private val agentDiaryDao: AgentDiaryDAO
) {
    suspend fun insertDiary(diary: AgentDiaryEntity) {
        agentDiaryDao.insertDiary(diary)
    }

    fun getDiariesByAssistant(assistantId: String): Flow<List<AgentDiaryEntity>> {
        return agentDiaryDao.getDiariesByAssistant(assistantId)
    }

    fun getAllDiaries(): Flow<List<AgentDiaryEntity>> {
        return agentDiaryDao.getAllDiaries()
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

    suspend fun getLastDiaryOfAssistant(assistantId: String): AgentDiaryEntity? {
        return agentDiaryDao.getLastDiaryOfAssistant(assistantId)
    }
}
