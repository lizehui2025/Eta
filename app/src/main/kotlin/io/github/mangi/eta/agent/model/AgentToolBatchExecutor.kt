package io.github.mangi.eta.agent.model

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * 工具批次执行器：连续的安全只读工具最多并行 [maxParallel] 个，其余工具保持顺序屏障。
 *
 * 结果始终按输入下标回填，调用方可以继续按模型给出顺序写入工具结果消息；并行只改变
 * 实际执行时间，不改变 transcript 顺序。任何任务抛出异常时取消同段在途任务，并向上抛出
 * 原始 cause，避免等待已失效的后台工作。
 */
internal class AgentToolBatchExecutor(
    private val maxParallel: Int = DEFAULT_MAX_PARALLEL,
) {
    fun <T, R> execute(
        items: List<T>,
        parallelSafe: (T) -> Boolean,
        execute: (T) -> R,
    ): List<R> {
        if (items.size <= 1) return items.map(execute)
        val results = MutableList<Any?>(items.size) { null }
        var index = 0
        while (index < items.size) {
            if (!parallelSafe(items[index])) {
                results[index] = execute(items[index])
                index++
                continue
            }
            var end = index
            while (end < items.size && parallelSafe(items[end])) end++
            executeSegment(items, index, end, execute, results)
            index = end
        }
        @Suppress("UNCHECKED_CAST")
        return results.map { it as R }
    }

    private fun <T, R> executeSegment(
        items: List<T>,
        start: Int,
        end: Int,
        execute: (T) -> R,
        results: MutableList<Any?>,
    ) {
        if (end - start == 1) {
            results[start] = execute(items[start])
            return
        }
        val pool = Executors.newFixedThreadPool(
            minOf(end - start, maxParallel.coerceAtLeast(1)),
        ) { runnable ->
            Thread(runnable, "agent-tool-batch").apply { isDaemon = true }
        }
        try {
            val futures = (start until end).map { position ->
                pool.submit(Callable { execute(items[position]) })
            }
            futures.forEachIndexed { offset, future ->
                try {
                    results[start + offset] = future.get()
                } catch (failure: ExecutionException) {
                    futures.forEach { it.cancel(true) }
                    throw failure.cause ?: failure
                } catch (interrupted: InterruptedException) {
                    futures.forEach { it.cancel(true) }
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val DEFAULT_MAX_PARALLEL = 4
    }
}
