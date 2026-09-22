package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCallArgsCacheTest {
    @Test
    fun parsedArgsAreCachedAndReused() {
        val call = AgentModelClient.ToolCall(
            id = "call-1",
            name = "read_file",
            argumentsJson = """{"path":"/a/b.txt"}""",
        )

        val first = call.parsedArgsOrNull()
        assertNotNull(first)
        assertEquals("/a/b.txt", first!!.optString("path"))
        // 同一 ToolCall 多次读取复用同一解析结果，避免每层重复 JSONObject 解析。
        assertSame(first, call.parsedArgsOrNull())
        assertTrue(call.parsedArgs().isSuccess)
    }

    @Test
    fun blankArgumentsParseAsEmptyObject() {
        val call = AgentModelClient.ToolCall(
            id = "call-2",
            name = "get_current_context",
            argumentsJson = "",
        )

        assertNotNull(call.parsedArgsOrNull())
        assertEquals(0, call.parsedArgsOrNull()!!.length())
    }

    @Test
    fun invalidArgumentsFailOnceAndStayFailed() {
        val call = AgentModelClient.ToolCall(
            id = "call-3",
            name = "read_file",
            argumentsJson = "{not-json",
        )

        assertTrue(call.parsedArgs().isFailure)
        assertNull(call.parsedArgsOrNull())
        assertTrue(call.parsedArgs().isFailure)
    }
}
