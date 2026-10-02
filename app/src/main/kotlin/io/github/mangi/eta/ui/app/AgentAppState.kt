package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentMode
import io.github.mangi.eta.agent.model.AgentKind
import io.github.mangi.eta.agent.model.InstructionReview
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRunArchiveStore
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillNoticeUi
import io.github.mangi.eta.ui.model.SkillReplacementUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.UserQuestionMessageUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import io.github.mangi.eta.ui.model.contentMatches
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
) {
    private val appContext = context.applicationContext
    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private val runConversationIds = mutableMapOf<String, String>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private var currentRunId: String? = null
    private var currentRunJob: Job? = null
    private val persistenceLock = Any()
    private var persistenceJob: Job? = null
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    /** 初始会话快照异步加载的完成门控；加载完成或失败降级后都会 complete，等待方不会永挂。 */
    private val loadCompletion = CompletableDeferred<Unit>()
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null
    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var fileAttachmentOwnerVersion = 0L

    private var selectedConversationId: String? = null
    private var conversationsById: Map<String, AgentChatHomeUiState> = emptyMap()
    private var conversationTitles: Map<String, String> = emptyMap()
    private var conversationUpdatedAt: Map<String, Long> = emptyMap()
    /** history/journal 已加载的会话；未加载会话的正文保存在磁盘，保存时必须跳过 checkpoint。 */
    private val transcriptLoadedConversationIds = mutableSetOf<String>()
    private var conversationSwitchJob: Job? = null

    var homeState by mutableStateOf(emptyChatState(defaultThinkingEnabled))
        private set

    /** 初始会话快照是否仍在异步加载；加载期间 UI 显示占位，写盘与恢复流程等待 [loadCompletion]。 */
    var conversationsLoading by mutableStateOf(true)
        private set

    /** 非空时表示正在从磁盘加载目标会话的完整 transcript，UI 可据此显示加载态。 */
    var switchingConversationId by mutableStateOf<String?>(null)
        private set

    var modelPickerState by mutableStateOf(AgentModelPickerUiState())
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    var toolsState by mutableStateOf(buildToolsState(appContext))
        private set

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    var permissionHealthState by mutableStateOf(PermissionHealthUiState(emptyList()))
        private set

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    /** 顶栏的聊天/编码模式；编码模式不主动保存记忆。 */
    var agentMode by mutableStateOf(AgentMode.current())
        private set

    var agentKind by mutableStateOf(AgentKind.current(agentMode))
        private set

    var instructionReview by mutableStateOf(InstructionReview.current())
        private set

    init {
        scope.launch {
            val snapshot = try {
                withContext(Dispatchers.IO) { AgentConversationStore.load(appContext) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                AndroidAgentLogger.error(
                    "Agent conversation initial load failed: type=${throwable.safeLogType()}"
                )
                null
            }
            withContext(Dispatchers.Main.immediate) {
                if (snapshot != null) applyConversationSnapshot(snapshot)
                conversationsLoading = false
                loadCompletion.complete(Unit)
            }
        }
        refreshConversationSummaries()
        observeRuntimeSelection()
        scope.launch {
            RootAccess.state.collectLatest { refreshPermissionHealth() }
        }
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    /** 等待初始会话加载（或失败降级）完成；供测试与外部流程使用。 */
    internal suspend fun awaitInitialLoad() = loadCompletion.await()

    /**
     * 是否还有排队中的落盘任务。落盘是 fire-and-forget 的，测试结束时若仍有在途写入，
     * 它会落到下一个用例新建的库上（既有用例靠 deleteDatabase 隔离，挡不住晚到的写）。
     */
    internal fun hasPendingPersistence(): Boolean =
        (synchronized(persistenceLock) { persistenceJob })?.isActive == true

    private fun observeRuntimeSelection() {
        scope.launch(Dispatchers.IO) {
            combine(
                RuntimeConfigRepository.selectedProviderIdFlow(),
                RuntimeConfigRepository.selectedModelIdFlow(),
                ProviderRepository.providersFlow(),
            ) { providerId, modelId, providers ->
                Triple(providerId, modelId, providers)
            }
                .distinctUntilChanged()
                .collectLatest { (providerId, modelId, providers) ->
                    val pickerState = AgentModelPickerProjector.project(
                        providers = providers,
                        selectedProviderId = providerId,
                        selectedModelId = modelId,
                    )
                    val capabilities = RuntimeConfigRepository.currentRuntimeConfig()
                        ?.reasoningCapabilities
                    withContext(Dispatchers.Main) {
                        modelPickerState = pickerState.copy(
                            isChanging = modelPickerState.isChanging,
                        )
                        applyReasoningCapabilities(capabilities)
                    }
                }
        }
    }

    private fun applyReasoningCapabilities(capabilities: ModelReasoningCapabilities?) {
        currentReasoningCapabilities = capabilities
        val next = homeState.withCurrentReasoningCapabilities()
        val changed = next.reasoningEffort != homeState.reasoningEffort ||
            next.availableReasoningEfforts != homeState.availableReasoningEfforts
        updateCurrentConversation(next)
        if (changed && selectedConversationId != null) persistConversations()
    }

    private fun AgentChatHomeUiState.withCurrentReasoningCapabilities(): AgentChatHomeUiState {
        val normalized = currentReasoningCapabilities?.normalize(reasoningEffort) ?: ReasoningEffort.OFF
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
    }

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                loadCompletion.await()
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Triple(snapshot, enabled, AgentMemoryContextBuilder.coreBudgetChars(contextWindow))
            }.fold(
                onSuccess = { (snapshot, enabled, coreBudget) ->
                    withContext(Dispatchers.Main) {
                        memoryState = AgentMemoryUiState(
                            enabled = enabled,
                            isLoading = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            draftBytes = snapshot.byteSize,
                            coreBudgetChars = coreBudget,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    /**
     * 切换聊天/编码模式并持久化。切换立即生效（下一次 run 使用新模式）；
     * 记忆写入许可在执行期动态复查，因此进行中的 run 也会立即停止保存。
     */
    fun updateAgentMode(mode: AgentMode) {
        if (agentMode == mode) return
        agentMode = mode
        Prefs.setAgentMode(mode.wireValue)
        agentKind = AgentKind.current(mode)
    }

    fun updateAgentKind(kind: AgentKind) {
        if (kind.isCodeAgent != (agentMode == AgentMode.CODING) || agentKind == kind) return
        agentKind = kind
        Prefs.setAgentKind(agentMode.wireValue, kind.wireValue)
    }

    fun updateInstructionReview(review: InstructionReview) {
        if (instructionReview == review) return
        instructionReview = review
        Prefs.setInstructionReview(review.wireValue)
    }

    suspend fun exportBackup(output: OutputStream): EtaBackupSummary =
        EtaBackupRepository.export(appContext, output)

    suspend fun importBackup(input: InputStream): EtaBackupSummary {
        loadCompletion.await()
        val locallyBusy = withContext(Dispatchers.Main.immediate) {
            currentRunId != null || conversationsById.values.any { it.isStreaming }
        }
        if (locallyBusy) {
            throw IllegalStateException("请先停止正在运行的 Agent 任务")
        }

        val activeRunQuery = withContext(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRun()
        }
        when (val active = activeRunQuery) {
            is AgentRuntimeClient.ActiveRunQuery.Known -> {
                if (active.runId != null) {
                    throw IllegalStateException("请先停止正在运行的 Agent 任务")
                }
            }
            AgentRuntimeClient.ActiveRunQuery.Unavailable -> {
                throw IllegalStateException("无法确认 Agent Runtime 状态，请稍后重试")
            }
        }

        val pendingPersistence = synchronized(persistenceLock) { persistenceJob }
        pendingPersistence?.join()
        val summary = EtaBackupRepository.import(appContext, input)
        reloadConversationsAfterBackup()
        return summary
    }

    private suspend fun reloadConversationsAfterBackup() {
        val snapshot = withContext(Dispatchers.IO) {
            AgentConversationStore.load(appContext)
        }
        withContext(Dispatchers.Main.immediate) {
            applyConversationSnapshot(snapshot)
        }
    }

    /** 把磁盘快照应用到内存状态；必须在主线程调用，初始加载与备份导入共用这条路径。 */
    private fun applyConversationSnapshot(snapshot: AgentConversationStore.Snapshot) {
        selectedConversationId = snapshot.selectedConversationId
        conversationsById = snapshot.conversationsById
        conversationTitles = snapshot.titles
        conversationUpdatedAt = snapshot.updatedAt
        transcriptLoadedConversationIds.clear()
        transcriptLoadedConversationIds += snapshot.transcriptLoadedIds
        fileAttachmentOwnerVersion += 1
        homeState = selectedConversationId
            ?.let(conversationsById::get)
            ?.withCurrentReasoningCapabilities()
            ?: emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
        refreshConversationSummaries()
    }

    /** 用 checkpoint、终态 outbox 与 active session 一次性对账，避免用进程存活推断 run 状态。 */
    private suspend fun recoverRuntimeRuns() {
        loadCompletion.await()
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val checkpoints = withContext(Dispatchers.IO) {
            AgentRunCheckpointStore.list(appContext)
        }
        val initialCompletedQuery = client.queryCompletedRuns()
        if (initialCompletedQuery is AgentRuntimeClient.CompletedRunsQuery.Unavailable) {
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed"
            }
        }
        val initialCompletedRuns =
            (initialCompletedQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val activeRunQuery = client.queryActiveRun()
        val terminalRaceQuery = if (
            activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known && checkpoints.isNotEmpty()
        ) {
            client.queryCompletedRuns()
        } else {
            initialCompletedQuery
        }
        val terminalRaceCompletedRuns =
            (terminalRaceQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val completedRuns = (initialCompletedRuns + terminalRaceCompletedRuns)
            .associateBy { completed ->
                completed.result.runId.ifBlank { completed.handoff.id }
            }
            .values
            .toList()
        val activeStateKnown = activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known
        val terminalStateKnown = terminalRaceQuery is AgentRuntimeClient.CompletedRunsQuery.Known
        val activeRunId = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)?.runId
        val locallyObservedRunId = withContext(Dispatchers.Main) { currentRunId }
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = checkpoints,
            completedRuns = completedRuns,
            activeStateKnown = activeStateKnown,
            terminalStateKnown = terminalStateKnown,
            activeRunId = activeRunId,
            locallyObservedRunId = locallyObservedRunId,
        )
        val orphanRewrites = if (activeStateKnown && terminalStateKnown) withContext(Dispatchers.Main) {
            val observed = checkpoints.mapTo(mutableSetOf()) { it.runId }.apply {
                addAll(completedRuns.map { it.result.runId })
                activeRunId?.let(::add)
                currentRunId?.let(::add)
            }
            conversationsById.flatMap { (id, state) ->
                state.roleplayMessages.pendingRewrites.keys.filterNot { it in observed }.map { id to it }
            }
        } else emptyList()
        if (
            plan.completed.isEmpty() &&
            plan.interrupted.isEmpty() &&
            plan.reattach == null && orphanRewrites.isEmpty()
        ) {
            return
        }

        // 恢复会直接改 history/journal；先把涉及会话的正文从磁盘补回内存，
        // 否则未打开会话的空占位会被当成真实历史覆盖进库。
        ensureTranscriptsLoaded(
            buildSet {
                plan.completed.forEach {
                    add(AgentUiHandoffPayload.from(it.result.handoff.payload).conversationId)
                }
                plan.interrupted.forEach {
                    add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId)
                }
                plan.reattach?.let {
                    add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId)
                }
                orphanRewrites.forEach { (conversationId, _) -> add(conversationId) }
            }
        )

        val acknowledgeAfterSave = mutableListOf<String>()
        val removeAfterSave = mutableListOf<String>()
        val changed = withContext(Dispatchers.Main) {
            var stateChanged = false
            plan.completed.forEach { recoveryPlan ->
                val completedRun = recoveryPlan.result
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                if (conversationId !in transcriptLoadedConversationIds) return@forEach
                val state = conversationsById[conversationId] ?: return@forEach
                recoveryPlan.checkpoint?.let { checkpoint ->
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = false,
                    ) || stateChanged
                }
                val result = completedRun.result
                val recovery = AgentPendingResultRecovery.apply(
                    state = conversationsById[conversationId] ?: state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                if (recovery.alreadyApplied) {
                    acknowledgeAfterSave += runId
                    return@forEach
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
                stateChanged = true
            }

            plan.interrupted.forEach { checkpoint ->
                val conversationId = AgentUiHandoffPayload.from(checkpoint.handoff.payload).conversationId
                if (conversationId !in transcriptLoadedConversationIds) return@forEach
                removeAfterSave += checkpoint.runId
                stateChanged = restoreCheckpointTrace(
                    checkpoint = checkpoint,
                    interrupted = true,
                ) || stateChanged
            }
            orphanRewrites.forEach { (conversationId, runId) ->
                if (conversationId !in transcriptLoadedConversationIds) return@forEach
                conversationsById[conversationId]?.let { state ->
                    updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(state, runId,
                        AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                            operation = AgentRuntimeWire.OP_REWRITE_REPLY)))
                    stateChanged = true
                }
            }
            if (stateChanged) refreshConversationSummaries()
            stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
        }

        var persistenceSucceeded = true
        if (changed) {
            val saved = withContext(Dispatchers.Main) { persistConversations() }.await()
            if (saved == null) {
                acknowledgeAfterSave.forEach(client::ackResult)
                removeAfterSave.forEach { runId ->
                    AgentRunCheckpointStore.remove(appContext, runId)
                }
            } else {
                persistenceSucceeded = false
            }
        }

        plan.reattach?.let { checkpoint ->
            val conversationId = AgentUiHandoffPayload.from(checkpoint.handoff.payload).conversationId
            if (conversationId in transcriptLoadedConversationIds) {
                withContext(Dispatchers.Main) { startReattachedRun(checkpoint) }
            }
        }
        if (plan.reattach == null && persistenceSucceeded) evictIdleTranscripts()
    }

    /**
     * 把一个或多个会话的 checkpoint 正文从磁盘补进内存。
     * 读取在 IO，状态替换回主线程；失败会话保持未加载，调用方必须按 [transcriptLoadedConversationIds] 兜底。
     */
    private suspend fun ensureTranscriptsLoaded(conversationIds: Collection<String>) {
        val alreadyLoaded = withContext(Dispatchers.Main) { transcriptLoadedConversationIds.toSet() }
        val missing = conversationIds
            .filter { it.isNotBlank() && it !in alreadyLoaded }
            .toSet()
        if (missing.isEmpty()) return
        val transcripts = withContext(Dispatchers.IO) {
            missing.mapNotNull { conversationId ->
                try {
                    conversationId to AgentConversationStore.loadConversationTranscript(
                        appContext,
                        conversationId,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation transcript preload failed: type=${throwable.safeLogType()}"
                    )
                    null
                }
            }
        }
        if (transcripts.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            transcripts.forEach { (conversationId, transcript) ->
                val state = conversationsById[conversationId] ?: return@forEach
                transcriptLoadedConversationIds += conversationId
                val loaded = state.copy(
                    history = transcript.history,
                    journal = transcript.journal,
                ).let(RoleplayConversationReducer::decorate)
                    .withCurrentReasoningCapabilities()
                conversationsById = conversationsById + (conversationId to loaded)
                if (selectedConversationId == conversationId) homeState = loaded
            }
        }
    }

    /** 把安全事件恢复为 UI 轨迹；半截回复不进入模型 history，设备工具也不会重放。 */
    private fun restoreCheckpointTrace(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        interrupted: Boolean,
    ): Boolean {
        val runId = checkpoint.runId
        if (runId.isBlank()) return false
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false
        if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            val restored = RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
            if (interrupted) updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(
                restored, runId, AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                    operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = checkpoint.rewriteTargetMessageId),
            )) else updateConversation(conversationId, restored)
            return interrupted || restored != existing
        }

        runConversationIds[runId] = conversationId
        updateConversation(conversationId, existing.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        restoreRunEvents(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = runMessageProjector.finishContextCompaction(runId, finalizedText, "上下文压缩已中断"),
                )
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    interruptedTools + SystemNoticeMessageUi(
                        id = noticeId,
                        code = SystemNoticeCode.Interrupted,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        if (interrupted && (checkpoint.contextSnapshot != null || checkpoint.transcript.isNotEmpty())) {
            applyConversationHistoryResult(runId, checkpoint.transcript, checkpoint.contextSnapshot, true)
        }
        conversationsById[conversationId]?.let { state ->
            updateConversation(conversationId, RoleplayConversationReducer.linkRun(state, runId))
        }
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    private fun startReattachedRun(checkpoint: AgentRunCheckpointStore.Checkpoint) {
        val runId = checkpoint.runId
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return
        if (currentRunId != null || AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return

        runConversationIds[runId] = conversationId
        currentRunId = runId
        val restored = if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
        } else existing
        updateConversation(conversationId, restored.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        refreshConversationSummaries()
        currentRunJob = scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val outcome = client.attachRun(
                runId = runId,
                onReplay = { events -> restoreRunEvents(runId, events) },
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            when (outcome) {
                is AgentRuntimeClient.AttachOutcome.Completed -> withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId = runId,
                        result = outcome.result,
                        acknowledgeRuntimeResult = true,
                    )
                }
                AgentRuntimeClient.AttachOutcome.NotActive -> {
                    withContext(Dispatchers.Main) {
                        if (currentRunId == runId) {
                            currentRunId = null
                            currentRunJob = null
                            setConversationStreaming(runId, false)
                        }
                    }
                    recoverRuntimeRuns()
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    if (currentRunId == runId) {
                        currentRunId = null
                        currentRunJob = null
                        setConversationStreaming(runId, false)
                        refreshConversationSummaries()
                    }
                }
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        loadCompletion.await()
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return

        ensureTranscriptsLoaded(
            archivedRuns.mapNotNull { archivedRun ->
                val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload)
                    ?: return@mapNotNull null
                archiveConversationId(
                    source = archivedRun.handoff.source,
                    conversationKey = payload.conversationKey,
                )
            }
        )
        withContext(Dispatchers.Main) {
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            scheduleIdleTranscriptEviction(
                persistConversations {
                    importedRunIds.forEach { runId ->
                        AgentRunArchiveStore.remove(appContext, runId)
                    }
                }
            )
        }
    }

    suspend fun openAssistantConversation(conversationKey: String): Boolean {
        loadCompletion.await()
        if (conversationKey.isBlank()) return false
        importArchivedExternalRuns()
        return withContext(Dispatchers.Main.immediate) {
            val conversationId = archiveConversationId(
                source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                conversationKey = conversationKey,
            )
            if (conversationsById[conversationId] == null) {
                false
            } else {
                selectConversation(conversationId)
                true
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingConversation = conversationsById[conversationId]
        // 正文未加载时宁可不导入，保留归档等下次 refresh，也不能拿空 history/journal 覆盖。
        if (existingConversation != null && conversationId !in transcriptLoadedConversationIds) return null
        val existingState = existingConversation ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort).also {
            transcriptLoadedConversationIds += conversationId
        }
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        runConversationIds[runId] = conversationId
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        val normalized = currentReasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        if (selectedConversationId != null) persistConversations()
    }

    fun selectModel(modelId: String) {
        if (
            homeState.isStreaming ||
            modelPickerState.isChanging ||
            modelPickerState.selectedModel?.id == modelId
        ) {
            return
        }
        modelPickerState = modelPickerState.copy(isChanging = true)
        scope.launch(Dispatchers.IO) {
            try {
                RuntimeConfigRepository.setSelectedModelId(modelId)
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_model_switching_failed_please_try_again_later_4af439), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelPickerState = modelPickerState.copy(isChanging = false)
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
        refreshConversationSummaries()
    }

    fun selectConversation(conversationId: String) {
        if (homeState.messageEdit != null) cancelMessageEdit()
        val state = conversationsById[conversationId] ?: return
        if (conversationId in transcriptLoadedConversationIds) {
            activateConversation(conversationId, state)
            return
        }
        if (switchingConversationId != null) return
        switchingConversationId = conversationId
        conversationSwitchJob = scope.launch {
            // 先落盘当前会话，成功后才驱逐它的 transcript；失败则保持现状，不能拿未保存的正文冒险。
            if (persistConversations().await() != null) {
                withContext(Dispatchers.Main.immediate) {
                    switchingConversationId = null
                    conversationSwitchJob = null
                }
                return@launch
            }
            // 切到目标会话的轻量状态并锁住输入，再释放其它会话 transcript；
            // 这样目标会话几十 MB 的分块恢复不会和上一个大会话同时在堆里。
            withContext(Dispatchers.Main.immediate) {
                val current = conversationsById[conversationId] ?: return@withContext
                fileAttachmentOwnerVersion += 1
                selectedConversationId = conversationId
                conversationsById = conversationsById + (conversationId to current)
                homeState = current
                conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
                evictIdleTranscripts()
            }
            val transcript = try {
                withContext(Dispatchers.IO) {
                    AgentConversationStore.loadConversationTranscript(appContext, conversationId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                AndroidAgentLogger.error(
                    "Agent conversation transcript load failed: type=${throwable.safeLogType()}"
                )
                null
            }
            withContext(Dispatchers.Main.immediate) {
                switchingConversationId = null
                conversationSwitchJob = null
                val current = conversationsById[conversationId] ?: return@withContext
                val loadedTranscript = transcript
                if (loadedTranscript == null) return@withContext
                transcriptLoadedConversationIds += conversationId
                val loaded = current.copy(
                    history = loadedTranscript.history,
                    journal = loadedTranscript.journal,
                ).let(RoleplayConversationReducer::decorate)
                conversationsById = conversationsById + (conversationId to loaded)
                activateConversation(conversationId, loaded)
            }
        }
    }

    private fun activateConversation(conversationId: String, state: AgentChatHomeUiState) {
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        val normalized = currentReasoningCapabilities?.normalize(state.reasoningEffort)
            ?: ReasoningEffort.OFF
        val resolvedState = state.copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
        conversationsById = conversationsById + (conversationId to resolvedState)
        homeState = resolvedState
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        scheduleIdleTranscriptEviction(persistConversations())
    }

    /**
     * 保存成功后再驱逐空闲会话的 history/journal，避免持久化捕获到空占位。
     * 只保留当前选中会话与在途 run 的会话，连续打开多个大会话时内存始终有界。
     */
    private fun scheduleIdleTranscriptEviction(persistence: Deferred<String?>) {
        scope.launch {
            if (persistence.await() == null) evictIdleTranscripts()
        }
    }

    private fun evictIdleTranscripts() {
        if (runConversationIds.isNotEmpty() || currentRunId != null) return
        val keep = buildSet {
            selectedConversationId?.let(::add)
            conversationsById.filterValues { it.isStreaming }.keys.forEach(::add)
        }
        val evicted = transcriptLoadedConversationIds.filterNot { it in keep }
        if (evicted.isEmpty()) return
        var changed = false
        evicted.forEach { conversationId ->
            transcriptLoadedConversationIds -= conversationId
            val state = conversationsById[conversationId] ?: return@forEach
            if (state.history.isEmpty() && state.journal.isEmpty()) return@forEach
            conversationsById = conversationsById +
                (conversationId to state.copy(history = emptyList(), journal = emptyList()))
            changed = true
        }
        if (changed) refreshConversationSummaries()
    }

    fun createConversation() {
        cancelConversationSwitch()
        if (homeState.messageEdit != null) cancelMessageEdit()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = null,
            searchQuery = "",
        )
        refreshConversationSummaries()
    }

    fun startCharacterConversation(binding: RoleplayBinding, greeting: String) {
        cancelConversationSwitch()
        createConversation()
        val id = newConversationId()
        val greetingId = "greeting-$id"
        val text = CharacterMacros.expand(
            text = greeting, card = CharacterCardCodec.decodeJson(binding.cardSnapshotJson),
            userName = binding.userName, userDescription = binding.userDescription,
        )
        val transcript = if (text.isBlank()) emptyList() else listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = text, messageId = greetingId),
        )
        selectedConversationId = id
        transcriptLoadedConversationIds += id
        homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities().copy(
            roleplay = binding,
            history = transcript,
            journal = transcript,
            messages = if (text.isBlank()) emptyList() else listOf(AgentMessageUi(greetingId, text, characterEditable = true)),
            roleplayMessages = RoleplayMessageState(links = if (text.isBlank()) emptyMap() else mapOf(
                greetingId to RoleplayMessageLink(greetingId),
            )),
        )
        conversationTitles = conversationTitles + (id to binding.characterName)
        updateConversation(id, homeState)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = id, searchQuery = "")
        refreshConversationSummaries()
        persistConversations()
    }

    fun selectReplyCandidate(messageId: String, index: Int) {
        if (switchingConversationId != null) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val updated = RoleplayConversationReducer.select(homeState, messageId, index) ?: return
        updateCurrentConversation(updated)
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteConversation(conversationId: String) {
        if (switchingConversationId == conversationId) {
            cancelConversationSwitch()
        }
        transcriptLoadedConversationIds -= conversationId
        val wasSelected = selectedConversationId == conversationId
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        if (wasSelected) {
            fileAttachmentOwnerVersion += 1
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId).withCurrentReasoningCapabilities()
                conversationsById = conversationsById + (nextId to homeState)
            } else {
                selectedConversationId = null
                homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
            }
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    fun exportConversationMarkdown(conversationId: String): String? {
        val state = conversationsById[conversationId] ?: return null
        val title = conversationTitles[conversationId]?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.conversation_unnamed)
        return ConversationMarkdownExporter.export(
            title = title,
            messages = state.messages,
            labels = ConversationMarkdownExporter.Labels(
                user = appContext.getString(R.string.conversation_export_user),
                assistant = appContext.getString(R.string.conversation_export_assistant),
                thinking = appContext.getString(R.string.conversation_export_thinking),
                toolLineFormat = appContext.getString(R.string.conversation_export_tool_line),
                toolsLineFormat = appContext.getString(R.string.conversation_export_tools_line),
                argumentsFormat = appContext.getString(R.string.conversation_export_tool_arguments),
                resultFormat = appContext.getString(R.string.conversation_export_tool_result),
                imagesFormat = appContext.getString(R.string.conversation_export_images),
                toolStatusRunning = appContext.getString(R.string.tool_status_running),
                toolStatusSuccess = appContext.getString(R.string.tool_status_success),
                toolStatusFailed = appContext.getString(R.string.tool_status_failed),
                toolStatusUnknown = appContext.getString(R.string.tool_status_unknown),
                noticeStopped = noticeText(SystemNoticeCode.Stopped),
                noticeEmptyResult = noticeText(SystemNoticeCode.EmptyResult),
                noticeModelRetry = noticeText(SystemNoticeCode.ModelRetry),
                noticeContextCompaction = noticeText(SystemNoticeCode.ContextCompaction),
                noticeRuntimeFailed = noticeText(SystemNoticeCode.RuntimeFailed),
                noticeInterrupted = noticeText(SystemNoticeCode.Interrupted),
            ),
        )
    }

    private fun noticeText(code: SystemNoticeCode): String = appContext.getString(
        when (code) {
            SystemNoticeCode.Stopped -> R.string.system_notice_stopped
            SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
            SystemNoticeCode.ContextCompaction -> R.string.context_compaction
            SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
            SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
            SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
        },
    )

    fun sendCurrentMessage(submittedText: String? = null) {
        if (switchingConversationId != null) return
        if (homeState.isCompacting) return
        val prompt = (submittedText ?: homeState.input).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (
            (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) ||
            homeState.isStreaming
        ) {
            return
        }
        homeState.messageEdit?.takeIf { it.preserveFollowingMessages }?.let { edit ->
            val updated = RoleplayConversationReducer.edit(homeState, edit.targetMessageId, prompt) ?: return
            updateCurrentConversation(updated.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            ))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val runtimePrompt = AgentFileReferencePromptCodec.format(prompt, fileReferences)

        val edit = homeState.messageEdit
        if (edit == null && selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) {
            moveCurrentDraftToNewConversation()
        }

        val editBoundary = edit?.let {
            AgentConversationRevisionReducer.boundary(homeState, it.targetMessageId)
        }
        if (edit != null && editBoundary == null) {
            cancelMessageEdit()
            return
        }

        val conversationId = selectedConversationId ?: newConversationId().also {
            selectedConversationId = it
            transcriptLoadedConversationIds += it
        }
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = pendingImages.map { it.dataUrl },
            isEdited = editBoundary != null,
        )
        val history = editBoundary?.historyPrefix ?: homeState.history
        val messages = if (editBoundary == null) {
            homeState.messages + userMessage
        } else {
            homeState.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = runtimePrompt,
            images = pendingImages.toHistoryImages(),
        ).copy(messageId = userMessage.id)

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle.isNullOrBlank())
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeIf(String::isNotBlank) ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = pendingImages,
            history = history,
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = homeState.copy(
                journal = editBoundary?.journalPrefix ?: homeState.journal,
                input = "",
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                messageEdit = null,
            ),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun beginMessageEdit(messageId: String) {
        if (switchingConversationId != null) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        if (homeState.roleplay != null) {
            if (messageId !in homeState.roleplayMessages.links) return
            val content = when (val message = homeState.messages.firstOrNull { it.id == messageId }) {
                is UserMessageUi -> message.content
                is AgentMessageUi -> message.content
                else -> return
            }
            updateCurrentConversation(homeState.copy(
                input = content, pendingImages = emptyList(), pendingFileReferences = emptyList(),
                messageEdit = MessageEditUiState(messageId, homeState.input, homeState.pendingImages,
                    homeState.pendingFileReferences, hasLaterTurns = false, preserveFollowingMessages = true),
            ))
            return
        }
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "edit-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val fileReferences = parsedPrompt.references.mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            homeState.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = homeState.input,
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                ),
            )
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
    }

    fun cancelMessageEdit() {
        val edit = homeState.messageEdit ?: return
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? =
        if (switchingConversationId != null) null
        else if (homeState.roleplay != null && messageId.startsWith("greeting-")) MessageRevisionImpact(0)
        else AgentConversationRevisionReducer.boundary(homeState, messageId)?.let { boundary ->
            MessageRevisionImpact(laterTurnCount = boundary.laterTurnCount)
        }

    fun deleteMessageTurn(messageId: String) {
        if (switchingConversationId != null) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) {
            val originalId = homeState.roleplayMessages.links[messageId]?.transcriptMessageId ?: return
            val updated = homeState.copy(
                messages = homeState.messages.filterNot { it.id == messageId },
                journal = homeState.journal.filterNot { it.messageId == originalId },
                history = homeState.history.filterNot { it.messageId == originalId || it.contextSummary },
                roleplayMessages = homeState.roleplayMessages.copy(
                    links = homeState.roleplayMessages.links - messageId,
                    revisions = homeState.roleplayMessages.revisions - messageId,
                ),
            )
            updateConversation(conversationId, updated.copy(history = RoleplayConversationReducer.projectJournal(updated)))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val removed = AgentConversationRevisionReducer.deleteFromTurn(homeState, messageId) ?: return
        val retainedIds = removed.messages.mapTo(mutableSetOf()) { it.id }
        val revised = if (removed.roleplay == null) removed else removed.copy(roleplayMessages = removed.roleplayMessages.copy(
            links = removed.roleplayMessages.links.filterKeys { it in retainedIds },
            revisions = removed.roleplayMessages.revisions.filterKeys { it in retainedIds },
        ))
        if (revised.messages.isEmpty()) {
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
            fileAttachmentOwnerVersion += 1
            selectedConversationId = null
            homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    fun regenerateMessage(messageId: String) {
        if (switchingConversationId != null) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null) {
            val target = homeState.messages.filterIsInstance<AgentMessageUi>().firstOrNull { it.id == messageId } ?: return
            val prefix = RoleplayConversationReducer.rewriteHistory(homeState, messageId) ?: return
            val runId = "run-${UUID.randomUUID()}"
            launchConversationRun(
                conversationId, runId, target.content, emptyList(), prefix, null, homeState.messages,
                homeState.copy(roleplayMessages = homeState.roleplayMessages.copy(
                    pendingRewrites = homeState.roleplayMessages.pendingRewrites + (runId to messageId),
                )), homeState.reasoningEffort, operation = AgentRuntimeWire.OP_REWRITE_REPLY,
                rewriteTargetMessageId = messageId,
            )
            return
        }
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "regenerate-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = boundary.userMessage.content,
            images = images.toHistoryImages(),
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = boundary.userMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState.copy(journal = boundary.journalPrefix),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun retryFailedRun(noticeId: String) {
        if (switchingConversationId != null || currentRunId != null ||
            homeState.isStreaming || homeState.messageEdit != null
        ) return
        val conversationId = selectedConversationId ?: return
        val notice = homeState.messages.lastOrNull() as? SystemNoticeMessageUi ?: return
        if (notice.id != noticeId || notice.code != SystemNoticeCode.RuntimeFailed) return
        val continuation = RetryFailedRun.continuationPrompt(homeState.messages)
        launchConversationRun(
            conversationId = conversationId,
            runId = "run-${UUID.randomUUID()}",
            prompt = continuation,
            images = emptyList(),
            history = homeState.history,
            userHistoryMessage = null,
            messages = homeState.messages,
            state = homeState,
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun compactCurrentContext() {
        if (switchingConversationId != null) return
        val conversationId = selectedConversationId ?: return
        if (currentRunId != null || !homeState.canCompactContext || modelPickerState.isChanging) return
        launchConversationRun(
            conversationId = conversationId,
            runId = java.util.UUID.randomUUID().toString(),
            prompt = "", images = emptyList(), history = homeState.history,
            userHistoryMessage = null, messages = homeState.messages, state = homeState,
            reasoningEffort = homeState.reasoningEffort, operation = AgentRuntimeWire.OP_COMPACT,
        )
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage?,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
        operation: String = AgentRuntimeWire.OP_CHAT,
        rewriteTargetMessageId: String? = null,
    ) {
        runConversationIds[runId] = conversationId
        currentRunId = runId

        updateConversation(
            conversationId,
            state.copy(
                isStreaming = true,
                history = if (operation == AgentRuntimeWire.OP_REWRITE_REPLY) state.history else history + listOfNotNull(userHistoryMessage),
                journal = state.journal.ifEmpty { state.history } + listOfNotNull(userHistoryMessage),
                isCompacting = operation == AgentRuntimeWire.OP_COMPACT,
                messages = messages,
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        val initialPersistence = persistConversations()

        val preparationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // write-ahead：用户消息未提交前不把可能产生副作用的 run 交给 Runtime。
            val persistenceFailure = initialPersistence.await()
            if (persistenceFailure != null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            // 诊断后缀：写盘失败一直无法复现，而日志只落到 logcat（release 还剥掉 debug），
                            // 把原因直接写在用户能看到的那条提示里，才能拿到异常类名/错误码。
                            error = appContext.getString(R.string.conversation_persistence_failed) +
                                "（$persistenceFailure）",
                        )
                    )
                }
                return@launch
            }
            state.roleplay?.let { binding ->
                try {
                    CharacterRepository.initialize(appContext)
                    CharacterRepository.get(binding.characterId)?.let { profile ->
                        val updatedBinding = binding.copy(
                            cardSnapshotJson = CharacterCardCodec.encodeJson(profile.card),
                            characterName = profile.card.name, avatarPath = profile.avatarPath,
                        )
                        val saved = withContext(Dispatchers.Main) {
                            conversationsById[conversationId]?.let { current ->
                                updateConversation(conversationId, current.copy(roleplay = updatedBinding))
                            }
                            persistConversations()
                        }.await()
                        check(saved == null) { "无法保存角色会话设定：$saved" }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("character_run_prepare_failed") {
                        "Character preparation failed: type=${failure.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "无法读取或保存角色设定，请重试。",
                            operation = operation, rewriteTargetMessageId = rewriteTargetMessageId))
                    }
                    return@launch
                }
            }
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                reasoningEffort
            } else {
                ReasoningEffort.OFF
            }
            val config = RuntimeConfigRepository.currentRuntimeConfig()?.copy(
                terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                deviceSensitiveReadTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                deviceSensitiveActionTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                thinkingEnabled = permittedReasoningEffort.enablesReasoning,
                reasoningEffort = permittedReasoningEffort,
            )
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.state_ui_please_configure_the_model_provider_and_model_fi_a36e15),
                        )
                    )
                }
                return@launch
            }
            val modelImages = images.map { p ->
                AgentModelClient.ModelImage(
                    reference = p.uri,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            if (withContext(Dispatchers.Main) { runId in stopRequestedRunIds }) {
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止", operation = operation))
                }
                return@launch
            }
            val result = runInterruptible {
                AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                    request = AgentRuntimeWire.RunRequest(
                        operation = operation,
                        rewriteTargetMessageId = rewriteTargetMessageId,
                        runId = runId,
                        prompt = prompt,
                        config = config,
                        images = modelImages,
                        history = history,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                )
            }
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
        currentRunJob = preparationJob
        if (!RootAccess.isGranted) {
            val leaseId = "prepare:$runId"
            val acquired = AgentExecutionService.acquire(appContext, leaseId) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (currentRunId == runId) stopCurrentRun()
                }
            }
            if (!acquired) {
                preparationJob.cancel()
                applyRunResult(runId, AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = appContext.getString(R.string.capability_background_failed),
                ))
                return
            }
            preparationJob.invokeOnCompletion { AgentExecutionService.release(leaseId) }
        }
        preparationJob.start()
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> =
        map { image ->
            AgentModelClient.ModelImage(
                reference = image.dataUrl,
                mimeType = image.mimeType,
                bytes = image.dataUrl.length,
                source = image.uri,
            )
        }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request, parsed.references)
    }

    private fun showCompactedRevisionNotice() {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.state_ui_the_earlier_context_has_been_compressed_and_will_cf6c86),
            Toast.LENGTH_LONG,
        ).show()
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                )
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath -> appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound -> appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType -> appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch -> appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable -> appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied -> appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed -> appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge -> appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut -> appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    private val stopRequestedRunIds = mutableSetOf<String>()

    fun stopCurrentRun() {
        val runId = currentRunId ?: return
        if (!stopRequestedRunIds.add(runId)) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
    }

    private var permissionRefreshJob: Job? = null

    fun refreshPermissionHealth() {
        permissionRefreshJob?.cancel()
        permissionRefreshJob = scope.launch(Dispatchers.IO) {
            val refreshed = buildPermissionHealthState(appContext)
            withContext(Dispatchers.Main) { permissionHealthState = refreshed }
        }
    }

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_read_skills_599082),
                            message = appContext.getString(R.string.state_the_skill_list_is_temporarily_unavailable_please_try_29b0be),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(skills = items, isLoading = false)
            }
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_update_skills_04e56c),
                            message = appContext.getString(R.string.state_the_skill_switch_has_not_changed_please_try_again_la_fa262f),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_has_been_deleted_34c29b),
                            message = appContext.getString(R.string.skill_deleted_message, skillName),
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_delete_skill_1583c9),
                            message = appContext.getString(R.string.state_deletion_is_not_complete_eta_will_try_to_recover_whe_c4297e),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_read_skill_pack_a53563),
                    message = appContext.getString(R.string.state_please_select_the_zip_file_provided_by_the_system_fi_fea145),
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_skill_pack_is_no_longer_available_please_select_the__7cdfb4),
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_replacement_confirmation_has_expired_please_select_t_fce9f2),
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error(appContext.getString(R.string.state_ui_unable_to_open_selection_9f0004))
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun isReplyRewrite(runId: String): Boolean =
        conversationsById[conversationIdForRun(runId)]?.roleplayMessages?.pendingRewrites?.containsKey(runId) == true

    private fun restoreRunEvents(runId: String, events: List<AgentEvent>) {
        if (isReplyRewrite(runId)) return
        // 恢复是完整快照：先清除同一 run 的旧投影，再一次发布，避免历史增量重复追加
        // 或中途的 Running 状态使已结束的思考重新展开、播放动画。
        Snapshot.withMutableSnapshot {
            flushPendingRunDelta(runId)
            updateMessages(runId, updateTimestamp = false) { messages ->
                runMessageProjector.resetForReplay(
                    runId = runId,
                    messages = messages,
                    replaySupplementIndexes = events.filterIsInstance<AgentEvent.UserSupplementReceived>()
                        .mapTo(mutableSetOf()) { it.index },
                )
            }
            events.forEach { event -> applyRunEvent(runId, event, persistSupplement = false) }
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return

            runEventCoalescer.append(runId, event)?.let { ready ->
                applyRunEvent(runId, ready)
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId)
        applyRunEvent(runId, event)
    }

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            runEventFlushJobs.remove(runId)
            flushPendingRunDelta(runId)
        }
    }

    private fun flushPendingRunDelta(runId: String) {
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            applyRunEvent(runId, event)
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_installed_b07e54),
                            message = appContext.getString(
                                R.string.skill_enabled_message,
                                installed.name.safeSkillDisplayName(),
                            ),
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> appContext.getString(R.string.state_ui_the_selected_file_is_not_a_valid_zip_package_bff052)
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> appContext.getString(R.string.state_ui_the_skill_pack_exceeds_the_safe_size_or_file_num_42e151)
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> appContext.getString(R.string.state_ui_the_skill_pack_contains_an_unsafe_file_path_and__d8cfc0)
            SkillZipImportOutcome.FailureCode.NO_SKILL -> appContext.getString(R.string.state_ui_skill_md_not_found_in_zip_a57975)
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> appContext.getString(R.string.state_ui_the_local_zip_must_contain_only_one_skill_b89daf)
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> appContext.getString(R.string.state_ui_skill_md_is_missing_required_information_or_is_i_debe6e)
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> appContext.getString(R.string.state_ui_the_zip_content_has_changed_please_reselect_and__ab8b12)
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> appContext.getString(R.string.state_ui_built_in_skills_with_the_same_name_are_protected_446ad3)
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                appContext.getString(R.string.state_ui_the_target_with_the_same_name_is_not_a_user_skil_00474b)
            SkillZipImportOutcome.FailureCode.READ_FAILED -> appContext.getString(R.string.state_ui_the_selected_file_cannot_be_read_please_select_a_7265b9)
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> appContext.getString(R.string.state_ui_unable_to_save_skills_original_skills_have_been__9d4748)
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                appContext.getString(R.string.state_ui_the_installation_failed_and_automatic_recovery_d_0d9e01)
        }
        return newSkillNotice(
            title = appContext.getString(R.string.state_unable_to_install_skill_0ec70b),
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_restore_skills_6b3d23),
                            message = appContext.getString(R.string.state_the_built_in_skills_have_not_changed_please_try_agai_34e5e3),
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { appContext.getString(R.string.state_ui_unnamed_skill_a58008) }.take(80)

    private fun applyRunEvent(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean = true,
    ) {
        if (isReplyRewrite(runId)) {
            if (event is AgentEvent.RunStarted && runId in stopRequestedRunIds) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            return
        }
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startAssistantBlock(runId, event, messages)
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateMessages(runId, updateTimestamp = false) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.appendTextDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.appendReasoningDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.finalizeTextBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.finalizeThinkingBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.UsageReceived -> {
                updateAssistantUsage(runId, event.round, event.usage.toUi())
            }

            is AgentEvent.UserSupplementReceived -> {
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement)
            }

            is AgentEvent.UserQuestionAsked -> {
                // The question card is its own entry on the timeline and must not be folded into the work-process card.
                updateMessages(runId) { messages ->
                    val id = userQuestionMessageId(event.questionId)
                    messages.filterNot { it.id == id } + UserQuestionMessageUi(
                        id = id,
                        questionId = event.questionId,
                        question = event.question,
                        options = event.options,
                        multiSelect = event.multiSelect,
                        allowFreeform = event.allowFreeform,
                        running = true,
                    )
                }
            }

            is AgentEvent.UserQuestionAnswered -> {
                updateMessages(runId) { messages ->
                    messages.map { message ->
                        if (message !is UserQuestionMessageUi || message.questionId != event.questionId) {
                            message
                        } else {
                            message.copy(
                                answer = event.answer.takeIf { it.isNotBlank() },
                                timedOut = event.timedOut,
                                running = false,
                            )
                        }
                    }
                }
            }

            is AgentEvent.ToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.ToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishTool(runId, event, messages)
                }
            }

            is AgentEvent.HostedToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startHostedTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.HostedToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishHostedTool(runId, event, messages)
                }
            }

            is AgentEvent.ContextCompaction -> {
                updateMessages(runId) { messages ->
                    val id = "assistant-$runId-compaction-${event.operationId}"
                    messages.filterNot { it.id == id } + SystemNoticeMessageUi(
                        id = id, code = SystemNoticeCode.ContextCompaction, detail = event.displayMessage,
                        contextTokens = event.tokensAfter,
                        running = event.phase == AgentEvent.ContextCompaction.PHASE_STARTED,
                    )
                }
            }

            is AgentEvent.ModelRetryScheduled -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.scheduleModelRetry(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    runMessageProjector.failRunningTools(event.reason, finalizedText)
                }
            }

            is AgentEvent.AssistantReceived -> {
                updateRunTrace(runId) { messages ->
                    if (event.reasoningContent.isNotBlank()) {
                        runMessageProjector.ensureCompletedThinking(
                            runId = runId,
                            round = event.round,
                            content = event.reasoningContent,
                            messages = messages,
                        )
                    } else runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                }
            }

            is AgentEvent.RunFinished -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    runMessageProjector.finalizeText(runId, finalizedThinking)
                }
            }

            is AgentEvent.RunStarted -> {
                if (runId in stopRequestedRunIds) scope.launch(Dispatchers.IO) {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
                }
            }
            is AgentEvent.SubagentsStarted -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startSubagents(runId, event, messages)
                }
            }

            is AgentEvent.SubagentStarted -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startSubagent(runId, event, messages)
                }
            }

            is AgentEvent.SubagentToolStarted -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startSubagentTool(runId, event, messages)
                }
            }

            is AgentEvent.SubagentToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishSubagentTool(runId, event, messages)
                }
            }

            is AgentEvent.SubagentFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishSubagent(runId, event, messages)
                }
            }

            is AgentEvent.SubagentsFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishSubagents(runId, event, messages)
                }
            }

            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            is AgentEvent.TodoUpdated,
            -> Unit
        }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        flushPendingRunDelta(runId)
        val rewriting = result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || isReplyRewrite(runId)
        stopRequestedRunIds.remove(runId)
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        if (rewriting) {
            val conversationId = conversationIdForRun(runId)
            val existing = conversationsById[conversationId]
            if (conversationId != null && existing != null && result.contextSnapshotRef.isBlank()) {
                updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(existing, runId, result))
            } else setConversationStreaming(runId, false)
            runMessageProjector.clearRun(runId)
            runConversationIds.remove(runId)
            refreshConversationSummaries()
            scheduleIdleTranscriptEviction(
                persistConversations(onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                    { AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId) }
                } else null)
            )
            return
        }
        updateRunTrace(runId) { messages ->
            runMessageProjector.finishContextCompaction(runId,
                runMessageProjector.finalizeRun(runId, messages),
                if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩已停止")
        }
        if (result.contextSnapshotRef.isBlank()) {
            applyConversationHistoryResult(runId, result.transcript, result.contextSnapshot, !result.ok || result.contextSnapshot != null)
        }
        when {
            result.operation == AgentRuntimeWire.OP_COMPACT ||
                conversationsById[conversationIdForRun(runId)]?.isCompacting == true -> updateMessages(runId) { messages ->
                    AgentRunMessageProjector.mergeCompactionResultNotice(
                        runId = runId,
                        messages = messages,
                        ok = result.ok,
                        detail = if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩失败",
                    )
                }
            result.ok && result.content.isNotBlank() -> completeLatestAssistantMessage(
                runId,
                fallbackContent = result.content,
            )
            result.ok -> replaceLatestAssistantWithNotice(runId, SystemNoticeCode.EmptyResult)
            result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED ->
                replaceLatestAssistantWithNotice(runId, SystemNoticeCode.Stopped)
            else -> appendFailureNoticePreservingOutput(
                runId,
                result.error,
            )
        }
        setConversationStreaming(runId, false)
        conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let {
            updateConversation(id, RoleplayConversationReducer.linkRun(it, runId))
        } }
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        scheduleIdleTranscriptEviction(
            persistConversations(
                onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                    {
                        AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                    }
                } else {
                    null
                }
            )
        )
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        updateMessages(runId, transform = transform)
        refreshConversationSummaries()
    }

    private fun updateAssistantUsage(runId: String, round: Int, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        // 只补充 token 用量。不能触碰 isStreaming：Usage 事件紧跟在文本块结束之后，
        // 若把 isStreaming 改回 true，流式渲染会在流式/静态两种视图间反复切换，整段重渲染。
        updateMessages(runId) { messages ->
            val targetIndex = messages.indexOfLast { message ->
                message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)
            }
            messages.mapIndexed { index, message ->
                if (index == targetIndex && message is AgentMessageUi) {
                    message.copy(usage = usage)
                } else {
                    message
                }
            }
        }
    }

    /**
     * Stable message id for the question card: re-projecting the same questionId replaces it in
     * place instead of duplicating.
     */
    private fun userQuestionMessageId(questionId: String): String = "question-$questionId"

    /**
     * The user answers in the UI: land it locally first (the event that follows confirms it again), then deliver it
     * to the waiting run.
     * A failed delivery only affects this one answer (a question that already timed out, say); it neither interrupts
     * the conversation nor rolls the card back.
     */
    fun answerUserQuestion(questionId: String, answer: String, selectedOptions: List<String>) {
        val runId = currentRunId ?: return
        val text = answer.trim()
        if (questionId.isBlank() || text.isBlank()) return
        val selected = selectedOptions.map { it.trim() }.filter { it.isNotBlank() }

        val conversationId = conversationIdForRun(runId) ?: selectedConversationId
        val state = conversationId?.let { conversationsById[it] }
        if (conversationId != null && state != null) {
            updateConversation(
                conversationId,
                state.copy(
                    messages = state.messages.map { message ->
                        if (message !is UserQuestionMessageUi || message.questionId != questionId) {
                            message
                        } else {
                            message.copy(answer = text, timedOut = false, running = false)
                        }
                    },
                ),
            )
            refreshConversationSummaries()
        }

        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).answerUserQuestion(
                runId = runId,
                questionId = questionId,
                answer = text,
                selectedOptions = selected,
            )
        }
    }

    private fun insertSupplementMessage(
        runId: String,
        index: Int,
        text: String,
        persist: Boolean = true,
    ) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        if (persist) persistConversations()
    }

    private fun completeLatestAssistantMessage(
        runId: String,
        fallbackContent: String,
    ) {
        updateMessages(runId) { messages ->
            val targetIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
            if (targetIndex < 0) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = fallbackContent,
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                val targetRound = (messages[targetIndex] as AgentMessageUi).id
                    .assistantRound(runId)
                val sameRoundBlocks = targetRound?.let { round ->
                    messages.count { message ->
                        message is AgentMessageUi && message.id.assistantRound(runId) == round
                    }
                } ?: 0
                messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        message.copy(
                            content = if (sameRoundBlocks <= 1) {
                                fallbackContent
                            } else {
                                message.content.ifBlank { fallbackContent }
                            },
                            isStreaming = false,
                            renderMarkdown = true,
                        )
                    } else {
                        message
                    }
                }
            }
        }
    }

    private fun replaceLatestAssistantWithNotice(
        runId: String,
        code: SystemNoticeCode,
        detail: String? = null,
    ) {
        updateMessages(runId) { messages ->
            val targetIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
            if (targetIndex < 0) {
                messages + SystemNoticeMessageUi(AgentRunMessageProjector.resultFallbackId(runId, messages), code, detail)
            } else {
                messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        SystemNoticeMessageUi(message.id, code, detail)
                    } else {
                        message
                    }
                }
            }
        }
    }

    private fun appendFailureNoticePreservingOutput(runId: String, detail: String?) {
        updateMessages(runId) { messages ->
            runMessageProjector.appendFailureNotice(runId, detail, messages)
        }
    }

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun assistantFallbackMessageId(runId: String): String =
        "${assistantMessagePrefix(runId)}1"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(
            conversationId = conversationId,
            state = state.copy(messages = transform(state.messages)),
            updateTimestamp = updateTimestamp,
        )
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot? = null,
        retainPendingSupplements: Boolean = snapshot != null,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions, snapshot, retainPendingSupplements)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        cancelConversationSwitch()
        val draft = homeState
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.reasoningEffort.enablesReasoning,
            reasoningEffort = draft.reasoningEffort,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            pendingImages = draft.pendingImages,
            pendingFileReferences = draft.pendingFileReferences,
        )
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        conversationsById = conversationsById + (conversationId to state)
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = state
        }
    }

    private fun cancelConversationSwitch() {
        conversationSwitchJob?.cancel()
        conversationSwitchJob = null
        switchingConversationId = null
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(isStreaming = isStreaming, isCompacting = state.isCompacting && isStreaming))
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        val summaries = conversationsById.entries
            .sortedByDescending { (id, _) ->
                conversationUpdatedAt[id] ?: 0L
            }
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id].orEmpty().ifBlank {
                        appContext.getString(R.string.conversation_unnamed)
                    },
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> noticeText(lastMessage.code)
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        else -> appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        appContext.getString(R.string.time_now)
                    } else {
                        conversationUpdatedAt[id]?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                locale = appContext.resources.configuration.locales[0],
                                use24HourClock = DateFormat.is24HourFormat(appContext),
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent)
                    },
                    updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    characterName = state.roleplay?.characterName,
                    isActiveRun = state.isStreaming,
                )
            }
        val query = conversationPaneState.searchQuery.trim()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            conversations = if (query.isBlank()) {
                summaries
            } else {
                contentMatchCache.keys.retainAll(conversationsById.keys)
                summaries.filter { summary ->
                    summary.title.contains(query, ignoreCase = true) ||
                        summary.preview.contains(query, ignoreCase = true) ||
                        conversationContentMatches(summary.id, query)
                }
            },
        )
    }

    // 内容匹配按（查询词, 会话状态引用）缓存：刷新摘要时未变化的会话不重复全文扫描。
    private val contentMatchCache = mutableMapOf<String, ContentMatchCacheEntry>()

    private fun conversationContentMatches(conversationId: String, query: String): Boolean {
        val state = conversationsById[conversationId] ?: return false
        val cached = contentMatchCache[conversationId]
        if (cached != null && cached.query == query && cached.state === state) {
            return cached.matches
        }
        val matches = state.contentMatches(query) { code -> noticeText(code) }
        contentMatchCache[conversationId] = ContentMatchCacheEntry(query, state, matches)
        return matches
    }

    private fun persistConversations(onSaved: (() -> Unit)? = null): Deferred<String?> {
        return synchronized(persistenceLock) {
            val previous = persistenceJob
            scope.async(Dispatchers.IO) {
                var attempt = 0
                while (true) {
                    try {
                        // 初始快照应用前禁止捕获状态：空快照会把磁盘上的全部会话删除。
                        loadCompletion.await()
                        previous?.join()
                        val captured = withContext(Dispatchers.Main) {
                            AgentConversationStore.Snapshot(
                                selectedConversationId = selectedConversationId,
                                conversationsById = conversationsById,
                                titles = conversationTitles,
                                updatedAt = conversationUpdatedAt,
                                transcriptLoadedIds = transcriptLoadedConversationIds.toSet(),
                            )
                        }
                        AgentConversationStore.save(
                            context = appContext,
                            selectedConversationId = captured.selectedConversationId,
                            conversationsById = captured.conversationsById,
                            titles = captured.titles,
                            updatedAt = captured.updatedAt,
                            transcriptLoadedConversationIds = captured.transcriptLoadedIds,
                        )
                        onSaved?.invoke()
                        return@async null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        // 多进程共享同一库时的瞬时写锁（busy/locked）退避重试，
                        // 不把一次锁竞争报成"无法保存对话"；其余异常照旧直接报失败。
                        if (attempt >= MAX_TRANSIENT_PERSISTENCE_RETRIES ||
                            !throwable.isTransientDatabaseContention()
                        ) {
                            val reason = throwable.persistenceFailureReason()
                            AndroidAgentLogger.error(
                                "Agent conversation persistence failed: reason=$reason"
                            )
                            return@async reason
                        }
                        attempt += 1
                        AndroidAgentLogger.warnThrottled("agent_conversation_persistence_retry") {
                            "Agent conversation persistence retrying after transient contention: " +
                                "attempt=$attempt, type=${throwable.safeLogType()}"
                        }
                        delay(TRANSIENT_PERSISTENCE_RETRY_DELAY_MS * attempt)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }.also { persistenceJob = it }
        }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        const val LEGACY_STOPPED_ERROR = "已停止"
        const val SYNTHETIC_STATUS_STOPPED = "eta_status:stopped"
        // 写锁竞争在几十到几百毫秒内自行消解；重试两次仍失败才真的报错退出。
        const val MAX_TRANSIENT_PERSISTENCE_RETRIES = 2
        const val TRANSIENT_PERSISTENCE_RETRY_DELAY_MS = 150L
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        // 这与 Kimi 将流式数据和视觉动画分层的做法一致。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 40L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                journal = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private data class ContentMatchCacheEntry(
    val query: String,
    val state: AgentChatHomeUiState,
    val matches: Boolean,
)

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

private fun String.isReadOnlyExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

private fun archiveConversationId(source: String, conversationKey: String): String {
    val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
        ASSISTANT_CONVERSATION_PREFIX
    } else {
        EXTERNAL_ARCHIVE_CONVERSATION_PREFIX
    }
    return prefix + stableArchiveId("$source:$conversationKey")
}

private const val ASSISTANT_CONVERSATION_PREFIX = "assistant-"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun buildToolsState(context: Context): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = context.getString(R.string.state_screens_and_controls_3f095b),
                tools = listOf(
                    ToolItemUi("observe_screen", context.getString(R.string.tool_ui_watch_the_screen_e70f2a), context.getString(R.string.tool_ui_read_the_current_node_and_attach_the_original_im_df1fec)),
                    ToolItemUi("tap_element", context.getString(R.string.tool_ui_click_element_7a3d91), context.getString(R.string.tool_ui_click_on_the_most_recently_observed_node_b4cf5a)),
                    ToolItemUi("tap_area", context.getString(R.string.tool_ui_click_area_cbaa08), context.getString(R.string.tool_ui_click_by_coordinate_area_2ad961)),
                    ToolItemUi("long_press", context.getString(R.string.tool_ui_long_press_f7a417), context.getString(R.string.tool_ui_long_press_on_coordinates_or_elements_796384)),
                    ToolItemUi("swipe", context.getString(R.string.tool_ui_slide_3723aa), context.getString(R.string.tool_ui_perform_up_down_left_and_right_swipe_gestures_3ef0de)),
                    ToolItemUi("scroll", context.getString(R.string.tool_ui_scroll_220e68), context.getString(R.string.tool_ui_scroll_the_page_or_specify_a_node_83ab24)),
                ),
            ),
            ToolGroupUi(
                id = "text",
                title = context.getString(R.string.state_text_and_clipboard_3a7340),
                tools = listOf(
                    ToolItemUi("input_text", context.getString(R.string.tool_ui_enter_text_ae47ab), context.getString(R.string.tool_ui_append_or_paste_text_to_the_current_focus_1efdcc)),
                    ToolItemUi("replace_text", context.getString(R.string.tool_ui_replacement_text_1a5c8d), context.getString(R.string.tool_ui_replace_text_in_focus_or_node_30d332)),
                    ToolItemUi("clear_text", context.getString(R.string.tool_ui_clear_text_d4cb57), context.getString(R.string.tool_ui_clear_focus_or_node_text_3e754a)),
                    ToolItemUi("paste_text", context.getString(R.string.tool_ui_paste_text_791b85), context.getString(R.string.tool_ui_reliably_enter_long_text_with_the_clipboard_b6041e)),
                    ToolItemUi("wait_for_text", context.getString(R.string.tool_ui_wait_for_text_9e9a54), context.getString(R.string.tool_ui_wait_for_the_specified_text_to_appear_on_the_scr_43f9b0)),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = context.getString(R.string.state_web_browsing_e56105),
                tools = listOf(
                    ToolItemUi("browser_use", context.getString(R.string.tool_ui_agent_browser_a66bd5), context.getString(R.string.tool_ui_open_web_pages_off_screen_and_keep_a_takeover_br_72972e)),
                    ToolItemUi("browser_read", context.getString(R.string.tool_ui_read_web_pages_4f0bb9), context.getString(R.string.tool_ui_extract_rendered_text_lists_and_links_8bdcdd)),
                    ToolItemUi("browser_interact", context.getString(R.string.tool_ui_web_page_interaction_331b3f), context.getString(R.string.tool_ui_find_click_and_enter_page_elements_8f102d)),
                    ToolItemUi("browser_screenshot", context.getString(R.string.tool_ui_page_screenshot_c823a2), context.getString(R.string.tool_ui_give_the_current_web_page_viewport_to_the_visual_f62274)),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = context.getString(R.string.state_applications_and_systems_9624e6),
                tools = listOf(
                    ToolItemUi("search_apps", context.getString(R.string.tool_ui_search_apps_897fdf), context.getString(R.string.tool_ui_query_installed_applications_by_name_or_package__32b004)),
                    ToolItemUi("get_current_context", context.getString(R.string.tool_ui_time_and_location_693893), context.getString(R.string.tool_ui_read_system_time_and_recent_location_b9f4ae)),
                    ToolItemUi("launch_app", context.getString(R.string.tool_ui_open_app_7c65e7), context.getString(R.string.tool_ui_start_the_specified_package_name_or_application__beabff)),
                    ToolItemUi("open_uri", context.getString(R.string.tool_ui_open_with_app_32c24e), context.getString(R.string.tool_ui_explicitly_hand_over_links_or_deep_links_to_exte_35ff26)),
                    ToolItemUi("press_key", context.getString(R.string.tool_ui_button_02eafa), context.getString(R.string.tool_ui_system_buttons_such_as_return_homepage_recent_ta_1b4cf0)),
                    ToolItemUi("open_system_panel", context.getString(R.string.tool_ui_system_panel_b0f7a3), context.getString(R.string.tool_ui_open_the_notification_bar_quick_settings_and_oth_5e51cf)),
                ),
            ),
            ToolGroupUi(
                id = "device_direct",
                title = context.getString(R.string.state_direct_access_to_equipment_eda92c),
                tools = listOf(
                    ToolItemUi("set_alarm", context.getString(R.string.tool_ui_set_alarm_25ca3c), context.getString(R.string.tool_ui_create_a_system_alarm_directly_and_open_the_cloc_9aa214)),
                    ToolItemUi("set_timer", context.getString(R.string.tool_ui_set_timer_aee60c), context.getString(R.string.tool_ui_directly_create_system_timers_up_to_24_hours_87c476)),
                    ToolItemUi("device_status", context.getString(R.string.tool_ui_device_status_567a4c), context.getString(R.string.tool_ui_read_power_memory_storage_and_system_version_c501d5)),
                    ToolItemUi("network_info", context.getString(R.string.tool_ui_network_status_6bd556), context.getString(R.string.tool_ui_read_networking_method_and_current_wi_fi_status_68016a)),
                    ToolItemUi("media_control", context.getString(R.string.tool_ui_media_control_585edc), context.getString(R.string.tool_ui_play_pause_and_switch_songs_without_operating_th_311cb8)),
                    ToolItemUi("set_volume", context.getString(R.string.tool_ui_set_volume_85a691), context.getString(R.string.tool_ui_set_by_media_alarm_clock_ringtone_and_other_chan_3fcc3e)),
                    ToolItemUi("top_memory_apps", context.getString(R.string.tool_ui_memory_ranking_408ca1), context.getString(R.string.tool_ui_view_the_currently_most_occupied_processes_8646c4)),
                    ToolItemUi("top_storage_apps", context.getString(R.string.tool_ui_storage_ranking_86a16c), context.getString(R.string.tool_ui_check_application_data_and_cache_usage_837e9f)),
                ),
            ),
            ToolGroupUi(
                id = "device_sensitive",
                title = context.getString(R.string.state_sensitive_equipment_capabilities_fbdc4b),
                tools = listOf(
                    ToolItemUi("read_sms_code", context.getString(R.string.tool_ui_read_verification_code_7d1121), context.getString(R.string.tool_ui_only_extract_verification_codes_from_recent_sms__0fb8c1)),
                    ToolItemUi("recent_notifications", context.getString(R.string.tool_ui_read_notification_7fdc09), context.getString(R.string.tool_ui_read_the_current_notification_title_and_text_0faee7)),
                    ToolItemUi("search_notification_history", context.getString(R.string.tool_ui_notification_history_95d015), context.getString(R.string.tool_ui_retrieve_the_last_7_days_of_notifications_saved__643e43)),
                    ToolItemUi("recent_app_activity", context.getString(R.string.tool_ui_recently_applied_08f74c), context.getString(R.string.tool_ui_view_recently_opened_apps_and_times_bf9d50)),
                    ToolItemUi("app_usage_summary", context.getString(R.string.tool_ui_app_usage_statistics_ee20d3), context.getString(R.string.tool_ui_summarize_recent_app_usage_by_foreground_duratio_b346c8)),
                    ToolItemUi("get_current_location", context.getString(R.string.tool_ui_current_location_b458ea), context.getString(R.string.tool_ui_read_the_closest_location_the_system_already_has_255a6c)),
                    ToolItemUi("get_device_environment", context.getString(R.string.tool_ui_equipment_environment_1026ec), context.getString(R.string.tool_ui_read_lock_screen_do_not_disturb_audio_output_and_9260b8)),
                    ToolItemUi("list_alarms", context.getString(R.string.tool_ui_alarm_clock_schedule_acae32), context.getString(R.string.tool_ui_read_the_alarm_clock_that_has_been_created_in_th_2320d6)),
                    ToolItemUi("list_active_timers", context.getString(R.string.tool_ui_activity_timer_36f107), context.getString(R.string.tool_ui_read_running_or_paused_timers_3437c8)),
                    ToolItemUi("search_clipboard_history", context.getString(R.string.tool_ui_clipboard_history_b377bb), context.getString(R.string.tool_ui_retrieve_clipboard_contents_saved_by_system_inpu_1dc9db)),
                    ToolItemUi("get_health_summary", context.getString(R.string.tool_ui_health_summary_951c0b), context.getString(R.string.tool_ui_summarize_steps_sleep_exercise_and_body_metrics_6ff66f)),
                    ToolItemUi("wifi_credentials", context.getString(R.string.tool_ui_wi_fi_password_80e9a4), context.getString(R.string.tool_ui_read_the_network_credentials_saved_by_the_phone_96d43a)),
                    ToolItemUi("get_setting", context.getString(R.string.tool_ui_read_system_settings_d455ce), context.getString(R.string.tool_ui_read_the_specified_settings_key_496975)),
                    ToolItemUi("set_setting", context.getString(R.string.tool_ui_modify_system_settings_ae1f4c), context.getString(R.string.tool_ui_modify_android_settings_keys_91a37e)),
                    ToolItemUi("set_device_state", context.getString(R.string.tool_ui_network_switch_834347), context.getString(R.string.tool_ui_directly_control_wi_fi_or_bluetooth_4fa0b9)),
                    ToolItemUi("app_state_control", context.getString(R.string.tool_ui_application_status_930ff0), context.getString(R.string.tool_ui_stop_freeze_or_unfreeze_apps_a27438)),
                    ToolItemUi("get_logcat", context.getString(R.string.tool_ui_system_log_096733), context.getString(R.string.tool_ui_bounded_reading_and_filtering_of_recent_logs_0a268a)),
                ),
            ),
            ToolGroupUi(
                id = "personal_data",
                title = context.getString(R.string.state_direct_access_to_personal_data_387d7b),
                tools = listOf(
                    ToolItemUi("search_media", context.getString(R.string.tool_ui_album_pictures_23bcc2), context.getString(R.string.tool_ui_retrieve_pictures_by_file_name_or_album_path_c08236)),
                    ToolItemUi("search_audio", context.getString(R.string.tool_ui_audio_file_1ccf2e), context.getString(R.string.tool_ui_search_audio_by_title_filename_or_author_82e20d)),
                    ToolItemUi("search_recordings", context.getString(R.string.tool_ui_system_recording_15eb19), context.getString(R.string.tool_ui_retrieve_recording_files_from_system_media_libra_314c4d)),
                    ToolItemUi("search_files", context.getString(R.string.tool_ui_share_files_a3b376), context.getString(R.string.tool_ui_retrieve_documents_and_files_from_shared_storage_7d6193)),
                    ToolItemUi("search_calendar_events", context.getString(R.string.tool_ui_calendar_events_970349), context.getString(R.string.tool_ui_search_events_by_title_location_or_description_1afd77)),
                    ToolItemUi("search_contacts", context.getString(R.string.tool_ui_address_book_9070cb), context.getString(R.string.tool_ui_retrieve_contact_name_and_open_address_6dacc5)),
                    ToolItemUi("search_call_history", context.getString(R.string.tool_ui_call_history_88e57b), context.getString(R.string.tool_ui_retrieve_calls_by_number_or_contact_name_2ce431)),
                    ToolItemUi("search_messages", context.getString(R.string.tool_ui_short_message_17e1a4), context.getString(R.string.tool_ui_search_text_messages_by_sender_or_text_keywords_e14363)),
                    ToolItemUi("search_downloads", context.getString(R.string.tool_ui_download_history_8494d7), context.getString(R.string.tool_ui_retrieve_system_download_tasks_and_files_3301b9)),
                    ToolItemUi("search_coloros_notes", context.getString(R.string.tool_ui_coloros_notes_6c324c), context.getString(R.string.tool_ui_retrieve_notes_to_dos_and_text_content_e806d7)),
                    ToolItemUi("search_coloros_recordings", context.getString(R.string.tool_ui_coloros_recording_a4e425), context.getString(R.string.tool_ui_retrieve_normal_recordings_and_call_recordings_55c192)),
                    ToolItemUi("search_recording_summaries", context.getString(R.string.tool_ui_recording_summary_2fe550), context.getString(R.string.tool_ui_retrieve_transcribed_summaries_and_notes_associa_9cb00f)),
                    ToolItemUi("search_coloros_memories", context.getString(R.string.tool_ui_coloros_system_memory_eff961), context.getString(R.string.tool_ui_retrieve_collected_information_and_its_structure_9c1c71)),
                    ToolItemUi("search_saved_places", context.getString(R.string.tool_ui_save_location_c29782), context.getString(R.string.tool_ui_retrieve_location_information_from_system_memory_52ea48)),
                    ToolItemUi("search_personal_orders", context.getString(R.string.tool_ui_personal_order_25e4c9), context.getString(R.string.tool_ui_retrieve_takeout_shopping_express_delivery_ticke_f8d002)),
                    ToolItemUi("search_qq_chat_images", context.getString(R.string.tool_ui_qq_chat_pictures_e21bf9), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_qq_chat_picture_cach_b8f009)),
                    ToolItemUi("search_wechat_chat_images", context.getString(R.string.tool_ui_wechat_chat_pictures_72b268), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_wechat_chat_picture__ab66f7)),
                ),
            ),
            ToolGroupUi(
                id = "file_vision",
                title = context.getString(R.string.state_document_vision_6a65a7),
                tools = listOf(
                    ToolItemUi("read_image", context.getString(R.string.tool_ui_read_pictures_ae993b), context.getString(R.string.tool_ui_read_pictures_of_known_paths_and_hand_them_over__7f9569)),
                ),
            ),
            ToolGroupUi(
                id = "memory",
                title = context.getString(R.string.state_memory_b55ff5),
                tools = listOf(
                    ToolItemUi("memory_get", context.getString(R.string.tool_ui_read_memory_979135), context.getString(R.string.tool_ui_paged_to_read_or_retrieve_long_term_memory_in_me_88afc4)),
                    ToolItemUi("memory_write", context.getString(R.string.tool_ui_organize_memory_2b08eb), context.getString(R.string.tool_ui_partially_update_append_or_clear_long_term_memor_c1bab6)),
                    ToolItemUi("character_memory_get", "读取剧情记忆", "仅角色会话可用，读取当前角色的长期剧情和关系。"),
                    ToolItemUi("character_memory_write", "整理剧情记忆", "仅角色会话可用，更新当前角色的剧情记忆，不写入现实 MEMORY.md。"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = context.getString(R.string.state_terminal_and_files_ae7c54),
                tools = listOf(
                    ToolItemUi("terminal", context.getString(R.string.tool_ui_session_terminal_09c6e6), context.getString(R.string.tool_ui_user_root_shell_conversational_execution_and_asy_13c2ab)),
                    ToolItemUi("run_command", context.getString(R.string.tool_ui_execute_command_bf1627), context.getString(R.string.tool_ui_directly_execute_a_single_shell_command_c40cef)),
                    ToolItemUi("read_file", context.getString(R.string.tool_ui_read_file_dc995c), context.getString(R.string.tool_ui_read_the_contents_of_mobile_phone_files_bf3066)),
                    ToolItemUi("write_file", context.getString(R.string.tool_ui_write_file_e620fd), context.getString(R.string.tool_ui_write_or_overwrite_mobile_files_29fae4)),
                    ToolItemUi("list_directory", context.getString(R.string.tool_ui_list_directory_96e765), context.getString(R.string.tool_ui_list_directory_contents_feff30)),
                    ToolItemUi("edit_file", context.getString(R.string.tool_ui_edit_file_3c7a1e), context.getString(R.string.tool_ui_exact_text_replacement_in_a_file_atomic_write_8d5b42)),
                    ToolItemUi("search_code", context.getString(R.string.tool_ui_search_code_5e2f90), context.getString(R.string.tool_ui_regex_search_in_a_directory_returns_path_line_text_a17c64)),
                ),
            ),
        )
    ).copy(groups = compactToolGroups())

