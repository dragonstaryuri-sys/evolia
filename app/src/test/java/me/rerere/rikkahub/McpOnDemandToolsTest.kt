package me.rerere.rikkahub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.McpToolRef
import me.rerere.rikkahub.data.ai.tools.buildMcpCatalogPrompt
import me.rerere.rikkahub.data.ai.tools.parseMcpCallArguments
import me.rerere.rikkahub.ui.pages.setting.McpToolDescItem
import me.rerere.rikkahub.ui.pages.setting.chunkedBySize
import me.rerere.rikkahub.ui.pages.setting.parseBatchSimplifyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpOnDemandToolsTest {

    // ==================== 有效描述优先级 ====================

    @Test
    fun `effective description prefers non-blank custom description`() {
        val tool = McpTool(
            name = "search",
            description = "原始描述",
            customDescription = "搜索网页内容"
        )
        assertEquals("搜索网页内容", tool.effectiveDescription)
    }

    @Test
    fun `effective description falls back to original when custom is null`() {
        val tool = McpTool(name = "search", description = "原始描述")
        assertEquals("原始描述", tool.effectiveDescription)
    }

    @Test
    fun `blank custom description is ignored`() {
        val tool = McpTool(name = "search", description = "原始描述", customDescription = "  ")
        assertEquals("原始描述", tool.effectiveDescription)
    }

    @Test
    fun `both descriptions missing yields null`() {
        val tool = McpTool(name = "search")
        assertNull(tool.effectiveDescription)
    }

    // ==================== 工具目录构建 ====================

    @Test
    fun `catalog groups tools by server and disambiguates same-name tools`() {
        val serverA = Uuid.random()
        val serverB = Uuid.random()
        val refs = listOf(
            McpToolRef(serverA, "serverA", McpTool(name = "search", description = "A 服务的搜索")),
            McpToolRef(serverB, "serverB", McpTool(name = "search", description = "B 服务的搜索"))
        )
        val catalog = buildMcpCatalogPrompt(refs)

        assertTrue(catalog.contains("serverA"))
        assertTrue(catalog.contains("serverB"))
        // 同名工具分别出现两次，各自归属自己的服务
        assertEquals(2, Regex("- `search`").findAll(catalog).count())
        assertTrue(catalog.contains("A 服务的搜索"))
        assertTrue(catalog.contains("B 服务的搜索"))
    }

    @Test
    fun `catalog uses custom description when present`() {
        val ref = McpToolRef(
            Uuid.random(), "serverA",
            McpTool(name = "search", description = "很长的原始描述", customDescription = "搜索")
        )
        val catalog = buildMcpCatalogPrompt(listOf(ref))
        assertTrue(catalog.contains("搜索"))
        assertFalse(catalog.contains("很长的原始描述"))
    }

    @Test
    fun `catalog mentions on-demand schema flow`() {
        val ref = McpToolRef(Uuid.random(), "serverA", McpTool(name = "search", description = "d"))
        val catalog = buildMcpCatalogPrompt(listOf(ref))
        assertTrue(catalog.contains("get_mcp_tool_schema"))
        assertTrue(catalog.contains("call_mcp_tool"))
    }

    @Test
    fun `empty tool list produces empty catalog`() {
        assertEquals("", buildMcpCatalogPrompt(emptyList()))
    }

    // ==================== call_mcp_tool arguments 解析 ====================

    @Test
    fun `arguments object passes through unchanged`() {
        val input = JsonObject(mapOf("query" to JsonPrimitive("hello")))
        assertEquals(input, parseMcpCallArguments(input))
    }

    @Test
    fun `missing arguments yields empty object`() {
        assertEquals(JsonObject(emptyMap()), parseMcpCallArguments(null))
    }

    @Test
    fun `arguments as json string is parsed into object`() {
        val parsed = parseMcpCallArguments(JsonPrimitive("""{"query": "hello"}"""))
        val query = (parsed["query"] as? JsonPrimitive)?.contentOrNull
        assertEquals("hello", query)
    }

    @Test
    fun `invalid arguments string yields structured error`() {
        val parsed = parseMcpCallArguments(JsonPrimitive("not a json"))
        assertTrue(parsed.containsKey("error"))
    }

    @Test
    fun `array arguments yields structured error`() {
        val parsed = parseMcpCallArguments(JsonArray(emptyList()))
        assertTrue(parsed.containsKey("error"))
    }

    // ==================== 批量简化输出解析 ====================

    @Test
    fun `batch response parses valid array`() {
        val raw = """[{"name": "a", "description": "简化A"}, {"name": "b", "description": "简化B"}]"""
        val result = parseBatchSimplifyResponse(raw, setOf("a", "b"))
        assertEquals(mapOf("a" to "简化A", "b" to "简化B"), result)
    }

    @Test
    fun `batch response strips markdown fences`() {
        val raw = "```json\n[{\"name\": \"a\", \"description\": \"简化A\"}]\n```"
        val result = parseBatchSimplifyResponse(raw, setOf("a"))
        assertEquals(mapOf("a" to "简化A"), result)
    }

    @Test
    fun `batch response filters unknown tool names`() {
        val raw = """[{"name": "unknown", "description": "x"}, {"name": "a", "description": "简化A"}]"""
        val result = parseBatchSimplifyResponse(raw, setOf("a"))
        assertEquals(mapOf("a" to "简化A"), result)
    }

    @Test(expected = IllegalStateException::class)
    fun `batch response with invalid json throws`() {
        parseBatchSimplifyResponse("not json at all", setOf("a"))
    }

    @Test(expected = IllegalStateException::class)
    fun `batch response with non-array json throws`() {
        parseBatchSimplifyResponse("""{"name": "a"}""", setOf("a"))
    }

    @Test
    fun `batch response skips blank descriptions`() {
        val raw = """[{"name": "a", "description": "  "}]"""
        val result = parseBatchSimplifyResponse(raw, setOf("a"))
        assertTrue(result.isEmpty())
    }

    // ==================== 批量分批 ====================

    private fun descItem(serverId: Uuid, name: String, descLength: Int) = McpToolDescItem(
        serverId = serverId,
        serverName = "srv",
        tool = McpTool(name = name, description = "x".repeat(descLength)),
        editContent = ""
    )

    @Test
    fun `chunking respects max tool count`() {
        val items = (1..7).map { descItem(Uuid.random(), "t$it", 10) }
        val batches = items.chunkedBySize(maxTools = 3, maxChars = 1000)
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.size <= 3 })
        assertEquals(items.size, batches.sumOf { it.size })
    }

    @Test
    fun `chunking respects max total chars`() {
        val items = (1..4).map { descItem(Uuid.random(), "t$it", 500) }
        val batches = items.chunkedBySize(maxTools = 10, maxChars = 800)
        // 每个描述 500 字符，上限 800 → 每批只能放 1 个
        assertEquals(4, batches.size)
        assertTrue(batches.all { it.size == 1 })
    }

    @Test
    fun `single oversized item still forms its own batch`() {
        val items = listOf(descItem(Uuid.random(), "big", 99999))
        val batches = items.chunkedBySize(maxTools = 5, maxChars = 100)
        assertEquals(1, batches.size)
        assertEquals(1, batches.first().size)
    }
}
