package io.github.mangi.eta.agent.terminal

import java.util.regex.Pattern

/** search_code 的跨实现共享约束与匹配辅助：Root 路径交给 grep，非 Root 路径在应用内扫描。 */
internal object AgentCodeSearch {
    const val MAX_PATTERN_CHARS = 200
    const val MAX_RESULTS = 200
    const val MAX_LINE_CHARS = 400
    const val MAX_FILE_BYTES = 512 * 1024
    const val MAX_SCAN_FILES = 3_000
    const val MAX_VISITED_FILES = 20_000
    const val MAX_ENTRIES_TEXT_CHARS = 16_000

    /** 逗号分隔的文件名通配列表，支持 *.ext、*片段* 与精确文件名。 */
    fun compileGlobs(glob: String?): List<Regex> =
        glob.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                val builder = StringBuilder()
                token.forEach { ch ->
                    when (ch) {
                        '*' -> builder.append(".*")
                        '?' -> builder.append('.')
                        else -> builder.append(Pattern.quote(ch.toString()))
                    }
                }
                Regex(builder.toString())
            }

    fun matchesGlobs(globs: List<Regex>, fileName: String): Boolean =
        globs.isEmpty() || globs.any { it.matches(fileName) }

    fun entry(path: String, lineNumber: Int, text: String): String =
        path + ":" + lineNumber + ":" + text.trimEnd().truncateByCodePoints(MAX_LINE_CHARS)

    private val GREP_LINE = Regex("^(.+?):(\\d+):(.*)$")

    /** 解析 grep -n 的 `path:line:text` 输出；文件名含冒号时仍能定位行号段。 */
    fun parseGrepLine(line: String): Triple<String, Int, String>? {
        val match = GREP_LINE.matchEntire(line) ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null
        return Triple(match.groupValues[1], number, match.groupValues[3])
    }
}
