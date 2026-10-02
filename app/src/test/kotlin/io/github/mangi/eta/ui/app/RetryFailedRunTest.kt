package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryFailedRunTest {
    @Test
    fun continuationIncludesOnlyRecentVisibleOutput() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-1", content = "完成任务"),
            AgentMessageUi(id = "assistant-1", content = "第一段"),
            AgentMessageUi(id = "assistant-2", content = "第二段"),
            AgentMessageUi(id = "assistant-failure", content = ""),
        )

        val prompt = RetryFailedRun.continuationPrompt(messages)

        assertTrue(prompt.contains("第二段"))
        assertTrue(prompt.contains("第一段"))
        assertTrue(prompt.contains("不要重复"))
    }

    @Test
    fun continuationDoesNotTreatSupplementAsNewUserTurn() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-1", content = "原始请求"),
            UserMessageUi(id = "run-supplement-0", content = "内部补充"),
            AgentMessageUi(id = "assistant-failure", content = ""),
        )

        val prompt = RetryFailedRun.continuationPrompt(messages)

        assertEquals(false, prompt.contains("内部补充"))
    }
}
