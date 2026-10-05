package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数宽容化（纯函数，便于单测）。
 *
 * 契约：必填字段（含 operation 契约）仍由 [AgentToolCallValidator] 严格校验；
 * 除此之外的参数一律"从宽解释"，尽量让调用产生结果而不是整单拒绝：
 * - 未识别/多余字段：剔除并记入调整说明（不再报"不允许额外字段"）；
 * - 可无损转换的类型：`"3000"`→3000、`"true"`→true、数字/布尔→字符串，自动转换并记录；
 * - 无法转换的可选字段：忽略并记录；必填字段保持原样交给校验层报错（不能静默丢弃）；
 * - 参数整体不是合法 JSON 对象：尝试数组包裹展开、从正文中提取 JSON 对象。
 *
 * 记录项由调用方（AgentLoop）以 `argument_adjustment` 字段回给模型，
 * 让模型知道哪些内容被忽略/修正，而不是悄无声息地改变行为。
 *
 * 保守边界：schema 未声明 properties（如 MCP 透传参数）、组合关键字
 * （anyOf/oneOf/allOf/if…）或显式 additionalProperties=true 时不做清洗；
 * $ref 解析失败同样保持原样。
 */
internal data class SanitizedToolCall(
    val call: AgentModelClient.ToolCall,
    val adjusted: List<String>,
)

internal object ToolCallArgumentSanitizer {

    private const val MAX_EXTRACT_CHARS = 200_000
    private const val MAX_VALUE_PREVIEW = 40
    private const val MAX_DEPTH = 12

    fun sanitize(call: AgentModelClient.ToolCall, schema: JSONObject?): SanitizedToolCall {
        val adjusted = mutableListOf<String>()
        val normalized = normalizeArguments(call.argumentsJson, adjusted)
        if (normalized == null) return SanitizedToolCall(call, adjusted)
        if (schema == null) {
            return SanitizedToolCall(call.copy(argumentsJson = normalized), adjusted)
        }
        val args = runCatching { JSONObject(normalized) }.getOrNull()
            ?: return SanitizedToolCall(call.copy(argumentsJson = normalized), adjusted)
        val cleaned = cleanObject(args, schema, schema, prefix = "", adjusted = adjusted, depth = 0)
        return SanitizedToolCall(call.copy(argumentsJson = cleaned.toString()), adjusted)
    }

