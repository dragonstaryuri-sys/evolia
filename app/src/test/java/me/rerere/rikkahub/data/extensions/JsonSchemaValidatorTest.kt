package me.rerere.rikkahub.data.extensions

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻量级 JSON Schema 校验器单测（详设 4.5 支持范围）。
 */
class JsonSchemaValidatorTest {

    @Test
    fun `valid object passes`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"))))
            put("properties", buildJsonObject {
                put("title", buildJsonObject { put("type", "string") })
                put("count", buildJsonObject { put("type", "integer"); put("minimum", 1) })
            })
        }
        val value = buildJsonObject {
            put("title", "hello")
            put("count", 3)
        }
        assertTrue(JsonSchemaValidator.validate(value, schema).isEmpty())
    }

    @Test
    fun `missing required field fails`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"))))
        }
        val value = buildJsonObject { }
        val errors = JsonSchemaValidator.validate(value, schema)
        assertTrue(errors.any { it.contains("title") })
    }

    @Test
    fun `type mismatch fails`() {
        val schema = buildJsonObject { put("type", "string") }
        val errors = JsonSchemaValidator.validate(JsonPrimitive(42), schema)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `integer accepts whole numbers rejects fractions`() {
        val schema = buildJsonObject { put("type", "integer") }
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive(3), schema).isEmpty())
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive(3.0), schema).isEmpty())
        assertFalse(JsonSchemaValidator.validate(JsonPrimitive(3.5), schema).isEmpty())
    }

    @Test
    fun `boolean rejects numeric one and zero`() {
        val schema = buildJsonObject { put("type", "boolean") }
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive(true), schema).isEmpty())
        assertFalse(JsonSchemaValidator.validate(JsonPrimitive(1), schema).isEmpty())
    }

    @Test
    fun `enum restricts values`() {
        val schema = buildJsonObject {
            put("enum", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        }
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive("a"), schema).isEmpty())
        assertFalse(JsonSchemaValidator.validate(JsonPrimitive("c"), schema).isEmpty())
    }

    @Test
    fun `min max bounds enforced`() {
        val numSchema = buildJsonObject { put("type", "number"); put("minimum", 1); put("maximum", 10) }
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive(5), numSchema).isEmpty())
        assertFalse(JsonSchemaValidator.validate(JsonPrimitive(0.5), numSchema).isEmpty())
        assertFalse(JsonSchemaValidator.validate(JsonPrimitive(11), numSchema).isEmpty())

        val strSchema = buildJsonObject { put("type", "string"); put("minLength", 2); put("maxLength", 4) }
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive("abc"), strSchema).isEmpty())
        assertFalse(JsonSchemaValidator.validate(JsonPrimitive("a"), strSchema).isEmpty())
        assertFalse(JsonSchemaValidator.validate(JsonPrimitive("abcde"), strSchema).isEmpty())

        val arrSchema = buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 2) }
        assertTrue(JsonSchemaValidator.validate(
            kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("x"))), arrSchema
        ).isEmpty())
        assertFalse(JsonSchemaValidator.validate(kotlinx.serialization.json.JsonArray(emptyList()), arrSchema).isEmpty())
    }

    @Test
    fun `items validates array elements`() {
        val schema = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "integer") })
        }
        val value = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("bad")))
        val errors = JsonSchemaValidator.validate(value, schema)
        assertTrue(errors.any { it.contains("[1]") })
    }

    @Test
    fun `unknown keyword must error not be ignored`() {
        // 详设 4.5：不支持的关键字必须明确报错，不得静默忽略后继续安装
        val schema = buildJsonObject {
            put("type", "string")
            put("format", "email") // format 未支持
        }
        val errors = JsonSchemaValidator.validate(JsonPrimitive("x@y.com"), schema)
        assertTrue(errors.any { it.contains("format") })
    }

    @Test
    fun `annotation keywords are allowed`() {
        val schema = buildJsonObject {
            put("type", "string")
            put("title", "名称")
            put("description", "描述")
            put("default", "x")
        }
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive("x"), schema).isEmpty())
    }

    @Test
    fun `additionalProperties false forbids undeclared fields`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("a", buildJsonObject { put("type", "string") })
            })
            put("additionalProperties", false)
        }
        val value = buildJsonObject { put("a", "ok"); put("b", "extra") }
        assertTrue(JsonSchemaValidator.validate(value, schema).any { it.contains("b") })
    }

    @Test
    fun `schema structure validation rejects bad structure and unknown keywords`() {
        val bad = JsonInstant.parseToJsonElement("""{"type": "array[]", "required": "x"}""")
        val errors = JsonSchemaValidator.validateSchemaStructure(bad)
        assertTrue(errors.isNotEmpty())

        val unknown = JsonInstant.parseToJsonElement("""{"allOf": []}""")
        assertTrue(JsonSchemaValidator.validateSchemaStructure(unknown).any { it.contains("allOf") })

        val good = JsonInstant.parseToJsonElement("""{"type": "object", "properties": {"a": {"type": "string"}}}""")
        assertTrue(JsonSchemaValidator.validateSchemaStructure(good).isEmpty())
    }
}
