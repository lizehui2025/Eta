package io.github.mangi.eta.agent.runtime

import android.os.SystemClock
import io.github.mangi.eta.core.AgentLogger

/** 记录首请求关键边界，区分本地准备、网络握手和模型首 Token 延迟。 */
internal class AgentRunTiming(
    private val logger: AgentLogger,
) {
    private val runStartedAt = SystemClock.elapsedRealtime()
    // 子代理多线程同时上报事件，round 在各子 loop 中都会从 1 开始，直接用 round 做 key
    // 会互相覆盖。这里只保证不崩、日志不错乱，不追求子代理各自的首 Token 精确统计。
    private val lock = Any()
    private val requestStartedAt = mutableMapOf<Int, Long>()
    private val responseStartedAt = mutableMapOf<Int, Long>()
    private val firstDeltaRounds = mutableSetOf<Int>()

    fun preparationFinished(skillCount: Int) {
        logger.debug {
            "Agent runtime preparation finished: elapsed_ms=${elapsedSince(runStartedAt)}, " +
                "skills=$skillCount"
        }
    }

    fun accept(event: AgentEvent) {
        // 锁内只更新 map/set 并快照日志文案，日志 IO 出锁执行。
        val message: String? = synchronized(lock) {
            when (event) {
                is AgentEvent.ProviderRequestStarted -> {
                    requestStartedAt[event.round] = SystemClock.elapsedRealtime()
                    "Agent provider request started: round=${event.round}, " +
                        "run_elapsed_ms=${elapsedSinceLocked(runStartedAt)}"
                }
                is AgentEvent.ProviderResponseStarted -> {
                    responseStartedAt[event.round] = SystemClock.elapsedRealtime()
                    val req = requestStartedAt[event.round]
                    "Agent provider response headers received: round=${event.round}, " +
                        "request_elapsed_ms=${elapsedSinceLocked(req)}"
                }
                is AgentEvent.AssistantBlockDelta -> {
                    if (firstDeltaRounds.add(event.round)) {
                        val req = requestStartedAt[event.round]
                        val resp = responseStartedAt[event.round]
                        "Agent provider first delta received: round=${event.round}, " +
                            "request_elapsed_ms=${elapsedSinceLocked(req)}, " +
                            "headers_elapsed_ms=${elapsedSinceLocked(resp)}"
                    } else null
                }
                else -> null
            }
        }
        if (message != null) logger.debug { message }
    }

    private fun elapsedSince(startedAt: Long?): Long =
        startedAt?.let { SystemClock.elapsedRealtime() - it } ?: -1L

    private fun elapsedSinceLocked(startedAt: Long?): Long = elapsedSince(startedAt)
}