    /** 规范化整体 JSON：已是对象则原样；否则尝试数组展开与文本提取。返回 null 表示无法规范化。 */
    internal fun normalizeArguments(raw: String, adjusted: MutableList<String>): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (runCatching { JSONObject(text) }.isSuccess) return text
        val array = runCatching { JSONArray(text) }.getOrNull()
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                adjusted += "参数被数组包裹，已展开第 ${index + 1} 个对象"
                return item.toString()
            }
        }
        if (text.length <= MAX_EXTRACT_CHARS) {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start in 0 until end) {
                runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()?.let { extracted ->
                    adjusted += "参数不是纯 JSON，已从文本中提取 JSON 对象"
                    return extracted.toString()
                }
            }
        }
        return null
    }

    private fun cleanObject(
        value: JSONObject,
        schema: JSONObject,
        root: JSONObject,
        prefix: String,
        adjusted: MutableList<String>,
        depth: Int,
    ): JSONObject {
        if (depth > MAX_DEPTH || !isCleanable(schema, root)) return value
        val resolved = resolveRef(schema, root)
        val properties = resolved.optJSONObject("properties")
        val patternProperties = resolved.optJSONObject("patternProperties")
        // 未声明 properties 且没有显式关闭额外字段的 schema（如 MCP 透传）不做清洗，
        // 否则会把调用方真正需要的字段全部剔除。
        val additional = resolved.opt("additionalProperties")
        if (properties == null && patternProperties == null && additional != false) return value
        val required = requiredNames(resolved)
        val out = JSONObject()
        for (key in value.keys()) {
            val childPath = if (prefix.isEmpty()) key else "$prefix.$key"
            val childValue = value.opt(key)
            val childSchema = properties?.optJSONObject(key)?.takeUnless { it === JSONObject.NULL }
            if (childSchema == null) {
                val patternSchema = matchingPatternSchema(patternProperties, key, root)
                if (patternSchema != null) {
                    out.put(key, resolveValue(childValue, patternSchema, root, childPath, adjusted, depth + 1, required = true).value)
                    continue
                }
                when (additional) {
                    true -> out.put(key, childValue)
                    is JSONObject -> out.put(
                        key,
                        resolveValue(childValue, additional, root, childPath, adjusted, depth + 1, required = true).value,
                    )
                    else -> adjusted += "$childPath（未识别字段，已忽略）"
                }
                continue
            }
            val entry = resolveValue(childValue, childSchema, root, childPath, adjusted, depth + 1, key in required)
            if (entry.keep) out.put(key, entry.value)
        }
        return out
    }

    private data class Resolved(val keep: Boolean, val value: Any?)

    private fun resolveValue(
        value: Any?,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        adjusted: MutableList<String>,
        depth: Int,
        required: Boolean,
    ): Resolved {
        if (depth > MAX_DEPTH) return Resolved(true, value)
        if (!isCleanable(schema, root)) return Resolved(true, value)
        val resolved = resolveRef(schema, root)
        if (value == null || value === JSONObject.NULL) return Resolved(true, JSONObject.NULL)
        val type = resolved.optString("type")
        // 递归清理嵌套对象与对象数组；结构不符时留给校验层报错。
        if (type == "object" && value is JSONObject) {
            return Resolved(true, cleanObject(value, resolved, root, path, adjusted, depth + 1))
        }
        if (type == "array" && value is JSONArray) {
            val itemSchema = resolved.optJSONObject("items")
            if (itemSchema != null && itemSchema.optString("type") == "object" && isCleanable(itemSchema, root)) {
                val cleaned = JSONArray()
                for (index in 0 until value.length()) {
                    val item = value.opt(index)
                    cleaned.put(
                        if (item is JSONObject) {
                            cleanObject(item, itemSchema, root, "$path[$index]", adjusted, depth + 1)
                        } else {
                            item
                        },
                    )
                }
                return Resolved(true, cleaned)
            }
            return Resolved(true, value)
        }
        if (type.isEmpty() || matchesType(value, type)) {
            // 枚举值不在此剔除：identity=root、direction、operation 这类字段是行为选择，
            // 静默忽略会把调用悄悄降级成另一个行为；保留原值交由校验层给出
            // “取值无效 + 合法候选”的明确错误。
            return Resolved(true, value)
        }
        // 类型不符：可无损转换则转换，否则可选字段忽略、必填字段保留交给校验层。
        if (value is String) {
            coerceString(value, type)?.let { coerced ->
                adjusted += "$path（已从字符串 \"${value.take(MAX_VALUE_PREVIEW)}\" 转换为 $type）"
                return Resolved(true, coerced)
            }
        }
        if ((type == "string") && (value is Number || value is Boolean)) {
            adjusted += "$path（已从 ${describe(value)} 转换为字符串）"
            return Resolved(true, value.toString())
        }
        if (type == "integer" && value is Number && isIntegral(value)) {
            adjusted += "$path（已规整为整数）"
            return Resolved(true, value.toLong())
        }
        if (!required) {
            adjusted += "$path（类型不符：期望 $type，收到 ${describe(value)}，已忽略）"
            return Resolved(false, null)
        }
        return Resolved(true, value)
    }

    private fun matchingPatternSchema(patterns: JSONObject?, key: String, root: JSONObject): JSONObject? {
        if (patterns == null) return null
        for (pattern in patterns.keys()) {
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
            if (!regex.containsMatchIn(key)) continue
            patterns.optJSONObject(pattern)?.let { return it }
        }
        return null
    }

    private fun requiredNames(schema: JSONObject): Set<String> {
        val array = schema.optJSONArray("required") ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    /** 组合关键字与不可解析的 $ref 一律不清洗：无法确定唯一有效形状时保持原样。 */
    private fun isCleanable(schema: JSONObject, root: JSONObject): Boolean {
        if (schema.opt("anyOf") != null || schema.opt("oneOf") != null || schema.opt("allOf") != null) return false
        if (schema.opt("if") != null || schema.opt("then") != null || schema.opt("else") != null) return false
        val reference = schema.optString("\$ref")
        if (reference.isNotEmpty() && resolveReference(root, reference) == null) return false
        return true
    }

    private fun resolveRef(schema: JSONObject, root: JSONObject): JSONObject {
        val reference = schema.optString("\$ref")
        if (reference.isEmpty()) return schema
        return (resolveReference(root, reference) as? JSONObject) ?: schema
    }

    private fun resolveReference(root: JSONObject, reference: String): Any? {
        if (!reference.startsWith("#")) return null
        if (reference == "#") return root
        if (!reference.startsWith("#/")) return null
        var current: Any? = root
        for (rawToken in reference.removePrefix("#/").split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JSONObject -> if (current.has(token)) current.opt(token) else return null
                is JSONArray -> token.toIntOrNull()?.let { current.opt(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun matchesType(value: Any?, type: String): Boolean = when (type) {
        "string" -> value is String
        "boolean" -> value is Boolean
        "integer" -> value is Number && isIntegral(value)
        "number" -> value is Number
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        else -> true
    }

    private fun isIntegral(value: Number): Boolean = when (value) {
        is Int, is Long, is Short, is Byte -> true
        is Double -> value.isFinite() && value == Math.floor(value)
        is Float -> value.isFinite() && value == Math.floor(value.toDouble()).toFloat()
        else -> false
    }

    private fun coerceString(value: String, type: String): Any? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return when (type) {
            "boolean" -> when (trimmed.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            "integer" -> trimmed.toLongOrNull()
                ?: trimmed.toDoubleOrNull()?.takeIf { it.isFinite() && it == Math.floor(it) }?.toLong()
            "number" -> trimmed.toDoubleOrNull()
            else -> null
        }
    }

    private fun describe(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "对象"
        is JSONArray -> "数组"
        is Boolean -> "布尔"
        is Number -> "数字"
        is String -> "字符串"
        else -> value.javaClass.simpleName
    }
}
