package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数宽容化：未识别字段剔除、无损类型转换、无效可选值忽略；
 * 必填字段与组合 schema 保持原有严格语义（交给校验层）。
 */
class ToolCallArgumentSanitizerTest {

    private fun call(json: String) = AgentModelClient.ToolCall("id", "file_ops", json)

    private fun toolSchema(): JSONObject = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "operation": {"type": "string", "enum": ["read", "write"]},
            "path": {"type": "string"},
            "offset_bytes": {"type": "integer"},
            "recursive": {"type": "boolean"},
            "mode": {"enum": ["a", "b"]}
          },
          "required": ["operation", "path"],
          "additionalProperties": false
        }
        """.trimIndent()
    )

    private fun sanitized(json: String, schema: JSONObject? = toolSchema()): SanitizedToolCall =
        ToolCallArgumentSanitizer.sanitize(call(json), schema)

    @Test
    fun unknownFieldIsStrippedAndRecorded() {
        val result = sanitized("""{"operation":"read","path":"a.txt","bogus":1}""")
        val args = result.call.parsedArgsOrNull()
        assertNotNull(args)
        assertFalse(args!!.has("bogus"))
        assertEquals("read", args.optString("operation"))
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("bogus") && it.contains("未识别") })
    }

    @Test
    fun numericAndBooleanStringsAreCoerced() {
        val result = sanitized("""{"operation":"read","path":"a.txt","offset_bytes":"1200","recursive":"true"}""")
        val args = result.call.parsedArgsOrNull()
        assertEquals(1200, args!!.optInt("offset_bytes"))
        assertTrue(args.optBoolean("recursive"))
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("offset_bytes") })
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("recursive") })
    }

    @Test
    fun numberIsCoercedToString() {
        val result = sanitized("""{"operation":"read","path":123}""")
        assertEquals("123", result.call.parsedArgsOrNull()!!.optString("path"))
    }

    @Test
    fun requiredNumbersAreCoercedToDeclaredStringType() {
        val result = sanitized("""{"operation":5,"path":"a.txt"}""")
        // 必填字段不做丢弃，但可无损转换（数字→字符串）时先转换，非法取值再交由校验层报错。
        assertEquals("5", result.call.parsedArgsOrNull()!!.optString("operation"))
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("operation") })
    }

    @Test
    fun optionalInvalidEnumIsKeptForValidator() {
        // 枚举是行为选择（identity、direction、operation 等），不能被静默剔除：
        // 保留原值，让校验层报“取值无效”并给出合法候选。
        val kept = sanitized("""{"operation":"read","path":"a.txt","mode":"z"}""")
        assertEquals("z", kept.call.parsedArgsOrNull()!!.optString("mode"))
        assertTrue(kept.adjusted.none { it.contains("取值无效") })
    }

    @Test
    fun conversionFailureDropsOptionalsButNotRequired() {
        val result = sanitized("""{"operation":"read","path":"a.txt","offset_bytes":"not-a-number"}""")
        assertFalse(result.call.parsedArgsOrNull()!!.has("offset_bytes"))
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("类型不符") })
    }

    @Test
    fun arrayWrappedArgumentsAreUnwrapped() {
        val result = sanitized("""[{"operation":"read","path":"a.txt"}]""")
        assertEquals("a.txt", result.call.parsedArgsOrNull()!!.optString("path"))
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("数组") })
    }

    @Test
    fun jsonObjectIsExtractedFromProseArguments() {
        val result = sanitized("""这是参数：{"operation":"read","path":"a.txt"} 请执行""")
        assertEquals("read", result.call.parsedArgsOrNull()!!.optString("operation"))
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("提取") })
    }

    @Test
    fun unparsableArgumentsStayUntouchedForOriginalErrorPath() {
        val result = sanitized("not json at all")
        assertEquals("not json at all", result.call.argumentsJson)
        assertTrue(result.adjusted.isEmpty())
    }

    @Test
    fun blankArgumentsStayUntouched() {
        val result = sanitized("")
        assertEquals("", result.call.argumentsJson)
    }

    @Test
    fun schemaWithoutPropertiesKeepsEverything() {
        val result = sanitized("""{"anything":1,"more":true}""", JSONObject("""{"type":"object"}"""))
        val args = result.call.parsedArgsOrNull()
        assertTrue(args!!.has("anything"))
        assertTrue(args.has("more"))
        assertTrue(result.adjusted.isEmpty())
    }

    @Test
    fun additionalPropertiesTrueKeepsExtras() {
        val schema = JSONObject(
            """{"type":"object","properties":{"path":{"type":"string"}},"additionalProperties":true}""",
        )
        val result = sanitized("""{"path":"a.txt","extra":"kept"}""", schema)
        assertEquals("kept", result.call.parsedArgsOrNull()!!.optString("extra"))
    }

    @Test
    fun nestedObjectIsCleanedRecursively() {
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "outer": {
                  "type": "object",
                  "properties": {"inner": {"type": "string"}},
                  "additionalProperties": false
                }
              }
            }
            """.trimIndent()
        )
        val result = sanitized("""{"outer":{"inner":"x","junk":1}}""", schema)
        val outer = result.call.parsedArgsOrNull()!!.optJSONObject("outer")!!
        assertEquals("x", outer.optString("inner"))
        assertFalse(outer.has("junk"))
        assertTrue(result.adjusted.toString(), result.adjusted.any { it.contains("outer.junk") })
    }

    @Test
    fun compositionSchemasAreLeftUntouched() {
        val schema = JSONObject(
            """{"type":"object","anyOf":[{"required":["a"]},{"required":["b"]}],"properties":{"a":{"type":"string"}}}""",
        )
        val result = sanitized("""{"a":"x","whatever":1}""", schema)
        assertTrue(result.call.parsedArgsOrNull()!!.has("whatever"))
        assertTrue(result.adjusted.isEmpty())
    }

    @Test
    fun nullSchemaOnlyNormalizesWholeJson() {
        val result = sanitized("""[{"path":"a.txt"}]""", schema = null)
        assertEquals("a.txt", result.call.parsedArgsOrNull()!!.optString("path"))
        assertTrue(result.adjusted.any { it.contains("数组") })
    }
}
