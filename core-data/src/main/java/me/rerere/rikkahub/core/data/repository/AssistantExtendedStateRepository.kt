package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.AssistantExtendedStateDAO
import me.rerere.rikkahub.core.data.db.entity.AssistantExtendedStateEntity

class AssistantExtendedStateRepository(
    private val assistantExtendedStateDAO: AssistantExtendedStateDAO
) {
    suspend fun getStateById(assistantId: String): AssistantExtendedStateEntity? {
        return assistantExtendedStateDAO.getStateById(assistantId)
    }

    fun getStateByIdFlow(assistantId: String): Flow<AssistantExtendedStateEntity?> {
        return assistantExtendedStateDAO.getStateByIdFlow(assistantId)
    }

    suspend fun updateState(state: AssistantExtendedStateEntity) {
        assistantExtendedStateDAO.insertOrUpdate(state)
    }

    suspend fun deleteState(assistantId: String) {
        assistantExtendedStateDAO.deleteByAssistantId(assistantId)
    }
}
