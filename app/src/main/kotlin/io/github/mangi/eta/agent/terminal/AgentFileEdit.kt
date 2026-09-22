package io.github.mangi.eta.agent.terminal

/**
 * 文本精确替换的编辑语义；不接触文件系统，便于单元测试。
 * 供 edit_file 工具在 Root 与非 Root 两条路径共用。
 */
internal object AgentFileEdit {
    sealed interface Outcome {
        data class Applied(val content: String, val replacements: Int) : Outcome
        data class Rejected(val code: String, val message: String) : Outcome
    }

    fun apply(
        content: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): Outcome {
        if (oldString.isEmpty()) {
            return Outcome.Rejected("INVALID_ARGUMENT", "old_string 不能为空")
        }
        if (oldString == newString) {
            return Outcome.Rejected("INVALID_ARGUMENT", "old_string 与 new_string 相同，未做修改")
        }
        var cursor = content.indexOf(oldString)
        if (cursor < 0) {
            return Outcome.Rejected("NO_MATCH", "未找到 old_string；请确认文本与目标文件一致（含空白与缩进）")
        }
        val firstIndex = cursor
        var count = 0
        while (cursor >= 0) {
            count++
            cursor = content.indexOf(oldString, cursor + oldString.length)
        }
        if (count > 1 && !replaceAll) {
            return Outcome.Rejected(
                "MULTI_MATCH",
                "old_string 匹配到 $count 处；请提供更精确的上下文，或设置 replace_all=true 全部替换",
            )
        }
        val updated = if (replaceAll) {
            content.replace(oldString, newString)
        } else {
            content.substring(0, firstIndex) + newString + content.substring(firstIndex + oldString.length)
        }
        return Outcome.Applied(updated, if (replaceAll) count else 1)
    }
}
