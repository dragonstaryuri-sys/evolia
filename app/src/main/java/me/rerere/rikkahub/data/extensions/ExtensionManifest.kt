package me.rerere.rikkahub.data.extensions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 扩展 Manifest 数据模型（详设第四章）。
 *
 * JsonInstant 不应用 snake_case 策略，字段映射必须手动通过 @SerialName 声明。
 * Manifest 中声明的权限只是权限申请，不代表已经获得授权。
 */
@Serializable
data class ExtensionManifest(
    @SerialName("manifest_version") val manifestVersion: Int = 0,
    @SerialName("app_id") val appId: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("extension_type") val extensionType: String = "",
    val version: String = "",
    @SerialName("min_evolia_version") val minEvoliaVersion: String? = null,
    val frontend: ManifestFrontend? = null,
    val storage: ManifestStorage? = null,
    val tools: List<ManifestTool> = emptyList(),
    val permissions: List<String> = emptyList(),
) {
    /** Tool 唯一标识统一采用 `app_id:tool_id`，避免不同扩展同名 Tool 冲突 */
    fun fullToolId(toolId: String): String = "$appId${ExtensionConstants.TOOL_ID_SEPARATOR}$toolId"

    val isMiniApp: Boolean get() = extensionType == ExtensionType.MINI_APP
    val isToolExtension: Boolean get() = extensionType == ExtensionType.TOOL
}

@Serializable
data class ManifestFrontend(
    val entry: String = "",
)

@Serializable
data class ManifestStorage(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val collections: List<ManifestCollection> = emptyList(),
)

/**
 * 数据集合定义。schema 既支持包内文件路径（string），也支持内联 JSON Schema（object）。
 */
@Serializable
data class ManifestCollection(
    val name: String = "",
    val schema: JsonElement? = null,
)

@Serializable
data class ManifestTool(
    @SerialName("tool_id") val toolId: String = "",
    val name: String = "",
    val description: String = "",
    val schema: JsonElement? = null,
    val entry: String = "",
    val permissions: List<String> = emptyList(),
)

/**
 * 解析后的数据集合（schema 已落定为内联对象）。
 */
data class ResolvedCollection(
    val name: String,
    val schemaVersion: Int,
    val schema: JsonObject?,
)

/**
 * 解析后的 Tool 定义（schema 已落定为内联对象）。
 */
data class ResolvedTool(
    val toolId: String,
    val fullId: String,
    val name: String,
    val description: String,
    val entry: String,
    val permissions: List<String>,
    val schema: JsonObject?,
)

/**
 * 安装态扩展（Registry 快照单元）：元数据 + 解析后的 Manifest 内容 +
 * 工具运行配置与权限授权状态（随 Room Flow 实时刷新）。
 */
data class InstalledExtension(
    val appId: String,
    val name: String,
    val description: String,
    val extensionType: String,
    val version: String,
    val sourceType: String,
    val sourceUri: String?,
    val createdOrigin: String,
    val isEnabled: Boolean,
    val isBuiltin: Boolean,
    val installPath: String,
    val packageHash: String,
    val manifest: ExtensionManifest,
    val tools: List<ResolvedTool>,
    val collections: List<ResolvedCollection>,
    val storageSchemaVersion: Int,
    /** tool_id → 本地运行配置（enable / custom_description） */
    val toolConfigs: Map<String, ToolConfigSnapshot>,
    /** 已授权权限集合 */
    val grantedPermissions: Set<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val installedAt: Long,
)
