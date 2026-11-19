package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.extensions.ExtensionCatalogEntry
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionToolError
import me.rerere.rikkahub.data.extensions.ExtensionToolRuntime
import me.rerere.rikkahub.data.extensions.JsonSchemaValidator
import me.rerere.rikkahub.data.extensions.ResolvedTool
import me.rerere.rikkahub.data.extensions.ToolCallCheck

/**
 * 扩展工具族 Agent 接入（详设第十章）。
 *
 * 复刻 MCP / Radio 的按需 Schema 双固定工具模式：
 * - 完整参数 Schema 不常驻模型上下文；
 * - 工具目录（扩展名 + Tool 名 + 描述 + 唯一标识）由 get_extension_tool_schema
 *   的 systemPrompt 注入（GenerationHandler 统一收集 Tool.systemPrompt）；
 * - Tool ID 统一 `app_id:tool_id`，避免不同扩展同名冲突；
 * - Schema 获取阶段的检查不能替代执行阶段的检查（call 前完整复查三层权限）；
 * - 当前无可用扩展工具时不注册（返回空列表，同 buildMcpAgentTools 约定）。
 */
object ExtensionAgentTools {

    const val TOOL_GET_EXTENSION_TOOL_SCHEMA = "get_extension_tool_schema"
    const val TOOL_CALL_EXTENSION_TOOL = "call_extension_tool"

    /**
     * 构建扩展双固定工具；当前无可用扩展工具时返回空列表（不注册）。
     */
    fun buildExtensionAgentTools(
        registry: ExtensionRegistry,
        toolRuntime: ExtensionToolRuntime,
        assistant: Assistant,
    ): List<Tool> {
        val catalog = registry.getAvailableCatalog(
            assistantEnabledMiniAppIds = assistant.enabledMiniAppIds,
            assistantEnabledToolExtensionIds = assistant.enabledToolExtensionIds,
        )
        if (catalog.isEmpty()) return emptyList()
        return listOf(
            buildGetExtensionToolSchemaTool(registry, assistant, catalog),
            buildCallExtensionToolTool(registry, toolRuntime, assistant),
        )
    }

    // ---------- 目录 Prompt（纯函数，有单测） ----------

    internal fun buildExtensionCatalogPrompt(entries: List<ExtensionCatalogEntry>): String {
        if (entries.isEmpty()) return ""
        val builder = StringBuilder()
        // 措辞中性化（2026-11-18 用户要求）：不向模型区分「扩展工具/普通工具」，
        // 只按「按需 Schema」机制说明调用方式；分组标题使用来源名称本身。
        builder.appendLine("## 可用工具 · 扩展")
        builder.appendLine(
            "以下工具的完整参数定义(Schema)不会随请求常驻，调用前必须先执行 " +
                "`get_extension_tool_schema`(传入 app_id + tool_id) 获取参数定义，再通过 " +
                "`call_extension_tool` 执行。工具唯一标识格式为 `app_id:tool_id`。"
        )
        entries.groupBy { it.appId }.forEach { (appId, group) ->
            val ext = group.first()
            builder.appendLine()
            builder.appendLine("### ${ext.extensionName}")
            group.forEach { entry ->
                val description = entry.description.trim()
                if (description.isBlank()) {
                    builder.appendLine("- `${entry.fullId}` (${entry.name}): (无描述，请先查询 Schema 了解用途)")
                } else {
                    builder.appendLine("- `${entry.fullId}` (${entry.name}): ${description.replace("\n", " ")}")
                }
            }
        }
        return builder.toString().trimEnd()
    }

    // ---------- get_extension_tool_schema ----------

