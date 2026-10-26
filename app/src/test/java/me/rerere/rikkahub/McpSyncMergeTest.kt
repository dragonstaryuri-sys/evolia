package me.rerere.rikkahub

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.mergeSyncedTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 服务同步合并逻辑（mergeSyncedTools）的行为验证：
 * 新工具加入、已有工具刷新描述/Schema 且保留用户自定义描述与启用状态、服务端已删工具移除。
 */
class McpSyncMergeTest {

    private fun serverTool(
        name: String,
        description: String? = null,
        schemaProps: Map<String, String> = emptyMap()
    ) = Tool(
        name = name,
        description = description,
        inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
            properties = buildJsonObject {
                schemaProps.forEach { (k, v) -> put(k, v) }
            }
        )
    )

    @Test
    fun `new server tools are added and enabled by default`() {
        val merged = mergeSyncedTools(
            existingTools = emptyList(),
            serverTools = listOf(serverTool("search", "搜索网页"))
        )
        assertEquals(1, merged.size)
        assertEquals("search", merged.first().name)
        assertEquals("搜索网页", merged.first().description)
        assertTrue(merged.first().enable)
    }

    @Test
    fun `existing tool refreshes description and schema but keeps custom description and enable state`() {
        val oldSchema = me.rerere.ai.core.InputSchema.Obj(
            properties = buildJsonObject { put("q", "string") }
        )
        val existing = McpTool(
            enable = false,
            name = "search",
            description = "旧描述",
            inputSchema = oldSchema,
            customDescription = "搜索"
        )
        val merged = mergeSyncedTools(
            existingTools = listOf(existing),
            serverTools = listOf(serverTool("search", "新描述", mapOf("query" to "string")))
        )
        assertEquals(1, merged.size)
        val tool = merged.first()
        assertEquals("新描述", tool.description)
        assertEquals("搜索", tool.customDescription)
        // 用户手动禁用的工具不因同步被重新启用
        assertEquals(false, tool.enable)
        // Schema 随服务端刷新
        val newProps = (tool.inputSchema as me.rerere.ai.core.InputSchema.Obj).properties
        assertTrue(newProps.containsKey("query"))
        assertTrue(!newProps.containsKey("q"))
    }

    @Test
    fun `tools removed from server are dropped locally`() {
        val existing = listOf(
            McpTool(name = "kept", description = "保留"),
            McpTool(name = "gone", description = "已删除")
        )
        val merged = mergeSyncedTools(
            existingTools = existing,
            serverTools = listOf(serverTool("kept", "保留"))
        )
        assertEquals(listOf("kept"), merged.map { it.name })
    }

    @Test
    fun `empty server list clears all local tools`() {
        val existing = listOf(McpTool(name = "a"), McpTool(name = "b"))
        val merged = mergeSyncedTools(existingTools = existing, serverTools = emptyList())
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `existing tools keep position and new tools append in server order`() {
        val existing = listOf(McpTool(name = "b", description = "旧b"))
        val merged = mergeSyncedTools(
            existingTools = existing,
            serverTools = listOf(
                serverTool("a", "A"),
                serverTool("b", "B"),
                serverTool("c", "C")
            )
        )
        // 既有工具 b 保留原位置，新工具 a/c 按服务端顺序追加（与历史同步行为一致）
        assertEquals(listOf("b", "a", "c"), merged.map { it.name })
    }

    @Test
    fun `empty schema converts to empty properties object`() {
        val toolWithEmptySchema = Tool(
            name = "no_args",
            description = "d",
            inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
                properties = buildJsonObject { }
            )
        )
        val merged = mergeSyncedTools(existingTools = emptyList(), serverTools = listOf(toolWithEmptySchema))
        val schema = merged.first().inputSchema as me.rerere.ai.core.InputSchema.Obj
        assertTrue(schema.properties.isEmpty())
    }
}
