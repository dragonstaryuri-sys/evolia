package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ProfileHistoryEntity

@Dao
interface ProfileHistoryDAO {
    @Insert
    suspend fun insert(entity: ProfileHistoryEntity): Long

    /**
     * 按时间倒序获取某个 target 的全部历史记录（每个字段最多保留最近
     * [ProfileHistoryEntity.MAX_KEEP_VERSIONS] 个版本）。
     * UI 端再按 fieldKey 分组渲染到对应字段下方。
     */
    @Query(
        """
        SELECT * FROM profile_history
        WHERE targetType = :targetType AND targetId = :targetId
        ORDER BY fieldKey ASC, createdAt DESC, id DESC
        """
    )
    fun getHistoryFlow(targetType: String, targetId: String): Flow<List<ProfileHistoryEntity>>

    /**
     * 按"字段"维度清理：保留每个 (targetType, targetId, fieldKey) 组合下最近的
     * [keepCount] 个版本，更早的全部删除。这样不同字段之间互不影响，
     * 例如"偏好"更新频繁保留 3 条，"职业"很少更新也独立保留 3 条。
     */
    @Query(
        """
        DELETE FROM profile_history
        WHERE targetType = :targetType AND targetId = :targetId AND fieldKey = :fieldKey
        AND id NOT IN (
            SELECT id FROM profile_history
            WHERE targetType = :targetType AND targetId = :targetId AND fieldKey = :fieldKey
            ORDER BY createdAt DESC, id DESC
            LIMIT :keepCount
        )
        """
    )
    suspend fun trimOldVersionsForField(
        targetType: String,
        targetId: String,
        fieldKey: String,
        keepCount: Int
    )

    @Query("DELETE FROM profile_history WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteByTarget(targetType: String, targetId: String)
}
