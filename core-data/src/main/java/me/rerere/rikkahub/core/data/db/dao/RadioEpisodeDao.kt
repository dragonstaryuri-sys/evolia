package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity

@Dao
interface RadioEpisodeDao {
    @Upsert
    suspend fun upsert(episode: RadioEpisodeEntity)

    @Query("SELECT * FROM radio_episodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RadioEpisodeEntity?

    @Query("SELECT * FROM radio_episodes WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<RadioEpisodeEntity?>

    @Query("SELECT * FROM radio_episodes WHERE channel_id = :channelId ORDER BY created_at DESC")
    fun observeByChannel(channelId: String): Flow<List<RadioEpisodeEntity>>

    @Query("SELECT * FROM radio_episodes WHERE channel_id = :channelId ORDER BY created_at DESC")
    suspend fun getByChannel(channelId: String): List<RadioEpisodeEntity>

    @Query("SELECT * FROM radio_episodes ORDER BY created_at DESC")
    suspend fun getAll(): List<RadioEpisodeEntity>

    @Query("SELECT COUNT(*) FROM radio_episodes WHERE channel_id = :channelId")
    suspend fun countByChannel(channelId: String): Int

    /**
     * 定向更新标题列（草稿与已发布节目均允许改标题），
     * 避免全实体覆盖并发列（如音频生成期间的中间状态）。
     */
    @Query("UPDATE radio_episodes SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    /** 定向更新文字稿列（仅草稿，调用方负责状态校验），并刷新 updated_at。 */
    @Query("UPDATE radio_episodes SET content = :content, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: String, content: String, updatedAt: Long)

    /** 定向发布：置状态 + 发布时间。 */
    @Query("UPDATE radio_episodes SET status = 'PUBLISHED', published_at = :publishedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun publish(id: String, publishedAt: Long, updatedAt: Long)

    /** 点赞标记（播放器点赞按钮），定向列更新。 */
    @Query("UPDATE radio_episodes SET liked = :liked WHERE id = :id")
    suspend fun updateLiked(id: String, liked: Int)

    /** 最近发布的节目（对话页发布卡片）。 */
    @Query("SELECT * FROM radio_episodes WHERE status = 'PUBLISHED' ORDER BY published_at DESC LIMIT 1")
    suspend fun getLatestPublished(): RadioEpisodeEntity?

    /** 点赞节目列表（我的喜欢 tab），按发布时间倒序。 */
    @Query("SELECT * FROM radio_episodes WHERE liked = 1 ORDER BY published_at DESC, updated_at DESC")
    fun observeLiked(): Flow<List<RadioEpisodeEntity>>

    /** 全部草稿（草稿箱 tab），按更新时间倒序。 */
    @Query("SELECT * FROM radio_episodes WHERE status = 'DRAFT' ORDER BY updated_at DESC")
    fun observeDrafts(): Flow<List<RadioEpisodeEntity>>

    /** 指定 Agent 的最近发布节目。 */
    @Query(
        "SELECT * FROM radio_episodes WHERE status = 'PUBLISHED' AND creator_agent_id = :agentId " +
            "ORDER BY published_at DESC LIMIT 1"
    )
    suspend fun getLatestPublishedByAgent(agentId: String): RadioEpisodeEntity?

    /** 指定 Agent 的最近发布节目（响应式，对话页发布卡片）。 */
    @Query(
        "SELECT * FROM radio_episodes WHERE status = 'PUBLISHED' AND creator_agent_id = :agentId " +
            "ORDER BY published_at DESC LIMIT 1"
    )
    fun observeLatestPublishedByAgent(agentId: String): kotlinx.coroutines.flow.Flow<RadioEpisodeEntity?>

    @Query("DELETE FROM radio_episodes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM radio_episodes WHERE channel_id = :channelId")
    suspend fun deleteByChannel(channelId: String)
}
