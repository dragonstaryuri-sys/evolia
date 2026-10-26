package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpToolRef

/**
 * MCP 工具按需加载的两个固定 Function Tool。
 *
 * 上下文中只常驻「工具目录」（各服务的工具名称 + 有效描述，见 [getMcpCatalogSystemPrompt]），
 * 完整 inputSchema 不再逐工具注入；Agent 需要调用时先通过 [TOOL_GET_MCP_TOOL_SCHEMA]
 * 查询参数定义，再通过 [TOOL_CALL_MCP_TOOL] 执行真实工具。
 */
const val TOOL_GET_MCP_TOOL_SCHEMA = "get_mcp_tool_schema"
const val TOOL_CALL_MCP_TOOL = "call_mcp_tool"

/**
 * 构建固定 MCP 工具。当前助手没有可用的 MCP 工具时不注册，避免空目录污染上下文。
 */
fun buildMcpAgentTools(mcpManager: McpManager): List<Tool> {
    val availableTools = mcpManager.getAllAvailableTools()
    if (availableTools.isEmpty()) return emptyList()
    return listOf(
        buildGetMcpToolSchemaTool(mcpManager),
        buildCallMcpToolTool(mcpManager)
    )
}

/**
 * MCP 工具目录（名称 + 有效描述，按服务分组），通过 [Tool.systemPrompt] 注入系统提示。
 * 只在存在可用工具时返回内容；目录出现一次，不随每个工具重复。
 */
fun getMcpCatalogSystemPrompt(mcpManager: McpManager): String {
    return buildMcpCatalogPrompt(mcpManager.getAllAvailableToolsWithServer())
}

/**
 * 目录构建的纯函数部分（便于单元测试）：同名工具按服务分组展示，
 * 描述使用 effectiveDescription（自定义优先，原始描述兜底）。
 */
internal fun buildMcpCatalogPrompt(toolRefs: List<McpToolRef>): String {
    if (toolRefs.isEmpty()) return ""

    val catalogBuilder = StringBuilder()
    catalogBuilder.appendLine("## MCP 工具目录")
    catalogBuilder.appendLine(
        "以下 MCP 工具已连接可用。它们的完整参数定义(Schema)不会随请求常驻，调用前必须先执行 " +
            "`get_mcp_tool_schema`(传入 server_id + tool_name) 获取参数定义，再通过 `call_mcp_tool` 执行。"
    )
    toolRefs.groupBy { it.serverId }.forEach { (serverId, refs) ->
        val serverName = refs.first().serverName
        catalogBuilder.appendLine()
        catalogBuilder.appendLine("### 服务「$serverName」(server_id: $serverId)")
        refs.forEach { ref ->
            val description = ref.tool.effectiveDescription?.trim().orEmpty()
            if (description.isBlank()) {
                catalogBuilder.appendLine("- `${ref.tool.name}`: (无描述，请先查询 Schema 了解用途)")
            } else {
                catalogBuilder.appendLine("- `${ref.tool.name}`: ${description.replace("\n", " ")}")
            }
        }
    }
    return catalogBuilder.toString().trimEnd()
}

private fun buildGetMcpToolSchemaTool(mcpManager: McpManager): Tool {
    return Tool(
        name = TOOL_GET_MCP_TOOL_SCHEMA,
        description = "查询指定 MCP 工具的完整定义(原始描述 + 参数 Schema)。在调用任何 MCP 工具之前，" +
            "先用本工具获取目标工具的参数定义，再根据 Schema 组织参数通过 call_mcp_tool 执行。不执行真实工具操作。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("server_id", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("MCP 服务的 server_id，来自工具目录中服务名后的括号标识"))
                    })
                    put("tool_name", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("目标工具名称，来自工具目录"))
                    })
                },
                required = listOf("server_id", "tool_name")
            )
        },
        systemPrompt = { _, _ -> getMcpCatalogSystemPrompt(mcpManager) },
        execute = { args ->
            val argsObj = args as? JsonObject ?: JsonObject(emptyMap())
            val serverId = argsObj.readString("server_id")
            val toolName = argsObj.readString("tool_name")
            if (serverId == null || toolName == null) {
                buildJsonObject {
                    put("error", JsonPrimitive("Both server_id and tool_name are required"))
                }
            } else {
                val serverUuid = parseServerId(serverId)
                val ref = serverUuid?.let { mcpManager.findTool(it, toolName) }
                if (ref == null) {
                    buildJsonObject {
                        put(
                            "error",
                            JsonPrimitive("MCP tool not found or not available: server_id=$serverId, tool_name=$toolName. Check the tool catalog for valid ids.")
                        )
                    }
                } else {
                    buildJsonObject {
                        put("server_id", JsonPrimitive(ref.serverId.toString()))
                        put("tool_name", JsonPrimitive(ref.tool.name))
                        put("description", JsonPrimitive(ref.tool.description.orEmpty()))
                        put("input_schema", ref.tool.inputSchema.toJsonElement())
                    }
                }
            }
        }
    )
}

