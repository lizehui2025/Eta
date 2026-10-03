package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.UserQuestionMessageUi
import io.github.mangi.eta.ui.model.latestContextUsage
import kotlin.math.exp
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 「当前浏览器工具行」定位键：只随影响行匹配的三个字段变化，避免订阅整个快照流。 */
private data class BrowserRowKey(
    val available: Boolean,
    val runId: String?,
    val toolCallId: String?,
)

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    input: String,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    messageEdit: MessageEditUiState?,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onAnswerUserQuestion: (String, String, List<String>) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onRetryFailedRun: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    agentControl: AgentControlUi = AgentControlUi(),
    characterName: String? = null,
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    // 只订阅影响「当前浏览器工具行」的三个字段：整个快照流含页面加载进度等高频变化，
    // 直接 collect 会让每次进度更新都重组整个聊天列表。
    val browserRowKey by remember {
        AgentBrowserSession.snapshots
            .map { snapshot ->
                BrowserRowKey(snapshot.available, snapshot.lastAgentRunId, snapshot.lastAgentToolCallId)
            }
            .distinctUntilChanged()
    }.collectAsState(
        initial = AgentBrowserSession.snapshots.value.let { snapshot ->
            BrowserRowKey(snapshot.available, snapshot.lastAgentRunId, snapshot.lastAgentToolCallId)
        },
    )
    val contextUsage = remember(messages, modelPickerState.selectedModel) {
        latestContextUsage(messages, modelPickerState.selectedModel)
    }

    val visibleMessages = remember(messages, messageEdit?.targetMessageId, messageEdit?.preserveFollowingMessages) {
        AgentConversationRevisionReducer.visibleMessagesForEdit(
            messages = messages,
            targetMessageId = messageEdit?.takeUnless { it.preserveFollowingMessages }?.targetMessageId,
        ).filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }
    }
    val currentBrowserMessageId = remember(visibleMessages, browserRowKey) {
        val runId = browserRowKey.runId
        val toolCallId = browserRowKey.toolCallId
        if (!browserRowKey.available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    var sentFromKeyboard by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    var userTurnScrollState by remember { mutableStateOf(UserTurnScrollState()) }
    var thinkingCollapseResetKey by remember { mutableStateOf(0L) }

    // A newly opened conversation should show its latest messages immediately. This effect is
    // keyed only by the first non-empty layout, so streaming updates and user scrolling do not
    // repeatedly steal the scroll position.
    LaunchedEffect(visibleMessages.isNotEmpty()) {
        if (visibleMessages.isNotEmpty()) {
            withFrameNanos { }
            scrollState.requestScrollToItem(scrollState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
        }
    }

    LaunchedEffect(isStreaming) {
        if (isStreaming && sentFromKeyboard) {
            keyboard?.hide()
            sentFromKeyboard = false
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            keyboard?.hide()
        }
    }

    AgentChatScaffold(
        visibleMessages = visibleMessages,
        hasMessages = visibleMessages.isNotEmpty(),
        scrollState = scrollState,
        input = input,
        modelPickerState = modelPickerState,
        isCompacting = isCompacting,
        contextUsage = contextUsage,
        isStreaming = isStreaming,
        reasoningEffort = reasoningEffort,
        availableReasoningEfforts = availableReasoningEfforts,
        pendingImages = pendingImages,
        pendingFileReferences = pendingFileReferences,
        messageEdit = messageEdit,
        showEmptySuggestions = !isKeyboardVisible,
        characterName = characterName,
        keepBottomAnchored = keepBottomAnchored,
        userTurnScrollState = userTurnScrollState,
        thinkingCollapseResetKey = thinkingCollapseResetKey,
        onBottomAnchorChanged = { keepBottomAnchored = it },
        onUserTurnScrollStateChanged = { next ->
            userTurnScrollState = next
        },
        onSubmit = { text ->
            val pending = visibleMessages.lastOrNull { it is UserQuestionMessageUi && it.running }
                as? UserQuestionMessageUi
            if (pending != null) {
                // While a question is pending the send key answers it (free-form input) instead of starting another turn.
                onAnswerUserQuestion(pending.questionId, text, emptyList())
            } else {
                sentFromKeyboard = true
                // 新回合先把用户消息定位到顶部；短内容保留留白，溢出后再平滑跟随。
                keepBottomAnchored = true
                userTurnScrollState = resolveUserTurnScrollTransition(
                    userTurnScrollState,
                    UserTurnScrollEvent.Submitted(
                        anchorMessageId = messageEdit?.targetMessageId?.takeIf { target ->
                            visibleMessages.any { it is UserMessageUi && it.id == target }
                        },
                        previousUserMessageId = visibleMessages.lastOrNull { it is UserMessageUi }?.id,
                    ),
                )
                thinkingCollapseResetKey += 1L
                onSubmit(text)
            }
        },
        onAnswerUserQuestion = onAnswerUserQuestion,
        pendingQuestion = visibleMessages.lastOrNull { it is UserQuestionMessageUi && it.running }
            as? UserQuestionMessageUi,
        onReasoningEffortChange = onReasoningEffortChange,
        onCompactContext = onCompactContext,
        canCompactContext = canCompactContext,
        onModelSelected = onModelSelected,
        onStop = onStop,
        onAttachImage = onAttachImage,
        onRemoveImage = onRemoveImage,
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
        onAttachFilePath = onAttachFilePath,
        onRemoveFileReference = onRemoveFileReference,
        onEditMessage = onEditMessage,
        onCancelMessageEdit = onCancelMessageEdit,
        onDeleteMessage = onDeleteMessage,
        onRegenerateMessage = onRegenerateMessage,
        onRetryFailedRun = onRetryFailedRun,
        onSelectReplyCandidate = onSelectReplyCandidate,
        onSuggestionClick = onSuggestionClick,
        onRunTraceClick = onRunTraceClick,
        onOpenBrowser = onOpenBrowser,
        agentControl = agentControl,
        currentBrowserMessageId = currentBrowserMessageId,
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    hasMessages: Boolean,
    scrollState: LazyListState,
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    messageEdit: MessageEditUiState?,
    showEmptySuggestions: Boolean,
    characterName: String?,
    keepBottomAnchored: Boolean,
    userTurnScrollState: UserTurnScrollState = UserTurnScrollState(),
    thinkingCollapseResetKey: Long = 0L,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onUserTurnScrollStateChanged: (UserTurnScrollState) -> Unit = {},
    onSubmit: (String) -> Unit,
    onAnswerUserQuestion: (String, String, List<String>) -> Unit,
    pendingQuestion: UserQuestionMessageUi?,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onRetryFailedRun: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    agentControl: AgentControlUi,
    currentBrowserMessageId: String?,
    modifier: Modifier = Modifier,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val frostEnabled = hasMessages && LocalBlurEnabled.current && isRuntimeShaderSupported()
    val messageBackdrop = rememberLayerBackdrop {
        // Backdrop 必须包含不透明底色，否则文字边缘模糊到透明区域时会出现黑边。
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            AgentChatBottomBar(
                messageBackdrop = messageBackdrop.takeIf { frostEnabled },
                input = input,
                modelPickerState = modelPickerState,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                messageEdit = messageEdit,
                onSubmit = onSubmit,
                onAnswerUserQuestion = onAnswerUserQuestion,
                pendingQuestion = pendingQuestion,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                agentControl = agentControl,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        if (!hasMessages) {
            EmptyChatState(
                showSuggestions = showEmptySuggestions,
                characterName = characterName,
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
            )
        } else {
            AgentConversationMessages(
                visibleMessages = visibleMessages,
                scrollState = scrollState,
                isStreaming = isStreaming,
                bottomInset = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
                userTurnScrollState = userTurnScrollState,
                thinkingCollapseResetKey = thinkingCollapseResetKey,
                onBottomAnchorChanged = onBottomAnchorChanged,
                onUserTurnScrollStateChanged = onUserTurnScrollStateChanged,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onRetryFailedRun = onRetryFailedRun,
                onSelectReplyCandidate = onSelectReplyCandidate,
                messageActionsEnabled = !isStreaming && messageEdit == null,
                editTargetMessageId = messageEdit?.targetMessageId,
                currentBrowserMessageId = currentBrowserMessageId,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (frostEnabled) Modifier.layerBackdrop(messageBackdrop) else Modifier),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    isStreaming: Boolean,
    bottomInset: Dp,
    keepBottomAnchored: Boolean,
    userTurnScrollState: UserTurnScrollState = UserTurnScrollState(),
    thinkingCollapseResetKey: Long = 0L,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onUserTurnScrollStateChanged: (UserTurnScrollState) -> Unit = {},
    assistantOverlay: Boolean = false,
    onSuggestionClick: (String) -> Unit = {},
    onRunTraceClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onRetryFailedRun: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    messageActionsEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    modifier: Modifier = Modifier,
) {
    val timelineEntries = remember(visibleMessages) { visibleMessages.toTimelineEntries() }
    // 整轮折叠：与 VS Code 的 collapseCompletedResponses 对齐——run 结束后，把最终回答
    // 之前的全部工作过程（思考/工具/中间正文）收成一行「已完成 N 个步骤」，点开可看全过程。
    val completedTurnRanges = remember(timelineEntries, isStreaming) {
        timelineEntries.completedTurnRanges(isStreaming)
    }
    // 会话切换（首条消息变化）时清空展开状态；配置变更通过 Saver 保留。
    val conversationKey = visibleMessages.firstOrNull()?.id
    var expandedTurnKeys by rememberSaveable(conversationKey, stateSaver = ExpandedTurnKeysSaver) {
        mutableStateOf(emptySet<String>())
    }
    val displayEntries = remember(timelineEntries, completedTurnRanges, expandedTurnKeys) {
        timelineEntries.withCompletedTurnCollapse(completedTurnRanges, expandedTurnKeys)
    }
    // 复制按钮只出现在每轮对话的最终结果上，中间步骤的过渡文本不提供复制入口。
    // 流式进行中当前这一轮尚未收尾，此时的“最后一条正文”只是中间步骤，不标记。
    val finalResultMessageIds = remember(visibleMessages, isStreaming) {
        resolveFinalResultMessageIds(visibleMessages, isStreaming = isStreaming)
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val streamingMarkdownStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    // 每个状态持有解析会话、AST 快照与显现全文副本，单会话内会随流式消息数线性累积；
    // 因此另用独立的插入顺序队列限制缓存条目数：超限淘汰最早插入的条目，最新插入的
    // 流式条目在队尾、不会被同一轮淘汰；被淘汰的旧消息退回无状态渲染，正确性不变
    // （条目缺席时读取端回退到组合内 remember，与历史消息加载路径一致）。
    val streamingMarkdownStateOrder = remember { ArrayDeque<String>() }

    fun retainStreamingMarkdownState(id: String): StreamingMarkdownState {
        streamingMarkdownStates[id]?.let { return it }
        val state = StreamingMarkdownState()
        streamingMarkdownStates[id] = state
        streamingMarkdownStateOrder.addLast(id)
        while (streamingMarkdownStateOrder.size > MAX_RETAINED_STREAMING_MARKDOWN_STATES) {
            streamingMarkdownStates.remove(streamingMarkdownStateOrder.removeFirst())
        }
        return state
    }

    val bottomItemIndex = displayEntries.size
    val latestUserMessage = visibleMessages.lastOrNull { it is UserMessageUi } as? UserMessageUi
    val latestUserEntryIndex = latestUserMessage?.let { user ->
        displayEntries.indexOfFirst { entry -> entry.key == user.id }.takeIf { it >= 0 }
    }
    val pinLatestUserTurn = isUserTurnAnchorReady(userTurnScrollState, latestUserMessage?.id)
    val baseBottomPadding = bottomInset + 14.dp
    val baseBottomPaddingPx = with(LocalDensity.current) { baseBottomPadding.roundToPx() }
    var pinnedMessageId by remember(thinkingCollapseResetKey) { mutableStateOf<String?>(null) }
    val pinReservePx by remember(scrollState, latestUserEntryIndex, pinLatestUserTurn, baseBottomPaddingPx) {
        derivedStateOf {
            if (!pinLatestUserTurn || latestUserEntryIndex == null) return@derivedStateOf 0
            val layoutInfo = scrollState.layoutInfo
            val viewportHeight = resolveUserTurnViewportEndPx(
                layoutInfo.viewportEndOffset,
                baseBottomPaddingPx,
            )
            val anchor = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestUserEntryIndex }
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
            resolveUserTurnReservePx(
                viewportHeightPx = viewportHeight,
                contentAfterAnchorPx = if (anchor != null && sentinel != null) {
                    (sentinel.offset + sentinel.size - anchor.offset).coerceAtLeast(0)
                } else null,
            )
        }
    }
    val pinReserve = with(LocalDensity.current) { pinReservePx.toDp() }
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember(scrollState) {
        derivedStateOf { !scrollState.canScrollForward }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()
    var wasStreaming by remember { mutableStateOf(false) }
    var finishingTurn by remember { mutableStateOf(false) }
    var preserveEndGap by remember { mutableStateOf(false) }
    val currentUserTurnPhase by rememberUpdatedState(userTurnScrollState.phase)
    val currentUserDragging by rememberUpdatedState(isUserDragging)

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            wasStreaming = true
            finishingTurn = false
            preserveEndGap = false
        } else if (wasStreaming) {
            wasStreaming = false
            finishingTurn = true
            preserveEndGap = !shouldAlignTurnToBottom(currentUserTurnPhase, currentUserDragging)
            // Let the thinking body finish folding and the temporary tail reserve disappear.
            delay(PANEL_COLLAPSE_MILLIS.toLong() + 80L)
            withFrameNanos { }
            if (shouldAlignTurnToBottom(currentUserTurnPhase, currentUserDragging)) {
                preserveEndGap = false
                scrollState.animateScrollToItem(bottomItemIndex)
                onUserTurnScrollStateChanged(UserTurnScrollState())
                onBottomAnchorChanged(true)
            }
            finishingTurn = false
        }
    }

    LaunchedEffect(preserveEndGap, isAtBottom, isUserDragging, userTurnScrollState.phase) {
        if (preserveEndGap && isAtBottom && !isUserDragging &&
            userTurnScrollState.phase != UserTurnScrollPhase.UserControlled
        ) {
            preserveEndGap = false
            withFrameNanos { }
            scrollState.animateScrollToItem(bottomItemIndex)
        }
    }

    LaunchedEffect(
        isUserDragging,
        isAtBottom,
        keepBottomAnchored,
        userTurnScrollState,
    ) {
        if (isUserDragging) {
            val next = resolveUserTurnScrollTransition(
                userTurnScrollState,
                UserTurnScrollEvent.UserDragged(atBottom = isAtBottom),
            )
            if (next != userTurnScrollState) onUserTurnScrollStateChanged(next)
            if (!isAtBottom) onBottomAnchorChanged(false)
        } else if (
            // 只有「拖动期间确实离开过底部」才在松手回底时恢复跟底：
            // 轻触/微扫不再重新武装跟底，避免一碰就被拉回底部。
            userTurnScrollState.phase == UserTurnScrollPhase.UserControlled &&
            userTurnScrollState.resumeAfterDrag &&
            userTurnScrollState.leftBottomDuringDrag &&
            isAtBottom
        ) {
            val next = resolveUserTurnScrollTransition(
                userTurnScrollState,
                UserTurnScrollEvent.ReturnedToBottom,
            )
            if (next != userTurnScrollState) onUserTurnScrollStateChanged(next)
            onBottomAnchorChanged(true)
        } else if (userTurnScrollState.phase == UserTurnScrollPhase.Idle) {
            val next = resolveKeepBottomAnchored(
                current = keepBottomAnchored,
                isUserDragging = isUserDragging,
                isAtBottom = isAtBottom,
            )
            if (next != keepBottomAnchored) onBottomAnchorChanged(next)
        }
    }

    LaunchedEffect(
        latestUserMessage?.id,
        latestUserEntryIndex,
        pinLatestUserTurn,
        thinkingCollapseResetKey,
    ) {
        val target = latestUserEntryIndex ?: return@LaunchedEffect
        if (!pinLatestUserTurn) return@LaunchedEffect
        if (isUserDragging) return@LaunchedEffect
        // Reserve is applied in the same layout as the new message, so it can reach the top
        // immediately, even when no response exists yet.
        withFrameNanos { }
        if (!currentUserDragging && currentUserTurnPhase == UserTurnScrollPhase.Pinned) {
            scrollState.scrollToItem(target, 0)
            withFrameNanos { }
            pinnedMessageId = latestUserMessage?.id
        }
    }

    val pinContentOverflow by remember(
        scrollState, latestUserEntryIndex, pinLatestUserTurn, pinnedMessageId, baseBottomPaddingPx,
    ) {
        derivedStateOf {
            if (!pinLatestUserTurn || latestUserEntryIndex == null ||
                pinnedMessageId != latestUserMessage?.id
            ) return@derivedStateOf false
            val layoutInfo = scrollState.layoutInfo
            val anchor = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestUserEntryIndex }
                ?: return@derivedStateOf false
            // Tail reserve enables top anchoring; it does not reduce the visible content area.
            val viewportEnd = resolveUserTurnViewportEndPx(layoutInfo.viewportEndOffset, baseBottomPaddingPx)
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
            sentinel == null || sentinel.offset + sentinel.size > viewportEnd
        }
    }

    LaunchedEffect(pinContentOverflow, userTurnScrollState.phase) {
        if (pinContentOverflow && userTurnScrollState.phase == UserTurnScrollPhase.Pinned) {
            onUserTurnScrollStateChanged(
                resolveUserTurnScrollTransition(
                    userTurnScrollState,
                    UserTurnScrollEvent.ContentOverflow,
                ),
            )
            onBottomAnchorChanged(true)
        }
    }

    val tailMessage = visibleMessages.lastOrNull() as? AgentMessageUi
    val isTailRendering = tailMessage?.let { message ->
        streamingMarkdownStates[message.id]?.let { state ->
            state.revealedContent != message.content
        }
    } == true
    var isBottomSettling by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming, isTailRendering, keepBottomAnchored, isUserDragging) {
        if (!keepBottomAnchored || isUserDragging) {
            isBottomSettling = false
        } else if (isStreaming || isTailRendering) {
            isBottomSettling = true
        } else if (isBottomSettling) {
            // 显现完成后还会切换稳定排版并插入操作行，等其完成测量再收口跟底。
            withFrameNanos { }
            withFrameNanos { }
            snapshotFlow { !isUserDragging && !scrollState.canScrollForward }.first { it }
            isBottomSettling = false
        }
    }

    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isBottomSettling = isBottomSettling,
            pinLatestUserTurn = pinLatestUserTurn,
            userTurnPhase = userTurnScrollState.phase,
        ) && !finishingTurn
    )
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    // 流式期间不硬跳到底部（requestScrollToItem 会瞬间夺走滚动位置，观感像“一碰就跳”）；
    // 需要回底时统一交给下面的连续跟底循环，它尊重拖动/用户接管状态且是动画过渡。

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            BottomFollowLayout(
                enabled = shouldFollowBottom,
                bottomItemIndex = currentBottomItemIndex,
                sentinelBottom = sentinel?.let { it.offset + it.size },
                // 输入器高度属于滚动内容的 bottom inset，而不是滚动容器高度。
                // 跟底目标应是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                )
                bottomFollowDecisions.trySend(decision)
            }
    }

    // 一个持续存在的帧时钟从当前屏幕位置追向最新目标。新字符继续到达时只更新目标，
    // 不取消并重启动画，因此速度连续；用户开始拖动后，enabled=false 会立即停止跟随。
    LaunchedEffect(scrollState, bottomFollowDecisions) {
        var remainingDistancePx = 0f
        var requestIndex: Int? = null
        var previousFrameNanos = 0L

        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.toFloat()
            requestIndex = decision.requestIndex
        }

        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f && requestIndex == null) {
                accept(bottomFollowDecisions.receive())
                previousFrameNanos = 0L
            }
            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }

            if (!shouldFollowBottom) {
                remainingDistancePx = 0f
                requestIndex = null
                continue
            }

            requestIndex?.let { targetIndex ->
                try {
                    scrollState.animateScrollToItem(targetIndex)
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                }
                requestIndex = null
                remainingDistancePx = 0f
                return@let
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos

            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom || requestIndex != null || remainingDistancePx <= 0f) continue

            val step = smoothBottomFollowStep(
                distancePx = remainingDistancePx,
                elapsedSeconds = elapsedSeconds,
                density = densityScale,
            )
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    // 用真实消费距离扣减，未消费时等下一次布局的新 overflow，避免丢距离造成的顿挫与空转。
                    consumedStep = scrollBy(step)
                }
                remainingDistancePx = if (consumedStep <= 0f) {
                    0f
                } else {
                    (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                remainingDistancePx = 0f
            }
        }
    }

    // 滚动层保持整屏，输入器作为后绘制浮层；输入器高度进入列表的
    // afterContentPadding，确保跟到底部时最后一行停在输入器上方。
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val thinkingViewportHeight = (maxHeight - bottomInset).coerceAtLeast(0.dp)
        LazyColumn(
            state = scrollState,
            verticalArrangement = if (
                isStreaming || userTurnScrollState.phase != UserTurnScrollPhase.Idle
            ) Arrangement.Top else Arrangement.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = baseBottomPadding + pinReserve,
            ),
            overscrollEffect = null,
        ) {
            itemsIndexed(
                items = displayEntries,
                key = { _, entry -> entry.key },
                contentType = { _, entry ->
                    when (entry) {
                        is AgentTimelineEntry.Message -> entry.message::class
                        is AgentTimelineEntry.ThinkingBlock -> AgentTimelineEntry.ThinkingBlock::class
                        is AgentTimelineEntry.CompletedSteps -> AgentTimelineEntry.CompletedSteps::class
                    }
                },
            ) { entryIndex, entry ->
                // 历史消息滚动是主要交互路径：不再给每个 item 挂 animateItem，
                // 避免滚动时额外的 item 动画调度与重组开销。
                val itemModifier = Modifier
                when (entry) {
                    is AgentTimelineEntry.Message -> {
                        val message = entry.message
                        ChatMessageItem(
                            message = message,
                            thinkingCollapseResetKey = thinkingCollapseResetKey,
                            thinkingViewportHeight = thinkingViewportHeight,
                            onThinkingToggle = {
                                onBottomAnchorChanged(false)
                                onUserTurnScrollStateChanged(
                                    resolveUserTurnScrollTransition(
                                        userTurnScrollState,
                                        UserTurnScrollEvent.PanelToggled,
                                    ),
                                )
                            },
                            assistantOverlay = assistantOverlay,
                            retainedStreamingState = (message as? AgentMessageUi)
                                ?.takeIf { it.isStreaming || streamingMarkdownStates.containsKey(it.id) }
                                ?.let { agentMessage ->
                                    retainStreamingMarkdownState(agentMessage.id)
                                },
                            onSuggestionClick = onSuggestionClick,
                            onRunTraceClick = onRunTraceClick,
                            onOpenBrowser = onOpenBrowser,
                            showBrowserShortcut = message is ToolActivityMessageUi &&
                                message.toolName == "browser_use" &&
                                message.id == currentBrowserMessageId,
                            showCopyAction = message !is AgentMessageUi ||
                                message.characterEditable || message.id in finalResultMessageIds,
                            showMessageActions = message.id in finalResultMessageIds ||
                                (message is AgentMessageUi && message.characterEditable),
                            messageActionsEnabled = messageActionsEnabled,
                            isEditing = message.id == editTargetMessageId,
                            onEditMessage = onEditMessage,
                            onDeleteMessage = onDeleteMessage,
                            onRegenerateMessage = onRegenerateMessage,
                            onRetryFailedRun = onRetryFailedRun,
                            canRetryFailedRun = !isStreaming && message is SystemNoticeMessageUi &&
                                message.code == SystemNoticeCode.RuntimeFailed &&
                                message.id == visibleMessages.lastOrNull()?.id,
                            onSelectReplyCandidate = onSelectReplyCandidate,
                            modifier = itemModifier,
                        )
                    }

                    is AgentTimelineEntry.ThinkingBlock -> {
                        AgentThinkingBlock(
                            id = entry.key,
                            messages = entry.messages,
                            assistantOverlay = assistantOverlay,
                            collapseResetKey = thinkingCollapseResetKey,
                            thinkingViewportHeight = thinkingViewportHeight,
                            // 思考块直到 run 结束或后续条目（工具行/正文）出现前都保持展开，
                            // 避免思考间隙里整个卡片反复折叠展开。
                            // 该判定依赖一个不变量：运行中追加到思考块之后的条目只可能
                            // 是工具行、正文或终结性消息（通知/提问会终结当前块）；若未来
                            // 引入时间线末尾的“运行中占位条目”，这里需同步调整。
                            blockActive = isStreaming && entryIndex == displayEntries.lastIndex,
                            onThinkingToggle = {
                                onBottomAnchorChanged(false)
                                onUserTurnScrollStateChanged(
                                    resolveUserTurnScrollTransition(
                                        userTurnScrollState,
                                        UserTurnScrollEvent.PanelToggled,
                                    ),
                                )
                            },
                            modifier = itemModifier,
                        )
                    }

                    is AgentTimelineEntry.CompletedSteps -> {
                        AgentCompletedStepsRow(
                            stepCount = entry.stepCount,
                            expanded = entry.expanded,
                            onToggle = {
                                expandedTurnKeys = if (entry.expanded) {
                                    expandedTurnKeys - entry.key
                                } else {
                                    expandedTurnKeys + entry.key
                                }
                                onBottomAnchorChanged(false)
                                onUserTurnScrollStateChanged(
                                    resolveUserTurnScrollTransition(
                                        userTurnScrollState,
                                        UserTurnScrollEvent.PanelToggled,
                                    ),
                                )
                            },
                            modifier = itemModifier,
                        )
                    }
                }
            }
            item(key = ChatBottomSentinelKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = !keepBottomAnchored && !isAtBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 12.dp),
            enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
        ) {
            IconButton(
                onClick = {
                    onBottomAnchorChanged(true)
                    onUserTurnScrollStateChanged(UserTurnScrollState())
                    coroutineScope.launch {
                        scrollState.animateScrollToItem(bottomItemIndex)
                    }
                },
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                minWidth = 40.dp,
                minHeight = 40.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(R.string.ui_back_to_bottom_32282e),
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
): BottomFollowDecision {
    if (!enabled) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null &&
            lastVisibleIndex != null &&
            lastVisibleIndex < bottomItemIndex -> BottomFollowDecision(requestIndex = bottomItemIndex)
        else -> BottomFollowDecision()
    }
}

