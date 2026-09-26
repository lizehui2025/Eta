package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs

/**
 * Agent 交互模式（顶栏左上角切换）。
 *
 * - [CHAT]：聊天模式，正常读取并保存持久记忆（默认）；
 * - [CODING]：编码模式，记忆只读、不主动保存：工具表不暴露 memory_write，
 *   执行期写入也会被拒绝并提示切换回聊天模式；读取（memory_get 与注入）保持可用。
 *
 * 模式在运行开始时快照，影响该次 run 的提示词与工具表；写入许可在执行期复查，
 * 中途切到编码模式会立即阻止未完成的 run 继续写入。
 */
internal enum class AgentMode(val wireValue: String) {
    CHAT(Prefs.AGENT_MODE_CHAT),
    CODING(Prefs.AGENT_MODE_CODING);

    /** 是否允许主动写入持久记忆（角色会话另有只读约束，单独判断）。 */
    val memoryWritable: Boolean get() = this == CHAT

    companion object {
        val DEFAULT: AgentMode = CHAT

        fun fromWireValue(value: String?): AgentMode =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT

        /** 读取当前模式；存储不可用时回退默认模式，不影响本次运行。 */
        fun current(): AgentMode =
            runCatching { fromWireValue(Prefs.agentMode()) }.getOrDefault(DEFAULT)
    }
}
