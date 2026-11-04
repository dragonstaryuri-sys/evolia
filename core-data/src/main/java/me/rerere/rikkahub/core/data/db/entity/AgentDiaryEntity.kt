package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

/**
 * AI 日记生成状态（内部管理字段，不影响正文展示）。
 *
 * 仅持久化三种状态：
 * - [GENERATING]：生成任务进行中 / Worker 异常退出后的残留（由 DiaryWorker 接管判定）
 * - [COMPLETED]：所有待处理消息均已成功处理（含用户手动编辑落定、历史日记默认值）
 * - [PARTIAL]：部分 Chunk 已成功保存，仍有消息未处理（可同天续写恢复）
 *
 * FAILED 不落库：任务完全失败（无任何正文）时空记录被直接删除，
 * "记录不存在"即等价于"可重新生成"，避免空日记卡片占位与删除重建抖动。
 */
object DiaryGenerationStatus {
    const val GENERATING = "GENERATING"
    const val COMPLETED = "COMPLETED"
    const val PARTIAL = "PARTIAL"
}

@Entity(tableName = "AgentDiaryEntity")
data class AgentDiaryEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "images")
    val images: List<DiaryImage> = emptyList(),
    @ColumnInfo(name = "date")
    val date: String, // yyyy-MM-dd
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 本日记【已成功生成并持久化】的最后一条消息的 createdAt（epoch ms）。
     *
     * 游标语义（严格约束）：
     * - 只能代表已成功写入正文的消息位置，绝不代表"本次查询到的最后一条"或任务起止时间；
     * - 只允许随 Chunk 成功持久化同步推进（正文+游标单条 UPDATE 原子写），
     *   chunk 失败时游标停留在上一个成功 chunk；
     * - 用作下一篇日记的消息起点锚点：`createdAt > lastMessageAt`（严格大于），
     *   分 chunk 时同毫秒时间戳组不可被拆开，保证不漏消息；
     * - 无交互日记无消息可取，取日记记录的 createdAt 兜底；
     * - 老日记为 null，回退到 date 次日 0 点（历史兼容规则保持不变）。
     */
    @ColumnInfo(name = "last_message_at")
    val lastMessageAt: Long? = null,
    /**
     * 是否被用户收藏。收藏后可在日记页「收藏」tab 中按时间线查看。
     */
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false,
    /**
     * AI 日记生成状态（见 [DiaryGenerationStatus]）。
     * 历史日记迁移默认 COMPLETED（全部视为已完成，不参与续写）；
     * 用户手动创建/编辑的日记同样保持 COMPLETED（用户编辑落定，禁止被自动续写覆盖）。
     */
    @ColumnInfo(name = "generation_status", defaultValue = "COMPLETED")
    val generationStatus: String = DiaryGenerationStatus.COMPLETED
)
