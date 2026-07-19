package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig

data class McpPreset(
    val name: String,
    val url: String,
    val type: McpPresetType,
    val description: String? = null
)

enum class McpPresetType {
    SSE,
    STREAMABLE_HTTP
}

val MCP_PRESETS = emptyList<McpPreset>()

fun McpPreset.toMcpServerConfig(): McpServerConfig {
    val commonOptions = McpCommonOptions(name = this.name)
    return when (this.type) {
        McpPresetType.SSE -> McpServerConfig.SseTransportServer(
            commonOptions = commonOptions,
            url = this.url
        )
        McpPresetType.STREAMABLE_HTTP -> McpServerConfig.StreamableHTTPServer(
            commonOptions = commonOptions,
            url = this.url
        )
    }
}
