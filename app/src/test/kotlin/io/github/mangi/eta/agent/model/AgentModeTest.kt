package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeTest {
    @Test
    fun wireValueRoundTripFallsBackToChat() {
        assertEquals(AgentMode.CHAT, AgentMode.fromWireValue("chat"))
        assertEquals(AgentMode.CODING, AgentMode.fromWireValue("coding"))
        assertEquals(AgentMode.CHAT, AgentMode.fromWireValue(null))
        assertEquals(AgentMode.CHAT, AgentMode.fromWireValue("unknown"))
        assertEquals("chat", AgentMode.CHAT.wireValue)
        assertEquals("coding", AgentMode.CODING.wireValue)
    }

    @Test
    fun onlyChatModeActivelySavesMemory() {
        assertTrue(AgentMode.CHAT.memoryWritable)
        assertFalse(AgentMode.CODING.memoryWritable)
    }

    @Test
    fun currentModeFallsBackToChatWhenPreferencesAreUnavailable() {
        // 纯 JVM 测试里本地 prefs 未初始化；读取失败必须回退聊天模式而不是抛错。
        assertEquals(AgentMode.CHAT, AgentMode.current())
    }
}
