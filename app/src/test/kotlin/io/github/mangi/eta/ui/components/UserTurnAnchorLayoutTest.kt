package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserTurnAnchorLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sendingAfterHistoryPlacesUserAtTopBeforeModelStarts() {
        verifyTopAnchor(historyCount = 12, showThinking = false)
    }

    @Test
    fun firstMessageAndInitialThinkingKeepTheSameTopPosition() {
        verifyTopAnchor(historyCount = 0, showThinking = true)
    }

    private fun verifyTopAnchor(historyCount: Int, showThinking: Boolean) {
        val history = List(historyCount) { UserMessageUi("history-$it", "Previous turn $it") }
        val messages = mutableStateOf<List<AgentChatMessageUi>>(history)
        val phase = mutableStateOf(UserTurnScrollState())
        val streaming = mutableStateOf(false)
        val scroll = LazyListState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiuixTheme {
                AgentConversationMessages(
                    visibleMessages = messages.value,
                    scrollState = scroll,
                    isStreaming = streaming.value,
                    bottomInset = 120.dp,
                    keepBottomAnchored = true,
                    userTurnScrollState = phase.value,
                    thinkingCollapseResetKey = 1L,
                    onBottomAnchorChanged = {},
                    onUserTurnScrollStateChanged = { phase.value = it },
                    modifier = Modifier.width(360.dp).height(600.dp),
                )
            }
        }
        advanceLayouts()
        compose.runOnIdle {
            phase.value = resolveUserTurnScrollTransition(
                phase.value,
                UserTurnScrollEvent.Submitted(previousUserMessageId = history.lastOrNull()?.id),
            )
            messages.value = history + UserMessageUi(
                "new-user",
                "Please analyze the power difference when the frequencies are similar.",
            )
        }
        advanceLayouts()
        compose.runOnIdle {
            assertEquals(UserTurnScrollPhase.Pinned, phase.value.phase)
            val user = scroll.layoutInfo.visibleItemsInfo.singleOrNull { it.key == "new-user" }
                ?: error("New user not laid out: phase=${phase.value}, layout=${scroll.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }}, viewport=${scroll.layoutInfo.viewportSize}, count=${scroll.layoutInfo.totalItemsCount}")
            assertEquals(0, user.offset)
            assertEquals(historyCount, scroll.firstVisibleItemIndex)
            assertEquals(0, scroll.firstVisibleItemScrollOffset)
        }
        if (showThinking) {
            compose.runOnIdle {
                streaming.value = true
                messages.value = messages.value + ThinkingMessageUi("thinking", "Checking evidence", true)
            }
            advanceLayouts()
            compose.runOnIdle {
                assertEquals(UserTurnScrollPhase.Pinned, phase.value.phase)
                val user = scroll.layoutInfo.visibleItemsInfo.single { it.key == "new-user" }
                assertEquals(0, user.offset)
                val thinking = scroll.layoutInfo.visibleItemsInfo.single { it.key == "thinking-thinking" }
                assertTrue(thinking.offset > user.offset)
            }
        }
    }

    private fun advanceLayouts() {
        repeat(8) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }
}
