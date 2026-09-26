package io.github.mangi.eta.agent.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * SKILL.md 解析器——从 YAML frontmatter + Markdown body 中提取结构化信息。
 *
 * 支持 `>` / `|` 多行块、缩进子块，以及普通的 `key: value` 行。
 * 纯字符串处理，不依赖外部 YAML 库。
 */
internal object SkillParser {

    /**
     * 读取并解析 [skillFile]，返回 frontmatter map + body string。
     * 文件不存在、不是文件或超过 [MAX_SKILL_FILE_BYTES]（512KB，与安装期上限一致）时返回 null。
     *
     * 超限按“整份拒绝”而不是截断处理：截断会静默改变 frontmatter 与正文语义，
     * 而调用方统一按 `?: return null` 处理（索引/加载阶段跳过该 Skill）。
     */
    fun parseSkillFile(skillFile: File): ParsedSkillFile? {
        if (!skillFile.exists() || !skillFile.isFile) return null
        val raw = readSkillText(skillFile) ?: return null
        if (!raw.startsWith("---")) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val markerIndex = raw.indexOf("\n---", startIndex = 3)
        if (markerIndex <= 0) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val frontmatterText = raw.substring(3, markerIndex).trim('\n', '\r')
        val body = raw.substring(markerIndex + 4).trim()
        return ParsedSkillFile(
            frontmatter = parseSimpleFrontmatter(frontmatterText),
            body = body,
        )
    }

    /**
     * 简单 YAML frontmatter 解析。
     *
     * 支持：
     * - `key: value` 单行
     * - `key: >` 折叠多行块
     * - `key: |` 字面多行块
     * - `key:` 后跟缩进子块
     */
    fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val lines = frontmatter.lines()
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank()) {
                index += 1
                continue
            }
            val keyMatch = FRONTMATTER_LINE.find(rawLine)
            if (keyMatch == null) {
                index += 1
                continue
            }
            val key = keyMatch.groupValues[1]
            val value = keyMatch.groupValues[2]
            if (YAML_BLOCK_SCALAR.matches(value)) {
                val literal = value.startsWith('|')
                val blockLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                    val next = lines[index]
                    blockLines += if (next.isBlank()) "" else next.trim()
                    index += 1
                }
                result[key] = if (literal) {
                    blockLines.joinToString("\n").trim()
                } else {
                    foldYamlLines(blockLines)
                }
                continue
            }
            if (value.isBlank()) {
                val builder = StringBuilder()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(lines[index].trimEnd())
                    index += 1
                }
                result[key] = builder.toString().trim()
                continue
            }
            result[key] = unquoteScalar(value)
            index += 1
        }
        return result
    }

    /** 支持 YAML 常见的单双引号标量；复杂转义仍交由 Skill 作者避免使用。 */
    private fun unquoteScalar(raw: String): String {
        val value = raw.trim()
        if (value.length < 2) return value
        val quoted = (value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\'')
        return if (quoted) value.substring(1, value.lastIndex) else value
    }

    /** `>` 折叠换行、保留空行形成的段落；尾部 chomp 对元数据没有语义差异。 */
    private fun foldYamlLines(lines: List<String>): String = buildString {
        var pendingBlankLines = 0
        lines.forEach { line ->
            if (line.isBlank()) {
                pendingBlankLines += 1
            } else {
                if (isNotEmpty()) {
                    if (pendingBlankLines == 0) append(' ')
                    else repeat(pendingBlankLines + 1) { append('\n') }
                }
                append(line)
                pendingBlankLines = 0
            }
        }
    }.trim()

    /**
     * 解析缩进子块为 key-value map（用于 metadata 字段）。
     */
    fun parseIndentedBlock(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val match = INDENTED_LINE.find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].trim().trim('"')
        }.toMap()
    }

    /**
     * 从目录名和 frontmatter name 生成规范化的 skill id。
     */
    fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(SKILL_ID_UNSAFE_CHARS, "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    /**
     * 规范化查找字符串——用于 id/name/path 匹配。
     */
    fun normalizeSkillLookup(value: String): String =
        value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(WHITESPACE_CHARS, "")
            .replace("-", "")
            .replace("_", "")

    /**
     * 读取 SKILL.md 文本，超过 [MAX_SKILL_FILE_BYTES] 时返回 null（整份拒绝，不截断）。
     * 先看长度再读，且在读取过程中二次校验，避免检查后被换文件绕过上限。
     */
    private fun readSkillText(skillFile: File): String? {
        if (skillFile.length() > MAX_SKILL_FILE_BYTES) return null
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        FileInputStream(skillFile).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (collected.size().toLong() + read > MAX_SKILL_FILE_BYTES) return null
                collected.write(buffer, 0, read)
            }
        }
        return String(collected.toByteArray(), StandardCharsets.UTF_8)
    }

    /** frontmatter 的 `key: value` 行；提为常量避免逐行重复编译。 */
    private val FRONTMATTER_LINE = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$")

    /** 缩进子块的 `key: value` 行。 */
    private val INDENTED_LINE = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$")

    /** skill id 里需要折叠成连字符的字符。 */
    private val SKILL_ID_UNSAFE_CHARS = Regex("[^a-z0-9-]+")

    /** 查找串归一化时移除的空白。 */
    private val WHITESPACE_CHARS = Regex("\\s+")

    private val YAML_BLOCK_SCALAR = Regex("[>|][+-]?")

    /** SKILL.md 体积上限：与安装期校验 SkillPackageLimits.maxSkillFileBytes 保持一致（512KB）；
     *  超过即视为不可解析，避免把异常大文件整体读进内存。 */
    private const val MAX_SKILL_FILE_BYTES = 512L * 1024L

}
