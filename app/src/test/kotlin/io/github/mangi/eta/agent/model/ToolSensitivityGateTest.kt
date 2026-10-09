package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据类工具闸门是纯函数：判定只取决于工具实名与当前用户指令，不依赖模型输出，
 * 因此可以直接在 JVM 单测里覆盖三个分支（无意图拒绝 / 有意图放行 / 非凭据类不干预）。
 */
class ToolSensitivityGateTest {
    @Test
    fun credentialToolWithoutExplicitIntentIsRejectedDeterministically() {
        val instructions = listOf("帮我看看手机现在的情况", "整理一下今天的笔记", "")

        listOf("read_sms_code", "wifi_credentials", "get_clipboard", "search_clipboard_history")
            .forEach { tool ->
                instructions.forEach { instruction ->
                    val decision = ToolSensitivityGate.evaluate(tool, instruction)
                    assertTrue("$tool + \"$instruction\" 应被拒绝", decision is ToolApprovalDecision.Reject)
                    val reject = decision as ToolApprovalDecision.Reject
                    assertFalse(reject.allowed)
                    assertEquals(ToolApprovalDecision.CODE_REVIEW_REJECTED, reject.code)
                    assertEquals(ToolSensitivityGate.TIER_CREDENTIAL, reject.sensitivityTier)
                    assertEquals(ToolSensitivityGate.GATE_NO_INTENT, reject.gate)
                    assertTrue(reject.reason.contains("凭据类"))
                    assertTrue(reject.reason.contains("terminal"))
                }
            }
    }

    @Test
    fun explicitIntentAllowsTheSameToolAndMarksTheGate() {
        val cases = mapOf(
            "read_sms_code" to listOf(
                "帮我读一下刚收到的验证码",
                "把短信验证码给我",
                "what is the OTP I just got",
                "念一下校验码",
            ),
            "wifi_credentials" to listOf(
                "当前 Wi-Fi 密码是多少",
                "告诉我 wifi密码",
                "show me the wifi password",
                "无线密码发我",
            ),
            "get_clipboard" to listOf("看看剪贴板里有什么", "read my clipboard"),
            "search_clipboard_history" to listOf("我刚才复制的那段文字是什么", "剪贴板历史"),
        )

        cases.forEach { (tool, instructions) ->
            instructions.forEach { instruction ->
                val decision = ToolSensitivityGate.evaluate(tool, instruction)
                assertTrue("$tool + \"$instruction\" 应放行", decision is ToolApprovalDecision.Allow)
                val allow = decision as ToolApprovalDecision.Allow
                assertTrue(allow.allowed)
                assertTrue(allow.code == null)
                assertEquals(ToolSensitivityGate.TIER_CREDENTIAL, allow.sensitivityTier)
                assertEquals(ToolSensitivityGate.GATE_INTENT_MATCHED, allow.gate)
            }
        }
    }

    @Test
    fun nonCredentialToolsAreNeverGated() {
        assertEquals(
            setOf("read_sms_code", "wifi_credentials", "get_clipboard", "search_clipboard_history"),
            ToolSensitivityGate.credentialTools(),
        )
        // 直接返回验证码明文的检索类工具不在凭据闸门内，仍走原有审核路径。
        assertNull(ToolSensitivityGate.evaluate("search_messages", "帮我找验证码"))
        assertNull(ToolSensitivityGate.evaluate("terminal", "把剪贴板内容写进文件"))
        assertFalse(ToolSensitivityGate.isCredentialTool("search_messages"))
        assertTrue(ToolSensitivityGate.isCredentialTool("read_sms_code"))
    }

    @Test
    fun intentMatchingIgnoresCaseWhitespaceAndHyphens() {
        assertTrue(ToolSensitivityGate.containsIntent("Current Wi-Fi Password?", listOf("wifipassword")))
        assertTrue(ToolSensitivityGate.containsIntent("WLAN 密码是多少", listOf("wlan密码")))
        assertTrue(ToolSensitivityGate.containsIntent("请读取 Clipboard", listOf("clipboard")))
        assertFalse(ToolSensitivityGate.containsIntent("帮我看看天气", listOf("验证码", "otp")))
        assertFalse(ToolSensitivityGate.containsIntent("", listOf("验证码")))
    }
}
