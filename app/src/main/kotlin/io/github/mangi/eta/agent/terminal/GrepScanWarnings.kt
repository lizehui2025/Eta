package io.github.mangi.eta.agent.terminal

/**
 * grep 逐文件扫描警告的识别（纯函数，便于单测）。
 *
 * 背景：toybox/BusyBox/GNU grep 在扫描中遇到个别无法读取的文件时会打印
 * `grep: <path>: <reason>` 并以退出码 2 收场——但搜索本身已经执行完成。
 * 没有命中时若把它当“搜索失败”，模型会反复重试同一搜索；
 * 这里把权限/IO 类警告与真正的执行失败（选项错误、目录不存在、正则被拒）区分开。
 */
internal object GrepScanWarnings {
    private val WARNING_LINE = Regex(
        "^.{0,80}?grep: .+?: " +
            "(Permission denied|Bad file descriptor|No such file or directory|Too many levels of symbolic links)$"
    )

    /** stderr 中的非空行。 */
    fun lines(stderr: String): List<String> =
        stderr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** 单行是否为逐文件扫描警告。 */
    fun isWarningLine(line: String): Boolean = WARNING_LINE.matches(line)

    /** stderr 是否只包含逐文件扫描警告（至少一行）。 */
    fun isScanWarningOnly(stderr: String): Boolean {
        val lines = lines(stderr)
        return lines.isNotEmpty() && lines.all { isWarningLine(it) }
    }
}
