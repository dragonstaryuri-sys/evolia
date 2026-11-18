package me.rerere.rikkahub.data.extensions

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.common.JsonInstant
import java.io.File

/**
 * 本地扩展创建服务（详设第七章）。
 *
 * 草稿（workspaces）与已安装扩展严格分离：草稿不能被 Agent 调用，也不出现在
 * 已安装列表。草稿目录结构即包规范（manifest.json + 资源），校验通过后经
 * ExtensionInstaller.installFromDirectory 进入统一安装链路。
 */
class ExtensionCreator(private val context: Context) {
    companion object {
        const val TAG = "ExtensionCreator"
        fun workspacesRoot(context: Context): File = ExtensionInstaller.workspacesRoot(context)
    }

    data class DraftMeta(
        val draftId: String,
        val appId: String,
        val name: String,
        val description: String,
        val extensionType: String,
        val version: String,
    )

    private fun draftDir(draftId: String): File {
        // draftId 必须是安全目录名（UUID），防路径注入
        require(Regex("^[A-Za-z0-9_-]{1,64}$").matches(draftId)) { "非法草稿 ID" }
        return File(workspacesRoot(context), draftId)
    }

    /**
     * 创建草稿：生成 app_id、目录结构与 Manifest。templateId 见 [ExtensionTemplates]。
     */
    fun createDraft(
        name: String,
        extensionType: String,
        templateId: String = ExtensionTemplates.TEMPLATE_EMPTY_TOOL,
    ): DraftMeta {
        require(name.isNotBlank()) { "扩展名称不能为空" }
        require(extensionType == ExtensionType.TOOL || extensionType == ExtensionType.MINI_APP) {
            "扩展类型不合法"
        }
        val draftId = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        val appId = sanitizeAppId(name, extensionType)
        val dir = draftDir(draftId)
        if (dir.exists() && !dir.deleteRecursively()) {
            throw IllegalStateException("草稿目录创建失败")
        }

        val files = ExtensionTemplates.scaffold(
            name = name,
            appId = appId,
            extensionType = extensionType,
            templateId = templateId,
        )
        files.forEach { (relative, content) ->
            val f = File(dir, relative)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        Log.i(TAG, "草稿已创建：$draftId ($appId, $templateId)")
        return DraftMeta(
            draftId = draftId,
            appId = appId,
            name = name,
            description = "",
            extensionType = extensionType,
            version = "1.0.0",
        )
    }

    /** app_id 生成：中文名转拼音不现实，采用类型前缀 + 时间戳派生的稳定可读 ID */
    private fun sanitizeAppId(name: String, extensionType: String): String {
        val ascii = name.lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(24)
            .trim('_')
        val prefix = if (extensionType == ExtensionType.MINI_APP) "app" else "tool"
        val base = if (ascii.length >= 3) ascii else "${prefix}_ext"
        val suffix = (System.currentTimeMillis() % 100000).toString().padStart(5, '0')
        var candidate = "${base}_$suffix"
        // 压缩到合法格式（正则约束）
        while (!Regex(ExtensionConstants.APP_ID_REGEX).matches(candidate) && candidate.length > 3) {
            candidate = candidate.dropLast(1)
        }
        return candidate
    }

    fun listDrafts(): List<DraftMeta> {
        val root = workspacesRoot(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching {
                val manifest = ZipPackageReader().parseManifestFromDirectory(dir)
                DraftMeta(
                    draftId = dir.name,
                    appId = manifest.appId,
                    name = manifest.name,
                    description = manifest.description,
                    extensionType = manifest.extensionType,
                    version = manifest.version,
                )
            }.getOrNull()
        } ?: emptyList()
    }

    fun getDraft(draftId: String): DraftMeta? {
        val dir = draftDir(draftId)
        if (!dir.isDirectory) return null
        return runCatching {
            val manifest = ZipPackageReader().parseManifestFromDirectory(dir)
            DraftMeta(
                draftId = draftId,
                appId = manifest.appId,
                name = manifest.name,
                description = manifest.description,
                extensionType = manifest.extensionType,
                version = manifest.version,
            )
        }.getOrNull()
    }

    /** 草稿内相对路径列表（仅文件） */
    fun listFiles(draftId: String): List<String> {
        val dir = draftDir(draftId)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
            .sorted()
    }

    fun readFile(draftId: String, relativePath: String): String? {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return null
        val f = File(draftDir(draftId), relativePath)
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun saveFile(draftId: String, relativePath: String, content: String): Boolean {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return false
        val f = File(draftDir(draftId), relativePath)
        f.parentFile?.mkdirs()
        return runCatching { f.writeText(content); true }.getOrDefault(false)
    }

    fun createFile(draftId: String, relativePath: String): Boolean {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return false
        val f = File(draftDir(draftId), relativePath)
        if (f.exists()) return false
        return runCatching { f.parentFile?.mkdirs(); f.createNewTextFile(); true }.getOrDefault(false)
    }

    private fun File.createNewTextFile() {
        writeText("")
    }

    fun deleteFile(draftId: String, relativePath: String): Boolean {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return false
        val f = File(draftDir(draftId), relativePath)
        return f.delete()
    }

    fun deleteDraft(draftId: String): Boolean = draftDir(draftId).deleteRecursively()

    /**
     * 基础校验（详设 7.2）：与安装校验同一套 ManifestValidator。
     */
    fun validate(draftId: String): List<String> {
        val dir = draftDir(draftId)
        if (!dir.isDirectory) return listOf("草稿不存在")
        return try {
            val manifest = ZipPackageReader().parseManifestFromDirectory(dir)
            ManifestValidator.validate(
                manifest = manifest,
                fileExists = { relative -> File(dir, relative).isFile },
                parseSchemaFile = { relative ->
                    val f = File(dir, relative)
                    if (f.isFile) runCatching {
                        JsonInstant.parseToJsonElement(f.readText()) as? JsonObject
                    }.getOrNull() else null
                },
            )
        } catch (e: Exception) {
            listOf(e.message ?: "Manifest 解析失败")
        }
    }
}
