package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 回放合并上限：连续 delta 无限拼接会让单个回放事件超 Binder 事务预算；达到上限后改为新起事件。 */
private const val MAX_REPLAY_MERGED_DELTA_CHARS = 64_000
private const val MAX_REPLAY_ENTRIES = 4_096
private const val MAX_REPLAY_BYTES = 2 * 1024 * 1024

/**
 * 一次 Runtime run 的控制权和唯一终态。
 *
 * Service 替换、用户取消和正常完成都必须经过此对象，避免旧 run 向新 reply channel 发消息，
 * 也避免同一 run 发送两个最终结果。
 *
 * 订阅者只在 run 运行期有效：attach 会清理失效绑定并让同一客户端标识的旧条目被取代，
 * 终态投递也跳过失效订阅者，重复 attach 不再无界累积。
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
    private val operation: String = AgentRuntimeWire.OP_CHAT,
    initialClientAlive: (() -> Boolean)? = null,
    /**
     * UI checkpoint run 才允许丢弃内存 replay；丢弃后 attach 由 DB checkpoint 回放。
     * 没有 checkpoint 的入口继续保留完整内存 replay，避免断线后无法恢复。
     */
    private val boundedReplay: Boolean = false,
) {
    private enum class State {
        RUNNING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private val latestTranscript = mutableListOf<AgentModelClient.ConversationMessage>()
    val transcript: List<AgentModelClient.ConversationMessage>
        get() = lock.withLock { latestTranscript.toList() }

    fun updateTranscript(messages: List<AgentModelClient.ConversationMessage>) = lock.withLock {
        if (state == State.RUNNING) {
            latestTranscript.clear()
            latestTranscript += messages
        }
    }

    /** 追加 transcript 增量；[fullRebuild] 为 true 时用增量消息整体替换。 */
    fun appendTranscript(
        messages: List<AgentModelClient.ConversationMessage>,
        fullRebuild: Boolean,
    ) = lock.withLock {
        if (state != State.RUNNING || messages.isEmpty() && !fullRebuild) return@withLock
        if (fullRebuild) latestTranscript.clear()
        latestTranscript += messages
    }

    private var latestContext: AgentContextSnapshot? = null
    val contextSnapshot: AgentContextSnapshot?
        get() = lock.withLock { latestContext }

    fun updateContext(snapshot: AgentContextSnapshot) = lock.withLock {
        if (state == State.RUNNING) latestContext = snapshot
    }
    private var state = State.RUNNING
    private val replayEvents = mutableListOf<ReplayEntry>()
    private var replayBytes = 0
    private var replayTruncated = false
    private val subscribers = mutableListOf<Subscriber>()

    /**
     * [clientKey] 为客户端标识（attach 时传入，如 sender uid）：同标识的旧条目会被取代；
     * [isAlive] 探测绑定是否仍可用，失效订阅者不再长期持有 eventSink/resultSink/Messenger。
     */
    private data class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        val clientKey: Any? = null,
        val isAlive: () -> Boolean = { true },
    )

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
                isAlive = initialClientAlive ?: { true },
            )
        }
    }

    val isTerminal: Boolean
        get() = lock.withLock { state == State.TERMINAL }

    /**
     * 并发安全说明：锁内只做状态检查、回放记录与订阅者快照，实际的 eventSink 分发在锁外。
     * 子代理多线程同时 emit 时，耗时 IPC/落盘不再互相阻塞；事件顺序以 recordForReplay 为准，
     * 分发顺序可能交错，但终态边界（RUNNING->COMMITTING/TERMINAL）仍由锁保证。
     */
    fun emit(event: AgentEvent): Boolean {
        val sinks = lock.withLock {
            if (state != State.RUNNING) return false
            recordForReplay(event)
            subscribers.map { it.eventSink }
        }
        sinks.forEach { sink ->
            runCatching { sink(event) }
        }
        return true
    }

    /**
     * Activity 被移出任务栈后 Runtime 仍可能继续执行。安全历史回放、完成确认和实时订阅
     * 共用同一把锁，保证客户端收到确认前的事件都是历史，新增事件与终态不会越过边界。
     *
     * [clientKey] 标识发起 attach 的客户端：同标识旧条目会被取代；[isClientAlive] 判定存量
     * 订阅者绑定是否仍可用，失效条目在本次 attach 一并清除。
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
        clientKey: Any? = null,
        isClientAlive: (() -> Boolean)? = null,
        replayLoader: (() -> List<AgentEvent>)? = null,
    ): Boolean = lock.withLock {
        if (state == State.TERMINAL) return false
        // åæ¾ãç¡®è®¤ãè®¢éä¸èå¨åä¸æéååå­å®æï¼å¹¶å emit/complete ä¸å¾è¶è¿åæ¾è¾¹çã
        // attach æ¯ä½é¢éç»ï¼æéåæ¾å¯æ¥åï¼é«é¢ emit ä»å¨éå¤ååï¼è§ emit æ³¨éï¼ã
        // 先清理失效绑定与同客户端标识的旧订阅者（attach 为低频操作），再回放、登记新订阅者，
        // 重复 attach/重连不再累积旧 eventSink/resultSink/Messenger。
        subscribers.removeAll { !it.isAlive() }
        if (clientKey != null) {
            subscribers.removeAll { it.clientKey == clientKey }
        }
        val replay = if (replayTruncated && replayLoader != null) {
            replayLoader()
        } else {
            replayEvents.map { entry -> entry.materialize() }
        }
        replay.forEach { event -> eventSink(event) }
        runCatching { onReplayComplete() }
        subscribers += Subscriber(
            eventSink = eventSink,
            resultSink = resultSink,
            clientKey = clientKey,
            isAlive = isClientAlive ?: { true },
        )
        true
    }

    fun steer(text: String): Boolean =
        lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT) return false
            controller.steer(text)
        }

    fun <T : AgentEvent> steer(
        text: String,
        eventFactory: () -> T,
    ): T? {
        val prepared = lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT || !controller.steer(text)) return null
            val event = eventFactory()
            recordForReplay(event)
            val sinks = subscribers.map { it.eventSink }
            event to sinks
        }
        val (event, sinks) = prepared
        sinks.forEach { sink -> runCatching { sink(event) } }
        return event
    }

    /**
     * Deliver the user's answer to [AgentEvent.UserQuestionAsked]: wake the waiting tool and broadcast
     * [AgentEvent.UserQuestionAnswered] so every subscriber (including replay after a reconnect) sees the same answer.
     *
     * False means no pending question matched (already timed out, already answered or another run's) —
     * nothing changes in that case, so a late answer cannot pollute a later run.
     */
    fun answerQuestion(questionId: String, answer: String, selectedOptions: List<String>): Boolean {
        if (questionId.isBlank()) return false
        if (!controller.answerUserQuestion(questionId, answer, selectedOptions)) return false
        val event = AgentEvent.UserQuestionAnswered(
            questionId = questionId,
            answer = answer,
            selectedOptions = selectedOptions,
            timedOut = false,
        )
        val sinks = lock.withLock {
            recordForReplay(event)
            subscribers.map { it.eventSink }
        }
        sinks.forEach { sink -> runCatching { sink(event) } }
        return true
    }

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.recoveryProjection() ?: return
        if (boundedReplay && replayTruncated) return
        val previous = replayEvents.lastOrNull()
        if (
            previous is ReplayDeltaGroup &&
            projected is AgentEvent.AssistantBlockDelta &&
            previous.canMerge(projected)
        ) {
            previous.append(projected)
            return
        }
        val entry: ReplayEntry = if (projected is AgentEvent.AssistantBlockDelta) {
            ReplayDeltaGroup(projected)
        } else {
            ReplaySingle(projected)
        }
        replayEvents += entry
        replayBytes += entry.approximateBytes()
        if (boundedReplay && (replayEvents.size > MAX_REPLAY_ENTRIES || replayBytes > MAX_REPLAY_BYTES)) {
            // DB checkpoint 已由 AgentRunCheckpointRecorder 在 emit 前同步写入；这里只丢弃内存副本。
            replayEvents.clear()
            replayBytes = 0
            replayTruncated = true
        }
    }

    /**
     * 回放缓冲条目：普通事件直接持有；连续 delta 合并段用 [StringBuilder] 增量累积，
     * 只有 attach 回放时才物化成字符串，避免每次合并都复制已累积的全部文本（O(n²) 字符拷贝）。
     */
    private interface ReplayEntry {
        fun materialize(): AgentEvent
        fun approximateBytes(): Int
    }

    private class ReplaySingle(private val event: AgentEvent) : ReplayEntry {
        override fun materialize(): AgentEvent = event
        override fun approximateBytes(): Int = event.toLogLine().length + 64
    }

    /**
     * 一段可继续合并的 delta：其余字段沿用该合并段首条事件（与旧实现 `previous.copy(...)` 一致），
     * 文本与 `deltaChars` 增量累加；[materialize] 结果缓存，未再合并时多次 attach 复用同一实例。
     */
    private class ReplayDeltaGroup(
        private val head: AgentEvent.AssistantBlockDelta,
    ) : ReplayEntry {
        private val text = StringBuilder(head.delta)
        private var combinedChars = head.deltaChars
        private var materialized: AgentEvent.AssistantBlockDelta? = null

        fun canMerge(next: AgentEvent.AssistantBlockDelta): Boolean =
            head.round == next.round &&
                head.kind == next.kind &&
                head.index == next.index &&
                // 合并上限：避免单个回放事件无限增长、超出 Binder 事务预算；达到上限后改为新起事件，
                // attach 客户端按序拼接所有 delta，最终文本与不设上限时一致。
                text.length + next.delta.length <= MAX_REPLAY_MERGED_DELTA_CHARS

        fun append(next: AgentEvent.AssistantBlockDelta) {
            text.append(next.delta)
            combinedChars += next.deltaChars
            materialized = null
        }

        override fun materialize(): AgentEvent {
            materialized?.let { return it }
            val event = head.copy(delta = text.toString(), deltaChars = combinedChars)
            materialized = event
            return event
        }

        override fun approximateBytes(): Int = text.length + 64
    }

    /**
     * 先原子竞争 COMMITTING，再完成提交前副作用和结果发布。取消与替换不能越过提交胜者，
     * 因而不会出现“客户端收到取消、outbox 却留下成功结果”的分裂状态；耗时 I/O 也不持有锁。
     * [beforePublish] 必须自行吸收非致命持久化异常。
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: () -> Unit = {},
    ): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
            require(result.runId == runId) { "Result runId does not match the active session" }
            state = State.COMMITTING
        }
        val commitFailure = runCatching(beforePublish).exceptionOrNull()
        val pending = lock.withLock {
            state = State.TERMINAL
            val copy = subscribers.toList()
            subscribers.clear()
            replayEvents.clear()
            copy
        }
        // 终态只投递给仍存活的订阅者，不再向失效绑定重复发送。
        pending.filter { it.isAlive() }.forEach { subscriber ->
            runCatching { subscriber.resultSink(result) }
        }
        commitFailure?.let { throw it }
        return true
    }

    fun cancel(reason: String): Boolean {
        val prepared = lock.withLock {
            if (state != State.RUNNING) return false
            state = State.TERMINAL
            val result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
                contextSnapshot = latestContext,
                transcript = AgentToolBatchRecovery.completeInterrupted(latestTranscript),
                operation = operation,
            )
            val pending = subscribers.toList()
            subscribers.clear()
            replayEvents.clear()
            result to pending
        }
        val (result, pending) = prepared
        controller.cancel()
        // 终态只投递给仍存活的订阅者，不再向失效绑定重复发送。
        pending.filter { it.isAlive() }.forEach { subscriber ->
            runCatching { subscriber.resultSink(result) }
        }
        return true
    }
}
