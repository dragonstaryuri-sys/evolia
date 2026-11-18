package me.rerere.rikkahub.data.sandboxfile

/**
 * 沙箱文件管理的纯决策函数集合。
 *
 * 不依赖任何 Android API（android.util.Log 未 mock，单测跑纯 JVM），全部逻辑可单测固化。
 */
object SandboxFileRules {

    /** 图片类扩展名白名单（作为 MIME 之外的辅助判断） */
    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"
    )

    /**
     * 扫描时应跳过的条目名：隐藏文件、Python 缓存与明显的临时/中间产物。
     */
    fun shouldSkipEntry(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith(".")) return true
        if (trimmed.equals("__pycache__", ignoreCase = true)) return true
        val lower = trimmed.lowercase()
        if (lower.endsWith(".pyc")) return true
        if (lower.endsWith(".pyo")) return true
        if (lower.endsWith(".tmp")) return true
        if (lower.endsWith(".part")) return true
        return false
    }

    /**
     * 规范化相对路径：统一 '/' 分隔、去首尾分隔符、折叠连续分隔符。
     */
    fun normalizeRelativePath(relativePath: String): String {
        return relativePath.replace('\\', '/').trim('/').replace(Regex("/{2,}"), "/")
    }

    /**
     * 稳定文件 ID = 来源前缀 + 规范化后的相对路径。
     * 同一文件在多次扫描间保持稳定；不同文件（大小写敏感文件系统）不会撞号；
     * 来源前缀保证两个来源目录的同名相对路径不冲突。
     */
    fun buildStableFileId(source: SandboxFileSource, relativePath: String): String {
        val prefix = when (source) {
            SandboxFileSource.PYTHON_SANDBOX -> "sandbox"
            SandboxFileSource.GENERATED_IMAGE -> "image"
        }
        return "$prefix:${normalizeRelativePath(relativePath)}"
    }

    /**
     * 从来源根目录下相对路径推导第一级子目录名。
     * 文件直接位于根目录（无目录层级）时返回 null。
     * 子目录语义随布局演进：新沙箱布局为助手 id，旧布局为会话 id。
     */
    fun deriveTopLevelDir(relativePath: String): String? {
        val normalized = normalizeRelativePath(relativePath)
        val segments = normalized.split('/')
        return if (segments.size >= 2) segments.first() else null
    }

    /**
     * 路径安全校验：目标路径必须位于沙箱根目录内部（严格内部，不允许等于根目录本身）。
     *
     * 传入的是规范化（canonical）路径；'/' 分隔。
     * 防御：
     * - "../" 路径穿越（canonical 化后表现为不在根内）
     * - 前缀相同的兄弟目录（如 /root2 与 /root：startsWith 命中但下一字符不是 '/'）
     * - 根目录本身
     */
    fun isPathInsideSandbox(rootCanonicalPath: String, targetCanonicalPath: String): Boolean {
        val root = normalizeAbsolutePath(rootCanonicalPath)
        val target = normalizeAbsolutePath(targetCanonicalPath)
        if (root.isEmpty() || target.isEmpty()) return false
        if (target == root) return false
        if (!target.startsWith(root)) return false
        // target 必须以 root + '/' 开头，排除 /sandbox2 这类前缀撞车的兄弟目录
        return target.length > root.length && target[root.length] == '/'
    }

    /**
     * 绝对路径规范化（纯字符串处理，canonical 化由调用方完成）。
     */
    private fun normalizeAbsolutePath(path: String): String {
        val normalized = path.replace('\\', '/')
        return normalized.trimEnd('/').ifEmpty { "/" }
    }

    /**
     * MIME 是否为图片类型。
     */
    fun isImageMime(mimeType: String?): Boolean {
        return mimeType != null && mimeType.lowercase().startsWith("image/")
    }

    /**
     * 扩展名是否为图片类扩展名。
     */
    fun isImageExtension(extension: String?): Boolean {
        if (extension.isNullOrBlank()) return false
        return extension.trimStart('.').lowercase() in IMAGE_EXTENSIONS
    }

    /**
     * 综合判定文件分类：
     * 1. 文件头可识别 → 以文件头为准（内容优先，防止伪装扩展名/伪装 MIME）；
     * 2. 文件头不可识别 → 按 MIME / 扩展名辅助判断；
     * 3. 都不可识别 → 归入其他文件（不忽略）。
     *
     * @param header 文件头字节（前若干字节，可为 null 表示无法读取）
     */
    fun decideCategory(extension: String?, mimeType: String?, header: ByteArray?): SandboxFileCategory {
        when (categoryFromHeader(header)) {
            SandboxFileCategory.IMAGE -> return SandboxFileCategory.IMAGE
            SandboxFileCategory.OTHER -> return SandboxFileCategory.OTHER
            null -> Unit // 文件头无法识别，继续走扩展名/MIME 辅助判断
        }
        if (isImageMime(mimeType) || isImageExtension(extension)) {
            return SandboxFileCategory.IMAGE
        }
        return SandboxFileCategory.OTHER
    }

    /**
     * 通过文件头字节识别内容类型。
     *
     * @return IMAGE / OTHER（明确识别出图片或明确的非图片内容），null 表示无法识别
     */
    fun categoryFromHeader(header: ByteArray?): SandboxFileCategory? {
        if (header == null || header.size < 2) return null

        fun startsWithBytes(vararg expected: Int): Boolean {
            if (header.size < expected.size) return false
            for (i in expected.indices) {
                if (header[i].toInt().and(0xFF) != expected[i]) return false
            }
            return true
        }

        fun asciiAt(index: Int): Char = (header[index].toInt().and(0xFF)).toChar()

        return when {
            // PNG: 89 50 4E 47
            startsWithBytes(0x89, 0x50, 0x4E, 0x47) -> SandboxFileCategory.IMAGE
            // JPEG: FF D8 FF
            startsWithBytes(0xFF, 0xD8, 0xFF) -> SandboxFileCategory.IMAGE
            // GIF: "GIF8"（GIF87a / GIF89a）
            asciiAt(0) == 'G' && asciiAt(1) == 'I' && asciiAt(2) == 'F' && asciiAt(3) == '8' ->
                SandboxFileCategory.IMAGE
            // BMP: "BM"
            asciiAt(0) == 'B' && asciiAt(1) == 'M' -> SandboxFileCategory.IMAGE
            // WEBP: "RIFF" + 4 字节长度 + "WEBP"
            header.size >= 12 &&
                asciiAt(0) == 'R' && asciiAt(1) == 'I' && asciiAt(2) == 'F' && asciiAt(3) == 'F' &&
                asciiAt(8) == 'W' && asciiAt(9) == 'E' && asciiAt(10) == 'B' && asciiAt(11) == 'P' ->
                SandboxFileCategory.IMAGE
            // HEIC / HEIF: offset 4..7 = "ftyp"，brand 以 heic/heix/heif/mif1 开头
            header.size >= 12 &&
                asciiAt(4) == 'f' && asciiAt(5) == 't' && asciiAt(6) == 'y' && asciiAt(7) == 'p' &&
                asciiAt(8) == 'h' && asciiAt(9) == 'e' -> SandboxFileCategory.IMAGE
            // PDF: "%PDF"
            startsWithBytes(0x25, 0x50, 0x44, 0x46) -> SandboxFileCategory.OTHER
            // ZIP 家族（zip/docx/xlsx/jar 等）: "PK\x03\x04" / "PK\x05\x06" / "PK\x07\x08"
            startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
                startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
                startsWithBytes(0x50, 0x4B, 0x07, 0x08) -> SandboxFileCategory.OTHER
            // 纯文本伪装（前 8 字节全部为可打印 ASCII 或常见控制符）→ 明确不是图片
            header.size >= 8 && header.take(8).all { b ->
                val v = b.toInt().and(0xFF)
                v in 0x20..0x7E || v == 0x0A || v == 0x0D || v == 0x09
            } -> SandboxFileCategory.OTHER
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 分组展示（按来源 + 第一级子目录聚簇）
    // ------------------------------------------------------------------

    /** 生图来源的分组键（平铺无子目录，整源一组） */
    const val GROUP_KEY_GENERATED_IMAGES = "img"

    /** python_sandbox 根目录直属文件的分组键（罕见，兜底组） */
    const val GROUP_KEY_SANDBOX_ROOT = "sandbox-root"

    /**
     * 分组键：来源 + 第一级子目录。
     * - 生图来源 → [GROUP_KEY_GENERATED_IMAGES]
     * - 沙箱根目录直属文件 → [GROUP_KEY_SANDBOX_ROOT]
     * - 沙箱子目录文件 → "sandbox:<dirName>"（新布局 dirName=助手 id，旧布局=会话 id）
     */
    fun groupKeyOf(file: SandboxFileInfo): String {
        return when (file.source) {
            SandboxFileSource.GENERATED_IMAGE -> GROUP_KEY_GENERATED_IMAGES
            SandboxFileSource.PYTHON_SANDBOX -> {
                val dir = file.topLevelDir
                if (dir.isNullOrBlank()) GROUP_KEY_SANDBOX_ROOT else "sandbox:$dir"
            }
        }
    }

    /**
     * 构建分组列表（纯函数，标签由调用方注入）：
     * - 组内文件按最后修改时间倒序；
     * - 组间按组内最新文件时间倒序（最近活跃的组在前）。
     */
    fun buildGroups(
        files: List<SandboxFileInfo>,
        labelOf: (groupKey: String) -> String
    ): List<SandboxFileGroup> {
        return files.groupBy { groupKeyOf(it) }
            .map { (key, groupFiles) ->
                SandboxFileGroup(
                    key = key,
                    label = labelOf(key),
                    files = groupFiles.sortedByDescending { it.lastModified }
                )
            }
            .sortedByDescending { group -> group.files.maxOf { it.lastModified } }
    }
}