internal fun smoothBottomFollowStep(
    distancePx: Float,
    elapsedSeconds: Float,
    density: Float,
): Float {
    if (distancePx <= 0f || elapsedSeconds <= 0f) return 0f
    if (distancePx <= BOTTOM_FOLLOW_SNAP_DISTANCE_PX) return distancePx

    val frameSeconds = elapsedSeconds.coerceAtMost(BOTTOM_FOLLOW_MAX_FRAME_SECONDS)
    val easedStep = distancePx * (1f - exp(-frameSeconds / BOTTOM_FOLLOW_RESPONSE_SECONDS))
    val speedLimitedStep = BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND * density * frameSeconds
    return min(distancePx, min(easedStep.coerceAtLeast(BOTTOM_FOLLOW_MIN_STEP_PX), speedLimitedStep))
}

/** Returns true once the latest user turn has produced any visible work or answer output. */
internal fun latestUserTurnHasOutput(messages: List<AgentChatMessageUi>): Boolean {
    val userIndex = messages.indexOfLast { it is UserMessageUi }
    if (userIndex < 0) return false
    return messages.drop(userIndex + 1).any { message ->
        when (message) {
            is AgentMessageUi -> message.content.isNotBlank()
            is ThinkingMessageUi -> message.isStreaming || message.content.isNotBlank()
            is ToolActivityMessageUi -> true
            else -> false
        }
    }
}

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class ThinkingBlock(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry

    /** 整轮工作过程的折叠摘要行（对应 Copilot Chat 的 "Completed N steps"）。 */
    data class CompletedSteps(
        override val key: String,
        val stepCount: Int,
        val expanded: Boolean,
    ) : AgentTimelineEntry
}

