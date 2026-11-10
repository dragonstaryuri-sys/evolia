package me.rerere.rikkahub.data.backgroundtask

import kotlinx.serialization.Serializable

/**
 * TTS 逐句 chunk 断点续传纯决策（不依赖 Android API，可单测）。
 *
 * 文件布局：`{workDir}/chunk_{0001..N}.pcm` + `manifest.json`。
 * 每句先写 `chunk_{i}.part` 再原子 rename 为 `chunk_{i}.pcm`——**rename 的原子性即句子完成事实源**，
 * 正确性不依赖 progress_json（其仅作 UI 进度展示）：
 * - 崩溃在写入中 → 只留下 .part 残留，恢复时清理；
 * - 崩溃在 rename 后 → chunk 文件已完整落盘，恢复时跳过该句，不重复合成；
 * - 消灭「文件已写 / checkpoint 未更新」的崩溃窗口。
 */
object ChunkResumeRules {

    const val CHUNK_FILE_PREFIX = "chunk_"
    const val CHUNK_DONE_SUFFIX = ".pcm"
    const val CHUNK_PART_SUFFIX = ".part"
    const val MANIFEST_FILE_NAME = "manifest.json"

    /** 句子序号（1-based）→ chunk 文件名。 */
    fun chunkFileName(sentenceIndex1Based: Int): String =
        "$CHUNK_FILE_PREFIX${sentenceIndex1Based.toString().padStart(4, '0')}$CHUNK_DONE_SUFFIX"

    fun partFileName(sentenceIndex1Based: Int): String =
        "$CHUNK_FILE_PREFIX${sentenceIndex1Based.toString().padStart(4, '0')}$CHUNK_PART_SUFFIX"

    /** 工作目录清单（恢复时从 manifest.json 读出；缺失视为无法安全续传）。 */
    @Serializable
    data class ChunkManifest(
        val version: Int = 1,
        val totalSentences: Int,
        val providerId: String,
        val sampleRate: Int = 0,
    )

    enum class Verdict {
        /** 目录干净，从第 1 句开始 */
        FRESH,
        /** 存在有效 chunk 前缀，从 completedCount+1 句继续（completedCount==0 等价 FRESH 语义） */
        RESUME,
        /** 清单缺失 / provider 不一致 / 总句数变化，全部 chunk 作废重跑 */
        RESTART,
    }

    data class Decision(
        val verdict: Verdict,
        /** 已完成的最大连续句数（RESUME 时跳过第 1..completedCount 句） */
        val completedCount: Int,
        /** 需要删除的残留文件（.part 半成品；RESTART 时为全部 chunk 文件） */
        val filesToDelete: List<String>,
    )

    /**
     * 恢复决策（输入 = 工作目录文件名集合 + 清单，纯函数）：
     * - 目录无任何 chunk/manifest → FRESH；
     * - 有 chunk 但无 manifest（无法确认采样率与 provider，PCM 无自描述头）→ RESTART；
     * - manifest.providerId 与本次执行不一致（用户中途换音色配置）→ RESTART，防采样率失配拼接；
     * - manifest.totalSentences 与本次分句数不一致（防御：正常应被 content_hash 校验拦截）→ RESTART；
     * - 其余 → RESUME：最大连续前缀 + 清理 .part 残留。
     */
    fun decideChunkResume(
        dirFileNames: Set<String>,
        manifest: ChunkManifest?,
        expectedTotalSentences: Int,
        expectedProviderId: String
    ): Decision {
        val doneChunks = dirFileNames.filter {
            it.startsWith(CHUNK_FILE_PREFIX) && it.endsWith(CHUNK_DONE_SUFFIX)
        }
        val staleParts = dirFileNames.filter { it.endsWith(CHUNK_PART_SUFFIX) }

        if (doneChunks.isEmpty() && manifest == null) {
            return Decision(Verdict.FRESH, 0, staleParts)
        }

        if (manifest == null) {
            return Decision(Verdict.RESTART, 0, doneChunks + staleParts)
        }

        val providerMismatch = manifest.providerId != expectedProviderId
        val totalMismatch = manifest.totalSentences != expectedTotalSentences
        if (providerMismatch || totalMismatch) {
            return Decision(Verdict.RESTART, 0, doneChunks + staleParts)
        }

        val completedCount = countContiguousCompleted(dirFileNames, expectedTotalSentences)
        return Decision(Verdict.RESUME, completedCount, staleParts)
    }

    /**
     * 最大连续完成前缀：chunk_0001..chunk_000k 全部存在才计 k。
     * 句子顺序合成不应出现空洞；出现空洞时前缀即安全续跑点（空洞句重新合成）。
     */
    fun countContiguousCompleted(dirFileNames: Set<String>, totalSentences: Int): Int {
        var count = 0
        while (count < totalSentences && chunkFileName(count + 1) in dirFileNames) {
            count++
        }
        return count
    }
}
