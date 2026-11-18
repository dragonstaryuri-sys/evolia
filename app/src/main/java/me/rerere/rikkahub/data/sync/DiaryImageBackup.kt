package me.rerere.rikkahub.data.sync

import java.io.File

/**
 * 扫描日记图片（diary_images）备份/恢复的纯决策函数（单测固化，见 DiaryImageBackupTest）。
 *
 * 备份规则（AGENTS.md 12.7）：
 * - 图片存储规则（DiaryImageUtil）：`filesDir/diary_images/<diaryId>/<imageId>.webp`
 * - FILES 项递归打包，zip 条目 = `diary_images/<diaryId>/<imageId>.webp`（相对 filesDir）；
 * - 直接复制 WebP 二进制，不重新解码/压缩/转格式；
 * - 打包读取失败 → 可识别异常上报，备份不得静默标记为完整成功；
 * - DB 引用但磁盘缺失（历史遗留）→ 明确记录并提示备份可能不完整。
 *
 * 恢复规则：
 * - 通用 FILES 分支按原目录层级还原到 `filesDir/diary_images/`；
 * - 历史备份无 diary_images 条目 → 正常恢复其余数据，不报错；
 * - 恢复后对账：路径失效但可按 diaryId/imageId 重建 → 修复 DB 路径；
 *   文件确实不存在 → 保留图片元数据与 OCR 结果，仅记录警告（绝不删日记）。
 */
object DiaryImageBackup {

    /** zip 内/相对 filesDir 的扫描日记图片顶层目录名 */
    const val ZIP_DIR = "diary_images"

    /**
     * 由 DB 存储的图片路径（绝对路径）推导 zip 条目名。
     * 仅接受 filesDir 下的路径（`diary_images/<diaryId>/<imageId>.webp`）；
     * 不在 filesDir 下、结构异常或空白时返回 null（调用方记录警告，不阻断备份）。
     */
    fun zipEntryNameOf(storedImagePath: String, filesDirPath: String): String? {
        val normalized = storedImagePath.replace('\\', '/').trim()
        if (normalized.isBlank()) return null
        val filesDirNormalized = filesDirPath.replace('\\', '/').trimEnd('/')
        if (filesDirNormalized.isBlank()) return null
        val prefix = "$filesDirNormalized/"
        if (!normalized.startsWith(prefix)) return null
        val relative = normalized.removePrefix(prefix).trimStart('/')
        if (!relative.startsWith("$ZIP_DIR/")) return null
        val subPath = relative.removePrefix("$ZIP_DIR/").trim('/')
        // 必须为 <diaryId>/<imageId>.webp 两级结构
        val segments = subPath.split('/')
        if (segments.size != 2 || segments.any { it.isBlank() }) return null
        return "$ZIP_DIR/$subPath"
    }

    /** 按 DiaryImageUtil 存储规则重建图片绝对路径（纯字符串版本，供决策与单测使用）。 */
    fun expectedImagePathOf(filesDirPath: String, diaryId: String, imageId: String): String {
        val normalizedDir = filesDirPath.replace('\\', '/').trimEnd('/')
        return "$normalizedDir/$ZIP_DIR/$diaryId/$imageId.webp"
    }

    /** 备份阶段：单条 DB 图片引用的磁盘状态分类。 */
    sealed interface RefFileStatus {
        /** 文件存在且符合 diary_images 布局，可按相对路径打包 */
        data class Packable(val zipEntryName: String) : RefFileStatus

        /** 文件存在但不在 diary_images 布局下（异常路径，无法按规则打包） */
        data object OutsideDiaryDir : RefFileStatus

        /** 文件不存在（备份前已缺失，属历史遗留） */
        data object Missing : RefFileStatus
    }

