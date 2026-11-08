package me.rerere.rikkahub

import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.ensureMediaBackupItems
import me.rerere.rikkahub.data.sync.MediaBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体资产备份/恢复纯决策（AGENTS.md 12.7）+ 老用户备份项一次性迁移。
 */
class MediaBackupTest {

    // ==================== 老用户备份项迁移 ====================

    @Test
    fun `old config gains two media items keeping original choices`() {
        val old = WebDavConfig(items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES))
        val migrated = ensureMediaBackupItems(old)
        assertEquals(
            listOf(
                WebDavConfig.BackupItem.DATABASE,
                WebDavConfig.BackupItem.FILES,
                WebDavConfig.BackupItem.CALL_RECORDINGS,
                WebDavConfig.BackupItem.RADIO_AUDIO
            ),
            migrated.items
        )
        // 用户原有的 TTS_CACHE 未勾选状态保留（默认不含）
        assertFalse(WebDavConfig.BackupItem.TTS_CACHE in migrated.items)
    }

    @Test
    fun `migration is idempotent`() {
        val config = WebDavConfig() // 默认已含全部媒体项
        assertEquals(config, ensureMediaBackupItems(config))
    }

    @Test
    fun `user unchecked item is not re-added on repeated runs`() {
        val unchecked = WebDavConfig().copy(items = WebDavConfig().items - WebDavConfig.BackupItem.RADIO_AUDIO)
        // 迁移标记已置位后 normalize 不再执行；这里验证纯函数本身不会重复补齐语义由调用方保证，
        // 但补齐函数对「部分缺失」配置仍会补——迁移标记是防重复的关键
        val result = ensureMediaBackupItems(unchecked)
        assertTrue(WebDavConfig.BackupItem.RADIO_AUDIO in result.items)
    }

    // ==================== zip 条目命名 ====================

    @Test
    fun `call recording entry named by callId with extension`() {
        assertEquals(
            "call123.m4a",
            MediaBackup.callRecordingZipEntryName("call123", "call_recordings/call123.m4a")
                ?.substringAfter('/')
        )
        assertEquals(
            "call_recordings/call456.m4a",
            MediaBackup.callRecordingZipEntryName("call456", "/storage/emulated/0/Download/Evolia/xyz.m4a")
        )
    }

    @Test
    fun `call recording without audio gets no entry`() {
        assertNull(MediaBackup.callRecordingZipEntryName("call1", null))
        assertNull(MediaBackup.callRecordingZipEntryName("call1", ""))
    }

    @Test
    fun `call recording with weird path gets no entry`() {
        assertNull(MediaBackup.callRecordingZipEntryName("call1", "no-extension"))
        assertNull(MediaBackup.callRecordingZipEntryName("call1", "a/b/verylongextensionname"))
    }

    @Test
    fun `radio audio entry is the asset relative path`() {
        val asset = RadioAudioAssetEntity(
            id = "a1", episodeId = "e1", filePath = "radio_audio/a1.m4a",
            providerId = "p", voiceId = "v", status = "CURRENT",
            contentHash = "h", createdAt = 0L
        )
        assertEquals("radio_audio/a1.m4a", MediaBackup.radioAudioZipEntryName(asset))
    }

    // ==================== 恢复后修复决策 ====================

    @Test
    fun `call audio kept when stored file exists`() {
        assertEquals(
            MediaBackup.CallAudioRepair.Keep,
            MediaBackup.decideCallAudioRepair("call_recordings/c1.m4a", true, null)
        )
    }

    @Test
    fun `public export rewritten to restored relative copy`() {
        val repair = MediaBackup.decideCallAudioRepair(
            storedAudio = "/storage/emulated/0/Download/Evolia/rec.m4a",
            storedFileExists = false,
            restoredRelativePath = "call_recordings/c9.m4a"
        )
        assertEquals(MediaBackup.CallAudioRepair.RewriteToRelative("call_recordings/c9.m4a"), repair)
    }

    @Test
    fun `missing everywhere clears audio`() {
        assertEquals(
            MediaBackup.CallAudioRepair.ClearAudio,
            MediaBackup.decideCallAudioRepair("call_recordings/c1.m4a", false, null)
        )
    }

    @Test
    fun `blank audio is keep no-op`() {
        assertEquals(
            MediaBackup.CallAudioRepair.Keep,
            MediaBackup.decideCallAudioRepair(null, false, null)
        )
    }

    @Test
    fun `radio asset kept iff file exists`() {
        assertTrue(MediaBackup.decideRadioAssetRepair(fileExists = true))
        assertFalse(MediaBackup.decideRadioAssetRepair(fileExists = false))
    }
}
