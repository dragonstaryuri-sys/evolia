package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.ProfileHistoryDAO
import me.rerere.rikkahub.core.data.db.entity.ProfileHistoryEntity

/**
 * 档案历史版本仓库
 *
 * 用于缓解 AI 调用 update_profile 时把档案字段整体覆盖、导致旧值丢失的问题。
 * 工具侧在执行真正的覆盖更新之前，先调用 [saveSnapshotBeforeUpdate] 把被改字段的
 * 旧值写入历史表；之后才执行原本的更新。
 *
 * 保留策略：按"字段"维度独立保留最近 [ProfileHistoryEntity.MAX_KEEP_VERSIONS]
 * 个版本，不同字段之间互不影响（例如"偏好"更新频繁保留 3 条，"职业"很少更新也
 * 独立保留 3 条）。
 */
class ProfileHistoryRepository(
    private val profileHistoryDAO: ProfileHistoryDAO
) {
    /**
     * 保存"覆盖前"的字段级旧值快照，并按字段维度清理超出保留数量的旧版本。
     *
     * @param targetType 目标类型，见 [ProfileHistoryEntity.TARGET_USER] / [TARGET_ASSISTANT]
     * @param targetId  目标 ID；user 用常量 "user"，assistant 用 assistantId
     * @param changes   本次将要修改的字段映射：fieldKey -> (oldValue, newValue)
     * @param batchId   同一次 update_profile 调用共享的时间戳批次 ID（仅用于日志排查）
     */
    suspend fun saveSnapshotBeforeUpdate(
        targetType: String,
        targetId: String,
        changes: Map<String, Pair<String, String>>,
        batchId: Long
    ) {
        if (changes.isEmpty()) return
        changes.forEach { (fieldKey, pair) ->
            val (oldValue, newValue) = pair
            profileHistoryDAO.insert(
                ProfileHistoryEntity(
                    targetType = targetType,
                    targetId = targetId,
                    fieldKey = fieldKey,
                    oldValue = oldValue,
                    newValue = newValue,
                    batchId = batchId
                )
            )
            // 按字段维度独立清理，保留最近 N 个版本
            profileHistoryDAO.trimOldVersionsForField(
                targetType = targetType,
                targetId = targetId,
                fieldKey = fieldKey,
                keepCount = ProfileHistoryEntity.MAX_KEEP_VERSIONS
            )
        }
    }

    fun getHistoryFlow(targetType: String, targetId: String): Flow<List<ProfileHistoryEntity>> {
        return profileHistoryDAO.getHistoryFlow(targetType, targetId)
    }

    suspend fun deleteByTarget(targetType: String, targetId: String) {
        profileHistoryDAO.deleteByTarget(targetType, targetId)
    }
}
