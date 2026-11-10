package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity

@Dao
interface RadioAudioAssetDao {
    @Upsert
    suspend fun upsert(asset: RadioAudioAssetEntity)

    @Query("SELECT * FROM radio_audio_assets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RadioAudioAssetEntity?

    @Query("SELECT * FROM radio_audio_assets WHERE episode_id = :episodeId ORDER BY created_at DESC")
    suspend fun getByEpisode(episodeId: String): List<RadioAudioAssetEntity>

    @Query("SELECT * FROM radio_audio_assets WHERE episode_id = :episodeId ORDER BY created_at DESC")
    fun observeByEpisode(episodeId: String): Flow<List<RadioAudioAssetEntity>>

    @Query("SELECT * FROM radio_audio_assets WHERE episode_id = :episodeId AND status = 'CURRENT' LIMIT 1")
    suspend fun getCurrent(episodeId: String): RadioAudioAssetEntity?

    @Query("SELECT * FROM radio_audio_assets WHERE episode_id = :episodeId AND status = 'BACKUP' LIMIT 1")
    suspend fun getBackup(episodeId: String): RadioAudioAssetEntity?

    /** 按后台任务 ID 查资产：提交原子边界崩溃恢复时判断该任务的音频是否已提交（不限 CURRENT，防重复合成）。 */
    @Query("SELECT * FROM radio_audio_assets WHERE task_id = :taskId AND content_hash = :contentHash LIMIT 1")
    suspend fun getByTaskId(taskId: String, contentHash: String): RadioAudioAssetEntity?

    @Query("SELECT * FROM radio_audio_assets")
    suspend fun getAll(): List<RadioAudioAssetEntity>

    /** 备用音频按 3 天有效期清理：只处理 BACKUP 且已过期记录。 */
    @Query("SELECT * FROM radio_audio_assets WHERE status = 'BACKUP' AND expires_at IS NOT NULL AND expires_at <= :now")
    suspend fun getExpiredBackups(now: Long): List<RadioAudioAssetEntity>

    /** 定向更新状态列（CURRENT <-> BACKUP 互换），不整实体覆盖。 */
    @Query("UPDATE radio_audio_assets SET status = :status, expires_at = :expiresAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, expiresAt: Long?)

    @Query("DELETE FROM radio_audio_assets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM radio_audio_assets WHERE episode_id = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)

    @Query("DELETE FROM radio_audio_assets")
    suspend fun deleteAll()
}
