package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSseReaderTest {
    @Test
    fun framingHandlesCommentsMultilineDataAndFinalFrameWithoutBlankLine() {
        val source = ": keepalive\r\n\r\nevent: delta\r\ndata: 第一行\r\ndata: 第二行\r\n\r\n" +
            "event: done\ndata: 终态"
        val events = mutableListOf<Pair<String, String>>()
        readProviderSse(source.byteInputStream(), AgentRunController()) { name, data ->
            events += name to data
            true
        }
        assertEquals(listOf("delta" to "第一行\n第二行", "done" to "终态"), events)
    }

    @Test
    fun terminalEventClosesStreamWithoutReadingAgain() {
        val stream = TrackedStream("data: completed\n\n", rejectEofRead = true)
        readProviderSse(stream, AgentRunController()) { _, data ->
            assertEquals("completed", data)
            false
        }
        assertTrue(stream.closed)
    }

    @Test
    fun cancellationStopsBufferedEventsAndClosesStream() {
        val stream = TrackedStream("data: first\n\ndata: second\n\n")
        val controller = AgentRunController()
        val received = mutableListOf<String>()
        val failure = runCatching {
            readProviderSse(stream, controller) { _, data ->
                received += data
                controller.cancel()
                true
            }
        }.exceptionOrNull()
        assertTrue(failure is AgentRunCancelledException)
        assertEquals(listOf("first"), received)
        assertTrue(stream.closed)
    }

    @Test
    fun consumerFailurePropagatesAndClosesStream() {
        val stream = TrackedStream("data: invalid\n\n")
        val expected = IllegalArgumentException("Invalid fixture")
        val failure = runCatching {
            readProviderSse(stream, AgentRunController()) { _, _ -> throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(stream.closed)
    }

    @Test
    fun streamHeadBomIsStrippedBeforeFirstFrame() {
        val source = "\uFEFFevent: delta\r\ndata: 首帧\r\n\r\ndata: 次帧\r\n\r\n"
        val events = mutableListOf<Pair<String, String>>()
        readProviderSse(source.byteInputStream(), AgentRunController()) { name, data ->
            events += name to data
            true
        }
        assertEquals(listOf("delta" to "首帧", "" to "次帧"), events)
    }

    @Test
    fun crlfFramesAndMultilineDataAreParsed() {
        val source = "event: delta\r\ndata: 甲\r\ndata: 乙\r\n\r\nevent: done\r\ndata: 完\r\n"
        val events = mutableListOf<Pair<String, String>>()
        readProviderSse(source.byteInputStream(), AgentRunController()) { name, data ->
            events += name to data
            true
        }
        assertEquals(listOf("delta" to "甲\n乙", "done" to "完"), events)
    }

    @Test
    fun fieldLinesWithLeadingWhitespaceAreStillRecognized() {
        val source = "  event: delta\n\t data: 甲\n \tdata: 乙\n\n"
        val events = mutableListOf<Pair<String, String>>()
        readProviderSse(source.byteInputStream(), AgentRunController()) { name, data ->
            events += name to data
            true
        }
        assertEquals(listOf("delta" to "甲\n乙"), events)
    }

    private class TrackedStream(
        content: String,
        private val rejectEofRead: Boolean = false,
    ) : ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) {
        var closed = false
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!rejectEofRead || available() > 0) { "Reader waited beyond terminal event" }
            return super.read(buffer, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
