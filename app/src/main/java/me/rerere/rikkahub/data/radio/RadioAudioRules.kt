package me.rerere.rikkahub.data.radio

import me.rerere.rikkahub.core.data.db.entity.RadioAudioStatus
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import java.security.MessageDigest

/**
 * 备用音频有效期：3 天。
 */
const val RADIO_BACKUP_TTL_MS: Long = 3L * 24L * 60L * 60L * 1000L

/**
 * 备用音频清理任务对无引用孤儿文件的保护期（与 CallRecovery 口径一致）。
 */
const val RADIO_ORPHAN_RETENTION_MS: Long = 24L * 60L * 60L * 1000L

/**
 * 节目状态相关的纯决策函数（无 Android 依赖，供单测固化业务规则）。
 */
object RadioEpisodeRules {
    /** 只有未发布节目允许修改文字稿。 */
    fun canModifyContent(status: String): Boolean = status == RadioEpisodeStatus.DRAFT

    /** 标题修改对草稿与已发布节目均允许。 */
    fun canModifyTitle(status: String): Boolean = true

    /** 文字稿内容哈希（MD5 hex，小写），用于音频与文字稿一致性判定。 */
    fun contentHash(content: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 一次音频状态变更计划：全部以「定向列更新 + 指定删除」表达，
 * 由 RadioService 执行（DB 更新与文件删除），本文件只做纯决策。
 */
data class AudioSwapPlan(
    /** 需要更新状态的资产：(assetId, newStatus, newExpiresAt)。 */
    val statusChanges: List<AudioStatusChange> = emptyList(),
    /** 需要删除的资产 ID（同步删除文件 + 记录）。 */
    val deleteAssetIds: List<String> = emptyList()
) {
    data class AudioStatusChange(
        val assetId: String,
        val newStatus: String,
        val newExpiresAt: Long?
    )
}

/**
 * 音频资产换备/恢复/清理的纯决策函数（单测固化，见 RadioAudioRulesTest）。
 */
object RadioAudioRules {

    /**
     * 同一文字稿重新生成成功后的换备计划：
     * - 新音频成为 CURRENT；
     * - 原 CURRENT 转 BACKUP（重新计算 3 天有效期）；
     * - 原 BACKUP 直接删除；
     * - 若文字稿哈希与旧 CURRENT 不一致（正常流程不会发生——内容修改时已全部清理，
     *   此处为并发兜底），旧 CURRENT/BACKUP 一并删除，不允许旧稿音频留作备用。
     */
    fun planRegenerationSwap(
        newAssetId: String,
        newContentHash: String,
        currentAssetId: String?,
        currentContentHash: String?,
        backupAssetId: String?,
        now: Long,
        ttlMs: Long = RADIO_BACKUP_TTL_MS
    ): AudioSwapPlan {
        val isSameContent = currentContentHash == null || currentContentHash == newContentHash
        val changes = mutableListOf<AudioSwapPlan.AudioStatusChange>()
        val deletes = mutableListOf<String>()

        changes += AudioSwapPlan.AudioStatusChange(newAssetId, RadioAudioStatus.CURRENT, null)
        if (currentAssetId != null) {
            if (isSameContent) {
                changes += AudioSwapPlan.AudioStatusChange(
                    currentAssetId, RadioAudioStatus.BACKUP, now + ttlMs
                )
            } else {
                deletes += currentAssetId
            }
        }
        if (backupAssetId != null) {
            deletes += backupAssetId
        }
        return AudioSwapPlan(statusChanges = changes, deleteAssetIds = deletes)
    }

    /**
     * 恢复备用音频计划：BACKUP → CURRENT（清除过期时间），原 CURRENT → BACKUP（重算 3 天）。
     */
    fun planRestoreSwap(
        backupAssetId: String,
        currentAssetId: String?,
        now: Long,
        ttlMs: Long = RADIO_BACKUP_TTL_MS
    ): AudioSwapPlan {
        val changes = buildList {
            add(AudioSwapPlan.AudioStatusChange(backupAssetId, RadioAudioStatus.CURRENT, null))
            if (currentAssetId != null) {
                add(
                    AudioSwapPlan.AudioStatusChange(
                        currentAssetId, RadioAudioStatus.BACKUP, now + ttlMs
                    )
                )
            }
        }
        return AudioSwapPlan(statusChanges = changes)
    }

    /**
     * 文字稿变化后的清理计划：CURRENT 与 BACKUP 全部删除（文件 + 记录）。
     */
    fun planContentWipe(allAssetIds: List<String>): AudioSwapPlan {
        return AudioSwapPlan(deleteAssetIds = allAssetIds)
    }

    /**
     * 发布前置校验（纯函数）。返回 null 表示允许发布，否则返回明确的失败原因
     * （直接透出给 Agent/用户，要求先生成音频）。
     */
    fun publishBlockReason(
        episodeExists: Boolean,
        episodeStatus: String,
        content: String,
        currentAssetExists: Boolean,
        currentAssetContentHash: String?,
        currentFilePlayable: Boolean,
        contentHash: String
    ): String? {
        if (!episodeExists) return "节目不存在"
        if (episodeStatus == RadioEpisodeStatus.PUBLISHED) return "节目已发布，无需重复发布"
        if (content.isBlank()) return "节目文字稿为空，无法发布"
        if (!currentAssetExists) return "节目尚无音频，请先调用 generate_episode_audio 生成音频后再发布"
        if (!currentFilePlayable) return "音频文件缺失或已损坏，请重新调用 generate_episode_audio 生成音频"
        if (currentAssetContentHash != contentHash) {
            return "音频与当前文字稿不一致，请重新调用 generate_episode_audio 生成音频后再发布"
        }
        return null
    }
}

/**
 * PCM 拼接纯函数：把逐句合成的 16-bit 小端单声道 PCM 拼接为完整音轨，
 * 句间插入指定时长的静音（自然停顿）。
 *
 * @return 拼接后的完整 PCM 与时长（毫秒）
 */
fun concatPcmWithGaps(
    chunks: List<ByteArray>,
    sampleRate: Int,
    gapMs: Int
): Pair<ByteArray, Long> {
    val validChunks = chunks.filter { it.isNotEmpty() }
    if (validChunks.isEmpty()) return ByteArray(0) to 0L

    // 16-bit mono：每毫秒 2 字节 * sampleRate / 1000；对齐到 2 字节避免半样本。
    val gapBytesPerChunk = ((sampleRate.toLong() * 2L * gapMs) / 1000L / 2L * 2L).toInt()
    val gap = if (gapBytesPerChunk > 0) ByteArray(gapBytesPerChunk) else null

    val totalSize = validChunks.sumOf { it.size.toLong() } +
        (validChunks.size - 1).toLong() * (gap?.size?.toLong() ?: 0L)
    val output = ByteArray(totalSize.toInt())
    var offset = 0
    validChunks.forEachIndexed { index, chunk ->
        System.arraycopy(chunk, 0, output, offset, chunk.size)
        offset += chunk.size
        if (gap != null && index != validChunks.lastIndex) {
            System.arraycopy(gap, 0, output, offset, gap.size)
            offset += gap.size
        }
    }
    val durationMs = offset.toLong() * 1000L / (sampleRate.toLong() * 2L)
    return output to durationMs
}

/**
 * WAV 头封装纯函数（16-bit PCM mono），编码器降级路径使用。
 * 返回 44 字节头 + PCM 数据的完整 WAV 字节。
 */
fun wrapPcmInWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcm.size
    val fileSize = 36 + dataSize

    val header = java.io.ByteArrayOutputStream(44).use { out ->
        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte()
            )
        )
        fun writeShortLE(value: Short) = out.write(
            byteArrayOf(
                (value.toInt() and 0xFF).toByte(),
                ((value.toInt() shr 8) and 0xFF).toByte()
            )
        )
        writeString("RIFF")
        writeIntLE(fileSize)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLE(16)                       // PCM fmt chunk 大小
        writeShortLE(1)                      // audio format = PCM
        writeShortLE(channels.toShort())
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign.toShort())
        writeShortLE(bitsPerSample.toShort())
        writeString("data")
        writeIntLE(dataSize)
        out.toByteArray()
    }
    return header + pcm
}
