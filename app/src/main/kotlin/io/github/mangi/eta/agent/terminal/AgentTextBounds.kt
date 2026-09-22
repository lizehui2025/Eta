package io.github.mangi.eta.agent.terminal

/**
 * 文本截断的公共边界：limit 与 String.length 一样按 UTF-16 单元计数，
 * 但不会把代理对（如 emoji）切成两半，避免产生孤立代理项导致 JSON 或下游渲染乱码。
 */
internal fun String.truncateByCodePoints(limit: Int): String {
    if (limit <= 0) return ""
    if (length <= limit) return this
    var end = limit
    // 截断点恰好切开一个代理对（末尾保留高代理项、被切掉的首字符是低代理项）时回退一位。
    if (Character.isHighSurrogate(this[end - 1]) && Character.isLowSurrogate(this[end])) {
        end--
    }
    return substring(0, end)
}
