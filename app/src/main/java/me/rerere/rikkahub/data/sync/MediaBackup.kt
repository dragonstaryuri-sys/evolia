package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity

/**
 * 媒体资产备份/恢复的纯决策函数（单测固化，见 MediaBackupTest）。
 *
 * 备份规则（AGENTS.md 12.7）：
 * - 通话录音 zip 条目 = `call_recordings/{callId}.{ext}`（私有相对路径或公共导出件统一以 callId 命名）；
 * - 电台音频 zip 条目 = 资产原相对路径 `radio_audio/{audioId}.{ext}`；
 * - 恢复时音频文件写回私有目录，公共导出件的 DB 绝对路径改写回相对路径（forever 语义不变）；
 * - `forever` / `audioExpiresAt` / `expires_at` 原值保留，不重置；
 * - 文件缺失：录音按无音频处理（audio=null），电台删对应资产记录并按无音频降级（节目保持 PUBLISHED）。
 */
object MediaBackup {

    /** 通话录音在 zip 中的条目名；无音频/无法确定扩展名时返回 null（不备份）。 */
    fun callRecordingZipEntryName(callId: String, storedAudio: String?): String? {
        if (storedAudio.isNullOrBlank()) return null
        val ext = storedAudio.substringAfterLast('.', "").lowercase()
        if (ext.isBlank() || ext.length > 5 || ext.contains('/')) return null
        return "call_recordings/$callId.$ext"
    }

    /** 电台音频在 zip 中的条目名（即资产相对路径）。 */
    fun radioAudioZipEntryName(asset: RadioAudioAssetEntity): String = asset.filePath

    /** 恢复后对通话录音 audio 引用的修复决策。 */
    sealed interface CallAudioRepair {
        /** 已存储路径指向的文件存在（同机恢复或公共目录文件仍在）→ 原样保留。 */
        data object Keep : CallAudioRepair

        /** 原路径不可用，但 zip 中恢复了私有副本 → DB 改写为相对路径（forever/audioExpiresAt 不变）。 */
        data class RewriteToRelative(val relativePath: String) : CallAudioRepair

        /** 原路径与恢复副本都不可用 → audio=null（按无音频降级，其它字段原值保留）。 */
        data object ClearAudio : CallAudioRepair
    }

    /**
     * @param storedAudio DB 中原 audio 值
     * @param storedFileExists storedAudio 指向的文件当前是否可解析且存在
     * @param restoredRelativePath zip 恢复出来的私有副本相对路径（未恢复为 null）
     */
    fun decideCallAudioRepair(
        storedAudio: String?,
        storedFileExists: Boolean,
        restoredRelativePath: String?
    ): CallAudioRepair {
        if (storedAudio.isNullOrBlank()) return CallAudioRepair.Keep
        if (storedFileExists) return CallAudioRepair.Keep
        val restored = restoredRelativePath?.takeIf { it.isNotBlank() }
            ?: return CallAudioRepair.ClearAudio
        return CallAudioRepair.RewriteToRelative(restored)
    }

    /** 恢复后对电台音频资产的修复决策：文件存在 → 保留；否则删除资产记录（节目保持现状）。 */
    fun decideRadioAssetRepair(fileExists: Boolean): Boolean = fileExists
}
