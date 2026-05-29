package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.MilestoneDAO
import me.rerere.rikkahub.core.data.db.entity.MilestoneEntity

class MilestoneRepository(
    private val milestoneDAO: MilestoneDAO
) {
    fun getMilestonesFlow(assistantId: String): Flow<List<MilestoneEntity>> =
        milestoneDAO.getMilestonesFlow(assistantId)

    suspend fun getMilestones(assistantId: String): List<MilestoneEntity> =
        milestoneDAO.getMilestones(assistantId)

    suspend fun addMilestone(milestone: MilestoneEntity) =
        milestoneDAO.insertMilestone(milestone)

    suspend fun updateMilestone(milestone: MilestoneEntity) =
        milestoneDAO.updateMilestone(milestone)

    suspend fun deleteMilestone(id: String) =
        milestoneDAO.deleteMilestone(id)

    suspend fun deleteMilestonesOfAssistant(assistantId: String) =
        milestoneDAO.deleteMilestonesOfAssistant(assistantId)
}
