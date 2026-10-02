package io.github.mangi.eta.agent.context

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEpochTest {
    @Test
    fun updateIsPersistedAsAnEventWhileBaselineStaysStable() {
        val store = InMemoryContextEpochStore()
        val epoch = ContextEpoch("conversation", "scope", store)

        val first = epoch.prepare(listOf(source("one")))!!
        val second = epoch.prepare(listOf(source("two")))!!

        assertEquals("baseline:one", first.baseline)
        assertEquals("baseline:one", second.baseline)
        assertEquals(1, second.pendingEvents.size)
        assertTrue(second.pendingEvents.single().sourceText.contains("update:one->two"))
    }

    @Test
    fun compactionRequestsReplacementOnTheNextPrepare() {
        val store = InMemoryContextEpochStore()
        val epoch = ContextEpoch("conversation", "scope", store)
        epoch.prepare(listOf(source("one")))
        epoch.onCompactionCompleted()

        val prepared = epoch.prepare(listOf(source("two")))!!

        assertTrue(prepared.replacement)
        assertEquals("baseline:two", prepared.baseline)
        assertTrue(prepared.pendingEvents.isEmpty())
    }

    private fun source(value: String) = object : ContextSource<String> {
        override val key = "core/value"
        override val codec = object : ContextCodec<String> {
            override fun encode(value: String): JsonElement = JsonPrimitive(value)
            override fun decode(value: JsonElement): String? = value.toString().trim('"')
        }
        override fun observe() = SourceObservation.Available(value)
        override fun baseline(current: String) = "baseline:$current"
        override fun update(previous: String, current: String) = "update:$previous->$current"
    }
}
