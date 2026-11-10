package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 电台节目音频资产实体。
 *
 * 同一节目最多一份 CURRENT（当前音频）与一份 BACKUP（备用音频）：
 * - 同一文字稿重新生成：新音频成为 CURRENT，原 CURRENT 转 BACKUP（3 天过期），原 BACKUP 删除。
 * - 文字稿变化：CURRENT 与 BACKUP 全部删除（旧稿音频不允许留作备用）。
 * - 恢复备用音频：BACKUP 与 CURRENT 互换，新 BACKUP 重新计算 3 天有效期。
 *
 * [providerId] / [voiceId] 记录生成该音频时**实际使用**的 TTS Provider 与音色
 * （Agent 专属音色不可用时回退全局默认，这里记录回退后的真实值）。
 * [contentHash] 为生成时文字稿的哈希，发布校验要求与当前文字稿一致。
 */
@Entity(
    tableName = "radio_audio_assets",
    indices = [
        Index(value = ["episode_id", "status"])
    ]
)
data class RadioAudioAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "episode_id")
    val episodeId: String,

    /** 音频文件路径（私有目录相对路径，如 `radio_audio/{audioId}.m4a`）。 */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** 生成时实际使用的 TTS Provider ID。 */
    @ColumnInfo(name = "provider_id")
    val providerId: String,

    /** 生成时实际使用的音色 ID（Provider 内部音色标识）。 */
    @ColumnInfo(name = "voice_id")
    val voiceId: String,

    /** 音频时长（毫秒）。 */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    /** 音频文件大小（字节）。 */
    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0L,

    /** 资产状态，见 [RadioAudioStatus]。 */
    @ColumnInfo(name = "status")
    val status: String = RadioAudioStatus.CURRENT,

    /** 生成时文字稿的内容哈希（用于发布校验与文字稿一致性判断）。 */
    @ColumnInfo(name = "content_hash")
    val contentHash: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 备用音频过期时间戳（毫秒），CURRENT 状态为 null。 */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,

    /** 生成该音频的后台任务 ID（background_tasks.id）；UI 路径直接生成为 null。
     *  提交原子边界崩溃恢复时据此判断该任务的音频是否已提交，防止重复合成/重复换备。 */
    @ColumnInfo(name = "task_id")
    val taskId: String? = null
)

/** 音频资产状态常量。 */
object RadioAudioStatus {
    const val CURRENT = "CURRENT"
    const val BACKUP = "BACKUP"
}
