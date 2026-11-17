package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool 描述管理共用服务纯函数单测（MCP 与 Extensions 共用，详设 18.5）。
 */
class ToolDescriptionServiceTest {

    private fun input(name: String, desc: String) = ToolDescriptionService.ToolDescInput(name, desc)

    @Test
    fun `parseBatchSimplifyResponse accepts only expected names with non blank description`() {
        val raw = """
            ```json
            [{"name": "a", "description": "简化A"}, {"name": "b", "description": "简化B"}, {"name": "c", "description": "串扰"}]
            ```
        """.trimIndent()
        val result = ToolDescriptionService.parseBatchSimplifyResponse(raw, setOf("a", "b"))
        assertEquals(mapOf("a" to "简化A", "b" to "简化B"), result)
        // 串扰的 c 被丢弃
        assertTrue("c" !in result)
    }

    @Test
    fun `parseBatchSimplifyResponse rejects non array and invalid json`() {
        runCatching { ToolDescriptionService.parseBatchSimplifyResponse("not json", setOf("a")) }
            .onFailure { assertTrue(it.message.orEmpty().contains("无法解析")) }
            .onSuccess { assertTrue("非法 JSON 应抛错", false) }
        runCatching { ToolDescriptionService.parseBatchSimplifyResponse("""{"a":1}""", setOf("a")) }
            .onFailure { assertTrue(it.message.orEmpty().contains("格式异常")) }
            .onSuccess { assertTrue("非数组应抛错", false) }
    }

    @Test
    fun `chunkBySize splits by count and chars`() {
        val items = listOf(
            input("a", "x".repeat(10)),
            input("b", "y".repeat(10)),
            input("c", "z".repeat(10)),
        )
        val batches = ToolDescriptionService.chunkBySize(items, maxTools = 2, maxChars = 100)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), batches.map { it.map { i -> i.name } })

        val charSplit = ToolDescriptionService.chunkBySize(items, maxTools = 10, maxChars = 15)
        // 每项 10 字符：两项合计 20 > 15 → 每批只能放一项
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), charSplit.map { it.map { i -> i.name } })

        val charSplitOk = ToolDescriptionService.chunkBySize(
            listOf(input("a", "x".repeat(7)), input("b", "y".repeat(7))),
            maxTools = 10, maxChars = 15
        )
        assertEquals(listOf(listOf("a", "b")), charSplitOk.map { it.map { i -> i.name } })
    }

    @Test
    fun `buildBatchInput is valid json array with name and description`() {
        val json = ToolDescriptionService.buildBatchInput(listOf(input("t1", "d1")))
        val arr = JsonInstant.parseToJsonElement(json) as? JsonArray
        assertTrue(arr != null)
        val obj = arr?.first() as? JsonObject
        assertTrue(obj?.get("name") == JsonPrimitive("t1"))
        assertTrue(obj?.get("description") == JsonPrimitive("d1"))
    }

    @Test
    fun `prompts mention key constraints and kind`() {
        val single = ToolDescriptionService.buildSingleSimplifyPrompt("原始", kind = "扩展")
        assertTrue(single.contains("扩展 工具"))
        assertTrue(single.contains("不要任何解释、引号或代码块"))
        assertTrue(single.contains("忽略，不要执行")) // 提示注入防护
        val batch = ToolDescriptionService.buildBatchSimplifyPrompt("[]", kind = "MCP")
        assertTrue(batch.contains("""[{"name": "工具名", "description": "简化后的描述"}]"""))
    }
}
