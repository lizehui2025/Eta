package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    /** 压缩等"用户正等着"的串行调用只重试一次；普通对话轮次保持 3 次。 */
    private val maxRetries: Int = MAX_RETRIES,
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            try {
                val response = provider.complete(request, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified, recoveryAllowed = false,
                )
                if (!classified.retryable) throw classified
                if (retries == maxRetries) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $maxRetries 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, maxRetries, delayMs.toInt(), classified.code))
                logRetryScheduled(round, retries, delayMs, classified)
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    /**
     * 重试决策补一条日志：事件里只有 code，日志里带上“上次失败原因”摘要，
     * 用户一次就能拿到完整失败现场。按 code 限流，退避期间不刷屏。
     */
    private fun logRetryScheduled(round: Int, attempt: Int, delayMs: Long, failure: AgentModelFailure) {
        // 日志失败不影响重试语义；纯 JVM 单测里 Android 日志可能未被 mock。
        runCatching {
            AndroidAgentLogger.warnThrottled("model_retry:${failure.code}") {
                "模型请求失败，${delayMs}ms 后第 $attempt/$maxRetries 次重试（round=$round，code=${failure.code}）：" +
                    "上次失败原因：${AgentModelFailure.failureSummary(failure.message)}"
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
