package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAccessibilityKeeperTest {
    @Test
    fun `stale protection setting without framework does not request recovery`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { error("不应请求未连接的保护后端") },
            awaitServiceBinding = { error("不应等待未发起的恢复") },
            protectionAvailable = { false },
            rootAvailable = { false },
        )

        assertEquals("ACCESSIBILITY_UNAVAILABLE", result.code)
        assertFalse(result.recoveryRequested)
    }

    @Test
    fun `connected service skips protection recovery`() {
        var recoveryCalls = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { true },
            protectionEnabled = { true },
            requestRecovery = {
                recoveryCalls++
                true
            },
            awaitServiceBinding = { false },
            rootAvailable = { false },
        )

        assertTrue(result.available)
        assertFalse(result.recoveryRequested)
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun `disabled protection rejects without changing settings`() {
        var recoveryCalls = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { false },
            requestRecovery = {
                recoveryCalls++
                true
            },
            awaitServiceBinding = { true },
            rootAvailable = { false },
        )

        assertFalse(result.available)
        assertFalse(result.recoveryRequested)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", result.code)
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun `unavailable system backend rejects the gui operation`() {
        var bindingChecks = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { false },
            awaitServiceBinding = {
                bindingChecks++
                true
            },
            rootAvailable = { false },
        )

        assertFalse(result.available)
        assertTrue(result.recoveryRequested)
        assertEquals("ACCESSIBILITY_PROTECTION_UNAVAILABLE", result.code)
        assertEquals(0, bindingChecks)
    }

    @Test
    fun `approved recovery waits for the real service binding`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { true },
            awaitServiceBinding = { true },
            rootAvailable = { false },
        )

        assertTrue(result.available)
        assertFalse(result.degraded)
        assertTrue(result.recoveryRequested)
    }

    @Test
    fun `binding timeout rejects after bounded system recovery`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { true },
            awaitServiceBinding = { false },
            rootAvailable = { false },
        )

        assertFalse(result.available)
        assertTrue(result.recoveryRequested)
        assertEquals("ACCESSIBILITY_REPAIR_TIMEOUT", result.code)
    }

    @Test
    fun `connected service does not consult the root probe`() {
        var rootChecks = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { true },
            protectionEnabled = { false },
            requestRecovery = { false },
            awaitServiceBinding = { false },
            rootAvailable = {
                rootChecks++
                false
            },
        )

        assertTrue(result.available)
        assertFalse(result.degraded)
        assertEquals(0, rootChecks)
    }

    @Test
    fun `root fallback degrades when accessibility and protection are unavailable`() {
        var recoveryCalls = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = {
                recoveryCalls++
                true
            },
            awaitServiceBinding = { error("保护后端不可用时不应等待重绑") },
            protectionAvailable = { false },
            rootAvailable = { true },
        )

        assertTrue(result.available)
        assertTrue(result.degraded)
        assertFalse(result.recoveryRequested)
        assertTrue(result.degradedReason.isNotBlank())
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun `root fallback degrades after the protection backend fails to recover`() {
        var bindingChecks = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { false },
            awaitServiceBinding = {
                bindingChecks++
                true
            },
            protectionAvailable = { true },
            rootAvailable = { true },
        )

        assertTrue(result.available)
        assertTrue(result.degraded)
        assertTrue(result.recoveryRequested)
        assertTrue(result.degradedReason.isNotBlank())
        assertEquals(0, bindingChecks)
    }

    @Test
    fun `root fallback degrades when the recovery binding times out`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { true },
            awaitServiceBinding = { false },
            protectionAvailable = { true },
            rootAvailable = { true },
        )

        assertTrue(result.available)
        assertTrue(result.degraded)
        assertTrue(result.recoveryRequested)
        assertTrue(result.degradedReason.isNotBlank())
    }

    @Test
    fun `without root the same failures stay fail closed`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { false },
            awaitServiceBinding = { true },
            protectionAvailable = { true },
            rootAvailable = { false },
        )

        assertFalse(result.available)
        assertFalse(result.degraded)
        assertEquals("ACCESSIBILITY_PROTECTION_UNAVAILABLE", result.code)
        assertTrue(result.message.contains("Root"))
    }

    @Test
    fun `failure messages carry concrete remedies`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { true },
            awaitServiceBinding = { false },
            protectionAvailable = { true },
            rootAvailable = { false },
        )

        assertEquals("ACCESSIBILITY_REPAIR_TIMEOUT", result.code)
        assertTrue(result.message.contains("无障碍"))
        assertTrue(result.message.contains("Root"))
    }
}
