package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.security.MessageDigest

/** Detects a repeated tool request without imposing a task or token quota. */
internal class AgentNoProgressGuard {
    private var lastFingerprint: String? = null
    private var streak = 0
    private var correctionIssued = false

    data class Decision(val reject: Boolean, val message: String)

    @Synchronized
    fun before(call: AgentModelClient.ToolCall): Decision {
        // Repeated reads are often legitimate polling/refresh operations. Guard actions with
        // possible side effects or UI state changes; read-only evidence can repeat while state
        // evolves and must not consume a task's progress budget.
        if (call.name in READ_ONLY_TOOLS) return Decision(false, "")
        val fingerprint = sha256(call.name + "\n" + canonical(call.argumentsJson))
        if (fingerprint == lastFingerprint) streak++ else {
            lastFingerprint = fingerprint
            streak = 1
            correctionIssued = false
        }
        if (streak < 3) return Decision(false, "")
        if (!correctionIssued) {
            correctionIssued = true
            return Decision(true, "检测到相同工具参数连续重复且没有推进。请改用新的观察、参数或方法；已完成结果保留，不要重放副作用操作。")
        }
        return Decision(true, "检测到同一工具调用仍无进展，已暂停该重复分支。请补充指令或继续时先改变策略；这不是任务总配额限制。")
    }

    private fun canonical(raw: String): String = runCatching { JSONObject(raw).toString() }.getOrDefault(raw)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val READ_ONLY_TOOLS = setOf(
            "observe_screen", "device_info", "app_action", "web_search", "skill", "memory",
            "read_image", "file_ops", "get_current_context", "search_apps", "read_file",
            "list_directory", "search_code", "skills_list", "skills_read", "skills_read_resource",
        )
    }
}
