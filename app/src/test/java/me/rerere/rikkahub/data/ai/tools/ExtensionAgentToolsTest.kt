package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.extensions.ExtensionCatalogEntry
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.data.extensions.ToolCallCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展 Agent 工具族纯函数单测：目录 Prompt 构建 / 错误码映射 / 参数解析。
 */
class ExtensionAgentToolsTest {

    private fun entry(
        appId: String = "personal_forum",
        extName: String = "个人论坛",
        toolId: String = "create_post",
        description: String = "在个人论坛中创建一条帖子",
    ) = ExtensionCatalogEntry(
        appId = appId,
        extensionName = extName,
        extensionDescription = "管理个人帖子",
        toolId = toolId,
        fullId = "$appId:$toolId",
        name = "创建帖子",
        description = description,
        isMiniAppTool = true,
    )

    @Test
    fun `empty catalog produces empty prompt`() {
        assertEquals("", ExtensionAgentTools.buildExtensionCatalogPrompt(emptyList()))
    }

    @Test
    fun `catalog prompt groups by extension and lists full ids`() {
        val prompt = ExtensionAgentTools.buildExtensionCatalogPrompt(listOf(entry()))
        // 2026-11-18 措辞中性化：不向模型区分「扩展工具/普通工具」
        assertTrue(prompt.contains("## 更多可用工具（按需 Schema）"))
        assertFalse(prompt.contains("扩展工具"))
        assertTrue(prompt.contains("get_extension_tool_schema"))
        assertTrue(prompt.contains("call_extension_tool"))
        assertTrue(prompt.contains("### 个人论坛"))
        assertTrue(prompt.contains("`personal_forum:create_post`"))
        assertTrue(prompt.contains("创建帖子"))
        assertTrue(prompt.contains("在个人论坛中创建一条帖子"))
    }

    @Test
    fun `catalog prompt handles blank description`() {
        val prompt = ExtensionAgentTools.buildExtensionCatalogPrompt(listOf(entry(description = "")))
        assertTrue(prompt.contains("(无描述，请先查询 Schema 了解用途)"))
    }

    @Test
    fun `multiple extensions appear as separate groups`() {
        val prompt = ExtensionAgentTools.buildExtensionCatalogPrompt(
            listOf(
                entry(appId = "forum", extName = "论坛", toolId = "create"),
                entry(appId = "weather", extName = "天气", toolId = "get_weather"),
            )
        )
        // 分组标题 = 来源名称本身（措辞中性化后不再标注 app_id/扩展字样）
        assertTrue(prompt.contains("### 论坛"))
        assertTrue(prompt.contains("### 天气"))
        assertTrue(prompt.contains("`forum:create`"))
        assertTrue(prompt.contains("`weather:get_weather`"))
    }

    @Test
    fun `check error maps to stable error codes`() {
        assertEquals(
            "EXTENSION_NOT_FOUND",
            ExtensionAgentTools.mapCheckError(ToolCallCheck.EXTENSION_NOT_FOUND)
        )
        assertEquals("TOOL_NOT_FOUND", ExtensionAgentTools.mapCheckError(ToolCallCheck.TOOL_NOT_FOUND))
        assertEquals("EXTENSION_DISABLED", ExtensionAgentTools.mapCheckError(ToolCallCheck.EXTENSION_DISABLED))
        assertEquals("TOOL_DISABLED", ExtensionAgentTools.mapCheckError(ToolCallCheck.TOOL_DISABLED))
        assertEquals(
            "ASSISTANT_NOT_AUTHORIZED",
            ExtensionAgentTools.mapCheckError(ToolCallCheck.ASSISTANT_NOT_AUTHORIZED)
        )
        assertEquals("PERMISSION_DENIED", ExtensionAgentTools.mapCheckError(ToolCallCheck.PERMISSION_DENIED))
        assertEquals("", ExtensionAgentTools.mapCheckError(ToolCallCheck.OK))
    }

    @Test
    fun `error result shape matches design contract`() {
        val result = ExtensionAgentTools.errorResult("PERMISSION_DENIED", "未授权")
        assertTrue(result["success"] == JsonPrimitive(false))
        val error = result["error"] as kotlinx.serialization.json.JsonObject
        assertTrue(error["code"] == JsonPrimitive("PERMISSION_DENIED"))
        assertTrue(error["message"] == JsonPrimitive("未授权"))
    }

    @Test
    fun `arguments accepts object or json string`() {
        val obj = buildJsonObject { put("city", "北京") }
        assertEquals(obj, ExtensionAgentTools.parseExtensionCallArguments(obj))
        assertEquals(
            obj,
            ExtensionAgentTools.parseExtensionCallArguments(JsonPrimitive("""{"city":"北京"}"""))
        )
        // 缺省 = 空对象
        assertEquals(kotlinx.serialization.json.JsonObject(emptyMap()), ExtensionAgentTools.parseExtensionCallArguments(null))
        // 非对象非法
        assertEquals(null, ExtensionAgentTools.parseExtensionCallArguments(JsonPrimitive(42)))
    }

    @Test
    fun `tool name constants follow phase1 contract`() {
        assertEquals("get_extension_tool_schema", ExtensionAgentTools.TOOL_GET_EXTENSION_TOOL_SCHEMA)
        assertEquals("call_extension_tool", ExtensionAgentTools.TOOL_CALL_EXTENSION_TOOL)
    }
}
