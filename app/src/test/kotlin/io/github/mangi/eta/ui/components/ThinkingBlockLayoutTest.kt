package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThinkingBlockLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activeBlockHidesHeaderAndCompletionRevealsSummary() {
        val thinking = mutableStateOf(ThinkingMessageUi("thought", "Evidence", true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                AgentThinkingBlock(
                    id = "block", messages = listOf(thinking.value),
                    thinkingViewportHeight = 600.dp,
                )
            }
        }
        advanceLayouts()
        // 流式期间：没有「正在思考」头部与折叠入口，正文与尾部工作行直接可见。
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText("Evidence").assertExists()
        compose.onNodeWithText("Reasoning…").assertExists()
        compose.runOnIdle { thinking.value = thinking.value.copy(isStreaming = false) }
        advanceLayouts()
        // 结束后：出现摘要头部，正文收起；再展开仍可查看。
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Reasoning completed").assertExists()
        compose.onNodeWithText("Evidence").assertDoesNotExist()
        compose.onNode(hasClickAction()).performClick()
        advanceLayouts()
        compose.onNodeWithText("Evidence").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun completionCollapsesContentAndRetainsOnlyExternalSpaceUntilRunEnds() {
        val thinking = mutableStateOf(ThinkingMessageUi("thought", "Evidence\n".repeat(200), true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                Column {
                    AgentThinkingBlock(
                        id = "block", messages = listOf(thinking.value),
                        thinkingViewportHeight = 600.dp,
                    )
                    top.yukonga.miuix.kmp.basic.Text("Following answer", Modifier.testTag("answer"))
                }
            }
        }
        advanceLayouts()
        val answerTop = compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInRoot.top
        val expandedCardHeight = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { thinking.value = thinking.value.copy(isStreaming = false) }
        advanceLayouts()
        compose.onNodeWithText("Evidence", substring = true).assertDoesNotExist()
        val collapsedCardHeight = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot.height
        assertTrue(collapsedCardHeight < expandedCardHeight / 2)
        assertEquals(compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot.height, collapsedCardHeight, 1f)
        assertTrue(compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInRoot.top < answerTop / 2)
    }

    @Test
    fun activeWindowCapsLongContentAndCompletionCollapses() {
        val thinking = mutableStateOf(ThinkingMessageUi("thought", "Evidence\n".repeat(500), true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                Column {
                    AgentThinkingBlock(
                        id = "block", messages = listOf(thinking.value),
                        thinkingViewportHeight = 600.dp,
                    )
                    top.yukonga.miuix.kmp.basic.Text("Following answer", Modifier.testTag("answer"))
                }
            }
        }
        advanceLayouts()
        val card = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot
        // 工作窗口限高：长内容不会把卡片撑满全屏（200dp 窗口 + 尾部行 + 内边距）。
        assertTrue(card.height < with(compose.density) { 320.dp.toPx() })
        // 卡片与后续内容之间没有预留空白。
        val answerTop = compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInRoot.top
        assertTrue(answerTop - card.bottom <= with(compose.density) { 8.dp.toPx() })
        // 结束后整块收起为一行摘要。
        compose.runOnIdle { thinking.value = thinking.value.copy(isStreaming = false) }
        advanceLayouts()
        val collapsed = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot
        assertTrue(collapsed.height < card.height / 2)
        assertTrue(compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInRoot.top < answerTop)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun workProcessDoesNotPaintItsSurface() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                MiuixTheme(colors = MiuixTheme.colorScheme.copy(surface = Color.Red, outline = Color.Black)) {
                    Column(Modifier.width(360.dp).height(600.dp).background(Color.White).testTag("canvas")) {
                        AgentThinkingBlock(
                            id = "block", messages = listOf(ThinkingMessageUi("thought", "Evidence", true)),
                            thinkingViewportHeight = 600.dp,
                        )
                    }
                }
            }
        }
        advanceLayouts()
        val card = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("canvas")
        val canvasTop = canvas.fetchSemanticsNode().boundsInRoot.top
        val pixels = canvas.captureToImage().toPixelMap()
        // 卡片下沿的保留区域与卡片内部的左侧轨道区域都不应出现 surface 底色。
        val belowCard = (card.bottom - canvasTop).toInt() + 24
        assertEquals(Color.White, pixels[pixels.width / 2, belowCard])
        assertEquals(Color.White, pixels[card.left.toInt() + 2, (card.top - canvasTop).toInt() + 2])
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun windowFadeBlendsIntoBackgroundInsteadOfErasingIt() {
        val thinking = ThinkingMessageUi("thought", "Evidence line\n".repeat(200), true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                Column(Modifier.width(360.dp).height(600.dp).background(Color.White).testTag("canvas")) {
                    AgentThinkingBlock(
                        id = "block", messages = listOf(thinking),
                        thinkingViewportHeight = 600.dp,
                    )
                }
            }
        }
        advanceLayouts()
        val card = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("canvas")
        val canvasTop = canvas.fetchSemanticsNode().boundsInRoot.top
        val pixels = canvas.captureToImage().toPixelMap()
        // 窗口顶部渐隐区必须融合到白色背景（不透明），而不是把底层背景一并抹掉（黑带/透明）。
        val x = (card.left + card.width * 0.4f).toInt()
        val y = (card.top - canvasTop).toInt() + 1
        val faded = pixels[x, y]
        assertTrue(faded.alpha > 0.9f)
    }

    @Test
    fun completedWorkProcessContainsToolRowsAndReportsStepCount() {
        val messages = listOf(
            ThinkingMessageUi("thought", "Evidence", false, elapsedSeconds = 3),
            ToolActivityMessageUi(
                id = "tool",
                toolName = "shell",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "echo hi",
            ),
        )
        compose.setContent {
            MiuixTheme {
                AgentThinkingBlock(id = "mixed", messages = messages)
            }
        }
        advanceLayouts()
        // 结束后折叠为一行：头部显示步骤数，工具行与思考正文默认不可见。
        compose.onNodeWithText("Completed 1 step").assertExists()
        compose.onNodeWithText("echo hi", substring = true).assertDoesNotExist()
        compose.onNode(hasClickAction()).performClick()
        advanceLayouts()
        compose.onNodeWithText("Evidence").assertExists()
        compose.onNodeWithText("echo hi", substring = true).assertExists()
    }

    @Test
    fun activeWorkProcessStaysExpandedAcrossMessageGaps() {
        val blockActive = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        val messages = listOf(
            ThinkingMessageUi("thought", "Evidence", false, elapsedSeconds = 3),
            ToolActivityMessageUi(
                id = "tool",
                toolName = "shell",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "echo hi",
            ),
        )
        compose.setContent {
            MiuixTheme {
                AgentThinkingBlock(id = "live", messages = messages, blockActive = blockActive.value)
            }
        }
        advanceLayouts()
        // 工作过程进行中：即使没有正在流式的思考/工具，也保持展开。
        compose.onNodeWithText("Evidence").assertExists()
        compose.onNodeWithText("echo hi", substring = true).assertExists()
        compose.runOnIdle { blockActive.value = false }
        advanceLayouts()
        // run 结束：收起为一行摘要。
        compose.onNodeWithText("echo hi", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Completed 1 step").assertExists()
    }

    private fun advanceLayouts() {
        repeat(8) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }
}
