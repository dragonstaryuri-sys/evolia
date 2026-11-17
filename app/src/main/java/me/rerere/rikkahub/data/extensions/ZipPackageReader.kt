package me.rerere.rikkahub.data.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.common.JsonInstant
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 扩展包解析/解压异常，message 面向用户展示。
 */
class ExtensionPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ZIP 扩展包读取器（详设第五章）。
 *
 * 安全限制集中在 [PackageLimits]；防范 ZIP Slip / 压缩炸弹 / 超大文件 /
 * 重复路径 / 非法文件名 / 绝对路径。解压与哈希计算必须在 IO 线程执行。
 */
class ZipPackageReader {

    data class PackageContent(
        val manifest: ExtensionManifest,
        val manifestHash: String,
        val packageHash: String,
        val fileCount: Int,
        val totalUncompressedBytes: Long,
    )

    /**
     * 解析并将扩展包解压到 [targetDir]（应为 staging 目录）。
     * 解压前完成全部安全校验；manifest.json 缺失或非法即失败。
     */
    suspend fun extractTo(zipFile: File, targetDir: File): PackageContent = withContext(Dispatchers.IO) {
        if (!zipFile.exists()) throw ExtensionPackageException("扩展包文件不存在")
        val packageSize = zipFile.length()
        if (packageSize > PackageLimits.MAX_PACKAGE_SIZE_BYTES) {
            throw ExtensionPackageException("扩展包体积 ${(packageSize / 1024 / 1024)}MB 超出上限 20MB")
        }
        val packageHash = sha256(zipFile)

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw ExtensionPackageException("无法创建解压目录")
        }

        val writtenPaths = mutableSetOf<String>()
        var fileCount = 0
        var totalBytes = 0L
        var manifestBytes: ByteArray? = null

        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                for (entry in entries) {
                    val safeName = sanitizeEntryName(entry.name)
                        ?: throw ExtensionPackageException("扩展包含有不安全的文件路径：「${entry.name}」")

                    if (entry.isDirectory) continue

                    fileCount++
                    if (fileCount > PackageLimits.MAX_FILE_COUNT) {
                        throw ExtensionPackageException("扩展包文件数量超出上限 ${PackageLimits.MAX_FILE_COUNT}")
                    }
                    if (safeName in writtenPaths) {
                        throw ExtensionPackageException("扩展包含有重复文件路径：「$safeName」")
                    }
                    writtenPaths.add(safeName)

                    // 压缩炸弹检测：声明解压比例超限直接拒绝
                    if (entry.compressedSize > 0 && entry.size > 0) {
                        val ratio = entry.size / entry.compressedSize
                        if (ratio > PackageLimits.MAX_COMPRESSION_RATIO) {
                            throw ExtensionPackageException("文件「$safeName」解压比例超出上限（疑似压缩炸弹）")
                        }
                    }

                    val bytes = readEntryLimited(zip, entry, safeName)
                    totalBytes += bytes.size
                    if (totalBytes > PackageLimits.MAX_UNCOMPRESSED_SIZE_BYTES) {
                        throw ExtensionPackageException("扩展包解压后总体积超出上限 50MB")
                    }

                    val outFile = File(targetDir, safeName)
                    validateNotColliding(targetDir, outFile, safeName)
                    outFile.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs()) {
                            throw ExtensionPackageException("无法创建目录：${parent.name}")
                        }
                    }
                    outFile.writeBytes(bytes)
                    if (safeName == "manifest.json") manifestBytes = bytes
                }
            }
        } catch (e: ExtensionPackageException) {
            throw e
        } catch (e: IOException) {
            throw ExtensionPackageException("扩展包读取失败：${e.message}", e)
        }

        val manifestJson = manifestBytes ?: run {
            // manifestBytes 是闭包内赋值的局部 var，无法 smart cast，显式判空
            throw ExtensionPackageException("扩展包缺少 manifest.json")
        }
        val manifest = try {
            JsonInstant.decodeFromString<ExtensionManifest>(manifestJson.decodeToString())
        } catch (e: Exception) {
            throw ExtensionPackageException("manifest.json 解析失败：${e.message}", e)
        }

        PackageContent(
            manifest = manifest,
            manifestHash = sha256(manifestJson),
            packageHash = packageHash,
            fileCount = fileCount,
            totalUncompressedBytes = totalBytes,
        )
    }

    /**
     * 从已解压目录（草稿/工作区）构建包内容，不做 ZIP 读取。
     */
    fun parseManifestFromDirectory(dir: File): ExtensionManifest {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) throw ExtensionPackageException("缺少 manifest.json")
        return try {
            JsonInstant.decodeFromString<ExtensionManifest>(manifestFile.readText())
        } catch (e: Exception) {
            throw ExtensionPackageException("manifest.json 解析失败：${e.message}", e)
        }
    }

    private fun readEntryLimited(zip: ZipFile, entry: ZipEntry, name: String): ByteArray {
        val declaredSize = entry.size
        if (declaredSize > PackageLimits.MAX_SINGLE_FILE_SIZE_BYTES) {
            throw ExtensionPackageException("单文件「$name」超出 10MB 上限")
        }
        zip.getInputStream(entry).use { input ->
            val buffer = input.readBytes()
            if (buffer.size > PackageLimits.MAX_SINGLE_FILE_SIZE_BYTES) {
                throw ExtensionPackageException("单文件「$name」超出 10MB 上限")
            }
            return buffer
        }
    }

    /** 目标文件不能与已有目录冲突，目录链上任何节点不能是普通文件（防止 a/b 与文件 a 冲突） */
    private fun validateNotColliding(root: File, outFile: File, name: String) {
        var cur: File = outFile
        while (cur != root) {
            if (cur.exists() && !cur.isDirectory) {
                throw ExtensionPackageException("文件路径与已有文件冲突：「$name」")
            }
            cur = cur.parentFile ?: return
        }
    }

    companion object {
        /**
         * ZIP Slip 防护：非法/越界/绝对路径返回 null。
         * 同时拒绝 Windows 盘符与反斜杠（ZIP 规范要求正斜杠）。
         */
        fun sanitizeEntryName(rawName: String): String? {
            if (rawName.isBlank() || rawName.length > 512) return null
            if (rawName.startsWith("/") || rawName.startsWith("\\")) return null
            val name = rawName
            if (name.contains('\\')) return null
            if (name.contains(':')) return null
            if (name.any { it.code < 0x20 }) return null
            val segments = name.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            return name
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            // java.util.Base64（minSdk 28 可用），避免 android.util.Base64 阻塞纯 JVM 单测
            return java.util.Base64.getEncoder().encodeToString(digest.digest())
        }

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return java.util.Base64.getEncoder().encodeToString(digest.digest(bytes))
        }
    }
}
