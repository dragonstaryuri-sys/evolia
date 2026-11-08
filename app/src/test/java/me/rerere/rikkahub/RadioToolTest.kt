package me.rerere.rikkahub

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.tools.RadioCatalogEntry
import me.rerere.rikkahub.data.ai.tools.buildRadioCatalogPrompt
import me.rerere.rikkahub.data.ai.tools.parseRadioCallArguments
import me.rerere.rikkahub.data.ai.tools.validateAgainstSchema
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio LocalTools 的纯函数：工具目录 prompt + arguments 解析 + Schema 校验。
 */
class RadioToolTest {

    // ==================== 目录 prompt ====================

    @Test
    fun `catalog prompt lists all tools with two-step usage`() {
        val prompt = buildRadioCatalogPrompt(
            listOf(
                RadioCatalogEntry("get_channels", "查询栏目"),
                RadioCatalogEntry("save_episode", "保存节目文字稿\n支持创建与更新")
            )
        )
        assertTrue(prompt.contains("## 小机电台工具目录"))
        assertTrue(prompt.contains("get_radio_tool_schema"))
        assertTrue(prompt.contains("call_radio_tool"))
        assertTrue(prompt.contains("`get_channels`: 查询栏目"))
        // 描述中的换行被替换为空格
        assertTrue(prompt.contains("保存节目文字稿 支持创建与更新"))
    }

    @Test
    fun `catalog prompt empty when no tools`() {
        assertEquals("", buildRadioCatalogPrompt(emptyList()))
    }

    // ==================== arguments 解析 ====================

    @Test
    fun `parse arguments accepts object string and missing`() {
        val obj = buildJsonObject { put("a", 1) }
        assertEquals(obj, parseRadioCallArguments(obj))
        assertEquals(obj, parseRadioCallArguments(JsonPrimitive("""{"a":1}""")))
        assertEquals(buildJsonObject { }, parseRadioCallArguments(null))
        assertTrue(parseRadioCallArguments(JsonPrimitive("not json")).containsKey("error"))
        assertTrue(parseRadioCallArguments(buildJsonObject { })["a"] == null)
    }

    // ==================== Schema 校验 ====================

    private val schema = InputSchema.Obj(
        properties = buildJsonObject {
            put("episode_id", buildJsonObject {
                put("type", JsonPrimitive("string"))
            })
            put("page", buildJsonObject {
                put("type", JsonPrimitive("integer"))
            })
            put("initiated_by_user", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
            })
            put("options", buildJsonObject {
                put("type", JsonPrimitive("object"))
            })
        },
        required = listOf("episode_id")
    )

    @Test
    fun `valid args pass validation`() {
        val args = buildJsonObject {
            put("episode_id", "ep1")
            put("page", 2)
            put("initiated_by_user", true)
            put("options", buildJsonObject { put("k", "v") })
        }
        assertTrue(validateAgainstSchema(args, schema).isEmpty())
    }

    @Test
    fun `missing required is reported`() {
        val errors = validateAgainstSchema(buildJsonObject { put("page", 1) }, schema)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("episode_id"))
    }

    @Test
    fun `wrong types are reported`() {
        val args = buildJsonObject {
            put("episode_id", 123)                    // 应为字符串
            put("initiated_by_user", "true")          // 应为布尔
            put("options", "not-object")              // 应为对象
        }
        val errors = validateAgainstSchema(args, schema)
        assertEquals(3, errors.size)
    }

    @Test
    fun `undeclared properties are tolerated`() {
        val args = buildJsonObject {
            put("episode_id", "ep1")
            put("extra_field", "whatever")
        }
        assertTrue(validateAgainstSchema(args, schema).isEmpty())
    }
}
