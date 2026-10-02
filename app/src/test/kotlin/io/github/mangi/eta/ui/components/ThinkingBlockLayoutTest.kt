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
    fun runningBlockCanBeCollapsedAndNewTokensDoNotReopenIt() {
        val thinking = mutableStateOf(ThinkingMessageUi("thought", "Evidence", true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                AgentThinkingBlock(
                    id = "block", messages = listOf(thinking.value),
                    thinkingViewportHeight = 600.dp, modifier = Modifier.testTag("panel"),
                )
            }
        }
        advanceLayouts()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Evidence").assertExists()
        val expandedHeight = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot.height
        compose.onNode(hasClickAction()).performClick()
        advanceLayouts()
        compose.onNodeWithText("Evidence").assertDoesNotExist()
        val collapsedHeight = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot.height
        assertTrue(collapsedHeight < expandedHeight / 2)
        assertEquals(compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot.height, collapsedHeight, 1f)
        compose.runOnIdle { thinking.value = thinking.value.copy(content = "Evidence grows") }
        advanceLayouts()
        compose.onNodeWithText("Evidence grows").assertDoesNotExist()
        compose.onNode(hasClickAction()).performClick()
        advanceLayouts()
        compose.onNodeWithText("Evidence grows").assertExists()
        compose.runOnIdle { thinking.value = thinking.value.copy(isStreaming = false) }
        advanceLayouts()
        compose.onNodeWithText("Evidence grows").assertDoesNotExist()
        compose.onNode(hasClickAction()).performClick()
        advanceLayouts()
        compose.onNodeWithText("Evidence grows").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun completionCollapsesContentAndRetainsOnlyExternalSpaceUntilRunEnds() {
        val thinking = mutableStateOf(ThinkingMessageUi("thought", "Evidence", true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                Column {
                    AgentThinkingBlock(
                        id = "block", messages = listOf(thinking.value),
                        thinkingViewportHeight = 600.dp,
                        modifier = Modifier.testTag("panel"),
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
        compose.onNodeWithText("Evidence").assertDoesNotExist()
        val collapsedCardHeight = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot.height
        assertTrue(collapsedCardHeight < expandedCardHeight / 2)
        assertEquals(compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot.height, collapsedCardHeight, 1f)
        assertTrue(compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInRoot.top < answerTop / 2)
    }

    @Test
    fun manuallyCollapsedStreamingCardShrinksWithoutReservedSpaceAndLongContent() {
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
        compose.onNode(hasClickAction()).performClick()
        advanceLayouts()
        repeat(3) {
            compose.runOnIdle { thinking.value = thinking.value.copy(content = thinking.value.content + "More evidence\n") }
            advanceLayouts()
            val card = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot
            val header = compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertEquals(header.height, card.height, 1f)
            assertEquals(header.bottom, card.bottom, 1f)
            assertTrue(compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInRoot.top < 600f)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun manuallyCollapsedCardDoesNotPaintItsSurfaceAcrossTheRetainedGap() {
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
        compose.onNode(hasClickAction()).performClick()
        advanceLayouts()
        val card = compose.onNodeWithTag("thinking-card-block").fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("canvas")
        val canvasTop = canvas.fetchSemanticsNode().boundsInRoot.top
        val pixels = canvas.captureToImage().toPixelMap()
        val belowCard = (card.bottom - canvasTop).toInt() + 24
        assertEquals(Color.White, pixels[pixels.width / 2, belowCard])
        assertEquals(Color.White, pixels[card.left.toInt(), belowCard])
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

    private fun advanceLayouts() {
        repeat(8) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }
}
