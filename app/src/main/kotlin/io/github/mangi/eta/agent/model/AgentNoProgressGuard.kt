package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.security.MessageDigest

/** Detects a repeated tool request without imposing a task or token quota. */
internal class AgentNoProgressGuard {
    private var lastFingerprint: String? = null
    private var streak = 0
    private var correctionIssued = false
    private var lastReadFingerprint: String? = null
    private var readStreak = 0
    private var readHintIssued = false

    /**
     * [reject] 为 true 时调用被拦截；[softHint] 非空时调用照常执行，但结果会带上换策略提示。
     * 两者互斥。
     */
    data class Decision(
        val reject: Boolean = false,
        val message: String = "",
        val softHint: String = "",
    )

    @Synchronized
    fun before(call: AgentModelClient.ToolCall): Decision {
        val fingerprint = sha256(call.name + "\n" + canonical(call.argumentsJson))
        // Repeated reads are often legitimate polling/refresh operations: keep executing them,
        // but after the same read repeats without changing anything, attach a strategy hint
        // instead of a hard block. Hard blocks stay reserved for side-effecting calls.
        if (isReadOnly(call)) {
            if (fingerprint == lastReadFingerprint) {
                readStreak++
            } else {
                lastReadFingerprint = fingerprint
                readStreak = 1
                readHintIssued = false
            }
            if (readStreak < READ_HINT_STREAK || readHintIssued) return Decision()
            readHintIssued = true
            return Decision(
                softHint = "同一读取已连续重复 $readStreak 次且参数未变；若仍拿不到新信息，" +
                    "请换观察、参数或方法，不要继续原样重复。",
            )
        }
        if (fingerprint == lastFingerprint) streak++ else {
            lastFingerprint = fingerprint
            streak = 1
            correctionIssued = false
        }
        if (streak < 3) return Decision()
        if (!correctionIssued) {
            correctionIssued = true
            return Decision(
                reject = true,
                message = "检测到相同工具参数连续重复且没有推进。请改用新的观察、参数或方法；" +
                    "已完成结果保留，不要重放副作用操作。",
            )
        }
        return Decision(
            reject = true,
            message = "检测到同一工具调用仍无进展，已暂停该重复分支。" +
                "请补充指令或继续时先改变策略；这不是任务总配额限制。",
        )
    }

    private fun isReadOnly(call: AgentModelClient.ToolCall): Boolean {
        // file_ops 的写操作有副作用：只有读/搜索/列目录算只读，其余工具按白名单。
        if (call.name == "file_ops") {
            val operation = call.parsedArgsOrNull()?.optString("operation").orEmpty()
            return operation in FILE_READ_OPERATIONS
        }
        return call.name in READ_ONLY_TOOLS
    }

    private fun canonical(raw: String): String = runCatching { JSONObject(raw).toString() }.getOrDefault(raw)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val READ_HINT_STREAK = 3
        val FILE_READ_OPERATIONS = setOf("read", "search", "list")
        val READ_ONLY_TOOLS = setOf(
            "observe_screen", "device_info", "app_action", "web_search", "skill", "memory",
            "read_image", "file_ops", "get_current_context", "search_apps", "read_file",
            "list_directory", "search_code", "skills_list", "skills_read", "skills_read_resource",
        )
    }
}