    /**
     * @param storedImagePath DB 中的 imagePath
     * @param existsOnDisk 调用方用 File(storedImagePath).exists() 的结果
     */
    fun classifyRef(storedImagePath: String, filesDirPath: String, existsOnDisk: Boolean): RefFileStatus {
        val entry = zipEntryNameOf(storedImagePath, filesDirPath)
        return when {
            !existsOnDisk -> RefFileStatus.Missing
            entry != null -> RefFileStatus.Packable(entry)
            else -> RefFileStatus.OutsideDiaryDir
        }
    }

    /** 恢复阶段：单条图片引用的修复决策。 */
    sealed interface ImageRepair {
        /** 存储路径指向的文件存在（同机恢复）→ 原样保留 */
        data object Keep : ImageRepair

        /** 存储路径失效，但按 diaryId/imageId 在恢复目录中找到文件 → 修复 DB 路径 */
        data class Rewrite(val absolutePath: String) : ImageRepair

        /** 文件确实不存在 → 保留元数据与 OCR 结果，仅记录警告 */
        data object Missing : ImageRepair
    }

    /**
     * @param storedImagePath DB 中的 imagePath
     * @param storedFileExists 存储路径当前是否指向真实文件
     * @param expectedAbsolutePath 按 DiaryImageUtil 规则重建的绝对路径（null = 无法重建）
     * @param expectedFileExists 重建路径处文件是否存在
     */
    fun decideRepair(
        storedImagePath: String,
        storedFileExists: Boolean,
        expectedAbsolutePath: String?,
        expectedFileExists: Boolean
    ): ImageRepair {
        if (storedFileExists) return ImageRepair.Keep
        val expected = expectedAbsolutePath?.takeIf { it.isNotBlank() && expectedFileExists }
            ?: return ImageRepair.Missing
        return ImageRepair.Rewrite(expected)
    }

    /**
     * 备份统计：只有预期纳入备份的图片全部成功打包，扫描日记图片备份才算完整。
     */
    data class BackupStats(
        /** 数据库中引用的图片总数 */
        val dbImageCount: Int = 0,
        /** 磁盘 diary_images 下实际发现的文件数（含孤儿文件） */
        val diskFileCount: Int = 0,
        /** 成功打包数量 */
        val packedCount: Int = 0,
        /** 打包失败（读取/写入 IO 异常）数量 */
        val failedCount: Int = 0,
        /** 成功打包的总字节数 */
        val totalBytes: Long = 0,
        /** DB 引用但磁盘缺失的路径（历史遗留，备份前就缺失） */
        val missingPaths: List<String> = emptyList(),
        /** 打包时读取/写入失败的路径（本次备份过程中的文件操作失败） */
        val failedPaths: List<String> = emptyList(),
        /** 磁盘存在但不在 diary_images 布局下的 DB 引用路径 */
        val outsidePaths: List<String> = emptyList(),
    ) {
        /** 任一图片未能打包 → 备份不完整 */
        val isComplete: Boolean
            get() = missingPaths.isEmpty() && failedPaths.isEmpty() && outsidePaths.isEmpty()

        /** 生成用户可读的不完整原因描述（三类问题分开列举） */
        fun describeIncomplete(): String {
            val parts = buildList {
                if (missingPaths.isNotEmpty()) {
                    add("数据库引用但文件缺失（历史遗留）${missingPaths.size} 张：${missingPaths.take(3).joinToString()}")
                }
                if (failedPaths.isNotEmpty()) {
                    add("打包读取失败 ${failedPaths.size} 张：${failedPaths.take(3).joinToString()}")
                }
                if (outsidePaths.isNotEmpty()) {
                    add("路径不符合 diary_images 布局 ${outsidePaths.size} 张：${outsidePaths.take(3).joinToString()}")
                }
            }
            return parts.joinToString("；")
        }
    }

    /** 打包失败时向上层传递的可识别异常：备份不完整，禁止静默成功。 */
    class IncompleteBackupException(val stats: BackupStats) :
        Exception("扫描日记图片备份不完整（发现${stats.dbImageCount}张引用，成功打包${stats.packedCount}张）：${stats.describeIncomplete()}")
}
