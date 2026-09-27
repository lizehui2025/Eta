package io.github.mangi.eta.agent.model

import java.util.concurrent.atomic.AtomicInteger

/**
 * Budget for one whole context compaction, created once per session and shared across the outer
 * retry loop and every chunk.
 *
 * Why it exists: compaction issues extra model calls while the user is waiting. Without a ceiling,
 * a very large history plus a slow provider can legitimately keep a single compaction running for a
 * long time, leaving the UI on an unresponsive wait where manual cancellation is the only escape.
 * The wall-clock and call-count limits here match `docs/AGENT_RUNTIME.md`; exceeding them fails
 * immediately and keeps the original context, handled by the session's existing compaction-failure
 * semantics (a partial summary is never committed).
 *
 * Division of labour with cancellation: this budget is not a substitute for a stop signal. User
 * cancellation still takes effect immediately through `AgentRunController`; the budget only bounds
 * the wait when nobody is interacting.
 */
internal class AgentCompactionBudget(
    private val maxCalls: Int = MAX_SUMMARY_CALLS,
    private val maxWallClockMs: Long = MAX_WALL_CLOCK_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startedAtMs = clock()
    private val calls = AtomicInteger(0)

    /** Summary and merge calls issued so far, for log reconciliation. */
    val callsUsed: Int get() = calls.get()

    private fun elapsedMs(): Long = clock() - startedAtMs

    /**
     * Claims one summary-call slot. Returns `null` when a slot was granted, otherwise the reason the
     * budget was exceeded.
     *
     * The claim is atomic so parallel chunks entering together cannot collectively overshoot the
     * call limit (four chunks would otherwise overshoot once each). The wall-clock check consumes
     * nothing and only fails fast.
     */
    fun tryAcquireCall(): String? {
        if (elapsedMs() >= maxWallClockMs) {
            return "本次上下文压缩已超过 ${maxWallClockMs / 1000} 秒，已保留原始上下文；可稍后重试或手动压缩。"
        }
        while (true) {
            val current = calls.get()
            if (current >= maxCalls) {
                return "本次上下文压缩已达到 $maxCalls 次摘要调用上限，已保留原始上下文；可稍后重试或手动压缩。"
            }
            if (calls.compareAndSet(current, current + 1)) return null
        }
    }

    /**
     * Throws a non-retryable failure from the same family as other compaction errors once the budget
     * is exceeded, so the model retry layer never replays it as a transient fault.
     */
    fun acquireOrThrow() {
        tryAcquireCall()?.let { reason ->
            throw AgentContextCompactor.failure(CODE_BUDGET_EXCEEDED, reason)
        }
    }

    companion object {
        /** Failure code surfaced through the session's compaction-failure path. */
        const val CODE_BUDGET_EXCEEDED = "CONTEXT_COMPACTION_BUDGET_EXCEEDED"

        /** Matches the "90 second wall clock" promised in `docs/AGENT_RUNTIME.md`. */
        const val MAX_WALL_CLOCK_MS = 90_000L

        /**
         * Call ceiling for a single compaction.
         *
         * Chunks are independent and summarised in parallel (one call each), plus one merge call,
         * plus a binary-split retry when the merge input overflows. The ceiling therefore has to
         * accommodate a legitimately large history while still stopping a pathological loop. It is
         * set well above the normal chunk count so the wall clock usually fires first: time is the
         * backstop by default, while the call count catches runaway amplification.
         */
        const val MAX_SUMMARY_CALLS = 24
    }
}
