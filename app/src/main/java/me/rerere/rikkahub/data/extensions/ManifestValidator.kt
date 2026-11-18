package me.rerere.rikkahub.data.extensions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Manifest 校验器（详设 4.4）。
 *
 * 纯函数实现：文件存在性通过 [fileExists] 注入、Schema 文件解析通过 [parseSchemaFile]
 * 注入，便于单元测试。安装前必须全部通过。
 */
object ManifestValidator {

    /**
     * @param manifest 已解析的 Manifest
     * @param fileExists 包内相对路径是否存在（含 manifest 引用的所有资源）
     * @param parseSchemaFile 读取并解析包内 Schema 文件（返回 null = 文件缺失或非法 JSON）
     */
    fun validate(
        manifest: ExtensionManifest,
        fileExists: (String) -> Boolean,
        parseSchemaFile: (String) -> JsonObject? = { null },
    ): List<String> {
        val errors = mutableListOf<String>()

        // 1. manifest_version 支持范围
        val versionInRange = manifest.manifestVersion in ExtensionConstants.MIN_MANIFEST_VERSION..ExtensionConstants.MAX_MANIFEST_VERSION
        if (!versionInRange) {
            errors.add(
                "manifest_version=${manifest.manifestVersion} 超出当前支持范围 " +
                    "[${ExtensionConstants.MIN_MANIFEST_VERSION}, ${ExtensionConstants.MAX_MANIFEST_VERSION}]"
            )
        }

        // 2. app_id 合法性
        val appIdValid = Regex(ExtensionConstants.APP_ID_REGEX).matches(manifest.appId)
        if (!appIdValid) {
            errors.add("app_id「${manifest.appId}」不合法：需小写字母开头，仅含小写字母/数字/下划线，2~64 字符")
        }

        // 3. name 必填
        if (manifest.name.isBlank()) {
            errors.add("name 不能为空")
        }

        // 4. extension_type
        val typeValid = manifest.extensionType == ExtensionType.TOOL || manifest.extensionType == ExtensionType.MINI_APP
        if (!typeValid) {
            errors.add("extension_type 必须为「${ExtensionType.TOOL}」或「${ExtensionType.MINI_APP}」")
        }

        // 5. version 格式
        val versionValid = Regex(ExtensionConstants.VERSION_REGEX).matches(manifest.version)
        if (!versionValid) {
            errors.add("version「${manifest.version}」不符合 SemVer 格式（major.minor.patch）")
        }

        // 6. Mini App 前端入口
        if (manifest.extensionType == ExtensionType.MINI_APP) {
            val entry = manifest.frontend?.entry.orEmpty()
            if (entry.isBlank() || !isSafeRelativePath(entry)) {
                errors.add("Mini App 必须提供合法的前端入口 frontend.entry")
            } else if (!fileExists(entry)) {
                errors.add("前端入口文件不存在：$entry")
            }
        }

        // 7. 权限声明
        manifest.permissions.forEach { permission ->
            if (permission !in ExtensionPermissions.KNOWN_PERMISSIONS) {
                errors.add("不支持的权限声明「$permission」，当前支持：${ExtensionPermissions.KNOWN_PERMISSIONS.joinToString("、")}")
            }
        }

        // 8. Tool 定义
        val toolIds = mutableSetOf<String>()
        manifest.tools.forEach { tool ->
            if (tool.toolId.isBlank() || !Regex(ExtensionConstants.TOOL_ID_REGEX).matches(tool.toolId)) {
                errors.add("Tool「${tool.toolId}」的 tool_id 不合法：需小写字母开头，仅含小写字母/数字/下划线")
            }
            if (!toolIds.add(tool.toolId)) {
                errors.add("Tool ID 在同一个扩展内不得重复：「${tool.toolId}」")
            }
            if (tool.name.isBlank()) {
                errors.add("Tool「${tool.toolId}」缺少名称")
            }
            if (tool.entry.isBlank() || !isSafeRelativePath(tool.entry)) {
                errors.add("Tool「${tool.toolId}」的执行入口 entry 不合法")
            } else if (!fileExists(tool.entry)) {
                errors.add("Tool「${tool.toolId}」的执行入口不存在：${tool.entry}")
            }
            tool.permissions.forEach { permission ->
                if (permission !in ExtensionPermissions.KNOWN_PERMISSIONS) {
                    errors.add("Tool「${tool.toolId}」声明了不支持的权限「$permission」")
                }
            }
            // Tool Schema：文件路径或内联对象，二者取其一（允许为空 = 无参数校验）
            resolveSchemaElement(tool.schema, fileExists, parseSchemaFile)?.let { (resolved, error) ->
                if (error != null) {
                    errors.add("Tool「${tool.toolId}」Schema 校验失败：$error")
                } else if (resolved != null) {
                    errors.addAll(
                        JsonSchemaValidator.validateSchemaStructure(resolved)
                            .map { "Tool「${tool.toolId}」Schema 不合法：$it" }
                    )
                }
            }
        }

        // 9. Tool Extension 至少一个有效 Tool
        if (manifest.extensionType == ExtensionType.TOOL && manifest.tools.isEmpty()) {
            errors.add("Tool Extension 必须至少包含一个有效 Tool")
        }

        // 10. 数据集合定义
        manifest.storage?.collections?.forEach { collection ->
            if (!Regex(ExtensionConstants.COLLECTION_NAME_REGEX).matches(collection.name)) {
                errors.add("数据集合「${collection.name}」命名不合法：需小写字母开头，仅含小写字母/数字/下划线")
            }
            val resolved = resolveSchemaElement(collection.schema, fileExists, parseSchemaFile)
            resolved?.second?.let { error ->
                errors.add("数据集合「${collection.name}」Schema 校验失败：$error")
            }
            resolved?.first?.let { schema ->
                if (schema != null) {
                    errors.addAll(
                        JsonSchemaValidator.validateSchemaStructure(schema)
                            .map { "数据集合「${collection.name}」Schema 不合法：$it" }
                    )
                }
            }
        }

        return errors
    }

