package io.github.mangi.eta.agent.mcp

/**
 * 本地 stdio 服务器的配置校验与解析。
 *
 * 本地命令由用户直接配置并以应用身份运行，解析时只做形状校验（非空、长度、
 * 条数、KEY 合法性），不做 PATH 白名单：是否信任该命令由用户在添加时决定。
 * 进程经 ProcessBuilder 直接启动（不经过 shell），参数中的 shell 元字符无特殊含义。
 */
internal object McpLocalConfig {
    const val MAX_COMMAND_CHARS = 512
    const val MAX_ARGS = 32
    const val MAX_ARG_CHARS = 512
    const val MAX_ENV_ENTRIES = 32
    const val MAX_ENV_VALUE_CHARS = 2048
    const val MAX_WORKING_DIR_CHARS = 1024

    private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun validateCommand(raw: String): String {
        val command = raw.trim()
        require(command.isNotEmpty()) { "本地命令不能为空" }
        require(command.length <= MAX_COMMAND_CHARS) { "本地命令超过 $MAX_COMMAND_CHARS 字符" }
        require(command.none { it == '\u0000' || it == '\n' || it == '\r' }) { "本地命令包含非法字符" }
        return command
    }

    /**
     * shell 风格切分：空白分隔，支持单/双引号与反斜杠转义。
     * 空输入返回空列表；引号未闭合时抛错。
     */
    fun parseArgs(raw: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var hasToken = false
        var quote: Char? = null
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (quote != null) {
                if (char == quote) {
                    quote = null
                } else if (quote == '"' && char == '\\' && index + 1 < raw.length) {
                    index++
                    current.append(raw[index])
                } else {
                    current.append(char)
                }
                index++
                continue
            }
            when {
                char == '\'' || char == '"' -> {
                    quote = char
                    hasToken = true
                    index++
                }
                char == '\\' && index + 1 < raw.length -> {
                    index++
                    current.append(raw[index])
                    hasToken = true
                    index++
                }
                char.isWhitespace() -> {
                    if (hasToken) {
                        out += current.toString()
                        current.clear()
                        hasToken = false
                    }
                    index++
                }
                else -> {
                    current.append(char)
                    hasToken = true
                    index++
                }
            }
        }
        require(quote == null) { "参数引号未闭合" }
        if (hasToken) out += current.toString()
        require(out.size <= MAX_ARGS) { "参数超过 $MAX_ARGS 个" }
        out.forEach { require(it.length <= MAX_ARG_CHARS) { "单个参数超过 $MAX_ARG_CHARS 字符" } }
        return out
    }

    /**
     * 按行 KEY=VALUE 解析，忽略空行与 # 注释行。
     * 空输入返回空表。
     */
    fun parseEnv(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val equals = trimmed.indexOf('=')
            require(equals > 0) { "环境变量格式应为 KEY=VALUE：$trimmed" }
            val key = trimmed.substring(0, equals)
            val value = trimmed.substring(equals + 1)
            require(ENV_KEY.matches(key)) { "环境变量名非法：$key" }
            require(value.length <= MAX_ENV_VALUE_CHARS) { "环境变量 $key 超过 $MAX_ENV_VALUE_CHARS 字符" }
            out[key] = value
        }
        require(out.size <= MAX_ENV_ENTRIES) { "环境变量超过 $MAX_ENV_ENTRIES 个" }
        return out
    }

    fun validateWorkingDir(raw: String): String {
        val dir = raw.trim()
        require(dir.length <= MAX_WORKING_DIR_CHARS) { "工作目录超过 $MAX_WORKING_DIR_CHARS 字符" }
        return dir
    }
}
