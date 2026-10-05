package io.github.mangi.eta.agent.terminal

/**
 * 文本精确替换的编辑语义；不接触文件系统，便于单元测试。
 * 供 edit_file 工具在 Root 与非 Root 两条路径共用。
 *
 * 失败可恢复性：NO_MATCH 时附带上下文（近似行 + 行号），MULTI_MATCH 时列出匹配行号，
 * 模型据此可以一次修正 old_string，而不是盲目重读整个文件。
 * 行尾宽容：模型常按 \n 生成 old_string，而目标文件是 \r\n（或反之），
 * 直接判定 NO_MATCH 会放大失败率；这里自动按文件行尾重试一次。
 */
internal object AgentFileEdit {
    sealed interface Outcome {
        data class Applied(val content: String, val replacements: Int) : Outcome

        /**
         * [context] 为可选的可恢复性提示（只读展示，不参与匹配）：
         * NO_MATCH 时给出文件中的近似位置与行号。
         */
        data class Rejected(val code: String, val message: String, val context: String? = null) : Outcome
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
        directOutcome(content, oldString, newString, replaceAll)?.let { return it }
        // 行尾宽容重试：把 old_string/new_string 按文件主行尾转换后再匹配一次。
        if (content.contains("\r\n")) {
            val crlfOld = toCrlf(oldString)
            if (crlfOld != oldString) {
                directOutcome(content, crlfOld, toCrlf(newString), replaceAll)?.let { return it }
            }
        }
        if (oldString.contains("\r\n")) {
            val lfOld = oldString.replace("\r\n", "\n")
            directOutcome(content, lfOld, newString.replace("\r\n", "\n"), replaceAll)?.let { return it }
        }
        return Outcome.Rejected(
            "NO_MATCH",
            "未找到 old_string；请确认文本与目标文件一致（含空白与缩进）",
            context = noMatchContext(content, oldString),
        )
    }

    /** 精确匹配一次；未命中返回 null，由调用方决定是否做行尾宽容重试。 */
    private fun directOutcome(
        content: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): Outcome? {
        val matchCount = countMatches(content, oldString)
        if (matchCount == 0) return null
        if (matchCount > 1 && !replaceAll) {
            val lines = matchingLineNumbers(content, oldString)
            val listed = lines.joinToString("、") { "第 $it 行" }
            return Outcome.Rejected(
                "MULTI_MATCH",
                "old_string 匹配到 $matchCount 处（$listed）；请补充上下文使其唯一，或设置 replace_all=true 全部替换",
            )
        }
        val updated = if (replaceAll) {
            content.replace(oldString, newString)
        } else {
            val index = content.indexOf(oldString)
            content.substring(0, index) + newString + content.substring(index + oldString.length)
        }
        return Outcome.Applied(updated, if (replaceAll) matchCount else 1)
    }

    private fun countMatches(content: String, target: String): Int {
        var cursor = content.indexOf(target)
        var count = 0
        while (cursor >= 0) {
            count++
            cursor = content.indexOf(target, cursor + target.length)
        }
        return count
    }

    /** 匹配位置的行号（1 基），最多统计 [MAX_LISTED_MATCH_LINES] 处。 */
    private fun matchingLineNumbers(content: String, target: String): List<Int> {
        val numbers = mutableListOf<Int>()
        var cursor = content.indexOf(target)
        while (cursor >= 0 && numbers.size < MAX_LISTED_MATCH_LINES) {
            numbers += lineNumberAt(content, cursor)
            cursor = content.indexOf(target, cursor + target.length)
        }
        return numbers
    }

    private fun lineNumberAt(content: String, index: Int): Int {
        var line = 1
        for (i in 0 until index.coerceAtMost(content.length)) {
            if (content[i] == '\n') line++
        }
        return line
    }

    /**
     * NO_MATCH 上下文：用 old_string 首个非空行定位文件中的近似位置，
     * 返回带行号的 ±2 行片段，让模型直接看到实际空白/缩进差异。
     */
    private fun noMatchContext(content: String, oldString: String): String? {
        val needle = oldString.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_NEEDLE_CHARS)
            .orEmpty()
        if (needle.length < MIN_NEEDLE_CHARS) return null
        val lines = content.split('\n').map { it.trimEnd('\r') }
        var hit = lines.indexOfFirst { it.trim() == needle }
        if (hit < 0) {
            val prefix = needle.take(MAX_NEEDLE_PREFIX_CHARS)
            hit = lines.indexOfFirst { it.trim().contains(prefix) }
        }
        if (hit < 0) return null
        val start = (hit - CONTEXT_RADIUS).coerceAtLeast(0)
        val end = (hit + CONTEXT_RADIUS).coerceAtMost(lines.size - 1)
        val snippet = (start..end).joinToString("\n") { index ->
            "${index + 1}: ${lines[index].take(MAX_CONTEXT_LINE_CHARS)}"
        }
        return "old_string 首行在文件中的近似位置（第 ${hit + 1} 行附近，请对照实际空白与缩进）：\n$snippet"
    }

    /** LF → CRLF；已有 CRLF 的行不受影响。 */
    private fun toCrlf(value: String): String =
        value.replace("\r\n", "\n").replace("\n", "\r\n")

    private const val MAX_LISTED_MATCH_LINES = 5
    private const val MAX_NEEDLE_CHARS = 200
    private const val MIN_NEEDLE_CHARS = 4
    private const val MAX_NEEDLE_PREFIX_CHARS = 40
    private const val CONTEXT_RADIUS = 2
    private const val MAX_CONTEXT_LINE_CHARS = 160
}