private fun buildCallMcpToolTool(mcpManager: McpManager): Tool {
    return Tool(
        name = TOOL_CALL_MCP_TOOL,
        description = "执行指定的 MCP 工具。必须先通过 get_mcp_tool_schema 获取参数定义，" +
            "然后按 Schema 组织 arguments 传入。arguments 支持任意 JSON 对象结构。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("server_id", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("MCP 服务的 server_id，来自工具目录"))
                    })
                    put("tool_name", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("要执行的工具名称"))
                    })
                    put("arguments", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("description", JsonPrimitive("目标工具的参数对象，结构以 get_mcp_tool_schema 返回的 input_schema 为准；无参数工具传空对象或不传"))
                    })
                },
                required = listOf("server_id", "tool_name")
            )
        },
        execute = { args ->
            val argsObj = args as? JsonObject ?: JsonObject(emptyMap())
            val serverIdRaw = argsObj.readString("server_id")
            val toolName = argsObj.readString("tool_name")
            if (serverIdRaw == null || toolName == null) {
                return@Tool buildJsonObject {
                    put("error", JsonPrimitive("Both server_id and tool_name are required"))
                }
            }
            val toolArguments = parseMcpCallArguments(argsObj["arguments"])
            if (toolArguments.containsKey("error")) {
                return@Tool toolArguments
            }
            val serverUuid = parseServerId(serverIdRaw)
            if (serverUuid == null) {
                return@Tool buildJsonObject {
                    put("error", JsonPrimitive("Invalid server_id format: $serverIdRaw"))
                }
            }
            mcpManager.callTool(serverUuid, toolName, toolArguments)
                .truncateLargeJsonText()
        }
    )
}

/**
 * 解析 call_mcp_tool 的 arguments 参数。兼容三种传法：
 * 标准 JSON object、部分模型退化成的 JSON 字符串、缺失（视为空参数）。
 * 非法输入返回带 error 字段的对象（非 object 结构直接判非法）。
 */
internal fun parseMcpCallArguments(raw: JsonElement?): JsonObject {
    return when (raw) {
        null -> JsonObject(emptyMap())
        is JsonObject -> raw
        is JsonPrimitive -> {
            val argumentsText = raw.content.takeIf { it.isNotBlank() } ?: "{}"
            runCatching {
                me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(argumentsText).jsonObject
            }.getOrElse {
                buildJsonObject {
                    put("error", JsonPrimitive("arguments must be a JSON object, got invalid input"))
                }
            }
        }
        else -> buildJsonObject {
            put("error", JsonPrimitive("arguments must be a JSON object"))
        }
    }
}

private fun parseServerId(raw: String): kotlin.uuid.Uuid? {
    return runCatching { kotlin.uuid.Uuid.parse(raw) }.getOrNull()
}

private fun JsonObject.readString(key: String): String? {
    return (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

/**
 * InputSchema.Obj 序列化为标准 JSON Schema 形状，与 provider 请求构建
 * （type: object + properties + required）保持一致，保证模型拿到的定义与直连时相同。
 */
private fun InputSchema?.toJsonElement(): JsonElement {
    val obj = this as? InputSchema.Obj ?: return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { })
    }
    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", obj.properties)
        obj.required?.let { required ->
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }
}

/**
 * 递归截断过长的 JSON 字符串值，防止超大 MCP 结果撑爆上下文。
 */
private fun JsonElement.truncateLargeJsonText(maxLength: Int = 32000): JsonElement {
    return when (this) {
        is JsonPrimitive -> {
            if (isString && content.length > maxLength) {
                JsonPrimitive(content.take(maxLength) + "...(truncated, ${content.length} chars total)")
            } else {
                this
            }
        }
        is JsonObject -> JsonObject(this.mapValues { it.value.truncateLargeJsonText(maxLength) })
        is JsonArray -> JsonArray(this.map { it.truncateLargeJsonText(maxLength) })
        else -> this
    }
}
