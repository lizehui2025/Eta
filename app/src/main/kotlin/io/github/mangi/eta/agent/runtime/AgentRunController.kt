package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    private val steeringMessages = ArrayDeque<String>()
    /** The question currently waiting for an answer; this run executes sequentially, so at most one is pending. */
    private var pendingQuestion: PendingQuestion? = null
    private var acceptingSteering = true
    @Volatile
    private var paused = false

    fun cancel() {
        lock.withLock {
            cancelled = true
            acceptingSteering = false
            steeringMessages.clear()
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * 将补充指令排入下一个 turn。steering 不取消当前模型请求或工具批次。
     */
    fun steer(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            steeringMessages.addLast(prompt)
        }
        return true
    }

    /** 默认逐条消费，避免后来的补充指令越过前一条的模型回合。 */
    fun pollSteeringMessage(): String? =
        lock.withLock { steeringMessages.pollFirst() }

    /**
     * 自然结束前原子地消费最后一条 steering；若队列为空则永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     */
    fun pollSteeringOrSeal(): String? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    /**
     * Ask the user a question and wait for the answer: blocks the worker thread until an answer,
     * the timeout or cancellation.
     *
     * Shaped like [awaitRetryDelay] (cancellable wait plus bounded timeout). Cancellation wins over timeout:
     * [cancel] sets cancelled before waking the wait (through [register]), so after waking [throwIfCancelled]
     * always throws: a cancellation is never disguised as "the user did not answer".
     */
    fun awaitUserAnswer(
        questionId: String,
        timeoutMs: Long,
        onAsked: () -> Unit,
    ): UserAnswer {
        throwIfCancelled()
        val pending = PendingQuestion(questionId)
        // Register before asking: an answer racing ahead of the registration would find no waiter and be dropped.
        lock.withLock {
            if (cancelled) throw AgentRunCancelledException()
            pendingQuestion = pending
        }
        val binding = register {
            // Only wakes the wait; cancelled is already set, so the wake-up value is never returned (see above).
            pending.complete(UserAnswer(answer = "", selectedOptions = emptyList()))
        }
        try {
            onAsked()
            pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
            lock.withLock { if (pendingQuestion === pending) pendingQuestion = null }
        }
        throwIfCancelled()
        // 超时也要原子地占用结果槽：否则在 latch 超时返回与 finally 清理之间到达的答案
        // 会被 answerUserQuestion 接住并广播一条与 timedOut 相冲突的回答事件。
        pending.complete(UserAnswer(answer = "", selectedOptions = emptyList(), timedOut = true))
        return checkNotNull(pending.answer)
    }

    /** Deliver an answer; false means the question already timed out, was already answered or belongs to another run. */
    fun answerUserQuestion(
        questionId: String,
        answer: String,
        selectedOptions: List<String>,
    ): Boolean {
        val pending = lock.withLock {
            pendingQuestion?.takeIf { it.questionId == questionId } ?: return false
        }
        return pending.complete(UserAnswer(answer = answer, selectedOptions = selectedOptions))
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }

    /**
     * A pending question; [complete] only takes the first result, so an answer racing cancellation
     * or timeout cannot overwrite it.
     */
    private class PendingQuestion(val questionId: String) {
        val latch = CountDownLatch(1)

        @Volatile
        var answer: UserAnswer? = null
            private set

        fun complete(result: UserAnswer): Boolean = synchronized(this) {
            if (answer != null) return false
            answer = result
            latch.countDown()
            true
        }
    }
}

/** Result of [AgentRunController.awaitUserAnswer]; on timeout [timedOut] is true and [answer] is empty. */
internal data class UserAnswer(
    val answer: String,
    val selectedOptions: List<String>,
    val timedOut: Boolean = false,
)

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
