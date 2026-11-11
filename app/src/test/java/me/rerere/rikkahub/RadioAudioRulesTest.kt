package me.rerere.rikkahub

import me.rerere.rikkahub.core.data.db.entity.RadioAudioStatus
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.data.radio.RADIO_BACKUP_TTL_MS
import me.rerere.rikkahub.data.radio.RadioAudioRules
import me.rerere.rikkahub.data.radio.RadioEpisodeRules
import me.rerere.rikkahub.data.radio.concatPcmWithGaps
import me.rerere.rikkahub.data.radio.wrapPcmInWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小机电台音频资产纯业务规则（AGENTS.md 12.2/12.3）。
 */
class RadioAudioRulesTest {

    // ==================== 节目状态规则 ====================

    @Test
    fun `content modifiable only for draft`() {
        assertTrue(RadioEpisodeRules.canModifyContent(RadioEpisodeStatus.DRAFT))
        assertFalse(RadioEpisodeRules.canModifyContent(RadioEpisodeStatus.PUBLISHED))
    }

    @Test
    fun `title always modifiable`() {
        assertTrue(RadioEpisodeRules.canModifyTitle(RadioEpisodeStatus.DRAFT))
        assertTrue(RadioEpisodeRules.canModifyTitle(RadioEpisodeStatus.PUBLISHED))
    }

    // ==================== 按创作者名字解析（query_episodes creator_name） ====================

    private val creators = listOf(
        "id-a" to "小安",
        "id-b" to "小安",
        "id-c" to "Bella",
        "id-d" to "user"
    )

    @Test
    fun `creator name resolves to all same-named agents`() {
        // 同名 Agent 全部命中，不区分具体是哪一个
        assertEquals(listOf("id-a", "id-b"), RadioEpisodeRules.resolveCreatorIdsByName("小安", creators))
        assertEquals(listOf("id-c"), RadioEpisodeRules.resolveCreatorIdsByName("bella", creators))
    }

    @Test
    fun `creator name match trims and ignores case`() {
        assertEquals(listOf("id-c"), RadioEpisodeRules.resolveCreatorIdsByName("  BELLA  ", creators))
        assertEquals(listOf("id-a", "id-b"), RadioEpisodeRules.resolveCreatorIdsByName(" 小安 ", creators))
    }

    @Test
    fun `creator name blank or unknown resolves to empty`() {
        assertTrue(RadioEpisodeRules.resolveCreatorIdsByName(null, creators).isEmpty())
        assertTrue(RadioEpisodeRules.resolveCreatorIdsByName("", creators).isEmpty())
        assertTrue(RadioEpisodeRules.resolveCreatorIdsByName("   ", creators).isEmpty())
        assertTrue(RadioEpisodeRules.resolveCreatorIdsByName("不存在", creators).isEmpty())
        // 部分匹配不算命中（精确匹配）
        assertTrue(RadioEpisodeRules.resolveCreatorIdsByName("小", creators).isEmpty())
    }

    @Test
    fun `creator ids deduplicated`() {
        val duplicated = listOf("id-a" to "小安", "id-a" to "小安", "id-b" to "小安")
        assertEquals(listOf("id-a", "id-b"), RadioEpisodeRules.resolveCreatorIdsByName("小安", duplicated))
    }

    @Test
    fun `content hash is stable md5 hex`() {
        val h1 = RadioEpisodeRules.contentHash("你好，世界")
        val h2 = RadioEpisodeRules.contentHash("你好，世界")
        val h3 = RadioEpisodeRules.contentHash("你好，世界！")
        assertEquals(h1, h2)
        assertTrue(h1 != h3)
        assertEquals(32, h1.length)
    }

    // ==================== 重新生成换备 ====================

    private val now = 1_000_000L
    private val ttl = RADIO_BACKUP_TTL_MS

    @Test
    fun `first generation just promotes new asset`() {
        val plan = RadioAudioRules.planRegenerationSwap(
            newAssetId = "new", newContentHash = "h1",
            currentAssetId = null, currentContentHash = null, backupAssetId = null,
            now = now, ttlMs = ttl
        )
        assertEquals(1, plan.statusChanges.size)
        assertEquals("new", plan.statusChanges[0].assetId)
        assertEquals(RadioAudioStatus.CURRENT, plan.statusChanges[0].newStatus)
        assertNull(plan.statusChanges[0].newExpiresAt)
        assertTrue(plan.deleteAssetIds.isEmpty())
    }

    @Test
    fun `regeneration demotes current to backup and deletes old backup`() {
        val plan = RadioAudioRules.planRegenerationSwap(
            newAssetId = "new", newContentHash = "h1",
            currentAssetId = "cur", currentContentHash = "h1", backupAssetId = "oldBackup",
            now = now, ttlMs = ttl
        )
        assertTrue(plan.deleteAssetIds.contains("oldBackup"))
        val demoted = plan.statusChanges.first { it.assetId == "cur" }
        assertEquals(RadioAudioStatus.BACKUP, demoted.newStatus)
        assertEquals(now + ttl, demoted.newExpiresAt)
    }