private fun compactToolGroups(): List<ToolGroupUi> = listOf(
    ToolGroupUi("screen", "屏幕操作", listOf(
        ToolItemUi("observe_screen", "观察屏幕", "读取节点和可选截图"),
        ToolItemUi("ui_action", "界面操作", "点击、滑动、输入、等待和系统面板"),
        ToolItemUi("clipboard", "剪贴板", "读取、写入或粘贴文本"),
    )),
    ToolGroupUi("apps", "应用与设备", listOf(
        ToolItemUi("app_action", "应用操作", "搜索、启动应用或打开 URI"),
        ToolItemUi("device_info", "设备信息", "状态、网络、环境和资源排行"),
        ToolItemUi("device_control", "设备控制", "闹钟、计时器、媒体和音量"),
    )),
    ToolGroupUi("web", "浏览器", listOf(ToolItemUi("browser_use", "网页浏览", "读取、交互和截图统一入口"))),
    ToolGroupUi("files", "文件与终端", listOf(
        ToolItemUi("terminal", "终端", "会话、命令、异步任务和后台服务"),
        ToolItemUi("file_ops", "文件操作", "读取、写入、编辑、搜索和列目录"),
        ToolItemUi("read_image", "图片分析", "把授权图片交给视觉模型"),
    )),
    ToolGroupUi("skills", "技能与记忆", listOf(
        ToolItemUi("skill", "本地技能", "列表、读取、资源和精选目录"),
        ToolItemUi("skill_github", "GitHub 技能", "检查或安装公共仓库技能"),
        ToolItemUi("memory", "长期记忆", "读取或更新跨会话记忆"),
    )),
    ToolGroupUi("personal_data", "个人数据", listOf(
        ToolItemUi("get_setting", "系统设置读取", "独立权限控制的 Settings 查询"),
        ToolItemUi("wifi_credentials", "Wi-Fi 凭据", "读取已保存的网络凭据，独立权限控制"),
        ToolItemUi("recent_notifications", "当前通知", "读取通知栏内容，独立通知权限"),
        ToolItemUi("search_notification_history", "通知历史", "搜索应用通知历史"),
        ToolItemUi("recent_app_activity", "最近应用", "读取最近使用的应用"),
        ToolItemUi("app_usage_summary", "应用使用统计", "按时长汇总应用使用情况"),
        ToolItemUi("get_current_location", "当前位置", "读取系统已有的最近位置"),
        ToolItemUi("read_sms_code", "短信验证码", "仅提取近期验证码"),
        ToolItemUi("get_logcat", "系统日志", "读取并过滤最近日志"),
        ToolItemUi("search_clipboard_history", "剪贴板历史", "搜索输入法保存的历史内容"),
        ToolItemUi("search_media", "媒体", "搜索图片媒体库"),
        ToolItemUi("search_audio", "音频", "搜索音乐和音频文件"),
        ToolItemUi("search_recordings", "录音", "搜索录音文件"),
        ToolItemUi("search_files", "共享文件", "搜索共享存储文件"),
        ToolItemUi("search_messages", "短信", "独立权限和敏感审核"),
        ToolItemUi("search_contacts", "联系人", "独立权限和敏感审核"),
        ToolItemUi("search_calendar_events", "日历", "独立权限和敏感审核"),
        ToolItemUi("search_call_history", "通话记录", "独立权限和敏感审核"),
        ToolItemUi("search_downloads", "下载记录", "独立权限和敏感审核"),
        ToolItemUi("search_coloros_notes", "ColorOS 便签", "读取系统便签和待办"),
        ToolItemUi("search_coloros_recordings", "ColorOS 录音", "读取系统录音索引"),
        ToolItemUi("search_recording_summaries", "录音摘要", "读取录音转写摘要"),
        ToolItemUi("search_coloros_memories", "ColorOS 记忆", "读取系统记忆索引"),
        ToolItemUi("search_saved_places", "保存地点", "读取系统记忆中的地点"),
        ToolItemUi("search_personal_orders", "个人订单", "读取通知或系统记忆中的订单"),
        ToolItemUi("search_qq_chat_images", "QQ 聊天图片", "读取 QQ 缓存索引"),
        ToolItemUi("search_wechat_chat_images", "微信聊天图片", "读取微信缓存索引"),
        ToolItemUi("set_setting", "修改系统设置", "独立权限控制的 Settings 修改"),
        ToolItemUi("set_device_state", "设备状态控制", "控制 Wi-Fi 或蓝牙"),
        ToolItemUi("app_state_control", "应用状态控制", "停止、冻结或解冻应用"),
        ToolItemUi("list_alarms", "闹钟列表", "读取系统闹钟"),
        ToolItemUi("list_active_timers", "活动计时器", "读取正在运行的计时器"),
        ToolItemUi("get_health_summary", "健康摘要", "读取健康数据汇总"),
    )),
)

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val appListEnabled = hasAppListAccess(context)
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val rootEnabled = RootAccess.isGranted
    val notificationsEnabled = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    val locationAccess = DeviceLocationProvider.accessState(context)
    val notificationHistoryEnabled = io.github.mangi.eta.agent.device.AgentNotificationHistoryService.isEnabled(context)
    val usageAccessEnabled = io.github.mangi.eta.agent.tool.AgentPersonalContextTools.hasUsageAccess(context)

    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "background",
                title = context.getString(R.string.state_background_running_permission_dde21b),
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = context.getString(R.string.state_floating_window_permissions_076b77),
                summary = "",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = context.getString(R.string.state_application_list_reading_135f16),
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "location",
                title = context.getString(R.string.state_location_permissions_b53f9c),
                summary = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_used_to_understand_the_location_of_mobile_phones_af52e9)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.capability_location_foreground)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_system_location_service_is_turned_off_3902e7)
                    DeviceLocationProvider.AccessState.AVAILABLE -> context.getString(R.string.state_ui_only_read_when_the_agent_calls_the_tool_8cf77b)
                },
                status = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> PermissionStatusUi.Missing
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> PermissionStatusUi.Warning
                    DeviceLocationProvider.AccessState.DISABLED -> PermissionStatusUi.Disabled
                    DeviceLocationProvider.AccessState.AVAILABLE -> PermissionStatusUi.Available
                },
                primaryActionLabel = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_to_authorize_762ec4)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.state_ui_go_to_settings_1f2998)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_to_open_13ec17)
                    DeviceLocationProvider.AccessState.AVAILABLE -> null
                },
            ),
            PermissionHealthItemUi(
                id = "notification_history",
                title = context.getString(R.string.state_notice_of_use_rights_1ae29a),
                summary = if (notificationHistoryEnabled) {
                    context.getString(R.string.state_ui_natively_bounded_storage_of_last_7_days_of_notif_ca7f01)
                } else {
                    context.getString(R.string.state_ui_start_logging_searchable_notification_history_af_b36af6)
                },
                status = if (notificationHistoryEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (notificationHistoryEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "usage_access",
                title = context.getString(R.string.state_usage_access_20f1f8),
                summary = context.getString(R.string.state_used_to_read_recently_opened_applications_and_foregr_73e796),
                status = if (usageAccessEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (usageAccessEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "accessibility",
                title = context.getString(R.string.state_accessibility_permissions_f80103),
                summary = "",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "notifications",
                title = context.getString(R.string.capability_notifications_title),
                summary = context.getString(R.string.capability_notifications_summary),
                status = if (notificationsEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = context.getString(R.string.state_ui_go_to_settings_1f2998),
            ),
            PermissionHealthItemUi(
                id = "root",
                title = context.getString(R.string.capability_enhancements),
                summary = context.getString(R.string.capability_optional_root),
                status = if (rootEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = if (rootEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
        )
    )
}

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}

private fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
    )

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}
