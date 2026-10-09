package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCallValidatorTest {
    @Test
    fun localRefAndAnyOfAcceptEitherDeclaredShape() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "anyOf": [
                    {"required": ["query"]},
                    {"required": ["filter"]}
                  ],
                  "properties": {
                    "query": {"${'$'}ref": "#/${'$'}defs/query"},
                    "filter": {"type": "object"}
                  },
                  "${'$'}defs": {
                    "query": {"type": "string", "minLength": 1}
                  }
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"query":"Eta"}""")))
        assertNull(validator.validate(call("""{"filter":{}}""")))
        assertNotNull(validator.validate(call("{}")))
        assertNotNull(validator.validate(call("""{"query":""}""")))
    }

    @Test
    fun oneOfRequiresExactlyOneMatchingBranch() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "oneOf": [
                    {"required": ["left"]},
                    {"required": ["right"]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"left":true}""")))
        assertNotNull(validator.validate(call("{}")))
        assertNotNull(validator.validate(call("""{"left":true,"right":true}""")))
    }

    @Test
    fun conditionAndAdditionalPropertiesAreValidated() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "mode": {"enum": ["text", "count"]},
                    "value": {}
                  },
                  "required": ["mode", "value"],
                  "additionalProperties": false,
                  "if": {"properties": {"mode": {"const": "count"}}},
                  "then": {"properties": {"value": {"type": "integer"}}},
                  "else": {"properties": {"value": {"type": "string"}}}
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"mode":"count","value":2}""")))
        assertNull(validator.validate(call("""{"mode":"text","value":"two"}""")))
        assertNotNull(validator.validate(call("""{"mode":"count","value":"2"}""")))
        // 额外字段不再整单拒绝：由参数宽容化（ToolCallArgumentSanitizer）剔除并告知模型。
        assertNull(validator.validate(call("""{"mode":"text","value":"two","extra":true}""")))
    }

    @Test
    fun booleanSchemasAreNotSilentlyIgnored() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "allowed": true,
                    "blocked": false
                  }
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"allowed":{"anything":true}}""")))
        assertNotNull(validator.validate(call("""{"blocked":1}""")))
    }

    @Test
    fun canonicalOperationRequiresRelatedFields() {
        val validator = validatorFor(
            name = "ui_action",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string"))
                    .put("x", JSONObject().put("type", "integer"))
                    .put("y", JSONObject().put("type", "integer"))
                    .put("index", JSONObject().put("type", "integer"))
                    .put("observation_id", JSONObject().put("type", "string"))
                )
                .put("additionalProperties", false)
                .put("required", JSONArray().put("action")),
        )
        assertNotNull(validator.validate(AgentModelClient.ToolCall("1", "ui_action", "{\"action\":\"tap\",\"index\":1}")))
        assertNull(validator.validate(AgentModelClient.ToolCall("2", "ui_action", "{\"action\":\"tap\",\"x\":1,\"y\":2}")))
    }

    @Test
    fun detailedValidationReportsStructuredMetadata() {
        val validator = validatorFor(
            name = "ui_action",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray().put("tap")))
                    .put("x", JSONObject().put("type", "integer"))
                    .put("y", JSONObject().put("type", "integer"))
                )
                .put("additionalProperties", false)
                .put("required", JSONArray().put("action")),
        )

        val missing = validator.validateDetailed(
            AgentModelClient.ToolCall("missing", "ui_action", "{\"action\":\"tap\"}"),
        )
        assertEquals("", missing.expected)
        assertEquals(listOf("x", "y"), missing.missing)

        val typeMismatch = validator.validateDetailed(
            AgentModelClient.ToolCall("type", "ui_action", "{\"action\":\"tap\",\"x\":\"one\",\"y\":2}"),
        )
        assertTrue(typeMismatch.message.contains("类型应为"))
        assertEquals("整数", typeMismatch.expected)
        assertEquals("\"one\"", typeMismatch.received)
    }

    @Test
    fun fileOpsMissingOrNullOperationIsRejectedAsMissingRequiredField() {
        val validator = validatorFor(
            name = "file_ops",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("operation", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("read").put("write")))
                    .put("path", JSONObject().put("type", "string"))
                )
                .put("additionalProperties", false)
                .put("required", JSONArray().put("operation")),
        )
        fun fileOps(arguments: String) = AgentModelClient.ToolCall(
            id = "file-ops-$arguments",
            name = "file_ops",
            argumentsJson = arguments,
        )

        // 缺键与显式 null 都按“缺少必填字段 operation”拒绝，并回传结构化 missing。
        listOf("{}", """{"operation":null}""").forEach { arguments ->
            val outcome = validator.validateDetailed(fileOps(arguments))
            assertTrue("arguments=$arguments → ${outcome.message}", outcome.message.contains("缺少必填字段 operation"))
            assertEquals(listOf("operation"), outcome.missing)
        }
        // 空白串由枚举约束拒绝（不在允许值集合中），同样不会进入执行。
        val blank = validator.validateDetailed(fileOps("""{"operation":""}"""))
        assertTrue("空白 operation 必须被拒：${blank.message}", blank.message.contains("operation"))
        // 合法调用不受影响。
        assertNull(validator.validate(fileOps("""{"operation":"read","path":"a.txt"}""")))
    }

    @Test
    fun fileOpsDeleteRequiresPathAndAcceptsItWhenPresent() {
        val validator = validatorFor(
            name = "file_ops",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("operation", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("read").put("list").put("delete")))
                    .put("path", JSONObject().put("type", "string"))
                    .put("recursive", JSONObject().put("type", "boolean")))
                .put("required", JSONArray().put("operation")),
        )
        fun fileOps(arguments: String) = AgentModelClient.ToolCall(
            id = "file-ops-delete-$arguments",
            name = "file_ops",
            argumentsJson = arguments,
        )

        // delete 在执行层已映射到 delete_path，校验层只应要求 path。
        val missingPath = validator.validateDetailed(fileOps("""{"operation":"delete"}"""))
        assertTrue("缺 path 必须被拦下：${missingPath.message}", missingPath.message.contains("缺少必填字段 path"))
        assertEquals(listOf("path"), missingPath.missing)
        assertNull(validator.validate(fileOps("""{"operation":"delete","path":"/workspace/gone.txt"}""")))
        assertNull(validator.validate(fileOps("""{"operation":"delete","path":"/workspace/tmp","recursive":true}""")))
    }

    @Test
    fun realCatalogFileOpsRejectsMissingOperationBeforeDispatch() {
        // 真实工具表链路：即使模型漏发整个 operation 键，也在执行分发前被同一套 schema 拦截。
        val tools = AgentToolCatalog.build(terminalTools = true, browserTools = false)
        val hasFileOps = (0 until tools.length()).any { index ->
            tools.optJSONObject(index)?.optJSONObject("function")?.optString("name") == "file_ops"
        }
        assertTrue("真实目录应包含 file_ops", hasFileOps)
        val validator = AgentToolCallValidator(tools)
        val outcome = validator.validateDetailed(
            AgentModelClient.ToolCall(
                id = "real-file-ops",
                name = "file_ops",
                argumentsJson = """{"path":"/workspace/a.txt"}""",
            ),
        )
        assertTrue("真实 schema 应报缺少 operation：${outcome.message}", outcome.message.contains("缺少必填字段 operation"))
        assertEquals(listOf("operation"), outcome.missing)
        assertTrue("示例必须带 operation 供模型修复", outcome.example.has("operation"))
    }

    @Test
    fun detailedValidationCachesOneValidationPerCall() {
        val validator = validator(
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("query")),
        )
        val call = AgentModelClient.ToolCall("call-cache", TOOL_NAME, "{}")

        val first = validator.validateDetailed(call)
        val second = validator.validateDetailed(call)

        assertEquals(first.message, second.message)
        assertEquals(1, validator.cachedValidationCount())
    }

    @Test
    fun malformedArgumentsProduceFixableOutcomeWithReceivedPreview() {
        val validator = validator(JSONObject().put("type", "object"))
        val outcome = validator.validateDetailed(AgentModelClient.ToolCall("bad", TOOL_NAME, "{not-json"))

        assertTrue(outcome.message.contains("JSON object"))
        assertEquals("对象", outcome.expected)
        assertTrue(outcome.received.contains("not-json"))
    }

    @Test
    fun minimalExampleUsesSchemaRequiredFieldsAndEnums() {
        val validator = validatorFor(
            name = "ui_action",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray().put("tap")))
                    .put("count", JSONObject().put("type", "integer")),
                )
                .put("required", JSONArray().put("action").put("count")),
        )

        val example = validator.minimalExample("ui_action")
        assertEquals("tap", example.getString("action"))
        assertEquals(1, example.getInt("count"))
    }

    private fun validator(parameters: JSONObject): AgentToolCallValidator =
        validatorFor(TOOL_NAME, parameters)

    private fun validatorFor(name: String, parameters: JSONObject): AgentToolCallValidator =
        AgentToolCallValidator(
            JSONArray().put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("parameters", parameters),
                    )
            )
        )

    private fun call(argumentsJson: String) = AgentModelClient.ToolCall(
        id = "call-1",
        name = TOOL_NAME,
        argumentsJson = argumentsJson,
    )

    private companion object {
        const val TOOL_NAME = "test_tool"
    }
}
