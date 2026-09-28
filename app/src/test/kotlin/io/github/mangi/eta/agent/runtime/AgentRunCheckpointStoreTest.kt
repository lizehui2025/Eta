package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunCheckpointStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun recorderCoalescesTextAndPreservesVisibleToolTrace() {
        var nowNanos = 0L
        val request = request("run-1")
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request,
            nanoTime = { nowNanos },
        )!!

        recorder.accept(textDelta("你"))
        nowNanos = 300_000_000L
        recorder.accept(textDelta("好"))
        recorder.accept(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                index = 1,
                deltaChars = 18,
                delta = "{\"secret\":\"value\"}",
            )
        )
        recorder.accept(
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "uptime",
            )
        )
        recorder.accept(
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                resultSummary = "完成",
                imageCount = 0,
                imageBytes = 0,
                success = true,
            )
        )

        val restored = AgentRunCheckpointStore.list(context).single()
        val delta = restored.events.filterIsInstance<AgentEvent.AssistantBlockDelta>().single()
        assertEquals("你好", delta.delta)
        assertEquals(2, delta.deltaChars)
        val toolStarted = restored.events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("执行命令 · Android · root", toolStarted.argsPreview)
        assertEquals("uptime", toolStarted.command)
        val toolFinished = restored.events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("完成", toolFinished.resultSummary)
        assertFalse(restored.events.any { event ->
            event is AgentEvent.AssistantBlockDelta &&
                event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL
        })

        recorder.seal()
        assertEquals(
            "run-1",
            AgentRunCheckpointStore.list(context).single().runId,
        )
        AgentRunCheckpointStore.remove(context, "run-1")
        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    @Test
    fun checkpointsRemainVisibleToAReplacementUiInTheSameProcess() {
        val request = request("run-2")
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = request,
                ownerInstanceId = "old-process",
            )
        )
        AgentRunCheckpointStore.append(
            context = context,
            runId = request.runId,
            sortIndex = 0,
            event = AgentEvent.RunStarted(0, 0, 1, false),
        )

        assertEquals(
            "run-2",
            AgentRunCheckpointStore.list(context).single().runId,
        )
    }

    @Test
    fun discardRemovesCheckpointWithoutFlushingBufferedText() {
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request("run-discard"),
            nanoTime = { 0L },
        )!!
        recorder.accept(textDelta("不应保留"))

        recorder.discard()

        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    @Test
    fun appendTranscriptKeepsExistingChunksAndRestoresFullHistory() {
        val runId = "run-append"
        assertTrue(AgentRunCheckpointStore.start(context, request(runId)))

        val first = listOf(message("user", "第一问"), message("assistant", "第一答"))
        AgentRunCheckpointStore.appendTranscript(context, runId, first, fullRebuild = true)
        val firstChunk = runBlocking {
            EtaDatabase.get(context).runtimeRunDao()
                .textChunks("runtime_inflight_runs", runId, "transcriptJson", limit = 1, offset = 0)
                .single()
        }

        val second = listOf(message("user", "第二问"))
        AgentRunCheckpointStore.appendTranscript(context, runId, second, fullRebuild = false)

        val restored = AgentRunCheckpointStore.list(context).single().transcript
        assertEquals(first + second, restored)
        val chunks = runBlocking {
            EtaDatabase.get(context).runtimeRunDao()
                .textChunks("runtime_inflight_runs", runId, "transcriptJson", limit = 32, offset = 0)
        }
        assertEquals(firstChunk.content, chunks.first().content)
        assertTrue("追加必须保留旧分块", chunks.size >= 2)
    }

    @Test
    fun appendTranscriptMigratesLegacyInlineTranscriptOnce() {
        val runId = "run-legacy"
        val legacy = listOf(message("user", "旧问题"), message("assistant", "旧回答"))
        runBlocking {
            EtaDatabase.get(context).runtimeRunDao().replaceInFlightRun(
                io.github.mangi.eta.data.db.RuntimeInFlightRunEntity(
                    runId = runId,
                    ownerInstanceId = "test",
                    transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(legacy),
                    handoffId = runId,
                    handoffSource = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                    handoffPayload = "conversation-1",
                    dismissEntrySurface = false,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            )
        }

        AgentRunCheckpointStore.appendTranscript(
            context = context,
            runId = runId,
            messages = listOf(message("user", "新问题")),
            fullRebuild = false,
        )

        assertEquals(
            legacy + message("user", "新问题"),
            AgentRunCheckpointStore.list(context).single().transcript,
        )
    }

    @Test
    fun fullRebuildReplacesTranscriptInsteadOfAppending() {
        val runId = "run-rebuild"
        assertTrue(AgentRunCheckpointStore.start(context, request(runId)))
        AgentRunCheckpointStore.appendTranscript(
            context, runId, listOf(message("user", "旧")), fullRebuild = true,
        )
        AgentRunCheckpointStore.appendTranscript(
            context, runId, listOf(message("assistant", "新")), fullRebuild = true,
        )
        assertEquals(
            listOf(message("assistant", "新")),
            AgentRunCheckpointStore.list(context).single().transcript,
        )
    }

    private fun message(role: String, content: String) =
        AgentModelClient.ConversationMessage(role = role, content = content)

    private fun request(runId: String): AgentRuntimeWire.RunRequest =
        AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "测试",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = "conversation-1",
            ),
        )

    private fun textDelta(text: String): AgentEvent.AssistantBlockDelta =
        AgentEvent.AssistantBlockDelta(
            round = 1,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
            deltaChars = text.length,
            delta = text,
        )
}
