package io.github.mangi.eta.core

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** 进程内日志节流器；使用单调时钟，不受系统时间调整影响。 */
internal class LogThrottle(
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis
) {
    private val lastAcceptedAt = ConcurrentHashMap<String, Long>()

    fun shouldLog(key: String, windowMs: Long): Boolean {
        require(key.isNotBlank()) { "日志节流 key 不能为空" }
        require(windowMs >= 0L) { "日志节流窗口不能为负数" }

        val now = uptimeMillis()
        // 容量保护：动态键（runId/host/status 拼接等）会持续增长；仅当“新键进入且已满”时整体清空，
        // ConcurrentHashMap.clear() 线程安全且实现最简；代价是被清空的键窗口重置、最多再多放行一条日志（节流为尽力而为语义）。
        if (lastAcceptedAt.size >= MAX_ENTRIES && !lastAcceptedAt.containsKey(key)) {
            lastAcceptedAt.clear()
        }
        var accepted = false
        lastAcceptedAt.compute(key) { _, previous ->
            if (previous == null || now < previous || now - previous >= windowMs) {
                accepted = true
                now
            } else {
                previous
            }
        }
        return accepted
    }

    private companion object {
        /** lastAcceptedAt 条目上限；新键进入且超限时整体清空。 */
        const val MAX_ENTRIES = 512
    }
}
