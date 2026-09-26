package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

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
    private var pendingDelta: PendingDelta? = null
    private var lastFlushNanos = nanoTime()

    fun accept(event: AgentEvent) {
        val checkpointEvent = event.recoveryProjection() ?: return
        val toAppend: List<AgentEvent> = synchronized(lock) {
            if (checkpointEvent is AgentEvent.AssistantBlockDelta) {
                val pending = pendingDelta
                if (pending != null && pending.matches(checkpointEvent)) {
                    pending.append(checkpointEvent)
                } else {
                    // 切换 delta 段：先把旧段落盘（出锁写），再缓存新段。
                    val flushed = pending?.let { listOf<AgentEvent>(it.toEvent()) }.orEmpty()
                    pendingDelta = PendingDelta(checkpointEvent)
                    if (flushed.isNotEmpty()) lastFlushNanos = nanoTime()
                    return@synchronized flushed
                }
                val elapsed = nanoTime() - lastFlushNanos
                if (pendingDelta.bufferedChars() >= MAX_BUFFERED_DELTA_CHARS ||
                    elapsed >= MAX_BUFFERED_DELTA_NANOS
                ) {
                    val flushed = pendingDelta?.let { listOf<AgentEvent>(it.toEvent()) }.orEmpty()
                    pendingDelta = null
                    if (flushed.isNotEmpty()) lastFlushNanos = nanoTime()
                    flushed
                } else {
                    emptyList()
                }
            } else {
                val flushed = pendingDelta?.let { listOf<AgentEvent>(it.toEvent()) }.orEmpty()
                pendingDelta = null
                lastFlushNanos = nanoTime()
                flushed + checkpointEvent
            }
        }
        // accept 处于高频事件回调路径：落盘失败（DB 异常等）不得向上抛给 run 执行器，
        // 只做限流日志；seal() 保持失败向上传播的原有语义，由调用方 runCatching 兜底。
        runCatching { appendBatch(toAppend) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("agent_checkpoint_append_failed") {
                    "Agent run checkpoint append failed: type=${throwable.safeLogType()}"
                }
            }
    }

    /** 把最后一段增量提交到日志；日志由结果 ACK 或中断恢复负责删除。 */
    fun seal() {
        val toAppend = synchronized(lock) {
            val flushed = pendingDelta?.let { listOf<AgentEvent>(it.toEvent()) }.orEmpty()
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

    /**
     * 待落盘的 delta 段：其余字段沿用该段首条事件（与旧实现的 `pending.copy(...)` 一致），
     * 文本与 [deltaChars] 增量累加，落盘时才物化成字符串。
     */
    private class PendingDelta(
        private val head: AgentEvent.AssistantBlockDelta,
    ) {
        private val text = StringBuilder(head.delta)

        /** 与旧实现一致：累计各条 deltaChars，用于判断是否达到落盘阈值。 */
        var deltaChars: Int = head.deltaChars
            private set

        fun matches(next: AgentEvent.AssistantBlockDelta): Boolean =
            head.round == next.round &&
                head.kind == next.kind &&
                head.index == next.index

        fun append(next: AgentEvent.AssistantBlockDelta) {
            text.append(next.delta)
            deltaChars += next.deltaChars
        }

        fun toEvent(): AgentEvent.AssistantBlockDelta =
            head.copy(delta = text.toString(), deltaChars = deltaChars)
    }

    private fun PendingDelta?.bufferedChars(): Int = this?.deltaChars ?: 0

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
