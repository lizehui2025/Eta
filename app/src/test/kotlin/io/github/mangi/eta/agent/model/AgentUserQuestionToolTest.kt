package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Executor and wait semantics of ask_user.
 *
 * Covers the three things that break easily: an answer wakes the wait, a timeout yields USER_NO_ANSWER,
 * and **cancellation wins over timeout** (a cancel wake-up must throw, never pose as "the user did not answer").
 */
class AgentUserQuestionToolTest {
    private val askedEvents = mutableListOf<AgentEvent>()
    private val answeredEvents = mutableListOf<AgentEvent>()

    private fun tool(
        controller: AgentRunController,
        timeoutMs: Long = 500L,
    ) = AgentUserQuestionTool(
        controller = controller,
        onEvent = { event ->
            when (event) {
                is AgentEvent.UserQuestionAsked -> askedEvents += event
                is AgentEvent.UserQuestionAnswered -> answeredEvents += event
                else -> Unit
            }
        },
        waitTimeoutMs = timeoutMs,
    )

    private fun toolCall(
        id: String = "call-1",
        question: String = "要保留哪个方案？",
        options: String = """["方案 A","方案 B"]""",
        multiSelect: Boolean = false,
        allowFreeform: Boolean = true,
    ) = AgentModelClient.ToolCall(
        id = id,
        name = AgentInteractionToolCatalog.TOOL_NAME,
        argumentsJson = JSONObject()
            .put("question", question)
            .put("options", org.json.JSONArray(options))
            .put("multi_select", multiSelect)
            .put("allow_freeform", allowFreeform)
            .toString(),
    )

    @Test
    fun answerWakesTheWaitAndReturnsThePickedOption() {
        val controller = AgentRunController()
        val result = AtomicReference<AgentModelClient.ToolResult?>()
        val thread = Thread {
            result.set(tool(controller).ask(round = 1, toolCall = toolCall()))
        }
        thread.start()

        awaitQuestionAsked()
        val asked = askedEvents.single() as AgentEvent.UserQuestionAsked
        assertEquals("call-1", asked.questionId)
        assertEquals(listOf("方案 A", "方案 B"), asked.options)
        assertTrue(controller.answerUserQuestion("call-1", "方案 A", listOf("方案 A")))

        thread.join(TimeUnit.SECONDS.toMillis(5))
        val content = JSONObject(result.get()!!.content)
        assertTrue(content.getBoolean("ok"))
        assertEquals("方案 A", content.getString("answer"))
        assertEquals("方案 A", content.getJSONArray("selected_options").getString(0))
        assertFalse(content.getBoolean("timed_out"))
        assertTrue("作答路径不再广播超时事件", answeredEvents.isEmpty())
    }

    @Test
    fun timeoutReturnsUserNoAnswerAndBroadcastsTerminalEvent() {
        val controller = AgentRunController()
        val result = tool(controller, timeoutMs = 80L).ask(round = 1, toolCall = toolCall(id = "call-timeout"))

        val content = JSONObject(result.content)
        assertFalse(content.getBoolean("ok"))
        assertEquals("USER_NO_ANSWER", content.getString("code"))
        val answered = answeredEvents.single() as AgentEvent.UserQuestionAnswered
        assertTrue(answered.timedOut)
        assertEquals("call-timeout", answered.questionId)
    }

    @Test
    fun cancellationWinsOverTimeout() {
        val controller = AgentRunController()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                tool(controller, timeoutMs = 5_000L).ask(round = 1, toolCall = toolCall(id = "call-cancel"))
                failure.set(IllegalStateException("取消后不应正常返回"))
            } catch (cancelled: AgentRunCancelledException) {
                failure.set(cancelled)
            }
        }
        thread.start()
        awaitQuestionAsked()
        controller.cancel()

        thread.join(TimeUnit.SECONDS.toMillis(5))
        assertTrue("取消必须抛 AgentRunCancelledException：${failure.get()}", failure.get() is AgentRunCancelledException)
    }

    @Test
    fun invalidArgumentsFailFastWithoutAsking() {
        val controller = AgentRunController()
        val blank = tool(controller).ask(1, toolCall(question = "   "))
        assertEquals("INVALID_ARGUMENT", JSONObject(blank.content).getString("code"))
        assertTrue("参数不合法时不应该向用户提问", askedEvents.isEmpty())

        val noWayToAnswer = tool(controller).ask(1, toolCall(options = "[]", allowFreeform = false))
        assertEquals("INVALID_ARGUMENT", JSONObject(noWayToAnswer.content).getString("code"))
    }

    @Test
    fun subagentsNeverMayAskTheUser() {
        assertTrue(AgentSubagentPolicy.isAllowed("read_file"))
        assertFalse("提问会反向打断用户，子代理一律禁止", AgentSubagentPolicy.isAllowed(AgentInteractionToolCatalog.TOOL_NAME))
    }

    private fun awaitQuestionAsked() {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (askedEvents.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "等待提问事件超时" }
            Thread.sleep(2)
        }
    }
}
