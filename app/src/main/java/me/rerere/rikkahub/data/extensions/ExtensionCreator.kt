package me.rerere.rikkahub.data.extensions

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.common.JsonInstantPretty
import java.io.File

/**
 * 本地扩展创建服务（详设第七章 + 2026-11-20 补充需求第二/三章）。
 *
 * Workspace（开发中草稿，workspaces/）与 Runtime（已安装，installed/）严格隔离：
 * 草稿不能被 Agent 调用，也不出现在已安装列表。工作区目录结构即包规范
 * （manifest.json + 资源 + workspace.json 元数据），校验通过后经
 * ExtensionInstaller.installFromDirectory 进入统一安装链路（失败旧版本保留）。
 *
 * 每个拓展（appId）最多一个活动工作区：
 * - 全新草稿：createDraft，初始版本 1.0.0；
 * - 二次开发：createDraftFromInstalled，基于安装版自动递增 Patch 版本，
 *   安装期间旧版本始终正常运行；已有工作区时幂等返回（禁止并行第二版本）。
 *
 * 版本号由系统自动管理：workspace.json 记录受管版本，saveFile 写 manifest.json
 * 时强制归一，用户无法通过编辑器持久化修改版本号。
 *
 * 二次开发权限（Manifest.development.allow_modification）在能力层统一拦截：
 * requireEditable 覆盖全部工作区创建/修改入口。Agent V1 无源码修改工具；
 * 未来任何能写工作区的 Agent 工具必须先过本类能力层检查。
 */