    private fun buildGetExtensionToolSchemaTool(
        registry: ExtensionRegistry,
        assistant: Assistant,
        catalog: List<ExtensionCatalogEntry>,
    ): Tool = Tool(
        name = TOOL_GET_EXTENSION_TOOL_SCHEMA,
        description = "查询工具的完整参数定义(Schema)。调用目录中的工具前必须先执行此工具获取参数定义，" +
            "再按 Schema 组织参数通过 call_extension_tool 执行。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("app_id", buildJsonObject {
                        put("type", "string")
                        put("description", "扩展唯一标识 app_id")
                    })
                    put("tool_id", buildJsonObject {
                        put("type", "string")
                        put("description", "扩展内 Tool 标识（不含 app_id 前缀）")
                    })
                },
                required = listOf("app_id", "tool_id"),
            )
        },
        systemPrompt = { _, _ -> buildExtensionCatalogPrompt(catalog) },
        execute = { rawArgs -> executeGetExtensionToolSchema(registry, assistant, rawArgs) },
    )

    private suspend fun executeGetExtensionToolSchema(
        registry: ExtensionRegistry,
        assistant: Assistant,
        rawArgs: JsonElement,
    ): JsonObject {
        val args = rawArgs as? JsonObject ?: JsonObject(emptyMap())
        val appId = args.readString("app_id")
        val toolId = args.readString("tool_id")
        if (appId.isNullOrBlank() || toolId.isNullOrBlank()) {
            return errorResult(
                ExtensionToolError.INVALID_ARGUMENTS,
                "缺少必填参数 app_id 或 tool_id"
            )
        }
        val resolvedPair = registry.getResolvedTool(appId, toolId)
            ?: return errorResult(
                ExtensionToolError.TOOL_NOT_FOUND,
                "扩展「$appId」不存在或其中没有工具「$toolId」"
            )
        val (installed, resolved) = resolvedPair

        // Schema 获取阶段同样做可用性检查（与目录一致），但不替代执行阶段复查
        val check = registry.checkToolCallable(
            appId = appId,
            toolId = toolId,
            assistantEnabledMiniAppIds = assistant.enabledMiniAppIds,
            assistantEnabledToolExtensionIds = assistant.enabledToolExtensionIds,
        )
        if (check != ToolCallCheck.OK) {
            return errorResult(mapCheckError(check), checkMessage(check, installed.name, resolved.name))
        }

        return buildJsonObject {
            put("app_id", appId)
            put("tool_id", toolId)
            put("full_id", resolved.fullId)
            put("name", resolved.name)
            put("description", resolved.description)
            put(
                "input_schema",
                resolved.schema ?: buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(emptyMap()))
                }
            )
            if (resolved.permissions.isNotEmpty()) {
                put("required_permissions", JsonInstant.encodeToString(resolved.permissions))
            }
        }
    }

    // ---------- call_extension_tool ----------

    private fun buildCallExtensionToolTool(
        registry: ExtensionRegistry,
        toolRuntime: ExtensionToolRuntime,
        assistant: Assistant,
    ): Tool = Tool(
        name = TOOL_CALL_EXTENSION_TOOL,
        description = "执行目录中的工具。执行前必须先通过 get_extension_tool_schema 获取参数定义，" +
            "并按 Schema 组织 arguments。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("app_id", buildJsonObject {
                        put("type", "string")
                        put("description", "扩展唯一标识 app_id")
                    })
                    put("tool_id", buildJsonObject {
                        put("type", "string")
                        put("description", "扩展内 Tool 标识（不含 app_id 前缀）")
                    })
                    put("arguments", buildJsonObject {
                        put("type", "object")
                        put("description", "工具参数，按 get_extension_tool_schema 返回的 Schema 组织")
                    })
                },
                required = listOf("app_id", "tool_id"),
            )
        },
        systemPrompt = { _, _ -> "" },
        execute = { rawArgs -> executeCallExtensionTool(registry, toolRuntime, assistant, rawArgs) },
    )

    private suspend fun executeCallExtensionTool(
        registry: ExtensionRegistry,
        toolRuntime: ExtensionToolRuntime,
        assistant: Assistant,
        rawArgs: JsonElement,
    ): JsonObject {
        val args = rawArgs as? JsonObject ?: JsonObject(emptyMap())
        val appId = args.readString("app_id")
        val toolId = args.readString("tool_id")
        if (appId.isNullOrBlank() || toolId.isNullOrBlank()) {
            return errorResult(
                ExtensionToolError.INVALID_ARGUMENTS,
                "缺少必填参数 app_id 或 tool_id"
            )
        }
        val resolvedPair = registry.getResolvedTool(appId, toolId)
            ?: return errorResult(
                ExtensionToolError.TOOL_NOT_FOUND,
                "扩展「$appId」不存在或其中没有工具「$toolId」"
            )
        val (installed, resolved) = resolvedPair

        // 执行前完整复查（三层权限），不可被 Schema 阶段检查替代
        val check = registry.checkToolCallable(
            appId = appId,
            toolId = toolId,
            assistantEnabledMiniAppIds = assistant.enabledMiniAppIds,
            assistantEnabledToolExtensionIds = assistant.enabledToolExtensionIds,
        )
        if (check != ToolCallCheck.OK) {
            return errorResult(mapCheckError(check), checkMessage(check, installed.name, resolved.name))
        }

        // 参数按 Manifest Schema 校验（复用统一校验器）
        val arguments = parseExtensionCallArguments(args["arguments"])
            ?: return errorResult(
                ExtensionToolError.INVALID_ARGUMENTS,
                "arguments 必须是 JSON 对象（或 JSON 字符串形式）"
            )
        val schema = resolved.schema
        if (schema != null) {
            val errors = JsonSchemaValidator.validate(arguments, schema)
            if (errors.isNotEmpty()) {
                return errorResult(
                    ExtensionToolError.INVALID_ARGUMENTS,
                    "参数校验失败：\n" + errors.joinToString("\n") { "· $it" } +
                        "\n请先调用 get_extension_tool_schema 查看参数定义。"
                )
            }
        }

        val entryFile = registry.resolveToolEntryFile(appId, resolved.entry)
            ?: return errorResult(
                ExtensionToolError.EXECUTION_FAILED,
                "工具执行入口文件缺失（扩展可能已损坏，请重新安装）"
            )

        val granted = registry.getGrantedPermissions(appId)
        val result = toolRuntime.execute(
            ExtensionToolRuntime.ToolExecutionRequest(
                appId = appId,
                toolId = toolId,
                entrySource = entryFile.readText(),
                arguments = arguments,
                grantedPermissions = granted,
            )
        )
        return result.toJson()
    }

    // ---------- helpers ----------

    /** Map ToolCallCheck → ExtensionToolError（纯函数，有单测） */
    internal fun mapCheckError(check: ToolCallCheck): String = when (check) {
        ToolCallCheck.OK -> ""
        ToolCallCheck.EXTENSION_NOT_FOUND -> ExtensionToolError.EXTENSION_NOT_FOUND
        ToolCallCheck.TOOL_NOT_FOUND -> ExtensionToolError.TOOL_NOT_FOUND
        ToolCallCheck.EXTENSION_DISABLED -> ExtensionToolError.EXTENSION_DISABLED
        ToolCallCheck.TOOL_DISABLED -> ExtensionToolError.TOOL_DISABLED
        ToolCallCheck.ASSISTANT_NOT_AUTHORIZED -> ExtensionToolError.ASSISTANT_NOT_AUTHORIZED
        ToolCallCheck.PERMISSION_DENIED -> ExtensionToolError.PERMISSION_DENIED
    }

    internal fun checkMessage(check: ToolCallCheck, extensionName: String, toolName: String): String =
        when (check) {
            ToolCallCheck.EXTENSION_NOT_FOUND -> "扩展「$extensionName」不存在或已卸载"
            ToolCallCheck.TOOL_NOT_FOUND -> "扩展「$extensionName」中没有工具「$toolName」"
            ToolCallCheck.EXTENSION_DISABLED -> "扩展「$extensionName」已被停用"
            ToolCallCheck.TOOL_DISABLED -> "工具「$toolName」已被全局关闭"
            ToolCallCheck.ASSISTANT_NOT_AUTHORIZED ->
                "当前助手尚未启用「$extensionName」，请在助手详情 → 工具与搜索 的 Mini Apps / Tools 卡片中开启"
            ToolCallCheck.PERMISSION_DENIED ->
                "扩展「$extensionName」缺少执行「$toolName」所需权限，请在设置中授权"
            ToolCallCheck.OK -> ""
        }

    internal fun errorResult(code: String, message: String): JsonObject = buildJsonObject {
        put("success", false)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            }
        )
    }

    /** 解析 arguments：接受 JsonObject 或 JSON 字符串（与 MCP/Radio 约定一致） */
    internal fun parseExtensionCallArguments(raw: JsonElement?): JsonObject? {
        return when (raw) {
            null -> JsonObject(emptyMap())
            is JsonObject -> raw
            is JsonPrimitive -> if (raw.isString) {
                runCatching { JsonInstant.parseToJsonElement(raw.content).jsonObject }.getOrNull()
            } else null
            else -> null
        }
    }

    private fun JsonObject.readString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}


/**
 * 顶层委托（与 buildMcpAgentTools / buildRadioAgentTools 同为顶层装配入口，
 * ChatService 装配链统一引用顶层函数）。
 */
fun buildExtensionAgentTools(
    registry: ExtensionRegistry,
    toolRuntime: ExtensionToolRuntime,
    assistant: Assistant,
): List<Tool> = ExtensionAgentTools.buildExtensionAgentTools(registry, toolRuntime, assistant)
