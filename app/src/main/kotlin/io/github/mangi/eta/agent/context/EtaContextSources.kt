package io.github.mangi.eta.agent.context

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentPromptBuilder
import io.github.mangi.eta.agent.skill.SkillContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal class RootCapabilitiesSource(
    private val capabilitiesProvider: () -> Boolean,
) : ContextSource<Boolean> {
    override val key: String = "core/capabilities"
    override val codec: ContextCodec<Boolean> = BooleanContextCodec
    override fun observe(): SourceObservation<Boolean> = SourceObservation.Available(capabilitiesProvider())
    override fun baseline(current: Boolean): String = AgentPromptBuilder.renderRootCapabilities(current)
    override fun update(previous: Boolean, current: Boolean): String =
        "设备 Root 可用性已从 $previous 变为 $current。请以后续规则为准。\n\n${baseline(current)}"
}

internal data class MemoryFingerprint(
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
)

private object MemoryFingerprintCodec : ContextCodec<MemoryFingerprint> {
    override fun encode(value: MemoryFingerprint): JsonElement = buildJsonObject {
        put("revision", JsonPrimitive(value.revision))
        put("bytes", JsonPrimitive(value.byteSize))
        put("core", JsonPrimitive(value.coreContent))
        put("truncated", JsonPrimitive(value.coreTruncated))
        put("headings", JsonPrimitive(value.headingIndex))
        put("budget", JsonPrimitive(value.coreBudgetChars))
    }

    override fun decode(value: JsonElement): MemoryFingerprint? = runCatching {
        val json = value as JsonObject
        MemoryFingerprint(
            revision = json["revision"]?.jsonPrimitive?.content ?: return null,
            byteSize = json["bytes"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null,
            coreContent = json["core"]?.jsonPrimitive?.content ?: "",
            coreTruncated = json["truncated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            headingIndex = json["headings"]?.jsonPrimitive?.content ?: "",
            coreBudgetChars = json["budget"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }.getOrNull()
}

internal class MemoryContextSource(
    private val contextProvider: () -> AgentMemoryContext,
    private val writable: Boolean,
    private val roleplay: Boolean,
) : ContextSource<MemoryFingerprint> {
    override val key: String = "core/memory"
    override val codec: ContextCodec<MemoryFingerprint> = MemoryFingerprintCodec

    override fun observe(): SourceObservation<MemoryFingerprint> {
        val context = contextProvider()
        if (!context.enabled) return SourceObservation.Removed
        return SourceObservation.Available(context.toFingerprint())
    }

    override fun baseline(current: MemoryFingerprint): String = render(current)

    override fun update(previous: MemoryFingerprint, current: MemoryFingerprint): String =
        "以下记忆上下文替换之前加载的记忆上下文。\n\n${render(current)}"

    override fun removal(previous: MemoryFingerprint): String =
        "之前加载的持久记忆上下文已不再适用。"

    private fun render(value: MemoryFingerprint): String = AgentPromptBuilder.renderMemoryContext(
        AgentMemoryContext(
            enabled = true,
            revision = value.revision,
            byteSize = value.byteSize,
            coreContent = value.coreContent,
            coreTruncated = value.coreTruncated,
            headingIndex = value.headingIndex,
            coreBudgetChars = value.coreBudgetChars,
        ),
        writable = writable,
        roleplay = roleplay,
    ).orEmpty()

    private fun AgentMemoryContext.toFingerprint() = MemoryFingerprint(
        revision = revision,
        byteSize = byteSize,
        coreContent = coreContent,
        coreTruncated = coreTruncated,
        headingIndex = headingIndex,
        coreBudgetChars = coreBudgetChars,
    )
}

internal data class SkillsFingerprint(val ids: List<String>)

private object SkillsFingerprintCodec : ContextCodec<SkillsFingerprint> {
    override fun encode(value: SkillsFingerprint): JsonElement = JsonArray(value.ids.map(::JsonPrimitive))
    override fun decode(value: JsonElement): SkillsFingerprint? = runCatching {
        SkillsFingerprint(value.jsonArray.map { it.jsonPrimitive.content })
    }.getOrNull()
}

internal class SkillsContextSource(
    private val contextProvider: () -> SkillContext,
) : ContextSource<SkillsFingerprint> {
    override val key: String = "core/skills"
    override val codec: ContextCodec<SkillsFingerprint> = SkillsFingerprintCodec

    override fun observe(): SourceObservation<SkillsFingerprint> {
        val context = contextProvider()
        if (context.installedSkills.isEmpty()) return SourceObservation.Removed
        return SourceObservation.Available(SkillsFingerprint(context.installedSkills.map { it.id }))
    }

    override fun baseline(current: SkillsFingerprint): String = render()

    override fun update(previous: SkillsFingerprint, current: SkillsFingerprint): String =
        "以下 Skills 索引替换之前加载的 Skills 索引。\n\n${render()}"

    override fun removal(previous: SkillsFingerprint): String =
        "之前加载的 Skills 索引已不再适用。"

    private fun render(): String = AgentPromptBuilder.renderSkillsContext(contextProvider()).orEmpty()
}
