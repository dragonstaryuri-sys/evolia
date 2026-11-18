package me.rerere.rikkahub.data.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 轻量级 JSON Schema 校验器（详设 4.5）。
 *
 * 第一阶段支持：type / required / properties / items / enum / minimum / maximum /
 * minLength / maxLength / minItems / maxItems / additionalProperties(false)，
 * 以及注释类关键字（title / description / default / examples）。
 *
 * 对不支持的 Schema 关键字必须明确报错，不得静默忽略后继续安装。
 * Manifest 校验、Tool 参数校验和 Mini App 数据写入校验复用同一个校验器。
 */
object JsonSchemaValidator {

    /** 支持的约束关键字 */
    private val SUPPORTED_KEYWORDS = setOf(
        "type", "required", "properties", "items", "enum",
        "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
        "additionalProperties",
    )

    /** 注释类关键字：不产生约束，允许存在 */
    private val ANNOTATION_KEYWORDS = setOf("title", "description", "default", "examples")

    private val SUPPORTED_TYPES = setOf("object", "string", "number", "integer", "boolean", "array")

    /**
     * 校验 [value] 是否符合 [schema]，返回错误列表（空列表 = 合法）。
     */
    fun validate(value: JsonElement, schema: JsonObject, path: String = "$"): List<String> {
        val errors = mutableListOf<String>()

        // 不支持的关键字必须报错
        schema.keys.forEach { key ->
            if (key !in SUPPORTED_KEYWORDS && key !in ANNOTATION_KEYWORDS) {
                errors.add("$path: Schema 使用了不支持的关键字「$key」，当前校验器不支持该约束")
            }
        }

        val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != null && type !in SUPPORTED_TYPES) {
            errors.add("$path: 不支持的数据类型「$type」")
            return errors
        }

        if (type != null && !matchesType(value, type)) {
            errors.add("$path: 类型应为「$type」，实际为 ${describeType(value)}")
            return errors
        }

        when (value) {
            is JsonObject -> {
                val required = schema["required"] as? JsonArray
                if (required != null) {
                    required.forEach { r ->
                        val key = (r as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (key == null) {
                            errors.add("$path: required 数组必须全部为字符串")
                        } else if (key !in value.keys) {
                            errors.add("$path: 缺少必填字段「$key」")
                        }
                    }
                }
                val properties = schema["properties"] as? JsonObject
                if (properties != null) {
                    properties.forEach { (key, subSchema) ->
                        val sub = subSchema as? JsonObject
                        if (sub == null) {
                            errors.add("$path.properties.$key: 字段定义必须是 Schema 对象")
                            return@forEach
                        }
                        val child = value[key]
                        if (child != null) {
                            errors.addAll(validate(child, sub, "$path.$key"))
                        }
                    }
                    // additionalProperties = false 时禁止未声明字段
                    val forbidExtra = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
                    if (forbidExtra && properties.isNotEmpty()) {
                        val extra = value.keys.filterNot { it in properties.keys }
                        if (extra.isNotEmpty()) {
                            errors.add("$path: 存在未在 Schema 中声明的字段「${extra.joinToString("、")}」")
                        }
                    }
                }
            }

            is JsonArray -> {
                val items = schema["items"] as? JsonObject
                if (items != null) {
                    value.forEachIndexed { index, element ->
                        errors.addAll(validate(element, items, "$path[$index]"))
                    }
                }
                val minItems = (schema["minItems"] as? JsonPrimitive)?.longOrNull
                if (minItems != null && value.size < minItems) {
                    errors.add("$path: 数组元素数量不能少于 $minItems")
                }
                val maxItems = (schema["maxItems"] as? JsonPrimitive)?.longOrNull
                if (maxItems != null && value.size > maxItems) {
                    errors.add("$path: 数组元素数量不能超过 $maxItems")
                }
            }

            is JsonPrimitive -> {
                if (value.isString) {
                    val minLength = (schema["minLength"] as? JsonPrimitive)?.longOrNull
                    if (minLength != null && value.content.length < minLength) {
                        errors.add("$path: 长度不能少于 $minLength 个字符")
                    }
                    val maxLength = (schema["maxLength"] as? JsonPrimitive)?.longOrNull
                    if (maxLength != null && value.content.length > maxLength) {
                        errors.add("$path: 长度不能超过 $maxLength 个字符")
                    }
                }
                val minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
                val num = value.doubleOrNull
                if (minimum != null && num != null && num < minimum) {
                    errors.add("$path: 数值不能小于 $minimum")
                }
                val maximum = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
                if (maximum != null && num != null && num > maximum) {
                    errors.add("$path: 数值不能大于 $maximum")
                }
            }

            else -> Unit
        }

        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null) {
            // 1 == JsonPrimitive(true) 在 kotlinx 中成立（内容与 isString 一致）
            val matched = enumValues.any { it == value }
            if (!matched) {
                errors.add("$path: 取值必须在枚举范围内（${enumValues.joinToString("、")}）")
            }
        }

        return errors
    }

    /**
     * 校验 Schema 本身结构是否合法（安装时校验包内 Schema 文件用）。
     * 返回错误列表（空列表 = 合法）。
     */
    fun validateSchemaStructure(schema: JsonElement, path: String = "$"): List<String> {
        val obj = schema as? JsonObject
            ?: return listOf("$path: Schema 必须是 JSON 对象")
        val errors = mutableListOf<String>()

        obj.keys.forEach { key ->
            if (key !in SUPPORTED_KEYWORDS && key !in ANNOTATION_KEYWORDS) {
                errors.add("$path: Schema 使用了不支持的关键字「$key」，当前校验器不支持该约束")
            }
        }

        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != null && type !in SUPPORTED_TYPES) {
            errors.add("$path: 不支持的数据类型「$type」")
        }

        (obj["required"] as? JsonArray)?.forEach { r ->
            if (r !is JsonPrimitive || !r.isString) {
                errors.add("$path.required: 必须为字符串数组")
            }
        }

        val properties = obj["properties"]
        if (properties != null && properties !is JsonObject) {
            errors.add("$path.properties: 必须是对象")
        }
        properties?.let { props ->
            (props as JsonObject).forEach { (key, sub) ->
                errors.addAll(validateSchemaStructure(sub, "$path.properties.$key"))
            }
        }

        val items = obj["items"]
        if (items != null && items !is JsonObject) {
            errors.add("$path.items: 必须是 Schema 对象")
        }
        items?.let { errors.addAll(validateSchemaStructure(it, "$path.items")) }

        return errors
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && run {
            val d = value.doubleOrNull
            d != null && d % 1.0 == 0.0
        }
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        else -> false
    }

    private fun describeType(value: JsonElement): String = when (value) {
        is JsonObject -> "对象"
        is JsonArray -> "数组"
        is JsonPrimitive -> if (value.isString) "字符串" else "数值/布尔"
        else -> "未知"
    }
}
