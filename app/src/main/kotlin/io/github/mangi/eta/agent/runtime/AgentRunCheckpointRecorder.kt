package io.github.mangi.eta.agent.runtime

import android.content.Context

/** 将高频文本增量合并后写入 checkpoint，结构化边界则同步落盘。 */
internal class AgentRunCheckpointRecorder private constructor(
    context: Context,
    private val runId: String,
    private val nanoTime: () -> Long,
) {
    private val appContext = context.applicationContext
    // 子代理多线程同时 accept 时必须串行化，否则 nextSortIndex 与 pendingDelta 会交错。
    // 锁内只做内存决策，DB append 出锁执行，避免 IO 阻塞子代理线程。
    private val lock = Any()
    private var nextSortIndex = 0
    private var pendingDelta: AgentEvent.AssistantBlockDelta? = null
    private var lastFlushNanos = nanoTime()

    fun accept(event: AgentEvent) {
        val checkpointEvent = event.recoveryProjection() ?: return
        val toAppend: List<AgentEvent> = synchronized(lock) {
            if (checkpointEvent is AgentEvent.AssistantBlockDelta) {
                val pending = pendingDelta
                if (pending != null &&
                    pending.round == checkpointEvent.round &&
                    pending.kind == checkpointEvent.kind &&
                    pending.index == checkpointEvent.index
                ) {
                    pendingDelta = pending.copy(
                        deltaChars = pending.deltaChars + checkpointEvent.deltaChars,
                        delta = pending.delta + checkpointEvent.delta,
                    )
                } else {
                    // 切换 delta 段：先把旧段落盘（出锁写），再缓存新段。
                    val flushed = pending?.let { listOf<AgentEvent>(it) }.orEmpty()
                    pendingDelta = checkpointEvent
                    if (flushed.isNotEmpty()) lastFlushNanos = nanoTime()
                    return@synchronized flushed
                }
                val elapsed = nanoTime() - lastFlushNanos
                if (pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS ||
                    elapsed >= MAX_BUFFERED_DELTA_NANOS
                ) {
                    val flushed = pendingDelta?.let { listOf<AgentEvent>(it) }.orEmpty()
                    pendingDelta = null
                    if (flushed.isNotEmpty()) lastFlushNanos = nanoTime()
                    flushed
                } else {
                    emptyList()
                }
            } else {
                val flushed = pendingDelta?.let { listOf<AgentEvent>(it) }.orEmpty()
                pendingDelta = null
                lastFlushNanos = nanoTime()
                flushed + checkpointEvent
            }
        }
        appendBatch(toAppend)
    }

    /** 把最后一段增量提交到日志；日志由结果 ACK 或中断恢复负责删除。 */
    fun seal() {
        val toAppend = synchronized(lock) {
            val flushed = pendingDelta?.let { listOf<AgentEvent>(it) }.orEmpty()
            pendingDelta = null
            flushed
        }
        appendBatch(toAppend)
    }

    fun discard() {
        synchronized(lock) { pendingDelta = null }
        AgentRunCheckpointStore.remove(appContext, runId)
    }

    /** 一批事件共用一个事务：落盘时机不变（返回前已提交），仅减少事务次数。 */
    private fun appendBatch(events: List<AgentEvent>) {
        if (events.isEmpty()) return
        val indexed = synchronized(lock) { events.map { event -> nextSortIndex++ to event } }
        AgentRunCheckpointStore.appendAll(
            context = appContext,
            runId = runId,
            events = indexed,
        )
    }

    private fun AgentEvent.AssistantBlockDelta?.orEmptyChars(): Int = this?.deltaChars ?: 0

    companion object {
        private const val MAX_BUFFERED_DELTA_CHARS = 256
        private const val MAX_BUFFERED_DELTA_NANOS = 120_000_000L

        fun create(
            context: Context,
            request: AgentRuntimeWire.RunRequest,
            nanoTime: () -> Long = System::nanoTime,
        ): AgentRunCheckpointRecorder? {
            if (!AgentRunCheckpointStore.start(context, request)) return null
            return AgentRunCheckpointRecorder(
                context = context,
                runId = request.runId,
                nanoTime = nanoTime,
            )
        }
    }
}
