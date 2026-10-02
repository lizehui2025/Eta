package io.github.mangi.eta.agent.model

/**
 * 文件写入展示用的轻量变更摘要。
 *
 * 只做共同前缀/后缀行裁剪，不做完整 LCS diff；输出有界，供写入工具的展开详情
 * 与子代理步骤详情复用。非文本内容（含 NUL）返回 null，由调用方回退到字节信息。
 */
internal object AgentTextDiff {
    const val MAX_LINE_CHARS = 120
    const val MAX_SEGMENT_LINES = 8

    fun isTextual(text: String): Boolean = !text.contains('\u0000')

    /**
     * 生成 old → new 的变更摘要；两段文本相同返回 null。
     * [maxChars] 为输出预算，超限截断。
     */
    fun summarize(oldText: String, newText: String, maxChars: Int = 1_200): String? {
        if (!isTextual(oldText) || !isTextual(newText)) return null
        if (oldText == newText) return null
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        val prefix = commonPrefixLines(oldLines, newLines)
        val suffix = commonSuffixLines(oldLines, newLines, prefix)
        val oldMid = oldLines.subList(prefix, oldLines.size - suffix)
        val newMid = newLines.subList(prefix, newLines.size - suffix)
        val builder = StringBuilder()
        builder.append("第 ${prefix + 1} 行起：旧 ${oldMid.size} 行 → 新 ${newMid.size} 行")
        if (suffix > 0) builder.append("（开头 $prefix 行与结尾 $suffix 行不变）")
        appendSegment(builder, "- ", oldMid)
        appendSegment(builder, "+ ", newMid)
        return builder.toString().bounded(maxChars)
    }

    /** edit_file 用：直接展示 old_string → new_string 两段文本。 */
    fun summarizeReplacement(oldText: String, newText: String, maxChars: Int = 1_200): String? {
        if (!isTextual(oldText) || !isTextual(newText)) return null
        if (oldText == newText) return "内容无变化"
        val builder = StringBuilder()
        appendSegment(builder, "- ", oldText.split('\n'))
        appendSegment(builder, "+ ", newText.split('\n'))
        return builder.toString().bounded(maxChars)
    }

    /**
     * 展开工具详情使用的完整替换内容。
     *
     * 与面向摘要的 [summarizeReplacement] 不同，这里不按行、字符或片段做预算；
     * 调用方会在 UI 中放入可滚动容器，避免用省略号隐藏已经返回的工具信息。
     */
    fun summarizeReplacementFull(oldText: String, newText: String): String? {
        if (!isTextual(oldText) || !isTextual(newText)) return null
        if (oldText == newText) return "内容无变化"
        return buildString {
            append("旧内容：")
            oldText.split('\n').forEach { append('\n').append("- ").append(it) }
            append("\n新内容：")
            newText.split('\n').forEach { append('\n').append("+ ").append(it) }
        }
    }

    private fun commonPrefixLines(a: List<String>, b: List<String>): Int {
        val max = minOf(a.size, b.size)
        var index = 0
        while (index < max && a[index] == b[index]) index++
        return index
    }

    private fun commonSuffixLines(a: List<String>, b: List<String>, prefix: Int): Int {
        val max = minOf(a.size, b.size) - prefix
        var index = 0
        while (index < max && a[a.size - 1 - index] == b[b.size - 1 - index]) index++
        return index
    }

    private fun appendSegment(builder: StringBuilder, prefix: String, lines: List<String>) {
        val shown = lines.take(MAX_SEGMENT_LINES)
        shown.forEach { line ->
            builder.append('\n').append(prefix).append(ellipsize(line))
        }
        if (lines.size > shown.size) {
            builder.append('\n').append(prefix).append("…（其余 ${lines.size - shown.size} 行省略）")
        }
    }

    private fun ellipsize(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…"

    private fun String.bounded(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars) + "\n…"
}
