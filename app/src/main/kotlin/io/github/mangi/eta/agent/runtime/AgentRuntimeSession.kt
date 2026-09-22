package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 回放合并上限：连续 delta 无限拼接会让单个回放事件超 Binder 事务预算；达到上限后改为新起事件。 */
private const val MAX_REPLAY_MERGED_DELTA_CHARS = 64_000

/**
 * 一次 Runtime run 的控制权和唯一终态。
 *
 * Service 替换、用户取消和正常完成都必须经过此对象，避免旧 run 向新 reply channel 发消息，
 * 也避免同一 run 发送两个最终结果。
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
    private val operation: String = AgentRuntimeWire.OP_CHAT,
) {
    private enum class State {
        RUNNING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private var latestTranscript: List<AgentModelClient.ConversationMessage> = emptyList()
    val transcript: List<AgentModelClient.ConversationMessage>
        get() = lock.withLock { latestTranscript }

    fun updateTranscript(messages: List<AgentModelClient.ConversationMessage>) = lock.withLock {
        if (state == State.RUNNING) latestTranscript = messages
    }

    private var latestContext: AgentContextSnapshot? = null
    val contextSnapshot: AgentContextSnapshot?
        get() = lock.withLock { latestContext }

    fun updateContext(snapshot: AgentContextSnapshot) = lock.withLock {
        if (state == State.RUNNING) latestContext = snapshot
    }
    private var state = State.RUNNING
    private val replayEvents = mutableListOf<AgentEvent>()
    private val subscribers = mutableListOf<Subscriber>()

    private data class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
    )

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
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
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = lock.withLock {
        if (state == State.TERMINAL) return false
        // åæ¾ãç¡®è®¤ãè®¢éä¸èå¨åä¸æéååå­å®æï¼å¹¶å emit/complete ä¸å¾è¶è¿åæ¾è¾¹çã
        // attach æ¯ä½é¢éç»ï¼æéåæ¾å¯æ¥åï¼é«é¢ emit ä»å¨éå¤ååï¼è§ emit æ³¨éï¼ã
        replayEvents.toList().forEach(eventSink)
        runCatching { onReplayComplete() }
        subscribers += Subscriber(eventSink, resultSink)
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

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.recoveryProjection() ?: return
        if (projected !is AgentEvent.AssistantBlockDelta) {
            replayEvents += projected
            return
        }
        val previous = replayEvents.lastOrNull() as? AgentEvent.AssistantBlockDelta
        if (
            previous != null &&
            previous.round == projected.round &&
            previous.kind == projected.kind &&
            previous.index == projected.index &&
            // 合并上限：避免单个回放事件无限增长、超出 Binder 事务预算；达到上限后改为新起事件，
            // attach 客户端按序拼接所有 delta，最终文本与不设上限时一致。
            previous.delta.length + projected.delta.length <= MAX_REPLAY_MERGED_DELTA_CHARS
        ) {
            replayEvents[replayEvents.lastIndex] = previous.copy(
                deltaChars = previous.deltaChars + projected.deltaChars,
                delta = previous.delta + projected.delta,
            )
        } else {
            replayEvents += projected
        }
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
        val sinks = lock.withLock {
            state = State.TERMINAL
            val copy = subscribers.map { it.resultSink }
            subscribers.clear()
            replayEvents.clear()
            copy
        }
        sinks.forEach { sink -> runCatching { sink(result) } }
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
            val sinks = subscribers.map { it.resultSink }
            subscribers.clear()
            replayEvents.clear()
            result to sinks
        }
        val (result, sinks) = prepared
        controller.cancel()
        sinks.forEach { sink -> runCatching { sink(result) } }
        return true
    }
}
