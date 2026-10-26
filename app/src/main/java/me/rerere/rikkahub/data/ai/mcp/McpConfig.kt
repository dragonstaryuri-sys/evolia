package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.InputSchema
import kotlin.uuid.Uuid

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList()
)

@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: InputSchema? = null,
    // 用户自定义的简化描述。null 表示未自定义，此时有效描述回退到 [description]。
    // MCP 服务同步原始描述时会保留此字段，避免用户编辑被服务端刷新覆盖。
    val customDescription: String? = null
) {
    /**
     * 注入模型上下文的有效描述：优先用户自定义版本，否则使用服务端原始描述。
     */
    val effectiveDescription: String?
        get() = customDescription?.takeIf { it.isNotBlank() } ?: description
}

/**
 * 工具及其所属服务的引用。Agent 工具目录与固定工具（get_mcp_tool_schema / call_mcp_tool）
 * 均以 serverId + toolName 精确定位，避免不同 MCP 服务中的同名工具互相混淆。
 */
data class McpToolRef(
    val serverId: Uuid,
    val serverName: String,
    val tool: McpTool
)

@Serializable
sealed class McpServerConfig {
    abstract val id: Uuid
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: Uuid = this.id,
        commonOptions: McpCommonOptions = this.commonOptions
    ): McpServerConfig

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
}
