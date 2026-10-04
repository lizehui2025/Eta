package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNoProgressGuardTest {
    @Test
    fun sideEffectingCallIsHardBlockedOnThirdIdenticalAttempt() {
        val guard = AgentNoProgressGuard()
        val call = AgentModelClient.ToolCall("1", "device_control", """{"operation":"volume"}""")

        assertFalse(guard.before(call).reject)
        assertFalse(guard.before(call).reject)
        val blocked = guard.before(call)
        assertTrue(blocked.reject)
        assertTrue(blocked.message.contains("连续重复"))
    }

    @Test
    fun correctionMessageIsIssuedOncePerRepeatStreak() {
        val guard = AgentNoProgressGuard()
        val call = AgentModelClient.ToolCall("1", "terminal", """{"action":"open"}""")

        repeat(2) { guard.before(call) }
        val firstBlock = guard.before(call)
        val secondBlock = guard.before(call)
        assertTrue(firstBlock.reject)
        assertTrue(secondBlock.reject)
        assertTrue(firstBlock.message.contains("请改用新的观察"))
        assertTrue(secondBlock.message.contains("已暂停该重复分支"))
    }

    @Test
    fun changingArgumentsResetsTheHardBlockStreak() {
        val guard = AgentNoProgressGuard()
        guard.before(AgentModelClient.ToolCall("1", "terminal", """{"action":"open"}"""))
        guard.before(AgentModelClient.ToolCall("2", "terminal", """{"action":"open"}"""))
        guard.before(AgentModelClient.ToolCall("3", "terminal", """{"action":"list"}"""))

        val decision = guard.before(AgentModelClient.ToolCall("4", "terminal", """{"action":"list"}"""))
        assertFalse(decision.reject)
    }

    @Test
    fun repeatedReadIsHintedNotBlocked() {
        val guard = AgentNoProgressGuard()
        val call = AgentModelClient.ToolCall("1", "read_file", """{"path":"/workspace/a.txt"}""")

        assertFalse(guard.before(call).reject)
        assertTrue(guard.before(call).softHint.isEmpty())
        val hinted = guard.before(call)
        assertFalse(hinted.reject)
        assertTrue(hinted.softHint.contains("同一读取已连续重复"))
        // 提示只发一次，避免每轮刷屏。
        assertTrue(guard.before(call).softHint.isEmpty())
    }

    @Test
    fun readOnlyFileOperationIsHintedButWriteIsBlocked() {
        val guard = AgentNoProgressGuard()
        val read = AgentModelClient.ToolCall("1", "file_ops", """{"operation":"read","path":"/workspace/a.txt"}""")
        repeat(2) { guard.before(read) }
        assertTrue(guard.before(read).softHint.isNotEmpty())

        val write = AgentModelClient.ToolCall("2", "file_ops", """{"operation":"write","path":"/workspace/a.txt"}""")
        repeat(2) { guard.before(write) }
        assertTrue(guard.before(write).reject)
    }

    @Test
    fun changingReadArgumentsResetsTheHint() {
        val guard = AgentNoProgressGuard()
        repeat(3) {
            guard.before(AgentModelClient.ToolCall("$it", "read_file", """{"path":"/workspace/a.txt"}"""))
        }
        val changed = guard.before(AgentModelClient.ToolCall("9", "read_file", """{"path":"/workspace/b.txt"}"""))
        assertEquals("", changed.softHint)
        assertFalse(changed.reject)
    }
}
