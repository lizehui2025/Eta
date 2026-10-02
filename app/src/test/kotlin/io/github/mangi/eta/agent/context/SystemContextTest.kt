package io.github.mangi.eta.agent.context

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemContextTest {
    @Test
    fun initializeCapturesStructuredSnapshotAndBaseline() {
        val source = source("core/a", "one")

        val result = SystemContext.initialize(listOf(source)) as ContextInitialization.Ready

        assertEquals("baseline:one", result.generation.baseline)
        assertEquals("one", result.generation.snapshot["core/a"].toString().trim('"'))
    }

    @Test
    fun equivalentValueDoesNotProduceAnUpdate() {
        val source = source("core/a", "one")
        val initial = (SystemContext.initialize(listOf(source)) as ContextInitialization.Ready)
        val result = SystemContext.reconcile(listOf(source("core/a", "one")), initial.generation.snapshot)

        assertEquals(ContextReconcileResult.Unchanged, result)
    }

    @Test
    fun changedValueProducesUpdateAndNewSnapshot() {
        val initial = (SystemContext.initialize(listOf(source("core/a", "one"))) as ContextInitialization.Ready)
        val result = SystemContext.reconcile(listOf(source("core/a", "two")), initial.generation.snapshot)

        assertTrue(result is ContextReconcileResult.Updated)
        assertEquals("two", (result as ContextReconcileResult.Updated).snapshot["core/a"].toString().trim('"'))
        assertTrue(result.text.contains("update:one->two"))
    }

    @Test
    fun unavailableKeepsOldSnapshotAndBlocksReplacement() {
        val initial = (SystemContext.initialize(listOf(source("core/a", "one"))) as ContextInitialization.Ready)
        val unavailable = source("core/a", "two", SourceObservation.Unavailable)

        assertEquals(ContextReconcileResult.Unchanged, SystemContext.reconcile(listOf(unavailable), initial.generation.snapshot))
        assertEquals(ContextReconcileResult.ReplacementBlocked, SystemContext.replace(listOf(unavailable)))
    }

    @Test
    fun duplicateAndUnnamespacedKeysAreRejected() {
        assertThrows { SystemContext.initialize(listOf(source("a", "one"))) }
        assertThrows { SystemContext.initialize(listOf(source("core/a", "one"), source("core/a", "two"))) }
    }

    private fun source(
        key: String,
        value: String,
        observation: SourceObservation<String> = SourceObservation.Available(value),
    ) = object : ContextSource<String> {
        override val key = key
        override val codec = object : ContextCodec<String> {
            override fun encode(value: String): JsonElement = JsonPrimitive(value)
            override fun decode(value: JsonElement): String? = value.toString().trim('"')
        }
        override fun observe() = observation
        override fun baseline(current: String) = "baseline:$current"
        override fun update(previous: String, current: String) = "update:$previous->$current"
    }

    private fun assertThrows(block: () -> Unit) {
        runCatching { block() }.onSuccess { error("Expected exception") }
    }
}
