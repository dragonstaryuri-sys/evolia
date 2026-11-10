package me.rerere.rikkahub.data.radio

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.RadioAudioAssetDao
import me.rerere.rikkahub.core.data.db.dao.RadioChannelDao
import me.rerere.rikkahub.core.data.db.dao.RadioEpisodeDao
import me.rerere.rikkahub.core.data.db.dao.RadioEpisodeDeletionLogDao
import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity
import me.rerere.rikkahub.core.data.db.entity.RadioChannelEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeDeletionLogEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity

/**
 * 节目查询条件（工具与 UI 共用）。列表默认只返回元数据，正文需另取。
 */
data class EpisodeQuery(
    val channelId: String? = null,
    val creatorAgentId: String? = null,
    val status: String? = null,
    val titleQuery: String? = null,
    val sortBy: EpisodeSort = EpisodeSort.CREATED_DESC,
    val page: Int = 0,
    val pageSize: Int = 20
)

enum class EpisodeSort { CREATED_DESC, CREATED_ASC, PUBLISHED_DESC, TITLE_ASC }

/**
 * Radio 数据访问层：DAO 薄封装与查询组装。业务规则在 [RadioService]。
 */
class RadioRepository(
    private val channelDao: RadioChannelDao,
    private val episodeDao: RadioEpisodeDao,
    private val assetDao: RadioAudioAssetDao,
    private val deletionLogDao: RadioEpisodeDeletionLogDao
) {
    // ================= 栏目 =================
    fun observeChannels(): Flow<List<RadioChannelEntity>> = channelDao.observeAll()
    fun observeChannel(id: String): Flow<RadioChannelEntity?> = channelDao.observeById(id)
    suspend fun getChannel(id: String): RadioChannelEntity? = channelDao.getById(id)
    suspend fun getAllChannels(): List<RadioChannelEntity> = channelDao.getAll()
    suspend fun upsertChannel(channel: RadioChannelEntity) = channelDao.upsert(channel)
    suspend fun deleteChannel(id: String) = channelDao.deleteById(id)

    // ================= 节目 =================
    fun observeEpisodesByChannel(channelId: String): Flow<List<RadioEpisodeEntity>> =
        episodeDao.observeByChannel(channelId)

    /** 点赞节目列表（我的喜欢 tab）。 */
    fun observeLiked(): Flow<List<RadioEpisodeEntity>> = episodeDao.observeLiked()

    /** 全部草稿（草稿箱 tab）。 */
    fun observeDrafts(): Flow<List<RadioEpisodeEntity>> = episodeDao.observeDrafts()

    fun observeEpisode(id: String): Flow<RadioEpisodeEntity?> = episodeDao.observeById(id)
    suspend fun getEpisode(id: String): RadioEpisodeEntity? = episodeDao.getById(id)
    suspend fun upsertEpisode(episode: RadioEpisodeEntity) = episodeDao.upsert(episode)
    suspend fun countEpisodesByChannel(channelId: String): Int = episodeDao.countByChannel(channelId)
    suspend fun updateEpisodeTitle(id: String, title: String, updatedAt: Long) =
        episodeDao.updateTitle(id, title, updatedAt)

    suspend fun updateEpisodeContent(id: String, content: String, updatedAt: Long) =
        episodeDao.updateContent(id, content, updatedAt)

    suspend fun publishEpisode(id: String, publishedAt: Long, updatedAt: Long) =
        episodeDao.publish(id, publishedAt, updatedAt)

    suspend fun updateEpisodeLiked(id: String, liked: Int) = episodeDao.updateLiked(id, liked)

    suspend fun getLatestPublished(): RadioEpisodeEntity? = episodeDao.getLatestPublished()

    suspend fun getLatestPublishedByAgent(agentId: String): RadioEpisodeEntity? =
        episodeDao.getLatestPublishedByAgent(agentId)

    fun observeLatestPublishedByAgent(agentId: String): Flow<RadioEpisodeEntity?> =
        episodeDao.observeLatestPublishedByAgent(agentId)

    suspend fun deleteEpisode(id: String) = episodeDao.deleteById(id)
    suspend fun deleteEpisodesByChannel(channelId: String) = episodeDao.deleteByChannel(channelId)

    /**
     * 组合过滤查询（电台节目量级小，内存过滤/排序/分页即可）。
     */
    suspend fun queryEpisodes(query: EpisodeQuery): List<RadioEpisodeEntity> {
        var list: List<RadioEpisodeEntity> = if (query.channelId != null) {
            episodeDao.getByChannel(query.channelId)
        } else {
            episodeDao.getAll()
        }
        query.creatorAgentId?.let { agentId -> list = list.filter { it.creatorAgentId == agentId } }
        query.status?.let { status -> list = list.filter { it.status == status } }
        query.titleQuery?.takeIf { it.isNotBlank() }?.let { q ->
            list = list.filter { it.title.contains(q, ignoreCase = true) }
        }
        list = when (query.sortBy) {
            EpisodeSort.CREATED_DESC -> list.sortedByDescending { it.createdAt }
            EpisodeSort.CREATED_ASC -> list.sortedBy { it.createdAt }
            EpisodeSort.PUBLISHED_DESC -> list.sortedWith(
                compareByDescending<RadioEpisodeEntity> { it.publishedAt ?: 0L }
                    .thenByDescending { it.createdAt }
            )

            EpisodeSort.TITLE_ASC -> list.sortedBy { it.title }
        }
        val safePageSize = query.pageSize.coerceIn(1, 100)
        val safePage = query.page.coerceAtLeast(0)
        return list.drop(safePage * safePageSize).take(safePageSize)
    }

    // ================= 音频资产 =================
    fun observeAssetsByEpisode(episodeId: String): Flow<List<RadioAudioAssetEntity>> =
        assetDao.observeByEpisode(episodeId)

    suspend fun getAssetsByEpisode(episodeId: String): List<RadioAudioAssetEntity> =
        assetDao.getByEpisode(episodeId)

    suspend fun getAsset(id: String): RadioAudioAssetEntity? = assetDao.getById(id)

    suspend fun getCurrentAsset(episodeId: String): RadioAudioAssetEntity? = assetDao.getCurrent(episodeId)
    suspend fun getBackupAsset(episodeId: String): RadioAudioAssetEntity? = assetDao.getBackup(episodeId)
    /** 按后台任务 ID 查已提交资产（不限状态）：提交原子边界崩溃恢复的幂等判据。 */
    suspend fun getAssetByTaskId(taskId: String, contentHash: String): RadioAudioAssetEntity? =
        assetDao.getByTaskId(taskId, contentHash)
    suspend fun upsertAsset(asset: RadioAudioAssetEntity) = assetDao.upsert(asset)
    suspend fun updateAssetStatus(id: String, status: String, expiresAt: Long?) =
        assetDao.updateStatus(id, status, expiresAt)

    suspend fun deleteAsset(id: String) = assetDao.deleteById(id)
    suspend fun deleteAssetsByEpisode(episodeId: String) = assetDao.deleteByEpisode(episodeId)
    suspend fun getAllAssets(): List<RadioAudioAssetEntity> = assetDao.getAll()
    suspend fun getExpiredBackups(now: Long): List<RadioAudioAssetEntity> = assetDao.getExpiredBackups(now)

    // ================= 删除审计 =================
    suspend fun insertDeletionLog(log: RadioEpisodeDeletionLogEntity) = deletionLogDao.insert(log)
    suspend fun getRecentDeletionLogs(limit: Int, offset: Int): List<RadioEpisodeDeletionLogEntity> =
        deletionLogDao.getRecent(limit, offset)
}