internal fun List<AgentChatMessageUi>.toTimelineEntries(): List<AgentTimelineEntry> = buildList {
    val thinkingMessages = mutableListOf<AgentChatMessageUi>()

    fun flushThinkingBlock() {
        if (thinkingMessages.isEmpty()) return
        add(
            AgentTimelineEntry.ThinkingBlock(
                key = "thinking-${thinkingMessages.first().id}",
                messages = thinkingMessages.toList(),
            )
        )
        thinkingMessages.clear()
    }

    this@toTimelineEntries.forEach { message ->
        // 思考收束为「思考块」；工具调用留在主流逐条显示——对齐 VS Code 定型后的
        // reasoning/items 分组（思考归容器，工具行独立成行，不与推理文本混排）。
        if (message is ThinkingMessageUi) {
            thinkingMessages += message
        } else {
            flushThinkingBlock()
            add(AgentTimelineEntry.Message(message))
        }
    }
    flushThinkingBlock()
}

/** 整轮折叠的展开状态：Set<String> 无法直接进 Bundle，用 listSaver 存成 List。 */
private val ExpandedTurnKeysSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/** 可被整轮折叠收走的步骤数：思考块按内部条目数、工具行与中间正文各第 1 步，其余为 0。 */
internal fun AgentTimelineEntry.workStepCount(): Int = when (this) {
    is AgentTimelineEntry.ThinkingBlock -> messages.size.coerceAtLeast(1)
    is AgentTimelineEntry.Message -> when (message) {
        is ToolActivityMessageUi, is AgentMessageUi -> 1
        else -> 0
    }
    else -> 0
}

