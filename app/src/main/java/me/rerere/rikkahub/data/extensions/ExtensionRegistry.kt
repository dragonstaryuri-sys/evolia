package me.rerere.rikkahub.data.extensions

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.core.data.db.entity.ExtensionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionToolEntity
import me.rerere.rikkahub.core.data.repository.ExtensionRepository
import java.io.File

/**
 * Tool 目录条目（第一层上下文：名称 + 描述 + 唯一标识，不含参数 Schema）。
 */
data class ExtensionCatalogEntry(
    val appId: String,
    val extensionName: String,
    val extensionDescription: String,
    val toolId: String,
    val fullId: String,
    val name: String,
    val description: String,
    val isMiniAppTool: Boolean,
)

/**
 * 扩展 Tool 的本地运行配置快照（enable / custom_description）。
 */
data class ToolConfigSnapshot(
    val enable: Boolean,
    val customDescription: String?,
)

/**
 * Tool 执行前完整校验结果（第三层执行入口检查，不可被 Schema 查询阶段替代）。
 */
enum class ToolCallCheck {
    OK,
    EXTENSION_NOT_FOUND,
    TOOL_NOT_FOUND,
    EXTENSION_DISABLED,
    TOOL_DISABLED,
    ASSISTANT_NOT_AUTHORIZED,
    PERMISSION_DENIED,
}

/**
 * Extension Registry（详设 2.3）：统一登记、查询、管理已安装扩展。
 *
 * 以 Room Flow（InvalidationTracker 驱动）为源构建内存快照（含 Manifest 解析、
 * 工具配置、权限授权状态），Catalog / 可用性判定 / 权限检查全部基于快照同步执行——
 * systemPrompt 注入 lambda 不可挂起，禁止在该链路读盘或查库。扩展身份禁止通过
 * app_id 前缀或名称推断——一律以 is_builtin 等显式系统属性为准。
 */
