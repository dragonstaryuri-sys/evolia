package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig

data class McpPreset(
    val name: String,
    val url: String,
    val type: McpPresetType,
    val description: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val authHelpUrl: String? = null
)

enum class McpPresetType {
    SSE,
    STREAMABLE_HTTP
}

val MCP_PRESETS = listOf(
    McpPreset(
        name = "麦当劳官方 MCP",
        url = "https://mcp.mcd.cn",
        type = McpPresetType.STREAMABLE_HTTP,
        description = "麦当劳官方 MCP 服务，支持点餐、优惠券、积分商城等",
        headers = listOf("Authorization" to "Bearer "),
        authHelpUrl = "https://open.mcd.cn/mcp"
    ),
    McpPreset(
        name = "瑞幸官方 MCP",
        url = "https://gwmcp.lkcoffee.com/order/user/mcp",
        type = McpPresetType.STREAMABLE_HTTP,
        description = "瑞幸咖啡官方 MCP 服务，支持门店搜索、点单、支付等",
        headers = listOf("Authorization" to "Bearer "),
        authHelpUrl = "https://open.lkcoffee.com"
    )
)

fun McpPreset.toMcpServerConfig(): McpServerConfig {
    val commonOptions = McpCommonOptions(
        name = this.name,
        headers = this.headers
    )
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

/**
 * 根据服务器 URL 查找对应的预设，用于在配置页展示授权获取指引
 */
fun findMcpPresetByUrl(url: String): McpPreset? {
    return MCP_PRESETS.firstOrNull { it.url == url }
}