/** 一轮内可折叠的工作过程区间。 */
internal data class CompletedTurnRange(
    val key: String,
    val startIndex: Int,
    val endIndexExclusive: Int,
    val stepCount: Int,
)

/**
 * 找出可整轮折叠的区间：一轮（两条用户消息之间）中最终回答之前的连续工作条目。
 * 只处理以用户消息开头的完整回合——被窗口截断的半截片段（如语音浮窗 takeLast 的
 * 起点）不折叠，避免出现步数少算的假摘要。
 * 进行中的最后一轮不折叠；不足 2 步或没有最终回答时不折叠（与 VS Code 一致）。
 */
internal fun List<AgentTimelineEntry>.completedTurnRanges(isStreaming: Boolean): List<CompletedTurnRange> {
    val ranges = mutableListOf<CompletedTurnRange>()
    var turnStart = 0
    var turnUserLed = false

    fun scanTurn(start: Int, endExclusive: Int, isLastTurn: Boolean, userLed: Boolean) {
        if (!userLed) return
        if (endExclusive <= start) return
        if (isStreaming && isLastTurn) return
        val finalAnswerIndex = (endExclusive - 1 downTo start).firstOrNull { index ->
            val entry = this[index]
            entry is AgentTimelineEntry.Message && entry.message is AgentMessageUi
        } ?: return
        var workStart = start
        while (workStart < finalAnswerIndex && this[workStart].workStepCount() == 0) workStart++
        if (workStart >= finalAnswerIndex) return
        var stepCount = 0
        for (index in workStart until finalAnswerIndex) {
            val steps = this[index].workStepCount()
            if (steps == 0) return
            stepCount += steps
        }
        if (stepCount < 2) return
        ranges += CompletedTurnRange(
            key = "completed-${this[workStart].key}",
            startIndex = workStart,
            endIndexExclusive = finalAnswerIndex,
            stepCount = stepCount,
        )
    }

    forEachIndexed { index, entry ->
        if (entry is AgentTimelineEntry.Message && entry.message is UserMessageUi) {
            scanTurn(turnStart, index, isLastTurn = false, userLed = turnUserLed)
            turnStart = index + 1
            turnUserLed = true
        }
    }
    scanTurn(turnStart, size, isLastTurn = true, userLed = turnUserLed)
    return ranges
}

