package io.github.mangi.eta.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 任务清单卡片：进度与条目可见、点击头部可折叠；todo_write 消息路由到卡片。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodoCardLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun todoMessage(id: String = "todo-1") = ToolActivityMessageUi(
        id = id,
        toolName = "todo_write",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "任务清单 · 已完成 1/3 · 进行中：第二步",
        // 真实投影：清单文本在 resultSummary（summarizeResult 输出）；detail 为空也不影响卡片。
        resultSummary = "进度 1/3\n✓ 第一步\n◐ 第二步\n○ 第三步",
    )

    @Test
    fun cardShowsProgressAndAllEntries() {
        val todo = parseTodoListDetail(todoMessage().resultSummary)
        assertNotNull(todo)
        compose.setContent {
            MiuixTheme {
                TodoCard(message = todoMessage(), todo = requireNotNull(todo))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TodoCardTag).assertExists()
        compose.onNodeWithText("1/3").assertExists()
        compose.onNodeWithText("第一步").assertExists()
        compose.onNodeWithText("第二步").assertExists()
        compose.onNodeWithText("第三步").assertExists()
        // 条目序号与「进行中」文字标注：执行到哪一步直接可读（Robolectric 默认 en-US 资源）。
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("In progress").assertExists()
    }

    @Test
    fun headerClickCollapsesAndExpandsEntries() {
        val todo = parseTodoListDetail(todoMessage().resultSummary)
        assertNotNull(todo)
        compose.setContent {
            MiuixTheme {
                TodoCard(message = todoMessage(), todo = requireNotNull(todo))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("第二步").assertExists()

        compose.onNodeWithTag(TodoCardHeaderTag).performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("第二步").assertDoesNotExist()

        compose.onNodeWithTag(TodoCardHeaderTag).performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("第二步").assertExists()
    }

    @Test
    fun todoWriteMessageRoutesToCardInsteadOfPlainToolRow() {
        compose.setContent {
            MiuixTheme {
                ChatMessageItem(
                    message = todoMessage(id = "todo-2"),
                    onSuggestionClick = {},
                    onRunTraceClick = {},
                    onOpenBrowser = {},
                    showBrowserShortcut = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TodoCardTag).assertExists()
        compose.onNodeWithText("第一步").assertExists()
    }
}
