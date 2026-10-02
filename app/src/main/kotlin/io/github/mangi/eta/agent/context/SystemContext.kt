package io.github.mangi.eta.agent.context

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An observation can be temporarily unavailable without removing the last good value. */
internal sealed interface SourceObservation<out T> {
    data class Available<T>(val value: T) : SourceObservation<T>
    data object Unavailable : SourceObservation<Nothing>
    data object Removed : SourceObservation<Nothing>
}

internal interface ContextCodec<T : Any> {
    fun encode(value: T): JsonElement
    fun decode(value: JsonElement): T?
    fun equivalent(previous: T, current: T): Boolean = encode(previous) == encode(current)
}

internal interface ContextSource<T : Any> {
    val key: String
    val codec: ContextCodec<T>
    fun observe(): SourceObservation<T>
    fun baseline(current: T): String
    fun update(previous: T, current: T): String
    fun removal(previous: T): String? = null
}

internal data class ContextGeneration(
    val baseline: String,
    val snapshot: JsonObject,
)

internal sealed interface ContextInitialization {
    data class Ready(val generation: ContextGeneration) : ContextInitialization
    data class Blocked(val keys: List<String>) : ContextInitialization
}

internal sealed interface ContextReconcileResult {
    data object Unchanged : ContextReconcileResult
    data class Updated(val text: String, val snapshot: JsonObject) : ContextReconcileResult
    data class ReplacementReady(val generation: ContextGeneration) : ContextReconcileResult
    data object ReplacementBlocked : ContextReconcileResult
}

/**
 * Pure source state machine. Observations are captured once per call so a source cannot change
 * between the decision and the snapshot that is persisted with it.
 */
internal object SystemContext {
    fun initialize(sources: List<ContextSource<*>>): ContextInitialization {
        validateSources(sources)
        val observations = observe(sources)
        val blocked = observations.filterValues { it is SourceObservation.Unavailable }.keys.sorted()
        if (blocked.isNotEmpty()) return ContextInitialization.Blocked(blocked)
        return ContextInitialization.Ready(generation(sources, observations))
    }

    fun reconcile(
        sources: List<ContextSource<*>>,
        snapshot: JsonObject,
    ): ContextReconcileResult {
        validateSources(sources)
        val observations = observe(sources)
        if (observations.values.any { it is SourceObservation.Unavailable }) {
            return ContextReconcileResult.Unchanged
        }

        val currentKeys = sources.mapTo(mutableSetOf()) { it.key }
        val removedKeys = snapshot.keys.filter { it !in currentKeys }.sorted()
        val removalText = removedKeys.mapNotNull { key ->
            val source = sources.firstOrNull { it.key == key } ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val typed = source as ContextSource<Any>
            val previous = decode(source, snapshot[key] ?: return@mapNotNull null) ?: return@mapNotNull null
            render(typed.removal(previous))
        }
        if (removedKeys.any { key -> sources.none { it.key == key } }) {
            return ContextReconcileResult.ReplacementReady(generation(sources, observations))
        }
        if (snapshot.keys.any { key -> key !in currentKeys }) {
            return ContextReconcileResult.ReplacementReady(generation(sources, observations))
        }

        val next = snapshot.toMutableMap()
        val updates = mutableListOf<String>()
        for (source in sources) {
            when (val observation = observations.getValue(source.key)) {
                SourceObservation.Unavailable -> Unit
                SourceObservation.Removed -> {
                    val previous = snapshot[source.key]?.let { decode(source, it) }
                    if (previous == null && source.key in snapshot) {
                        return ContextReconcileResult.ReplacementReady(generation(sources, observations))
                    }
                    next.remove(source.key)
                    if (previous != null) {
                        @Suppress("UNCHECKED_CAST")
                        val typed = source as ContextSource<Any>
                        typed.removal(previous)?.let { updates += render(it) }
                    }
                }
                is SourceObservation.Available<*> -> {
                    val current = observation.value ?: continue
                    val previousJson = snapshot[source.key]
                    val previous = previousJson?.let { decode(source, it) }
                    if (previousJson != null && previous == null) {
                        return ContextReconcileResult.ReplacementReady(generation(sources, observations))
                    }
                    @Suppress("UNCHECKED_CAST")
                    val typed = source as ContextSource<Any>
                    if (previous == null) {
                        updates += render(typed.baseline(current))
                    } else if (!typed.codec.equivalent(previous, current)) {
                        updates += render(typed.update(previous, current))
                    }
                    next[source.key] = typed.codec.encode(current)
                }
            }
        }
        updates += removalText
        return if (updates.isEmpty()) {
            ContextReconcileResult.Unchanged
        } else {
            ContextReconcileResult.Updated(updates.joinToString("\n\n"), buildJsonObject { next.forEach { (key, value) -> put(key, value) } })
        }
    }

    fun replace(
        sources: List<ContextSource<*>>,
    ): ContextReconcileResult {
        validateSources(sources)
        val observations = observe(sources)
        if (observations.values.any { it is SourceObservation.Unavailable }) {
            return ContextReconcileResult.ReplacementBlocked
        }
        return ContextReconcileResult.ReplacementReady(generation(sources, observations))
    }

    private fun generation(
        sources: List<ContextSource<*>>,
        observations: Map<String, SourceObservation<*>>,
    ): ContextGeneration {
        val snapshot = buildJsonObject {
            sources.forEach { source ->
                val observation = observations.getValue(source.key)
                if (observation is SourceObservation.Available<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val typed = source as ContextSource<Any>
                    put(source.key, typed.codec.encode(observation.value as Any))
                }
            }
        }
        val text = sources.mapNotNull { source ->
            val observation = observations.getValue(source.key)
            if (observation is SourceObservation.Available<*>) {
                @Suppress("UNCHECKED_CAST")
                render((source as ContextSource<Any>).baseline(observation.value as Any))
            } else {
                null
            }
        }.joinToString("\n\n")
        return ContextGeneration(render(text), snapshot)
    }

    private fun observe(sources: List<ContextSource<*>>): Map<String, SourceObservation<*>> =
        sources.associate { it.key to it.observe() }

    private fun validateSources(sources: List<ContextSource<*>>) {
        val keys = mutableSetOf<String>()
        sources.forEach { source ->
            require(source.key.substringBefore('/').isNotBlank() && '/' in source.key) {
                "Context source key must be namespaced: ${source.key}"
            }
            require(keys.add(source.key)) { "Duplicate context source key: ${source.key}" }
        }
    }

    private fun decode(source: ContextSource<*>, json: JsonElement): Any? {
        @Suppress("UNCHECKED_CAST")
        return (source.codec as ContextCodec<Any>).decode(json)
    }

    private fun render(text: String?): String {
        require(!text.isNullOrBlank()) { "Context source render must not be blank" }
        return text
    }
}

internal object BooleanContextCodec : ContextCodec<Boolean> {
    override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
    override fun decode(value: JsonElement): Boolean? = value.jsonPrimitive.booleanOrNull
}

private val kotlinx.serialization.json.JsonPrimitive.booleanOrNull: Boolean?
    get() = content.toBooleanStrictOrNull()
