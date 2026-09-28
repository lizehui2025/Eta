package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executor behind [AgentInteractionToolCatalog.TOOL_NAME].
 *
 * It owns the whole ask-and-wait exchange: emit the question so every subscriber (main app,
 * voice overlay, replay buffer) can render it, block the run thread until an answer arrives,
 * and turn the outcome into a tool result the model can act on.
 *
 * The blocking wait is bounded and cancellable by design: [AgentRunController.awaitUserAnswer]
 * wakes up on answers, on cancellation and on the timeout, and cancellation still wins because
 * the loop aborts right after the wait returns.
 */
internal class AgentUserQuestionTool(
    private val controller: AgentRunController,
    private val onEvent: (AgentEvent) -> Unit,
    /** Wait cap; tests inject a short limit to cover the timeout path. */
    private val waitTimeoutMs: Long = WAIT_TIMEOUT_MS,
) {
    fun ask(round: Int, toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = toolCall.parsedArgsOrNull() ?: JSONObject()
        val question = args.optString("question").trim()
        if (question.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "问题内容为空，未向用户提问")
        }
        val options = parseOptions(args)
        val multiSelect = args.optBoolean("multi_select", false)
        val allowFreeform = args.optBoolean("allow_freeform", true)
        if (options.isEmpty() && !allowFreeform) {
            // No options and no free-form input means the user cannot answer at all, so make the model restate the question.
            return errorResult("INVALID_ARGUMENT", "既没有候选项也不允许自由输入，用户无法作答")
        }

        // questionId is the toolCallId: it aligns with the tool trace, so the answering side needs no extra mapping.
        val questionId = toolCall.id
        val answer = controller.awaitUserAnswer(questionId, waitTimeoutMs) {
            onEvent(
                AgentEvent.UserQuestionAsked(
                    questionId = questionId,
                    round = round,
                    toolCallId = toolCall.id,
                    question = question,
                    options = options,
                    multiSelect = multiSelect,
                    allowFreeform = allowFreeform,
                ),
            )
        }
        if (answer.timedOut) {
            // A timeout must broadcast a terminal event too, or the card would wait forever.
            onEvent(
                AgentEvent.UserQuestionAnswered(
                    questionId = questionId,
                    answer = "",
                    selectedOptions = emptyList(),
                    timedOut = true,
                ),
            )
            return errorResult(
                code = "USER_NO_ANSWER",
                message = "用户在 ${waitMinutes()} 分钟内未作答；请按最安全的默认继续，或说明需要用户决定什么",
            )
        }
        return AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("answer", answer.answer)
                .put("selected_options", JSONArray(answer.selectedOptions))
                .put("timed_out", false)
                .toString(),
        )
    }

    /** 向上取整到分钟且至少为 1：短超时（测试注入）或非整分值不能回给模型"0 分钟"。 */
    private fun waitMinutes(): Long = ((waitTimeoutMs + 59_999L) / 60_000L).coerceAtLeast(1L)

    private fun parseOptions(args: JSONObject): List<String> {
        val raw = args.optJSONArray("options") ?: return emptyList()
        val bounded = mutableListOf<String>()
        for (index in 0 until raw.length()) {
            val text = raw.optString(index).trim()
            if (text.isEmpty()) continue
            bounded += text.take(AgentInteractionToolCatalog.MAX_OPTION_CHARS)
            if (bounded.size >= AgentInteractionToolCatalog.MAX_OPTIONS) break
        }
        return bounded.distinct()
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    companion object {
        /** Wait cap for a user answer: 5 minutes. On timeout it returns USER_NO_ANSWER and the model decides
     * whether to continue or stop. */
        const val WAIT_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