class ExtensionRegistry(
    private val context: Context,
    private val repository: ExtensionRepository,
    appScope: CoroutineScope,
) {
    companion object {
        const val TAG = "ExtensionRegistry"
    }

    /**
     * 安装态扩展快照。Manifest 解析在 IO 线程；manifest 缺失（文件被异常
     * 删除且孤儿清理尚未运行）时跳过该扩展并记日志。
     */
    val installedExtensions: StateFlow<List<InstalledExtension>> =
        combine(
            repository.observeAllExtensions(),
            repository.observeAllToolConfigs(),
            repository.observeAllPermissions(),
        ) { extensions, toolConfigs, permissions ->
            Triple(extensions, toolConfigs, permissions)
        }
            .map { (extensions, toolConfigs, permissions) ->
                withContext(Dispatchers.IO) {
                    val grantedByApp = permissions.filter { it.granted }
                        .groupBy { it.appId }
                        .mapValues { (_, list) -> list.map { it.permission }.toSet() }
                    extensions.mapNotNull { entity ->
                        buildInstalledExtension(entity, toolConfigs, grantedByApp[entity.appId] ?: emptySet())
                    }
                }
            }
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    private fun buildInstalledExtension(
        entity: ExtensionEntity,
        toolConfigs: List<ExtensionToolEntity>,
        grantedPermissions: Set<String>,
    ): InstalledExtension? {
        val dir = File(context.filesDir, entity.installPath)
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) {
            Log.w(TAG, "扩展 ${entity.appId} 的 manifest 缺失（install_path=${entity.installPath}），跳过")
            return null
        }
        val manifest = try {
            me.rerere.rikkahub.common.JsonInstant.decodeFromString<ExtensionManifest>(manifestFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "扩展 ${entity.appId} manifest 解析失败", e)
            return null
        }
        val readSchema = { relative: String ->
            val f = File(dir, relative)
            if (f.isFile) runCatching {
                me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() else null
        }
        // 权限 granted 状态随快照内聚，避免目录构建链路查库
        val configMap = toolConfigs.filter { it.appId == entity.appId }
            .associate { it.toolId to ToolConfigSnapshot(it.enable, it.customDescription) }
        return InstalledExtension(
            appId = entity.appId,
            name = entity.name,
            description = entity.description,
            extensionType = entity.extensionType,
            version = entity.version,
            sourceType = entity.sourceType,
            sourceUri = entity.sourceUri,
            createdOrigin = entity.createdOrigin,
            isEnabled = entity.isEnabled,
            isBuiltin = entity.isBuiltin,
            installPath = entity.installPath,
            packageHash = entity.packageHash,
            manifest = manifest,
            tools = ManifestValidator.resolveTools(manifest, readSchema),
            collections = ManifestValidator.resolveCollections(manifest, readSchema),
            storageSchemaVersion = manifest.storage?.schemaVersion ?: 1,
            toolConfigs = configMap,
            grantedPermissions = grantedPermissions,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            installedAt = entity.installedAt,
        )
    }

    fun getInstalled(appId: String): InstalledExtension? =
        installedExtensions.value.firstOrNull { it.appId == appId }

    fun getResolvedTool(appId: String, toolId: String): Pair<InstalledExtension, ResolvedTool>? {
        val extension = getInstalled(appId) ?: return null
        val tool = extension.tools.firstOrNull { it.toolId == toolId } ?: return null
        return extension to tool
    }

    /** Tool 执行入口文件（安装目录内绝对路径，供 Runtime 读取源码） */
    fun resolveToolEntryFile(appId: String, entry: String): File? {
        val extension = getInstalled(appId) ?: return null
        if (!ManifestValidator.isSafeRelativePath(entry)) return null
        val file = File(File(context.filesDir, extension.installPath), entry)
        if (!file.isFile) return null
        return file
    }

    // ---------- 权限（查询走快照，写入落库） ----------

    fun getGrantedPermissions(appId: String): Set<String> =
        getInstalled(appId)?.grantedPermissions ?: emptySet()

    fun isPermissionGranted(appId: String, permission: String): Boolean =
        permission in getGrantedPermissions(appId)

    suspend fun grantPermission(appId: String, permission: String, granted: Boolean) {
        repository.setPermissionGranted(appId, permission, granted, System.currentTimeMillis())
    }

    // ---------- Tool 可用性与目录（三层权限，13.3） ----------

    /**
     * 判定某个 Tool 是否可被某助手调用（执行期完整复查）。
     * 状态与权限均取自快照（Room Flow 实时驱动，等同最新库态）。
     *
     * @param assistantEnabledMiniAppIds 该助手已启用的 Mini App 集合
     */
    fun checkToolCallable(
        appId: String,
        toolId: String,
        assistantEnabledMiniAppIds: Set<String>,
    ): ToolCallCheck {
        val installed = getInstalled(appId) ?: return ToolCallCheck.EXTENSION_NOT_FOUND
        val tool = installed.tools.firstOrNull { it.toolId == toolId } ?: return ToolCallCheck.TOOL_NOT_FOUND

        if (!installed.isEnabled) return ToolCallCheck.EXTENSION_DISABLED

        val config = installed.toolConfigs[toolId]
        if (config?.enable != true) return ToolCallCheck.TOOL_DISABLED

        val isMiniAppTool = installed.extensionType == ExtensionType.MINI_APP
        if (isMiniAppTool && appId !in assistantEnabledMiniAppIds) {
            return ToolCallCheck.ASSISTANT_NOT_AUTHORIZED
        }

        val missing = tool.permissions.filter { it !in installed.grantedPermissions }
        if (missing.isNotEmpty()) return ToolCallCheck.PERMISSION_DENIED

        return ToolCallCheck.OK
    }

    /**
     * 第一层目录：当前可调用的 Tool 列表（用于 catalog systemPrompt）。
     * 不含参数 Schema；Schema 由 get_extension_tool_schema 按需获取。
     */
    fun getAvailableCatalog(
        assistantEnabledMiniAppIds: Set<String>,
    ): List<ExtensionCatalogEntry> {
        val result = mutableListOf<ExtensionCatalogEntry>()
        installedExtensions.value.forEach { installed ->
            if (!installed.isEnabled) return@forEach
            val isMiniApp = installed.extensionType == ExtensionType.MINI_APP
            installed.tools.forEach { tool ->
                val config = installed.toolConfigs[tool.toolId]
                val available = ExtensionRules.isExtensionToolAvailable(
                    extensionInstalled = true,
                    extensionEnabled = installed.isEnabled,
                    toolConfigEnable = config?.enable,
                    permissionsGranted = tool.permissions.all { it in installed.grantedPermissions },
                    isMiniAppTool = isMiniApp,
                    assistantEnabled = installed.appId in assistantEnabledMiniAppIds,
                )
                if (available) {
                    result.add(
                        ExtensionCatalogEntry(
                            appId = installed.appId,
                            extensionName = installed.name,
                            extensionDescription = installed.description,
                            toolId = tool.toolId,
                            fullId = tool.fullId,
                            name = tool.name,
                            description = ExtensionRules.effectiveDescription(
                                config?.customDescription, tool.description
                            ),
                            isMiniAppTool = isMiniApp,
                        )
                    )
                }
            }
        }
        return result
    }

    // ---------- 状态管理 ----------

    suspend fun setExtensionEnabled(appId: String, enabled: Boolean) {
        repository.setExtensionEnabled(appId, enabled, System.currentTimeMillis())
    }

    suspend fun setToolEnable(appId: String, toolId: String, enable: Boolean) {
        repository.setToolEnable(appId, toolId, enable, System.currentTimeMillis())
    }

    suspend fun setToolCustomDescription(appId: String, toolId: String, description: String?) {
        repository.setToolCustomDescription(appId, toolId, description, System.currentTimeMillis())
    }
}
