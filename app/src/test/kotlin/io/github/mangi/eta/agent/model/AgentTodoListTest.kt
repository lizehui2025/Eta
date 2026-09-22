package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTodoListTest {
    private val events = mutableListOf<AgentEvent>()

    private fun write(json: String): AgentModelClient.ToolResult =
        AgentTodoList().write(
            round = 2,
            toolCall = AgentModelClient.ToolCall("call-1", AgentTodoList.TOOL_NAME, json),
            onEvent = { events += it },
        )

    @Test
    fun writeReplacesListAndEmitsProgressEvent() {
        val first = write(
            """
            {"todos":[
              {"content":"a","status":"completed"},
              {"content":"b","status":"in_progress"},
              {"content":"c","status":"pending"}
            ]}
            """.trimIndent(),
        )
        val firstJson = JSONObject(first.content)
        assertTrue(firstJson.getBoolean("ok"))
        assertEquals(3, firstJson.getInt("total"))
        assertEquals(1, firstJson.getInt("completed"))
        val event = events.single() as AgentEvent.TodoUpdated
        assertEquals(2, event.round)
        assertEquals("call-1", event.toolCallId)
        assertEquals(3, event.total)
        assertEquals(1, event.completed)
        assertEquals("b", event.current)

        val second = write("""{"todos":[{"content":"d","status":"in_progress"}]}""")
        val secondJson = JSONObject(second.content)
        assertEquals(1, secondJson.getInt("total"))
        assertEquals(0, secondJson.getInt("completed"))
        assertEquals(2, events.size)
        assertEquals("d", (events.last() as AgentEvent.TodoUpdated).current)
    }

    @Test
    fun validationRejectsMalformedItems() {
        fun code(json: String): String =
            JSONObject(write(json).content).getString("code")

        assertEquals("INVALID_ARGUMENT", code("{}"))
        assertEquals("INVALID_ARGUMENT", code("""{"todos":[]}"""))
        assertEquals("INVALID_ARGUMENT", code("""{"todos":[{"content":"","status":"pending"}]}"""))
        assertEquals("INVALID_ARGUMENT", code("""{"todos":[{"content":"a","status":"doing"}]}"""))
        val tooLong = "x".repeat(AgentTodoList.MAX_CONTENT_CHARS + 1)
        assertEquals("INVALID_ARGUMENT", code("""{"todos":[{"content":"$tooLong","status":"pending"}]}"""))
        val tooMany = (1..AgentTodoList.MAX_ITEMS + 1).joinToString(",") {
            """{"content":"t$it","status":"pending"}"""
        }
        assertEquals("INVALID_ARGUMENT", code("""{"todos":[$tooMany]}"""))
        assertTrue(events.isEmpty())
    }
}
