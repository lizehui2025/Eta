package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 记忆写入拒绝策略是纯函数，可以在无 Android 环境的测试里直接验证。 */
class MemoryWritePolicyTest {
    @Test
    fun chatModeAllowsWriting() {
        assertNull(memoryWriteBlockedReasonFor(roleplay = false, mode = AgentMode.CHAT))
    }

    @Test
    fun codingModeBlocksWritingWithModeHint() {
        val reason = memoryWriteBlockedReasonFor(roleplay = false, mode = AgentMode.CODING)
        assertEquals(CODING_MEMORY_READ_ONLY_REASON, reason)
    }

    @Test
    fun roleplayStaysReadOnlyInEitherMode() {
        assertEquals(
            ROLEPLAY_MEMORY_READ_ONLY_REASON,
            memoryWriteBlockedReasonFor(roleplay = true, mode = AgentMode.CHAT),
        )
        // 角色会话优先给出剧情工具提示，即使同时处于编码模式。
        assertEquals(
            ROLEPLAY_MEMORY_READ_ONLY_REASON,
            memoryWriteBlockedReasonFor(roleplay = true, mode = AgentMode.CODING),
        )
    }
}
