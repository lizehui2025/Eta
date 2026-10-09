package io.github.mangi.eta.agent.model

/**
 * 一次工具审批的结论。
 *
 * 旧回调只回布尔值，拒绝原因在传递中被丢弃，模型与用户都只看到千篇一律的
 * `TOOL_REVIEW_REJECTED`。这里让允许与拒绝都能携带原因、拒绝码与敏感分级，
 * 由 [AgentLoop] 原样写进回给模型的工具结果。
 */
internal sealed interface ToolApprovalDecision {
    val allowed: Boolean
    val reason: String

    /** 拒绝时回给模型的错误码；允许时为 null（沿用既有 code 契约）。 */
    val code: String?

    /** 敏感分级（如 [ToolSensitivityGate.TIER_CREDENTIAL]）；非敏感类工具为 null。 */
    val sensitivityTier: String?

    /** 命中的判定闸门名，便于事后审计“这次为什么放行/拒绝”。 */
    val gate: String?

    data class Allow(
        override val reason: String = "",
        override val sensitivityTier: String? = null,
        override val gate: String? = null,
    ) : ToolApprovalDecision {
        override val allowed: Boolean get() = true
        override val code: String? get() = null
    }

    data class Reject(
        override val reason: String,
        override val code: String? = ToolApprovalDecision.CODE_REVIEW_REJECTED,
        override val sensitivityTier: String? = null,
        override val gate: String? = null,
    ) : ToolApprovalDecision {
        override val allowed: Boolean get() = false
    }

    companion object {
        /** 审核拒绝沿用既有错误码，避免破坏模型侧与测试依赖的契约。 */
        const val CODE_REVIEW_REJECTED = "TOOL_REVIEW_REJECTED"
    }
}

/**
 * 凭据材料工具的确定性审批闸门。
 *
 * 这类工具的返回值本身就是凭据（验证码、Wi-Fi 密码、剪贴板内容），审批口径必须与模型裁量无关：
 * 只有当前用户指令显式表达了对应数据类意图才放行，否则一律拒绝并说明原因。
 * 非凭据类工具返回 null，表示“闸门不干预”，仍走原有的自动/人工审核路径。
 */
internal object ToolSensitivityGate {
    const val TIER_CREDENTIAL = "credential"
    const val GATE_INTENT_MATCHED = "intent_matched"
    const val GATE_NO_INTENT = "no_intent"

    private val clipboardIntents = listOf(
        "剪贴板", "剪切板", "粘贴板", "clipboard",
        "我复制", "刚复制", "刚才复制", "最新复制", "复制的内容", "粘贴的内容",
        "copiedtext", "pastebuffer",
    )

    /**
     * 工具实名 → 该工具所读取的数据类意图关键词。
     *
     * 关键词一律写成**归一化形式**（小写、无空白、无连字符），比对前两侧都会归一化，
     * 因此 "Wi-Fi 密码"、"wifi password"、"WLAN 密码" 都能命中。工具名取自
     * [AgentSensitiveToolPolicy] 中登记的工具实名（与工具注册名一致）；新增同类工具只需加一行。
     */
    private val credentialIntents: Map<String, List<String>> = mapOf(
        "read_sms_code" to listOf(
            "验证码", "短信码", "短信验证", "校验码", "动态码", "一次性密码",
            "otp", "verificationcode", "smscode", "onetimepassword", "2fa", "两步验证",
        ),
        "wifi_credentials" to listOf(
            "wifi密码", "wifi的密码", "wifi口令", "wifi密码是多少", "wlan密码", "无线密码",
            "无线网密码", "无线网络的密码", "网络密码", "热点密码", "路由器密码", "ssid",
            "wifipassword", "wifikey", "networkpassword", "wirelesspassword",
        ),
        "get_clipboard" to clipboardIntents,
        "search_clipboard_history" to clipboardIntents,
    )

    /** 凭据类工具实名集合（判定恒定，不受模型输出影响）。 */
    fun credentialTools(): Set<String> = credentialIntents.keys

    fun isCredentialTool(toolName: String): Boolean = credentialIntents.containsKey(toolName)

    /**
     * 确定性判定入口。
     *
     * 返回 null 表示不干预（非凭据类工具，交给原有审核路径）；
     * 凭据类工具只按 [userInstruction] 是否显式包含对应意图关键词决定放行或拒绝。
     */
    fun evaluate(toolName: String, userInstruction: String): ToolApprovalDecision? {
        val keywords = credentialIntents[toolName] ?: return null
        if (containsIntent(userInstruction, keywords)) {
            return ToolApprovalDecision.Allow(
                reason = "当前指令显式要求${intentLabel(toolName)}，凭据类工具按意图放行。",
                sensitivityTier = TIER_CREDENTIAL,
                gate = GATE_INTENT_MATCHED,
            )
        }
        return ToolApprovalDecision.Reject(
            reason = "工具属于凭据类（$TIER_CREDENTIAL）、当前指令未显式要求${intentLabel(toolName)}；" +
                "需要时请先让用户表达意图，或改用 terminal 由用户手动确认。",
            sensitivityTier = TIER_CREDENTIAL,
            gate = GATE_NO_INTENT,
        )
    }

    /** 归一化后做子串包含判断：大小写、空白与连字符差异不影响结论。 */
    fun containsIntent(userInstruction: String, keywords: List<String>): Boolean {
        val text = normalize(userInstruction)
        if (text.isEmpty()) return false
        return keywords.any { keyword -> keyword.isNotBlank() && text.contains(normalize(keyword)) }
    }

    private fun intentLabel(toolName: String): String = when (toolName) {
        "read_sms_code" -> "短信验证码"
        "wifi_credentials" -> "Wi-Fi 密码"
        else -> "剪贴板内容"
    }

    private fun normalize(text: String): String =
        text.lowercase().filterNot { it.isWhitespace() || it == '-' || it == '_' }
}