class ExtensionCreator(
    private val context: Context,
    private val registry: ExtensionRegistry,
) {
    companion object {
        const val TAG = "ExtensionCreator"

        /** 工作区元数据文件（受管版本 / 基础版本 / 校验状态；不参与内容哈希） */
        const val WORKSPACE_META_FILE = "workspace.json"

        fun workspacesRoot(context: Context): File = ExtensionInstaller.workspacesRoot(context)

        /**
         * 版本归一（纯函数，有单测）：把 manifest JSON 文本中的 version 字段
         * 改写为受管版本。无需改写 / 解析失败 / 非对象时返回 null（调用方按
         * 原文写盘，交由校验报错）。
         */
        fun normalizeManifestVersion(content: String, managedVersion: String?): String? {
            if (managedVersion.isNullOrBlank()) return null
            val element = runCatching { JsonInstant.parseToJsonElement(content) }.getOrNull()
            val obj = element as? JsonObject ?: return null
            val currentVersion = (obj["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (currentVersion == managedVersion) return null
            return rewriteManifestVersionJson(content, managedVersion)
        }

        /**
         * 重写 manifest JSON 文本的 version 字段（纯函数，有单测）。
         * 非法 JSON / 非对象返回 null；其余字段原样保留。
         */
        fun rewriteManifestVersionJson(content: String, newVersion: String): String? {
            val element = runCatching { JsonInstant.parseToJsonElement(content) }.getOrNull()
            val obj = element as? JsonObject ?: return null
            val rewritten = buildJsonObject {
                obj.forEach { (key, value) ->
                    if (key == "version") put(key, newVersion) else put(key, value)
                }
            }
            return JsonInstantPretty.encodeToString(rewritten)
        }
    }

    // ---------- 工作区元数据（workspace.json，纯文件不建 Room 表） ----------

    @Serializable
    data class WorkspaceMeta(
        /** 受管版本号：创建时生成，开发过程中不变，校验/安装一致使用 */
        val version: String,
        /** 展示快照：manifest 被编辑成暂态非法 JSON 时，草稿箱仍能显示与恢复 */
        val name: String? = null,
        @SerialName("extension_type") val extensionType: String? = null,
        /** 基于已安装拓展开发时记录；null = 全新拓展 */
        @SerialName("base_app_id") val baseAppId: String? = null,
        @SerialName("base_version") val baseVersion: String? = null,
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
        @SerialName("last_validation") val lastValidation: LastValidation? = null,
    )

    @Serializable
    data class LastValidation(
        /** ExtensionDraftValidator.Level：PASS / WARN / FAIL */
        val level: String,
        @SerialName("content_hash") val contentHash: String,
        val at: Long,
    )

    data class DraftMeta(
        val draftId: String,
        val appId: String,
        val name: String,
        val description: String,
        val extensionType: String,
        /** 受管版本号（meta 优先，manifest 兜底——兼容无元数据的旧草稿） */
        val version: String,
        val baseAppId: String? = null,
        val baseVersion: String? = null,
        @SerialName("updated_at") val updatedAt: Long = 0,
        /** 最近校验等级（null = 从未校验）：PASS / WARN / FAIL */
        val lastValidationLevel: String? = null,
        val lastValidationHash: String? = null,
    )

    private fun draftDir(draftId: String): File {
        // draftId 必须是安全目录名（UUID），防路径注入
        require(Regex("^[A-Za-z0-9_-]{1,64}$").matches(draftId)) { "非法草稿 ID" }
        return File(workspacesRoot(context), draftId)
    }

    private fun metaFile(dir: File) = File(dir, WORKSPACE_META_FILE)

    // ---------- 创建：全新草稿 ----------

    /**
     * 创建全新草稿：生成 app_id、目录结构、Manifest 与 workspace.json。
     * 初始版本统一 1.0.0（用户不可修改）。templateId 见 [ExtensionTemplates]。
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
        val dir = draftDir(draftId)
        if (dir.exists() && !dir.deleteRecursively()) {
            throw IllegalStateException("草稿目录创建失败")
        }

        // app_id 撞车规避：与既有草稿或已安装拓展同 ID 会导致安装判定失真
        val takenAppIds = listDrafts().map { it.appId }.toSet() +
            registry.installedExtensions.value.map { it.appId }.toSet()
        var appId = sanitizeAppId(name, extensionType)
        var attempt = 0
        while (appId in takenAppIds && attempt < 5) {
            appId = sanitizeAppId(name, extensionType, suffixSource = System.nanoTime())
            attempt++
        }

        val now = System.currentTimeMillis()
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
        val meta = WorkspaceMeta(
            version = "1.0.0",
            name = name,
            extensionType = extensionType,
            createdAt = now,
            updatedAt = now,
        )
        writeWorkspaceMeta(dir, meta)
        Log.i(TAG, "草稿已创建：$draftId ($appId, $templateId)")
        return DraftMeta(
            draftId = draftId,
            appId = appId,
            name = name,
            description = "",
            extensionType = extensionType,
            version = meta.version,
            baseAppId = null,
            baseVersion = null,
            updatedAt = now,
        )
    }

    /** app_id 生成：中文名转拼音不现实，采用类型前缀 + 时间戳派生的稳定可读 ID */
    private fun sanitizeAppId(name: String, extensionType: String, suffixSource: Long = System.currentTimeMillis()): String {
        val ascii = name.lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(24)
            .trim('_')
        val prefix = if (extensionType == ExtensionType.MINI_APP) "app" else "tool"
        val base = if (ascii.length >= 3) ascii else "${prefix}_ext"
        val suffix = (suffixSource % 100000).toString().padStart(5, '0')
        var candidate = "${base}_$suffix"
        // 压缩到合法格式（正则约束）
        while (!Regex(ExtensionConstants.APP_ID_REGEX).matches(candidate) && candidate.length > 3) {
            candidate = candidate.dropLast(1)
        }
        return candidate
    }

    // ---------- 创建：基于已安装拓展开发新版本 ----------

    /**
     * 基于安装版本创建开发工作区（需求一.4「修改已安装拓展」）：
     * 复制安装目录 → 版本自动递增 Patch → 写 workspace.json。
     * 旧版本始终保留运行；安装成功后由调用方删除工作区。
     *
     * 幂等：该拓展已有工作区时直接返回既有草稿（V1 禁止并行第二开发版本）。
     */
    fun createDraftFromInstalled(appId: String): DraftMeta {
        val installed = registry.getInstalled(appId)
            ?: throw IllegalArgumentException("拓展不存在或已卸载")
        if (!installed.manifest.isModificationAllowed) {
            throw IllegalStateException("「${installed.name}」不允许二次开发")
        }
        findWorkspaceByAppId(appId)?.let {
            Log.i(TAG, "拓展 $appId 已有开发工作区 ${it.draftId}，继续使用")
            return it
        }
        val srcDir = File(context.filesDir, installed.installPath)
        if (!srcDir.isDirectory) {
            throw IllegalStateException("拓展安装文件缺失，无法创建开发副本")
        }
        val newVersion = ExtensionRules.incrementPatchVersion(installed.version)
            ?: throw IllegalStateException("当前版本号 ${installed.version} 无法自动递增，请导出后手动调整版本再导入")

        val draftId = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        val dir = draftDir(draftId)
        if (dir.exists()) {
            throw IllegalStateException("草稿目录冲突，请重试")
        }
        runCatching { srcDir.copyRecursively(dir, overwrite = false) }
            .onFailure { e ->
                dir.deleteRecursively()
                throw IllegalStateException("复制拓展文件失败：${e.message}")
            }

        // 工作区 Manifest 版本写为受管新版本（copy 自安装版，development 字段随包保留）
        val dirForManifest = dir
        runCatching {
            rewriteManifestVersion(dirForManifest, newVersion)
        }.onFailure { e ->
            dir.deleteRecursively()
            throw IllegalStateException("改写工作区版本失败：${e.message}")
        }

        val now = System.currentTimeMillis()
        writeWorkspaceMeta(
            dir,
            WorkspaceMeta(
                version = newVersion,
                name = installed.name,
                extensionType = installed.extensionType,
                baseAppId = appId,
                baseVersion = installed.version,
                createdAt = now,
                updatedAt = now,
            ),
        )
        Log.i(TAG, "开发工作区已创建：$draftId ($appId ${installed.version} → $newVersion)")
        return buildDraftMeta(draftId, dir) ?: throw IllegalStateException("工作区 Manifest 解析失败")
    }

    // ---------- 元数据读写 ----------

    fun getWorkspaceMeta(draftId: String): WorkspaceMeta? {
        val dir = draftDir(draftId)
        if (!dir.isDirectory) return null
        return readWorkspaceMeta(dir)
    }

    private fun readWorkspaceMeta(dir: File): WorkspaceMeta? = runCatching {
        val f = metaFile(dir)
        if (!f.isFile) return null
        JsonInstant.decodeFromString<WorkspaceMeta>(f.readText())
    }.getOrNull()

    private fun writeWorkspaceMeta(dir: File, meta: WorkspaceMeta): Boolean = runCatching {
        dir.mkdirs()
        metaFile(dir).writeText(JsonInstantPretty.encodeToString(WorkspaceMeta.serializer(), meta))
        true
    }.getOrDefault(false)

    private fun touchMetaUpdatedAt(draftId: String) {
        val dir = draftDir(draftId)
        val meta = readWorkspaceMeta(dir) ?: return
        writeWorkspaceMeta(dir, meta.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * 按 appId 定位活动工作区（V1 每个拓展至多一个）。
     */
    fun findWorkspaceByAppId(appId: String): DraftMeta? {
        return listDrafts().firstOrNull { it.appId == appId }
    }

    // ---------- 草稿读取 ----------

    fun listDrafts(): List<DraftMeta> {
        val root = workspacesRoot(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching { buildDraftMeta(dir.name, dir) }.getOrNull()
        }?.sortedByDescending { it.updatedAt } ?: emptyList()
    }

    /**
     * 读取草稿元信息。**草稿在被编辑过程中 manifest 可能是暂时的非法 JSON**
     * （自动保存落盘的中间态），此时返回 null 而不是抛异常——编辑器/列表
     * 调用方不得因草稿瞬态损坏崩溃（2026-11-20 E2E 实测修复）。
     */
    fun getDraft(draftId: String): DraftMeta? {
        val dir = draftDir(draftId)
        if (!dir.isDirectory) return null
        return runCatching { buildDraftMeta(draftId, dir) }.getOrNull()
    }

    /**
     * 构建 DraftMeta：manifest 优先（最鲜活），manifest 暂态损坏时回退
     * workspace.json 展示快照（name/extensionType）；两者皆不可读返回 null
     * （列表过滤该目录，用户数据不丢）。
     */
    private fun buildDraftMeta(draftId: String, dir: File): DraftMeta? {
        val manifest = runCatching { ZipPackageReader().parseManifestFromDirectory(dir) }.getOrNull()
        val meta = readWorkspaceMeta(dir)
        if (manifest == null && meta == null) return null
        return DraftMeta(
            draftId = draftId,
            appId = manifest?.appId ?: meta?.baseAppId.orEmpty(),
            name = manifest?.name?.takeIf { it.isNotBlank() } ?: meta?.name ?: "未命名草稿",
            description = manifest?.description.orEmpty(),
            extensionType = manifest?.extensionType ?: meta?.extensionType ?: ExtensionType.TOOL,
            version = meta?.version ?: manifest?.version.orEmpty(),
            baseAppId = meta?.baseAppId,
            baseVersion = meta?.baseVersion,
            updatedAt = meta?.updatedAt ?: dir.lastModified(),
            lastValidationLevel = meta?.lastValidation?.level,
            lastValidationHash = meta?.lastValidation?.contentHash,
        )
    }

    // ---------- 工作区文件读写（能力层入口，全部过 requireEditable） ----------

    /** 草稿内相对路径列表（仅文件；workspace.json 为系统元数据不对编辑器展示） */
    fun listFiles(draftId: String): List<String> {
        val dir = draftDir(draftId)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
            .filter { it != WORKSPACE_META_FILE }
            .sorted()
    }

    fun readFile(draftId: String, relativePath: String): String? {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return null
        val f = File(draftDir(draftId), relativePath)
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()
    }

    internal fun readFileBytes(draftId: String, relativePath: String): ByteArray? {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return null
        val f = File(draftDir(draftId), relativePath)
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    /**
     * 保存文件，返回实际落盘的内容（失败返回 null）。
     *
     * manifest.json 特殊处理（版本自动管理）：写盘前把 version 归一为受管版本号，
     * 并把归一后的内容返回给调用方同步编辑器显示——用户无法持久化修改版本号。
     */
    fun saveFile(draftId: String, relativePath: String, content: String): String? {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return null
        requireEditable(draftId)
        val dir = draftDir(draftId)
        val f = File(dir, relativePath)
        val finalContent = if (relativePath == "manifest.json") {
            normalizeManifestVersion(content, readWorkspaceMeta(dir)?.version) ?: content
        } else {
            content
        }
        val saved = runCatching {
            f.parentFile?.mkdirs()
            f.writeText(finalContent)
            true
        }.getOrDefault(false)
        if (!saved) return null
        touchMetaUpdatedAt(draftId)
        return finalContent
    }

    /** 把工作区 manifest.json 的 version 改写为指定值（创建开发副本时使用） */
    private fun rewriteManifestVersion(dir: File, newVersion: String) {
        val f = File(dir, "manifest.json")
        if (!f.isFile) throw IllegalStateException("工作区缺少 manifest.json")
        val rewritten = rewriteManifestVersionJson(f.readText(), newVersion)
            ?: throw IllegalStateException("manifest.json 不是合法 JSON 对象")
        f.writeText(rewritten)
    }

    fun createFile(draftId: String, relativePath: String): Boolean {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return false
        if (relativePath == WORKSPACE_META_FILE) return false
        requireEditable(draftId)
        val f = File(draftDir(draftId), relativePath)
        if (f.exists()) return false
        return runCatching { f.parentFile?.mkdirs(); f.createNewTextFile(); true }.getOrDefault(false)
    }

    private fun File.createNewTextFile() {
        writeText("")
    }

    fun deleteFile(draftId: String, relativePath: String): Boolean {
        if (!ManifestValidator.isSafeRelativePath(relativePath)) return false
        if (relativePath == WORKSPACE_META_FILE || relativePath == "manifest.json") return false
        requireEditable(draftId)
        val f = File(draftDir(draftId), relativePath)
        val deleted = f.delete()
        if (deleted) touchMetaUpdatedAt(draftId)
        return deleted
    }

    /**
     * 删除草稿（含元数据）。安装成功后的「移入我的拓展」也走此方法。
     */
    fun deleteDraft(draftId: String): Boolean = draftDir(draftId).deleteRecursively()

    // ---------- 二次开发权限（能力层拦截） ----------

    /**
     * 二次开发权限检查（能力层，覆盖全部工作区修改入口）。
     * - 全新草稿（无 base）：始终允许；
     * - 基于安装版的草稿：基础拓展已卸载 → 允许（草稿可独立安装）；
     *   基础拓展存在且 Manifest 声明不允许修改 → 拒绝。
     * UI 层的入口隐藏只是辅助，修改能力的唯一判定在此。
     */
    fun requireEditable(draftId: String) {
        val meta = getWorkspaceMeta(draftId) ?: return
        val baseAppId = meta.baseAppId ?: return
        val installed = registry.getInstalled(baseAppId) ?: return
        if (!installed.manifest.isModificationAllowed) {
            throw IllegalStateException("「${installed.name}」不允许二次开发，无法修改该草稿")
        }
    }

    // ---------- 校验（三层）与内容一致性 ----------

    /**
     * 三层校验（2026-11-20 补充需求第四章）：结构 / 代码与运行环境 / 内容哈希。
     * 结果持久化到 workspace.json（安装前比对哈希，保证校验文件 = 安装文件）。
     *
     * 二次开发权限同样在能力层把守：基础拓展被重新导入为「不允许二次开发」后，
     * 基于旧版本的开发草稿校验直接失败（安装入口必然先校验，从能力层阻断）。
     */
    fun validate(draftId: String): ExtensionDraftValidator.Result? {
        val dir = draftDir(draftId)
        if (!dir.isDirectory) return null
        val editabilityError: String? = try {
            requireEditable(draftId)
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        val manifest = runCatching { ZipPackageReader().parseManifestFromDirectory(dir) }.getOrNull()
            ?: return ExtensionDraftValidator.Result(
                level = ExtensionDraftValidator.Level.FAIL,
                errors = listOf(ExtensionDraftValidator.Issue(file = null, message = "manifest.json 缺失或解析失败")),
                warnings = emptyList(),
                contentHash = "",
            )
        if (editabilityError != null) {
            return ExtensionDraftValidator.Result(
                level = ExtensionDraftValidator.Level.FAIL,
                errors = listOf(ExtensionDraftValidator.Issue(file = null, message = editabilityError)),
                warnings = emptyList(),
                contentHash = "",
            )
        }
        val result = ExtensionDraftValidator.validate(
            manifest = manifest,
            listFiles = { listFiles(draftId) },
            readFile = { readFileBytes(draftId, it) },
            fileExists = { relative -> File(dir, relative).isFile },
            parseSchemaFile = { relative ->
                val f = File(dir, relative)
                if (f.isFile) runCatching {
                    JsonInstant.parseToJsonElement(f.readText()) as? JsonObject
                }.getOrNull() else null
            },
            jsSyntaxCheck = ExtensionDraftValidator.QuickJsSyntaxChecker,
        )
        val dirForMeta = dir
        readWorkspaceMeta(dirForMeta)?.let { meta ->
            writeWorkspaceMeta(
                dirForMeta,
                meta.copy(
                    updatedAt = System.currentTimeMillis(),
                    lastValidation = LastValidation(
                        level = result.level.name,
                        contentHash = result.contentHash,
                        at = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        Log.i(TAG, "草稿 $draftId 校验完成：${result.level}（errors=${result.errors.size}, warnings=${result.warnings.size}）")
        return result
    }

    /**
     * 安装前一致性复查（需求四.5）：校验通过后文件是否又被修改。
     * 返回 null = 一致可安装；非 null = 阻断原因。
     */
    fun checkValidationFreshness(draftId: String): String? {
        val meta = getWorkspaceMeta(draftId)
            ?: return "缺少工作区元数据，请先执行校验"
        val lastValidation = meta.lastValidation
            ?: return "尚未通过校验，请先执行校验"
        if (lastValidation.level == ExtensionDraftValidator.Level.FAIL.name) {
            return "最近一次校验未通过，请修复错误后重新校验"
        }
        val currentHash = ExtensionDraftValidator.computeContentHash(
            listFiles(draftId),
            { readFileBytes(draftId, it) },
        )
        if (currentHash != lastValidation.contentHash) {
            return "校验后文件已发生修改，请重新校验后再安装"
        }
        return null
    }
}