    @Test
    fun `content hash change wipes old audio instead of keeping backup`() {
        // 并发兜底：文字稿变化后不允许旧稿音频留作备用
        val plan = RadioAudioRules.planRegenerationSwap(
            newAssetId = "new", newContentHash = "h2",
            currentAssetId = "cur", currentContentHash = "h1", backupAssetId = "oldBackup",
            now = now, ttlMs = ttl
        )
        assertTrue(plan.deleteAssetIds.containsAll(listOf("cur", "oldBackup")))
    }

    // ==================== 恢复备用 ====================

    @Test
    fun `restore swaps backup and current with fresh ttl`() {
        val plan = RadioAudioRules.planRestoreSwap(
            backupAssetId = "backup", currentAssetId = "cur", now = now, ttlMs = ttl
        )
        val promoted = plan.statusChanges.first { it.assetId == "backup" }
        assertEquals(RadioAudioStatus.CURRENT, promoted.newStatus)
        assertNull(promoted.newExpiresAt)
        val demoted = plan.statusChanges.first { it.assetId == "cur" }
        assertEquals(RadioAudioStatus.BACKUP, demoted.newStatus)
        assertEquals(now + ttl, demoted.newExpiresAt)
        assertTrue(plan.deleteAssetIds.isEmpty())
    }

    // ==================== 发布校验 ====================

    @Test
    fun `publish allowed when all checks pass`() {
        assertNull(
            RadioAudioRules.publishBlockReason(
                episodeExists = true,
                episodeStatus = RadioEpisodeStatus.DRAFT,
                content = "正文",
                currentAssetExists = true,
                currentAssetContentHash = "h1",
                currentFilePlayable = true,
                contentHash = "h1"
            )
        )
    }

    @Test
    fun `publish blocked without audio`() {
        val reason = RadioAudioRules.publishBlockReason(
            episodeExists = true,
            episodeStatus = RadioEpisodeStatus.DRAFT,
            content = "正文",
            currentAssetExists = false,
            currentAssetContentHash = null,
            currentFilePlayable = false,
            contentHash = "h1"
        )
        assertTrue(reason!!.contains("音频"))
    }

    @Test
    fun `publish blocked when hash mismatch`() {
        val reason = RadioAudioRules.publishBlockReason(
            episodeExists = true,
            episodeStatus = RadioEpisodeStatus.DRAFT,
            content = "正文",
            currentAssetExists = true,
            currentAssetContentHash = "old",
            currentFilePlayable = true,
            contentHash = "h1"
        )
        assertTrue(reason!!.contains("不一致"))
    }

    @Test
    fun `publish blocked when file not playable`() {
        val reason = RadioAudioRules.publishBlockReason(
            episodeExists = true,
            episodeStatus = RadioEpisodeStatus.DRAFT,
            content = "正文",
            currentAssetExists = true,
            currentAssetContentHash = "h1",
            currentFilePlayable = false,
            contentHash = "h1"
        )
        assertTrue(reason!!.contains("缺失或已损坏"))
    }

    @Test
    fun `publish idempotent guard for published episode`() {
        val reason = RadioAudioRules.publishBlockReason(
            episodeExists = true,
            episodeStatus = RadioEpisodeStatus.PUBLISHED,
            content = "正文",
            currentAssetExists = true,
            currentAssetContentHash = "h1",
            currentFilePlayable = true,
            contentHash = "h1"
        )
        assertTrue(reason!!.contains("已发布"))
    }

    // ==================== PCM 拼接与 WAV 头 ====================

    @Test
    fun `concat pcm inserts silence gaps`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        // 24kHz 16bit mono: 每毫秒 48 字节；200ms 间隔 = 9600 字节
        val (pcm, durationMs) = concatPcmWithGaps(listOf(chunk, chunk), sampleRate = 24000, gapMs = 200)
        assertEquals(4 + 9600 + 4, pcm.size)
        // 时长 = 总字节 / 48 = 约 200.17ms
        assertEquals(200L, durationMs)
    }

    @Test
    fun `concat pcm empty input`() {
        val (pcm, durationMs) = concatPcmWithGaps(emptyList(), sampleRate = 24000, gapMs = 200)
        assertEquals(0, pcm.size)
        assertEquals(0L, durationMs)
    }

    @Test
    fun `wav header is 44 bytes little endian pcm`() {
        val pcm = ByteArray(1000)
        val wav = wrapPcmInWav(pcm, sampleRate = 24000)
        assertEquals(44 + 1000, wav.size)
        // RIFF
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        // file size = 36 + dataSize (LE)
        assertEquals(36 + 1000, wav[4].toInt() and 0xFF or ((wav[5].toInt() and 0xFF) shl 8))
        // channels = 1
        assertEquals(1, wav[22].toInt() and 0xFF)
        // sampleRate @24
        val sampleRate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8)
        assertEquals(24000, sampleRate)
    }
}
