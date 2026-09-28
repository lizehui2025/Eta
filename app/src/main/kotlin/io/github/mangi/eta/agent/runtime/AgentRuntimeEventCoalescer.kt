package io.github.mangi.eta.agent.runtime

/**
 * Runtime 侧流式事件合并。
 *
 * 模型可能按 token 产生大量 [AgentEvent.AssistantBlockDelta]；如果每个 delta 都跨 Binder
 * 并投递到主线程，UI 的 40ms 合并只能减少状态更新，不能减少 IPC/分配。这里在
 * session/checkpoint 之前先合并 TEXT/THINKING 增量；TOOL_CALL 参数增量不再向下游发送，
 * 工具完整参数仍由 Provider 的 BlockEnd 交给模型历史。
 *
 * 结构化事件是天然屏障：它们会先冲刷待处理 delta，再按原顺序返回，保证工具边界、
 * BlockEnd 和终态的语义不被推迟。
 */
internal class AgentRuntimeEventCoalescer(
    private val maxBufferedChars: Int = MAX_BUFFERED_DELTA_CHARS,
    private val maxBufferedNanos: Long = MAX_BUFFERED_DELTA_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var pending: PendingDelta? = null
    private var lastFlushNanos = nanoTime()

    /**
     * 返回本次可以向下游发布的事件；没有达到刷新阈值的 delta 返回空列表。
     * TOOL_CALL delta 返回空列表且不缓存，避免大参数逐片跨 Binder。
     */
    fun offer(event: AgentEvent): List<AgentEvent> = synchronized(lock) {
        if (event is AgentEvent.AssistantBlockDelta && event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL) {
            return@synchronized emptyList()
        }
        if (event !is AgentEvent.AssistantBlockDelta) {
            val flushed = flushPendingLocked()
            pending = null
            lastFlushNanos = nanoTime()
            return@synchronized flushed + event
        }

        val current = pending
        if (current != null && current.matches(event)) {
            current.append(event)
            val elapsed = nanoTime() - lastFlushNanos
            if (current.bufferedChars >= maxBufferedChars || elapsed >= maxBufferedNanos) {
                val flushed = listOf(current.toEvent())
                pending = null
                lastFlushNanos = nanoTime()
                return@synchronized flushed
            }
            return@synchronized emptyList()
        }

        val flushed = flushPendingLocked()
        pending = PendingDelta(event)
        if (flushed.isNotEmpty()) lastFlushNanos = nanoTime()
        return@synchronized flushed
    }

    /** 在终态、取消或异常收尾前把最后一段增量提交出去。 */
    fun flush(): List<AgentEvent> = synchronized(lock) {
        val flushed = flushPendingLocked()
        pending = null
        if (flushed.isNotEmpty()) lastFlushNanos = nanoTime()
        flushed
    }

    private fun flushPendingLocked(): List<AgentEvent> =
        pending?.let { listOf(it.toEvent()) }.orEmpty()

    private class PendingDelta(
        private val head: AgentEvent.AssistantBlockDelta,
    ) {
        private val text = StringBuilder(head.delta)
        var bufferedChars: Int = head.deltaChars
            private set

        fun matches(next: AgentEvent.AssistantBlockDelta): Boolean =
            head.round == next.round &&
                head.kind == next.kind &&
                head.index == next.index

        fun append(next: AgentEvent.AssistantBlockDelta) {
            text.append(next.delta)
            bufferedChars += next.deltaChars
        }

        fun toEvent(): AgentEvent.AssistantBlockDelta =
            head.copy(delta = text.toString(), deltaChars = bufferedChars)
    }

    private companion object {
        const val MAX_BUFFERED_DELTA_CHARS = 256
        const val MAX_BUFFERED_DELTA_NANOS = 40_000_000L
    }
}
