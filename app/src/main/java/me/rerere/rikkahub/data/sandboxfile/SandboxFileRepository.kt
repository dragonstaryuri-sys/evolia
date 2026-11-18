package me.rerere.rikkahub.data.sandboxfile

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.PythonSandbox
import java.io.File

/**
 * Agent 创建文件管理仓库。
 *
 * 统一管理两类 Agent 产物（[SandboxFileSource]）：
 * - `python_sandbox/`：Python 沙箱工作目录（与 [PythonSandbox].baseDir 一致）
 * - `images/`：Agent 生图产物（generate_image 工具与生图页写入，平铺）
 *
 * 文件生命周期与对话解耦：不随对话删除/清空片段记忆而清理，仅用户在管理页主动删除。
 * 不改变 [PythonSandbox] 与生图的运行逻辑，只读取/管理其产物。
 */
class SandboxFileRepository(private val context: Context) {

    companion object {
        private const val TAG = "SandboxFileRepo"
    }

    /** Python 沙箱根目录 */
    fun sandboxRootDir(): File = File(context.filesDir, "python_sandbox")

    /** Agent 生图根目录（ChatUtil.getImagesDir 同路径） */
    fun generatedImagesRootDir(): File = File(context.filesDir, "images")

    private fun rootDirOf(source: SandboxFileSource): File = when (source) {
        SandboxFileSource.PYTHON_SANDBOX -> sandboxRootDir()
        SandboxFileSource.GENERATED_IMAGE -> generatedImagesRootDir()
    }

    /**
     * 扫描结果：整体失败（根目录不可读等）与空列表必须区分，避免用户误以为文件被清空。
     */
    sealed interface ScanResult {
        data class Success(val files: List<SandboxFileInfo>) : ScanResult
        data class Failure(val error: Throwable) : ScanResult
    }

    /** 删除结果（精确语义，UI 逐类反馈） */
    enum class DeleteResult {
        /** 删除成功 */
        DELETED,

        /** 文件不存在（可能已被外部删除/移动） */
        NOT_FOUND,

        /** 路径校验失败，拒绝删除（防御路径穿越） */
        OUTSIDE_SANDBOX,

        /** 删除执行失败（IO 异常等） */
        FAILED
    }

    /**
     * 扫描全部来源的 Agent 文件。
     *
     * 要求（方案 5.2）：
     * - IO 线程执行；不读取文件完整内容（图片只嗅探文件头）；
     * - 单个文件/目录读取失败不终止整体扫描；单个来源失败不拖垮另一来源；
     * - 跳过目录、隐藏文件与明显临时产物；
     * - 无法识别类型的文件归入其他文件。
     */
    suspend fun scanFiles(): ScanResult = withContext(Dispatchers.IO) {
        val allFiles = mutableListOf<SandboxFileInfo>()
        val failures = mutableListOf<Throwable>()
        SandboxFileSource.entries.forEach { source ->
            runCatching { scanSource(source) }
                .onSuccess { allFiles.addAll(it) }
                .onFailure { error ->
                    Log.w(TAG, "scan source $source failed", error)
                    failures.add(error)
                }
        }
        when {
            // 全部来源都失败才算整体失败；部分失败时保留成功来源的结果
            failures.size == SandboxFileSource.entries.size ->
                ScanResult.Failure(failures.first())
            else -> ScanResult.Success(
                allFiles.sortedWith(
                    compareByDescending<SandboxFileInfo> { it.lastModified }.thenBy { it.id }
                )
            )
        }
    }

    /**
     * 快速统计文件总数（设置页入口展示用）。
     * 仅计数，不构建完整模型；全部来源统计失败返回 -1（调用方据此决定是否展示数字）。
     */
    suspend fun countFiles(): Int = withContext(Dispatchers.IO) {
        var counted = 0
        var anyFailed = false
        SandboxFileSource.entries.forEach { source ->
            val root = rootDirOf(source)
            if (!root.isDirectory) return@forEach
            runCatching {
                counted += root.walkTopDown()
                    .filter { it.isFile }
                    .filter { !SandboxFileRules.shouldSkipEntry(it.name) }
                    .count()
            }.onFailure {
                Log.w(TAG, "count source $source failed", it)
                anyFailed = true
            }
        }
        if (anyFailed && counted == 0) -1 else counted
    }

