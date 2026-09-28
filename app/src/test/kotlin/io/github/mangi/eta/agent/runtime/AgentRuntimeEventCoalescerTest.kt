package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeEventCoalescerTest {
    @Test
    fun textDeltasAreCoalescedWithoutChangingFinalText() {
        var now = 0L
        val coalescer = AgentRuntimeEventCoalescer(
            maxBufferedChars = 64,
            maxBufferedNanos = 40_000_000L,
            nanoTime = { now },
        )
        val ready = mutableListOf<AgentEvent>()
        repeat(10) { index ->
            ready += coalescer.offer(delta("x"))
            now += 1_000_000L
        }
        ready += coalescer.flush()

        assertEquals(10, ready.filterIsInstance<AgentEvent.AssistantBlockDelta>().sumOf { it.deltaChars })
        assertEquals("x".repeat(10), ready.filterIsInstance<AgentEvent.AssistantBlockDelta>().joinToString("") { it.delta })
        assertTrue(ready.size < 10)
    }

    @Test
    fun structuredEventFlushesPendingDeltaFirst() {
        var now = 0L
        val coalescer = AgentRuntimeEventCoalescer(
            maxBufferedChars = 1024,
            maxBufferedNanos = 10_000_000L,
            nanoTime = { now },
        )
        coalescer.offer(delta("先"))
        now += 1_000_000L
        val ready = coalescer.offer(
            AgentEvent.AssistantBlockEnd(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                contentChars = 1,
            )
        )

        assertEquals(2, ready.size)
        assertTrue(ready[0] is AgentEvent.AssistantBlockDelta)
        assertTrue(ready[1] is AgentEvent.AssistantBlockEnd)
    }

    @Test
    fun toolCallArgumentDeltasAreNotForwarded() {
        val coalescer = AgentRuntimeEventCoalescer()
        repeat(100) {
            assertTrue(coalescer.offer(delta("secret", AgentEvent.AssistantBlockKind.TOOL_CALL)).isEmpty())
        }
        assertTrue(coalescer.flush().isEmpty())
    }

    private fun delta(
        text: String,
        kind: AgentEvent.AssistantBlockKind = AgentEvent.AssistantBlockKind.TEXT,
    ) = AgentEvent.AssistantBlockDelta(
        round = 1,
        kind = kind,
        index = 0,
        deltaChars = text.length,
        delta = text,
    )
}
