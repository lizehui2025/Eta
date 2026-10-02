package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

/** Pure continuation prompt construction for a failed run. */
internal object RetryFailedRun {
    fun continuationPrompt(messages: List<AgentChatMessageUi>): String {
        val latestUserIndex = messages.indexOfLast {
            it is UserMessageUi && "-supplement-" !in it.id
        }
        if (latestUserIndex < 0) return DEFAULT_PROMPT

        val followingMessages = messages.drop(latestUserIndex + 1).let { following ->
            following.dropLast(1).takeIf { following.lastOrNull() is SystemNoticeMessageUi }
                ?: following
        }
        val alreadyShown = followingMessages
            .filterIsInstance<AgentMessageUi>()
            .asSequence()
            .map { it.content.trim() }
            .filter(String::isNotBlank)
            .toList()
            .takeLast(2)
            .joinToString("\n\n")
            .takeLast(MAX_SHOWN_OUTPUT_CHARS)

        return buildString {
            append(DEFAULT_PROMPT)
            if (alreadyShown.isNotBlank()) {
                append("\n已显示给用户的末尾内容（仅用于避免重复，不要复述）：\n")
                append(alreadyShown)
            }
        }
    }

    private const val MAX_SHOWN_OUTPUT_CHARS = 4_000
    private const val DEFAULT_PROMPT =
        "继续完成上一条用户请求。此前已完成的工具调用和结果都在历史中；" +
            "不要重复执行已完成的操作，也不要重复已向用户输出的内容。" +
            "先核对已有结果，再处理尚未完成的部分。"
}