/**
 * 按折叠状态生成渲染列表：折叠时区间被一行摘要替换，展开时摘要行后重新显示区间内容。
 */
internal fun List<AgentTimelineEntry>.withCompletedTurnCollapse(
    ranges: List<CompletedTurnRange>,
    expandedKeys: Set<String>,
): List<AgentTimelineEntry> {
    if (ranges.isEmpty()) return this
    val source = this
    val rangeByStart = ranges.associateBy { it.startIndex }
    return buildList {
        var index = 0
        while (index < source.size) {
            val range = rangeByStart[index]
            if (range == null) {
                add(source[index])
                index++
                continue
            }
            val expanded = range.key in expandedKeys
            add(AgentTimelineEntry.CompletedSteps(range.key, range.stepCount, expanded))
            if (expanded) {
                for (inner in range.startIndex until range.endIndexExclusive) {
                    add(source[inner])
                }
            }
            index = range.endIndexExclusive
        }
    }
}

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式传输期间当前轮次尚未结束，最后一轮不标记，等传输结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            else -> Unit
        }
    }
    if (!isStreaming) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

@Composable
private fun AgentChatBottomBar(
    messageBackdrop: LayerBackdrop?,
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    messageEdit: MessageEditUiState?,
    onSubmit: (String) -> Unit,
    onAnswerUserQuestion: (String, String, List<String>) -> Unit,
    pendingQuestion: UserQuestionMessageUi?,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    agentControl: AgentControlUi,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        if (messageBackdrop != null) {
            val blurColors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.72f))
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatBottomFrostHeight)
                    // DstIn 让真实磨砂在顶部透明、靠近输入框时逐渐变实，消除硬裁切线。
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .textureBlur(
                        backdrop = messageBackdrop,
                        shape = RectangleShape,
                        blurRadius = 20f,
                        colors = blurColors,
                    ),
            )
        } else {
            // 空白主页沿用原来的轻微渐隐，不改变主页视觉。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MiuixTheme.colorScheme.surface,
                            ),
                        )
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            AgentChatInputBar(
                input = input,
                modelPickerState = modelPickerState,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                pendingQuestion = pendingQuestion,
                onAnswerUserQuestion = onAnswerUserQuestion,
                isEditingMessage = messageEdit != null,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                preserveFollowingMessages = messageEdit?.preserveFollowingMessages == true,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                agentControl = agentControl,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val ChatBottomFrostHeight = 24.dp

private const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"
private const val BOTTOM_FOLLOW_RESPONSE_SECONDS = 0.16f
private const val BOTTOM_FOLLOW_MAX_FRAME_SECONDS = 0.05f
private const val BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND = 1400f
private const val BOTTOM_FOLLOW_MIN_STEP_PX = 1f
private const val BOTTOM_FOLLOW_SNAP_DISTANCE_PX = 3f

// 流式 Markdown 缓存状态（解析会话、AST 快照、显现全文副本）按消息 id 保留的数量上限。
private const val MAX_RETAINED_STREAMING_MARKDOWN_STATES = 64

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
): Boolean = when {
    isUserDragging -> isAtBottom
    isAtBottom -> true
    else -> current
}

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
    pinLatestUserTurn: Boolean = false,
    userTurnPhase: UserTurnScrollPhase? = null,
): Boolean = (isStreaming || isBottomSettling) &&
    keepBottomAnchored &&
    !isUserDragging &&
    !pinLatestUserTurn &&
    (userTurnPhase == null || userTurnPhase == UserTurnScrollPhase.Idle || userTurnPhase == UserTurnScrollPhase.FollowingOverflow)

