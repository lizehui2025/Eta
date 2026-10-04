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
        assertNotNull(validator.validate(call("""{"mode":"text","value":"two","extra":true}""")))
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
