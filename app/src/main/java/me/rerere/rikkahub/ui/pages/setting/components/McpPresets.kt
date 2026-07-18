package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig

data class McpPreset(
    val name: String,
    val url: String,
    val type: McpPresetType
)

enum class McpPresetType {
    SSE,
    STREAMABLE_HTTP
}

val MCP_PRESETS = listOf(
    McpPreset(
        name = "大富翁",
        url = "https://spicy-monopoly.lol/mcp",
        type = McpPresetType.SSE
    ),
    McpPreset(
        name = "4399",
        url = "https://toy.cedarstar.org",
        type = McpPresetType.STREAMABLE_HTTP
    )
)

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