@Composable
private fun EmptyChatState(
    showSuggestions: Boolean,
    characterName: String?,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCharacterConversation = characterName != null
    val suggestions = listOf(
        SuggestionItem(
            title = stringResource(R.string.ui_analyze_current_screen_ebf08f),
            icon = Icons.Rounded.DocumentScanner,
            prompt = stringResource(R.string.suggestion_analyze_screen_prompt),
            tint = Color(0xFF2E9DA5),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_open_wechat_6b2c28),
            icon = Icons.Rounded.RocketLaunch,
            prompt = stringResource(R.string.suggestion_open_wechat_prompt),
            tint = Color(0xFFE1864D),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_browse_the_web_da7afb),
            icon = Icons.Rounded.Language,
            prompt = stringResource(R.string.suggestion_browse_web_prompt),
            tint = Color(0xFF4F8DFF),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_check_memory_pressure_2d9600),
            icon = Icons.Rounded.Terminal,
            prompt = stringResource(R.string.suggestion_memory_pressure_prompt),
            tint = Color(0xFF3A9B72),
        ),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isCharacterConversation) {
                Text(
                    text = characterName.orEmpty(),
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "故事从这里开始",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    text = stringResource(R.string.ui_how_can_i_help_you_e75391),
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            AnimatedVisibility(
                visible = showSuggestions && !isCharacterConversation,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 220)
                ) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it / 3 },
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 130)
                ) + slideOutVertically(
                    animationSpec = tween(durationMillis = 180),
                    targetOffsetY = { it / 4 },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    suggestions.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { item ->
                                SuggestionCard(
                                    item = item,
                                    onClick = { onSuggestionClick(item.prompt) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 92.dp)
            .liquidGlassSurface(cornerRadius = 20.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier.size(32.dp).background(item.tint.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = item.tint,
            )
        }
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val icon: ImageVector,
    val prompt: String,
    val tint: Color,
)
