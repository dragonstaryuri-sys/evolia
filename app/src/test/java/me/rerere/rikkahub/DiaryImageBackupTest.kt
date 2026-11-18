package me.rerere.rikkahub

import me.rerere.rikkahub.data.sync.DiaryImageBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描日记图片备份/恢复纯决策（AGENTS.md 12.7）：
 * zip 条目推导、DB 引用磁盘分类、恢复后路径修复决策、备份完整性统计。
 */
class DiaryImageBackupTest {

    private val filesDir = "/data/user/0/me.rerere.rikkahub/files"

    // ==================== zip 条目名推导 ====================

    @Test
    fun `absolute path under filesDir maps to diary_images zip entry`() {
        val path = "$filesDir/diary_images/diary1/img1.webp"
        assertEquals(
            "diary_images/diary1/img1.webp",
            DiaryImageBackup.zipEntryNameOf(path, filesDir)
        )
    }

    @Test
    fun `backslash separators are normalized`() {
        val path = "C:\\data\\files\\diary_images\\diary1\\img1.webp"
        assertEquals(
            "diary_images/diary1/img1.webp",
            DiaryImageBackup.zipEntryNameOf(path, "C:\\data\\files")
        )
    }

    @Test
    fun `path outside filesDir gets no entry`() {
        assertNull(
            DiaryImageBackup.zipEntryNameOf(
                "/storage/emulated/0/Download/img.webp",
                filesDir
            )
        )
    }

    @Test
    fun `path not under diary_images gets no entry`() {
        assertNull(
            DiaryImageBackup.zipEntryNameOf("$filesDir/images/gen1.png", filesDir)
        )
    }

    @Test
    fun `nested or flat structure inside diary_images gets no entry`() {
        // 旧版可能有根目录直属文件（无 diaryId 层级）
        assertNull(DiaryImageBackup.zipEntryNameOf("$filesDir/diary_images/root.webp", filesDir))
        // 异常深层级（>2 段）
        assertNull(
            DiaryImageBackup.zipEntryNameOf("$filesDir/diary_images/a/b/c.webp", filesDir)
        )
    }

    @Test
    fun `blank path gets no entry`() {
        assertNull(DiaryImageBackup.zipEntryNameOf("", filesDir))
        assertNull(DiaryImageBackup.zipEntryNameOf("   ", filesDir))
    }

    // ==================== 备份阶段引用分类 ====================

    @Test
    fun `existing file under diary_images is packable`() {
        val status = DiaryImageBackup.classifyRef(
            storedImagePath = "$filesDir/diary_images/d1/i1.webp",
            filesDirPath = filesDir,
            existsOnDisk = true
        )
        assertTrue(status is DiaryImageBackup.RefFileStatus.Packable)
        assertEquals("diary_images/d1/i1.webp", (status as DiaryImageBackup.RefFileStatus.Packable).zipEntryName)
    }

    @Test
    fun `non-existent referenced file is missing`() {
        assertEquals(
            DiaryImageBackup.RefFileStatus.Missing,
            DiaryImageBackup.classifyRef("$filesDir/diary_images/d1/i1.webp", filesDir, existsOnDisk = false)
        )
    }

    @Test
    fun `existing file outside diary_images layout is outside`() {
        assertEquals(
            DiaryImageBackup.RefFileStatus.OutsideDiaryDir,
            DiaryImageBackup.classifyRef("/sdcard/DCIM/photo.webp", filesDir, existsOnDisk = true)
        )
    }

    // ==================== 恢复阶段修复决策 ====================

    @Test
    fun `stored path valid on same device is kept`() {
        assertEquals(
            DiaryImageBackup.ImageRepair.Keep,
            DiaryImageBackup.decideRepair(
                storedImagePath = "$filesDir/diary_images/d1/i1.webp",
                storedFileExists = true,
                expectedAbsolutePath = "$filesDir/diary_images/d1/i1.webp",
                expectedFileExists = true
            )
        )
    }

    @Test
    fun `broken path rewritten to rebuilt path after cross-device restore`() {
        val expected = DiaryImageBackup.expectedImagePathOf(filesDir, "d1", "i1")
        assertEquals(
            "$filesDir/diary_images/d1/i1.webp",
            expected
        )
        assertEquals(
            DiaryImageBackup.ImageRepair.Rewrite(expected),
            DiaryImageBackup.decideRepair(
                storedImagePath = "/data/user/0/old.device/files/diary_images/d1/i1.webp",
                storedFileExists = false,
                expectedAbsolutePath = expected,
                expectedFileExists = true
            )
        )
    }

    @Test
    fun `file truly missing keeps metadata as missing`() {
        assertEquals(
            DiaryImageBackup.ImageRepair.Missing,
            DiaryImageBackup.decideRepair(
                storedImagePath = "/old/files/diary_images/d1/i1.webp",
                storedFileExists = false,
                expectedAbsolutePath = "$filesDir/diary_images/d1/i1.webp",
                expectedFileExists = false
            )
        )
    }

    @Test
    fun `unrebuildable expected path is missing`() {
        assertEquals(
            DiaryImageBackup.ImageRepair.Missing,
            DiaryImageBackup.decideRepair(
                storedImagePath = "/old/files/diary_images/d1/i1.webp",
                storedFileExists = false,
                expectedAbsolutePath = null,
                expectedFileExists = false
            )
        )
    }

    // ==================== 备份完整性统计 ====================

    @Test
    fun `empty stats mean complete backup`() {
        assertTrue(DiaryImageBackup.BackupStats().isComplete)
    }

    @Test
    fun `missing historical file makes backup incomplete`() {
        val stats = DiaryImageBackup.BackupStats(
            dbImageCount = 2,
            packedCount = 1,
            missingPaths = listOf("$filesDir/diary_images/d1/gone.webp")
        )
        assertFalse(stats.isComplete)
        assertTrue(stats.describeIncomplete().contains("历史遗留"))
    }

    @Test
    fun `pack io failure makes backup incomplete`() {
        val stats = DiaryImageBackup.BackupStats(
            dbImageCount = 1,
            packedCount = 0,
            failedCount = 1,
            failedPaths = listOf("diary_images/d1/broken.webp")
        )
        assertFalse(stats.isComplete)
        assertTrue(stats.describeIncomplete().contains("打包读取失败"))
    }

    @Test
    fun `incomplete exception message contains packed ratio`() {
        val stats = DiaryImageBackup.BackupStats(
            dbImageCount = 3,
            packedCount = 2,
            missingPaths = listOf("$filesDir/diary_images/d1/gone.webp")
        )
        val message = DiaryImageBackup.IncompleteBackupException(stats).message.orEmpty()
        assertTrue(message.contains("发现3张引用"))
        assertTrue(message.contains("成功打包2张"))
    }

    @Test
    fun `outside layout reference makes backup incomplete`() {
        val stats = DiaryImageBackup.BackupStats(
            dbImageCount = 1,
            packedCount = 0,
            outsidePaths = listOf("/sdcard/DCIM/photo.webp")
        )
        assertFalse(stats.isComplete)
    }
}
