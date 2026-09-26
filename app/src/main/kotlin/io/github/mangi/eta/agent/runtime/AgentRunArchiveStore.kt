package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeArchiveEventEntity
import io.github.mangi.eta.data.db.RuntimeArchiveRunEntity
import io.github.mangi.eta.data.db.RuntimeArchiveRunWithEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * Process-persistent archive for externally initiated runs that should later be
 * mirrored into the module's own chat history.
 *
 * Unlike [AgentRuntimeResultStore], entries here are not an entry-adapter retry
 * queue. They preserve the event trace so the first-party UI can reconstruct
 * thinking and tool activity that third-party assistant surfaces cannot show.
 */
internal object AgentRunArchiveStore {
    /** 外部归档保留上限：超限删最旧，避免进程级持久堆积。 */
    private const val MAX_RETAINED_ARCHIVES = 20

    data class ArchivedRun(
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val result: AgentRuntimeWire.RunResult,
        val createdAt: Long,
        val userImagePreviews: List<String> = emptyList(),
    )

    fun add(context: Context, run: ArchivedRun) {
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(appContext).runtimeRunDao()
            val compacted = run.copy(events = compactEvents(run.events))
            val archiveRunId = compacted.archiveRunId
            dao.replaceArchivedRun(
                run = compacted.toEntity(archiveRunId),
                events = compacted.toEventEntities(archiveRunId),
            )
            runCatching {
                // 旧实现在这里加载全部归档（含事件 JSON）只为裁掉最旧的溢出项；现在只做一次计数。
                val deleteCount = dao.archivedRunCount() - MAX_RETAINED_ARCHIVES
                if (deleteCount > 0) {
                    // 上限常量与“淘汰最旧”的语义不变：仍按 created_at 升序取溢出的 archiveId，再批量删主记录与事件。
                    val archiveRunIds = dao.oldestArchivedRunIds(deleteCount)
                    if (archiveRunIds.isNotEmpty()) {
                        dao.deleteArchivedRunsByArchiveIds(archiveRunIds)
                    }
                }
            }.onFailure { throwable ->
                // 裁剪失败保持原有语义（异常不外抛、归档写入结果不变），但必须可见：
                // 失败意味着 MAX_RETAINED_ARCHIVES 上限可能暂时失效，归档会继续堆积。
                AndroidAgentLogger.warnThrottled("runtime_archive_prune_failed") {
                    "Agent run archive prune failed; retained archives may exceed " +
                        "MAX_RETAINED_ARCHIVES=$MAX_RETAINED_ARCHIVES: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    fun list(context: Context): List<ArchivedRun> {
        val appContext = context.applicationContext
        return runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext).runtimeRunDao().archivedRuns()
                .mapNotNull { it.toDomain() }
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .runtimeRunDao()
                .deleteArchivedRun(runId)
        }
    }

    private val ArchivedRun.archiveRunId: String
        get() = result.runId.ifBlank { handoff.id }

    private fun ArchivedRun.toEntity(archiveRunId: String): RuntimeArchiveRunEntity =
        RuntimeArchiveRunEntity(
            archiveRunId = archiveRunId,
            runId = result.runId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            ok = result.ok,
            content = result.content,
            error = result.error,
            reasoningContent = result.reasoningContent,
            transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(result.transcript),
            contextSnapshotJson = result.contextSnapshot?.encode().orEmpty(),
            operation = result.operation,
            rewriteTargetMessageId = result.rewriteTargetMessageId,
            userImagePreviewsJson = JSONArray(userImagePreviews).toString(),
            createdAt = createdAt,
        )

    private fun ArchivedRun.toEventEntities(archiveRunId: String): List<RuntimeArchiveEventEntity> =
        events.mapIndexed { index, event ->
            RuntimeArchiveEventEntity(
                archiveRunId = archiveRunId,
                sortIndex = index,
                eventJson = AgentEventJsonCodec.encode(event),
            )
        }

    private fun RuntimeArchiveRunWithEvents.toDomain(): ArchivedRun? =
        runCatching {
            ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = run.handoffId,
                    source = run.handoffSource,
                    payload = run.handoffPayload,
                    dismissEntrySurfaceOnForegroundOperation = run.dismissEntrySurface,
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = run.runId.ifBlank { run.archiveRunId },
                    ok = run.ok,
                    content = run.content,
                    error = run.error,
                    reasoningContent = run.reasoningContent,
                    contextSnapshot = AgentContextSnapshot.decode(run.contextSnapshotJson),
                    operation = run.operation,
                    rewriteTargetMessageId = run.rewriteTargetMessageId,
                    transcript = AgentConversationCodec.decodeTranscript(run.transcriptJson).ifEmpty {
                        if (!run.ok || run.content.isBlank()) return@ifEmpty emptyList()
                        listOf(
                            AgentModelClient.ConversationMessage(
                                role = "assistant",
                                content = run.content,
                                reasoningContent = run.reasoningContent,
                            )
                        )
                    },
                ),
                createdAt = run.createdAt,
                userImagePreviews = JSONArray(run.userImagePreviewsJson).let { previews ->
                    buildList {
                        for (index in 0 until previews.length()) {
                            previews.optString(index)
                                .takeIf { it.startsWith("data:image/") }
                                ?.let(::add)
                        }
                    }
                },
                events = events
                    .sortedBy { it.sortIndex }
                    .mapNotNull { event -> AgentEventJsonCodec.decode(event.eventJson) },
            )
        }.getOrNull()

    /**
     * 归档合并段：文本增量写入 [StringBuilder]，段落结束时才物化一次，
     * 避免 `previous.delta + event.delta` 的逐次拼接（整段合并是 O(n²) 字符拷贝）。
     */
    private class DeltaGroup(
        private val head: AgentEvent.AssistantBlockDelta,
    ) {
        private val text = StringBuilder(head.delta)

        private var deltaChars: Int = head.deltaChars

        fun matches(other: AgentEvent.AssistantBlockDelta): Boolean =
            head.round == other.round &&
                head.kind == other.kind &&
                head.index == other.index

        fun append(other: AgentEvent.AssistantBlockDelta) {
            text.append(other.delta)
            deltaChars += other.deltaChars
        }

        fun toEvent(): AgentEvent.AssistantBlockDelta =
            head.copy(delta = text.toString(), deltaChars = deltaChars)
    }

    private fun compactEvents(events: List<AgentEvent>): List<AgentEvent> {
        val compacted = mutableListOf<AgentEvent>()
        var group: DeltaGroup? = null

        fun flushGroup() {
            group?.let { compacted += it.toEvent() }
            group = null
        }

        events.forEach { event ->
            if (
                event is AgentEvent.AssistantBlockDelta &&
                event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL
            ) {
                // 与旧实现一致：tool_call 增量整条丢弃，且不影响正在合并的段落。
                return@forEach
            }
            val current = group
            if (
                current != null &&
                event is AgentEvent.AssistantBlockDelta &&
                current.matches(event)
            ) {
                current.append(event)
                return@forEach
            }
            // 段落边界：先把上一段落成一条事件（位置顺序与旧实现相同），再开启新段。
            flushGroup()
            if (event is AgentEvent.AssistantBlockDelta) {
                group = DeltaGroup(event)
            } else {
                compacted += event
            }
        }
        flushGroup()
        return compacted
    }
}