    /**
     * 解析 schema 元素：字符串 = 包内路径；对象 = 内联 Schema。
     * 返回 (内联 JsonObject?（路径模式下为解析结果）, 错误?)。
     */
    private fun resolveSchemaElement(
        element: kotlinx.serialization.json.JsonElement?,
        fileExists: (String) -> Boolean,
        parseSchemaFile: (String) -> JsonObject?,
    ): Pair<JsonObject?, String?> {
        return when (element) {
            null -> null to null
            is JsonObject -> element to null
            is JsonPrimitive -> {
                if (!element.isString) return null to "Schema 字段必须是路径字符串或内联 Schema 对象"
                val path = element.content
                if (path.isBlank() || !isSafeRelativePath(path)) {
                    return null to "Schema 路径「$path」不合法"
                }
                if (!fileExists(path)) return null to "Schema 文件不存在：$path"
                val parsed = parseSchemaFile(path) ?: return null to "Schema 文件不是合法的 JSON 对象：$path"
                parsed to null
            }

            else -> null to "Schema 字段必须是路径字符串或内联 Schema 对象"
        }
    }

    /**
     * 包内相对路径安全检查（详设 4.4 规则 9/10/15）：
     * 禁止绝对路径、`..` 穿越、反斜杠、盘符、空段、控制字符、超长路径。
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > 512) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\')) return false
        if (path.contains(':')) return false
        if (path.any { it.code < 0x20 }) return false
        val segments = path.split('/')
        if (segments.isEmpty()) return false
        return segments.all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." && segment.trim() == segment
        }
    }

    /**
     * 将 Manifest 解析为安装态结构（Schema 全部落定为内联对象）。
     * 调用前应先通过 [validate]。
     */
    fun resolveCollections(manifest: ExtensionManifest, readSchemaFile: (String) -> JsonObject?): List<ResolvedCollection> {
        val schemaVersion = manifest.storage?.schemaVersion ?: 1
        return manifest.storage?.collections.orEmpty().map { collection ->
            val schema = when (val element = collection.schema) {
                null -> null
                is JsonObject -> element
                is JsonPrimitive -> if (element.isString) readSchemaFile(element.content) else null
                else -> null
            }
            ResolvedCollection(name = collection.name, schemaVersion = schemaVersion, schema = schema)
        }
    }

    fun resolveTools(manifest: ExtensionManifest, readSchemaFile: (String) -> JsonObject?): List<ResolvedTool> {
        return manifest.tools.map { tool ->
            val schema = when (val element = tool.schema) {
                null -> null
                is JsonObject -> element
                is JsonPrimitive -> if (element.isString) readSchemaFile(element.content) else null
                else -> null
            }
            ResolvedTool(
                toolId = tool.toolId,
                fullId = manifest.fullToolId(tool.toolId),
                name = tool.name,
                description = tool.description,
                entry = tool.entry,
                permissions = tool.permissions,
                schema = schema,
            )
        }
    }
}
