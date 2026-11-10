package me.rerere.rikkahub.data.backgroundtask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 逐句 chunk 断点续传纯决策单测：
 * 完成事实源 = chunk 文件原子 rename 的存在性，正确性不依赖 progress_json。
 */
class ChunkResumeRulesTest {

    private fun manifest(
        total: Int = 10,
        providerId: String = "provider-a",
        sampleRate: Int = 24000
    ) = ChunkResumeRules.ChunkManifest(
        totalSentences = total, providerId = providerId, sampleRate = sampleRate
    )

    private fun dirUpTo(n: Int, withPart: Boolean = false): Set<String> {
        val names = (1..n).map { ChunkResumeRules.chunkFileName(it) }.toMutableSet()
        if (withPart) names += ChunkResumeRules.partFileName(n + 1)
        return names
    }

    @Test
    fun `chunk file name padding is stable`() {
        assertEquals("chunk_0001.pcm", ChunkResumeRules.chunkFileName(1))
        assertEquals("chunk_0123.pcm", ChunkResumeRules.chunkFileName(123))
        assertEquals("chunk_0001.part", ChunkResumeRules.partFileName(1))
    }

    @Test
    fun `empty dir is fresh`() {
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = emptySet(), manifest = null, expectedTotalSentences = 10, expectedProviderId = "provider-a"
        )
        assertEquals(ChunkResumeRules.Verdict.FRESH, decision.verdict)
        assertEquals(0, decision.completedCount)
        assertTrue(decision.filesToDelete.isEmpty())
    }

    @Test
    fun `chunks without manifest restarts`() {
        // PCM 无自描述头：缺清单则无法确认采样率/provider，安全起见全部作废
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = dirUpTo(5), manifest = null, expectedTotalSentences = 10, expectedProviderId = "provider-a"
        )
        assertEquals(ChunkResumeRules.Verdict.RESTART, decision.verdict)
        assertEquals(5, decision.filesToDelete.count { it.endsWith(".pcm") })
    }

    @Test
    fun `provider mismatch restarts`() {
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = dirUpTo(5), manifest = manifest(providerId = "provider-old"),
            expectedTotalSentences = 10, expectedProviderId = "provider-new"
        )
        assertEquals(ChunkResumeRules.Verdict.RESTART, decision.verdict)
    }

    @Test
    fun `total mismatch restarts`() {
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = dirUpTo(5), manifest = manifest(total = 8),
            expectedTotalSentences = 10, expectedProviderId = "provider-a"
        )
        assertEquals(ChunkResumeRules.Verdict.RESTART, decision.verdict)
    }

    @Test
    fun `partial completion resumes from next sentence`() {
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = dirUpTo(5, withPart = true), manifest = manifest(),
            expectedTotalSentences = 10, expectedProviderId = "provider-a"
        )
        assertEquals(ChunkResumeRules.Verdict.RESUME, decision.verdict)
        assertEquals(5, decision.completedCount)
        // .part 残留进入清理清单，已完成 chunk 不动
        assertEquals(listOf(ChunkResumeRules.partFileName(6)), decision.filesToDelete)
    }

    @Test
    fun `all completed resumes straight to merge`() {
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = dirUpTo(10), manifest = manifest(),
            expectedTotalSentences = 10, expectedProviderId = "provider-a"
        )
        assertEquals(ChunkResumeRules.Verdict.RESUME, decision.verdict)
        assertEquals(10, decision.completedCount)
    }

    @Test
    fun `gap in chunk sequence stops at first hole`() {
        // 正常顺序合成不会出现空洞；出现时前缀即安全续跑点
        val names = setOf(
            ChunkResumeRules.chunkFileName(1),
            ChunkResumeRules.chunkFileName(2),
            ChunkResumeRules.chunkFileName(4),
        )
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = names, manifest = manifest(),
            expectedTotalSentences = 10, expectedProviderId = "provider-a"
        )
        assertEquals(ChunkResumeRules.Verdict.RESUME, decision.verdict)
        assertEquals(2, decision.completedCount)
    }

    @Test
    fun `part residue alone does not count as completed`() {
        val decision = ChunkResumeRules.decideChunkResume(
            dirFileNames = setOf(ChunkResumeRules.partFileName(1)), manifest = manifest(),
            expectedTotalSentences = 10, expectedProviderId = "provider-a"
        )
        assertEquals(ChunkResumeRules.Verdict.RESUME, decision.verdict)
        assertEquals(0, decision.completedCount)
        assertEquals(listOf(ChunkResumeRules.partFileName(1)), decision.filesToDelete)
    }

    @Test
    fun `contiguous count is bounded by total`() {
        val names = (1..15).map { ChunkResumeRules.chunkFileName(it) }.toSet()
        assertEquals(10, ChunkResumeRules.countContiguousCompleted(names, 10))
    }
}
