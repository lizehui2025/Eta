package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.ThreadLocalRandom

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    /** 临时网络错误默认有限重试；普通聊天可显式启用持续恢复。 */
    private val maxRetries: Int = MAX_RETRIES,
    /** 退避抖动；测试可注入固定值，生产默认按 ±20% 抖动，避免多客户端重试风暴。 */
    private val delayTransform: (Long) -> Long = { it },
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
                if (retries >= maxRetries) {
                    val retryDescription = if (maxRetries == 0) {
                        "请求未完成"
                    } else {
                        "已重试 $maxRetries 次仍未恢复"
                    }
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} $retryDescription，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val exponent = (retries - 1).coerceAtMost(5)
                val delayMs = delayTransform((BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)).coerceAtLeast(0L)
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
        private const val BASE_DELAY_MS = 2_000L
        private const val MAX_DELAY_MS = 60_000L
        private const val MAX_RETRIES = 3
    }
}

/** ±20% 的通用退避抖动；保底 1ms，避免计算出 0 后形成忙重试。 */
internal fun defaultRetryJitter(delayMs: Long): Long {
    if (delayMs <= 1L) return delayMs
    val factor = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4
    return (delayMs * factor).toLong().coerceAtLeast(1L)
}