    /**
     * 扫描单个来源根目录。
     * 根目录不存在 → 空列表（正常状态，非错误）。
     */
    private fun scanSource(source: SandboxFileSource): List<SandboxFileInfo> {
        val root = rootDirOf(source)
        if (!root.isDirectory) return emptyList()
        val rootCanonical = root.canonicalPath
        return root.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                !SandboxFileRules.shouldSkipEntry(file.name) &&
                    file.parentFile?.let { !SandboxFileRules.shouldSkipEntry(it.name) } ?: true
            }
            .mapNotNull { file -> buildFileInfoOrNull(source, rootCanonical, file) }
            .toList()
    }

    /**
     * 构建单个文件的展示模型；失败返回 null（跳过该文件，不影响整体）。
     */
    private fun buildFileInfoOrNull(
        source: SandboxFileSource,
        rootCanonical: String,
        file: File
    ): SandboxFileInfo? {
        return runCatching {
            // 防御：仅收集 canonical 路径位于对应根内的文件（符号链接等特殊情况不越界展示）
            val canonical = file.canonicalPath
            if (!SandboxFileRules.isPathInsideSandbox(rootCanonical, canonical)) return@runCatching null

            val relativePath = SandboxFileRules.normalizeRelativePath(
                canonical.removePrefix(rootCanonical).trimStart('/', '\\')
            )
            if (relativePath.isEmpty()) return@runCatching null

            val extension = file.extension.lowercase().ifEmpty { null }
            val mimeType = resolveMimeType(extension)
            val header = sniffImageHeader(file, extension, mimeType)
            val category = SandboxFileRules.decideCategory(extension, mimeType, header)

            SandboxFileInfo(
                id = SandboxFileRules.buildStableFileId(source, relativePath),
                name = file.name,
                relativePath = relativePath,
                size = file.length(),
                lastModified = file.lastModified(),
                mimeType = mimeType,
                extension = extension,
                topLevelDir = SandboxFileRules.deriveTopLevelDir(relativePath),
                source = source,
                category = category
            )
        }.getOrNull()
    }

    /**
     * 解析扩展名对应 MIME（扩展名辅助判断的一部分，MimeMap 是 Android 唯一入口）。
     */
    private fun resolveMimeType(extension: String?): String? {
        if (extension.isNullOrBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    /**
     * 对「可能是图片」的文件嗅探文件头（最多 16 字节），供分类判定。
     * 非图片候选文件不读文件，避免无谓 IO。
     */
    private fun sniffImageHeader(file: File, extension: String?, mimeType: String?): ByteArray? {
        val isCandidate = SandboxFileRules.isImageMime(mimeType) || SandboxFileRules.isImageExtension(extension)
        if (!isCandidate) return null
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(16)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) null else buffer.copyOf(read)
            }
        }.getOrNull()
    }

    /**
     * 获取文件在其来源目录内的 File 对象（带路径安全校验）。
     * 返回 null 表示路径非法或解析失败。
     */
    fun resolveFile(file: SandboxFileInfo): File? {
        val root = rootDirOf(file.source)
        val target = File(root, file.relativePath)
        return runCatching {
            if (!SandboxFileRules.isPathInsideSandbox(root.canonicalPath, target.canonicalPath)) {
                null
            } else {
                target
            }
        }.getOrNull()
    }

    /**
     * 内容 URI（FileProvider，供系统查看器/预览使用）。
     * file_paths.xml 的 files-path path="." 已覆盖 filesDir 根，两个来源目录天然共享。
     */
    fun contentUriFor(file: SandboxFileInfo): Uri? {
        val target = resolveFile(file) ?: return null
        if (!target.isFile) return null
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
        }.getOrNull()
    }

    /**
     * 删除单个文件。
     *
     * 安全边界（方案 5.6）：
     * - 规范化后路径必须严格位于其来源根目录内；
     * - 不允许删除根目录本身；
     * - canonical 化天然消解 "../" 路径穿越；
     * - 只处理调用方明确传入的单个文件。
     */
    suspend fun deleteFile(file: SandboxFileInfo): DeleteResult =
        withContext(Dispatchers.IO) {
            val target = resolveFile(file)
                ?: return@withContext DeleteResult.OUTSIDE_SANDBOX
            if (!target.exists()) return@withContext DeleteResult.NOT_FOUND
            if (!target.isFile) return@withContext DeleteResult.FAILED
            runCatching {
                if (target.delete()) DeleteResult.DELETED else DeleteResult.FAILED
            }.getOrElse { DeleteResult.FAILED }
        }

    /**
     * 将单个文件流式导出到目标 Uri（ACTION_CREATE_DOCUMENT 返回的本地目标）。
     *
     * 要求（方案 5.5）：不整读 ByteArray、失败不删源文件、由调用方反馈结果。
     *
     * @return true 导出成功；false 失败（源不存在/不可读/写入失败）
     */
    suspend fun exportFile(file: SandboxFileInfo, target: Uri): Boolean =
        withContext(Dispatchers.IO) {
            exportInternal(resolveFile(file) ?: return@withContext false, target)
        }

    /**
     * 将单个文件导出到用户选择的目录树（ACTION_OPEN_DOCUMENT_TREE）。
     * 通过 DocumentsContract.createDocument 在目标目录创建文件（重名时 provider 自动改名，不覆盖）。
     *
     * @return true 导出成功
     */
    suspend fun exportFileToDirectory(file: SandboxFileInfo, treeUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val source = resolveFile(file) ?: return@withContext false
            if (!source.isFile) return@withContext false
            runCatching {
                val docUri = android.provider.DocumentsContract.createDocument(
                    context.contentResolver,
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    ),
                    file.mimeType ?: "application/octet-stream",
                    file.name
                ) ?: return@runCatching false
                exportInternal(source, docUri)
            }.getOrDefault(false)
        }

    private fun exportInternal(source: File, target: Uri): Boolean {
        if (!source.isFile) return false
        return runCatching {
            context.contentResolver.openOutputStream(target, "w")?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                    output.flush()
                }
                true
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * 导出时使用的建议展示名（含扩展名），供 SAF 选择器预填。
     */
    fun suggestedDisplayName(file: SandboxFileInfo): String = file.name
}
