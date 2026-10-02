package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.ui.app.SUBAGENT_TOOL_NAME
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.UserQuestionMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberDataUrlBitmap(dataUrl: String): ImageBitmap? {
    var bitmap by remember(dataUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(dataUrl) {
        bitmap = withContext(Dispatchers.IO) { decodeDataUrlBitmap(dataUrl) }
    }
    return bitmap
}

/**
 * dataUrl → 解码位图的进程级 LRU 缓存。
 *
 * 消息里的图片在 LazyColumn 滚出视口后会被销毁，滚回来时 remember 状态已不存在，只能重新
 * 做 base64 解码与整图采样解码；这里按 dataUrl 记住最近几条结果，命中即复用同一 ImageBitmap。
 * 解码入参、采样率与像素内容都不变，所以渲染结果与加缓存前完全一致。
 *
 * 容量按解码后字节数（16 MiB）与条目数（8）双重限制：采样后长边不超过 1024，ARGB_8888 下
 * 单张最多约 4 MiB，16 MiB 约能同时留住 4 张大图或更多小图；条目上限兜住大量极小图。
 * 淘汰只释放引用，不对位图调用 recycle（可能仍被 Compose 绘制使用），回收交给 GC。
 */
private const val DATA_URL_BITMAP_CACHE_MAX_BYTES = 16 * 1024 * 1024
private const val DATA_URL_BITMAP_CACHE_MAX_ENTRIES = 8

private class DataUrlBitmapLruCache {
    private data class Key(val maxLongEdge: Int, val dataUrl: String)

    private val lock = Any()
    private val entries = LinkedHashMap<Key, ImageBitmap>(16, 0.75f, true)
    private var bytes = 0L

    fun get(maxLongEdge: Int, dataUrl: String): ImageBitmap? = synchronized(lock) {
        entries[Key(maxLongEdge, dataUrl)]
    }

    fun put(maxLongEdge: Int, dataUrl: String, bitmap: ImageBitmap) {
        val size = bitmap.decodedByteCount()
        synchronized(lock) {
            entries.put(Key(maxLongEdge, dataUrl), bitmap)?.let { previous ->
                bytes -= previous.decodedByteCount()
            }
            bytes += size
            val iterator = entries.entries.iterator()
            while (iterator.hasNext() && overCapacity()) {
                bytes -= iterator.next().value.decodedByteCount()
                iterator.remove()
            }
        }
    }

    /** 仅在上锁的临界区内读取 entries/bytes。 */
    private fun overCapacity(): Boolean =
        entries.size > DATA_URL_BITMAP_CACHE_MAX_ENTRIES ||
            bytes > DATA_URL_BITMAP_CACHE_MAX_BYTES
}

private val dataUrlBitmapCache = DataUrlBitmapLruCache()

private fun ImageBitmap.decodedByteCount(): Long =
    asAndroidBitmap().allocationByteCount.toLong()

internal fun decodeDataUrlBitmap(dataUrl: String, maxLongEdge: Int = 1024): ImageBitmap? {
    dataUrlBitmapCache.get(maxLongEdge, dataUrl)?.let { cached -> return cached }
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    val decoded = runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        // 先只读边界算采样率，长边压到 maxLongEdge 以内，避免全图进内存再缩。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / sample > maxLongEdge && sample < 8) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
    if (decoded != null) dataUrlBitmapCache.put(maxLongEdge, dataUrl, decoded)
    return decoded
}

/**
 * 等待首个文本片段时的轻量反馈。
 */
@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer(alpha = alpha)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary, CircleShape)
            )
        }
    }
}

/**
 * 只有正在执行的状态才持有无限动画。历史思考和工具条目保持静态，避免长会话里
 * 每个已完成节点都持续产生帧时钟与状态更新。
 */
@Composable
private fun rememberActivePulse(
    active: Boolean,
    label: String,
): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = label)
    val alpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    return alpha
}

@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    messageActionsEnabled: Boolean = true,
    isEditing: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onRetryFailedRun: (String) -> Unit = {},
    canRetryFailedRun: Boolean = false,
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    thinkingCollapseResetKey: Long = 0L,
    onThinkingToggle: () -> Unit = {},
    thinkingViewportHeight: androidx.compose.ui.unit.Dp? = null,
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(
            message = message,
            assistantOverlay = assistantOverlay,
            actionsEnabled = messageActionsEnabled,
            isEditing = isEditing,
            onEdit = { onEditMessage(message.id) },
            onDelete = { onDeleteMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            assistantOverlay = assistantOverlay,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            onDelete = { onDeleteMessage(message.id) },
            onRegenerate = { onRegenerateMessage(message.id) },
            onEdit = { onEditMessage(message.id) },
            onSelectCandidate = { onSelectReplyCandidate(message.id, it) },
            modifier = modifier,
        )
        is UserQuestionMessageUi -> UserQuestionCard(message = message, modifier = modifier)
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.ContextCompaction) {
            ContextCompactionMarker(message = message, modifier = modifier)
        } else {
            Column(modifier = modifier) {
            AgentMessageBlock(
                message = AgentMessageUi(
                    id = message.id,
                    content = buildString {
                        append(
                            stringResource(
                                when (message.code) {
                                    SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                    SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                    SystemNoticeCode.ContextCompaction -> R.string.context_compaction
                                    SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                    SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                    SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                },
                            ),
                        )
                        message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                            append("\n\n")
                            append(detail)
                        }
                    },
                    renderMarkdown = false,
                ),
                assistantOverlay = false,
                retainedStreamingState = null,
                showCopyAction = showCopyAction,
                showMessageActions = showMessageActions,
                messageActionsEnabled = messageActionsEnabled,
                onDelete = { onDeleteMessage(message.id) },
                onRegenerate = { onRegenerateMessage(message.id) },
                modifier = Modifier,
            )
            if (message.code == SystemNoticeCode.RuntimeFailed && canRetryFailedRun) {
                TextButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onRetryFailedRun(message.id) },
                    modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                )
            }
            }
        }
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            modifier = modifier,
            compact = compact,
            collapseResetKey = thinkingCollapseResetKey,
            onThinkingToggle = onThinkingToggle,
            thinkingViewportHeight = thinkingViewportHeight,
        )
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> ToolActivityInline(
            message = message,
            onOpenBrowser = onOpenBrowser,
            showBrowserShortcut = showBrowserShortcut,
            modifier = modifier,
            compact = compact,
        )
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier, compact = compact)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = onSuggestionClick, modifier = modifier)
    }
}

/**
 * 把连续的思考与工具调用收束为一个可展开的工作过程，避免 Agent 事件退化为聊天气泡噪音。
 */
@Composable
internal fun AgentThinkingBlock(
    id: String,
    messages: List<AgentChatMessageUi>,
    assistantOverlay: Boolean = false,
    collapseResetKey: Long = 0L,
    onThinkingToggle: () -> Unit = {},
    thinkingViewportHeight: androidx.compose.ui.unit.Dp? = null,
    currentBrowserMessageId: String? = null,
    onOpenBrowser: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val thinkingRunning = messages.any { message ->
        message is ThinkingMessageUi && message.isStreaming
    }
    val toolsRunning = messages.any { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    }
    val running = thinkingRunning || toolsRunning
    val lastThinking = messages.lastOrNull { it is ThinkingMessageUi } as? ThinkingMessageUi
    val stepCount = messages.count { it is ToolActivityMessageUi }
    val hasThinkingContent = messages.any { it is ThinkingMessageUi && it.content.isNotBlank() }
    // 头部文案对齐 VS Code 的工作过程用词：工具执行中显示步骤数，思考中显示思考中，
    // 结束后优先显示步骤总数，只有思考内容时保留原有的计时文案。
    val headerText = when {
        toolsRunning -> pluralStringResource(R.plurals.work_processing_step, stepCount, stepCount)
        thinkingRunning -> stringResource(R.string.reasoning_in_progress)
        stepCount > 0 -> pluralStringResource(R.plurals.work_completed_steps, stepCount, stepCount)
        else -> lastThinking?.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
            pluralStringResource(R.plurals.reasoning_completed_seconds, seconds, seconds)
        } ?: stringResource(R.string.reasoning_completed)
    }
    // A new thinking segment opens by default; its end resets manual overrides.
    var expansionOverride by rememberSaveable(id, running, lastThinking?.id) {
        mutableStateOf<Boolean?>(null)
    }
    val expanded = expansionOverride ?: running
    var startedRunning by remember(id) { mutableStateOf(running) }
    if (running) startedRunning = true
    var collapsedHeightPx by rememberSaveable(id) { mutableStateOf(0) }
    var appliedCollapseResetKey by rememberSaveable(id) { mutableStateOf(0L) }
    val expansionProgress = rememberThinkingExpansionProgress(expanded)
    LaunchedEffect(collapseResetKey) {
        if (resetThinkingCollapseSlots(appliedCollapseResetKey, collapseResetKey)) {
            collapsedHeightPx = 0
            appliedCollapseResetKey = collapseResetKey
        }
    }

    val pulseAlpha = rememberActivePulse(active = running, label = "work_pulse")

    if (assistantOverlay) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .clickable {
                        onThinkingToggle()
                        expansionOverride = !expanded
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurface)
                        .graphicsLayer(alpha = if (running) pulseAlpha else 1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = headerText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            ThinkingCollapseBody(
                expanded = expanded,
                progress = expansionProgress,
                measuredHeightPx = collapsedHeightPx,
                onHeightMeasured = { collapsedHeightPx = it },
            ) {
                ThinkingMessageList(
                    id = id,
                    messages = messages,
                    running = running,
                    assistantOverlay = true,
                    onThinkingToggle = onThinkingToggle,
                    currentBrowserMessageId = currentBrowserMessageId,
                    onOpenBrowser = onOpenBrowser,
                    runningBodyHeight = thinkingViewportHeight?.takeIf { startedRunning }?.let {
                        thinkingBodyHeightDp(it.value).dp
                    },
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("thinking-card-$id")
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f),
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onThinkingToggle()
                    expansionOverride = !expanded
                }
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (stepCount > 0 && !hasThinkingContent) {
                    Icons.Rounded.Build
                } else {
                    Icons.Rounded.Lightbulb
                },
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer(alpha = if (running) pulseAlpha else 1f),
                tint = if (running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = headerText,
                style = MiuixTheme.textStyles.body2,
                color = if (running) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    when {
                        stepCount > 0 ->
                            if (expanded) R.string.work_collapse else R.string.work_expand
                        else ->
                            if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand
                    },
                ),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        ThinkingCollapseBody(
            expanded = expanded,
            progress = expansionProgress,
            measuredHeightPx = collapsedHeightPx,
            onHeightMeasured = { collapsedHeightPx = it },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp)
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
            )
            ThinkingMessageList(
                id = id,
                messages = messages,
                running = running,
                assistantOverlay = false,
                onThinkingToggle = onThinkingToggle,
                currentBrowserMessageId = currentBrowserMessageId,
                onOpenBrowser = onOpenBrowser,
                runningBodyHeight = thinkingViewportHeight?.takeIf { startedRunning }?.let {
                    thinkingBodyHeightDp(it.value).dp
                },
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
        }
    }
    }
}

/**
 * 工作过程块展开后的内层列表。
 *
 * 一个块可能包含多条思考消息与工具调用；这里用限高 LazyColumn 懒组合，
 * 只渲染视口附近的条目，并在用户没有上滑时跟随最后一条输出。
 */
@Composable
private fun ThinkingMessageList(
    id: String,
    messages: List<AgentChatMessageUi>,
    running: Boolean,
    assistantOverlay: Boolean,
    onFollowInterrupted: () -> Unit = {},
    onThinkingToggle: () -> Unit = {},
    currentBrowserMessageId: String? = null,
    onOpenBrowser: () -> Unit = {},
    runningBodyHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current.density
    // 思考块折叠后会离开组合，重新展开时用 remember 让跟随状态回到 true。
    var followOutput by remember(id) { mutableStateOf(true) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val atBottom by remember(listState) {
        derivedStateOf { !listState.canScrollForward }
    }
    val currentFollow by rememberUpdatedState(followOutput)
    val currentDragging by rememberUpdatedState(isUserDragging)

    // 用户上滑离开底部 -> 脱离自动跟随；重新滑到底部并松手 -> 恢复。
    LaunchedEffect(isUserDragging, atBottom) {
        when {
            isUserDragging && !atBottom -> {
                followOutput = false
                onFollowInterrupted()
            }
            atBottom && !isUserDragging -> followOutput = true
        }
    }

    // 连续帧时钟逐步追底，避免每次增量瞬间跳到底部造成抖动。
    // 历史思考等布局稳定后退出；流式思考持续跟随直到不再运行。
    LaunchedEffect(listState, followOutput, running, messages.isEmpty()) {
        if (!followOutput || messages.isEmpty()) return@LaunchedEffect
        var previousFrameNanos = 0L
        var stableFrames = 0
        while (currentFollow) {
            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos
            if (currentDragging) continue
            val layoutInfo = listState.layoutInfo
            val lastIndex = layoutInfo.totalItemsCount - 1
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastIndex < 0 || lastVisibleItem == null) {
                stableFrames = 0
                continue
            }
            if (lastVisibleItem.index != lastIndex) {
                // 最后一条还没进入视口：先平滑滚到它，再交给下面的帧循环跟随其内部增长。
                listState.animateScrollToItem(lastIndex)
                previousFrameNanos = 0L
                stableFrames = 0
                continue
            }
            val viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
            val distance = (lastVisibleItem.offset + lastVisibleItem.size - viewportEnd).toFloat()
            val viewportHeight = (viewportEnd - layoutInfo.viewportStartOffset).coerceAtLeast(1)
            if (distance > viewportHeight * 2f) {
                listState.scroll { scrollBy(distance - viewportHeight) }
                previousFrameNanos = 0L
                continue
            }
            if (distance <= 0f) {
                // 历史内容等异步 Markdown 排版稳定后再退出。
                if (!running && ++stableFrames > 60) break
                continue
            }
            stableFrames = 0
            val step = smoothBottomFollowStep(distance, elapsedSeconds, density)
            if (step > 0f) listState.scroll { scrollBy(step) }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (runningBodyHeight != null) Modifier.height(runningBodyHeight)
                else Modifier.heightIn(max = if (assistantOverlay) 360.dp else 420.dp)
            ),
    ) {
        items(
            items = messages,
            key = { it.id },
            contentType = { it::class },
        ) { message ->
            ChatMessageItem(
                message = message,
                onSuggestionClick = {},
                onRunTraceClick = {},
                onOpenBrowser = onOpenBrowser,
                showBrowserShortcut = message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id == currentBrowserMessageId,
                compact = true,
                assistantOverlay = assistantOverlay,
                onThinkingToggle = onThinkingToggle,
            )
        }
    }
}

// ── 用户消息：轻盈美观气泡 ──────────────────────────────────────────────

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    assistantOverlay: Boolean,
    actionsEnabled: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val tooltipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(actionsEnabled) {
        if (!actionsEnabled) tooltipState.dismiss()
    }
    val visiblePrompt = remember(message.content) {
        AgentFileReferencePromptCodec.parse(message.content)
    }
    val overlayBubbleColor = overlayBubbleColor()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (assistantOverlay) 16.dp else 20.dp,
                vertical = if (assistantOverlay) 4.dp else 7.dp,
            ),
        horizontalArrangement = Arrangement.End,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                positioning = TooltipAnchorPosition.Below,
            ),
            tooltip = {
                RichTooltip(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        MessageTooltipAction(
                            icon = Icons.Rounded.ContentCopy,
                            label = stringResource(R.string.ui_copy_4edd1d),
                            onClick = {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(message.content))
                                tooltipState.dismiss()
                            },
                        )
                        MessageTooltipAction(
                            icon = Icons.Rounded.Edit,
                            label = stringResource(R.string.ui_edit_a7f814),
                            onClick = {
                                tooltipState.dismiss()
                                onEdit()
                            },
                        )
                        MessageTooltipAction(
                            icon = Icons.Rounded.Delete,
                            label = stringResource(R.string.ui_delete_3755f5),
                            onClick = {
                                tooltipState.dismiss()
                                onDelete()
                            },
                        )
                    }
                }
            },
            state = tooltipState,
            focusable = true,
            enableUserInput = actionsEnabled,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (assistantOverlay) 0.88f else 0.84f)
                    .widthIn(max = 360.dp)
                    .then(
                        if (assistantOverlay) {
                            Modifier.background(overlayBubbleColor, RoundedCornerShape(10.dp))
                        } else {
                            Modifier.squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomEnd = 6.dp,
                                bottomStart = 20.dp,
                            )
                        }
                    )
                    .then(
                        if (isEditing) {
                            Modifier.squircleBorder(
                                width = 1.dp,
                                color = MiuixTheme.colorScheme.primary,
                                cornerRadius = 20.dp,
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(
                        horizontal = if (assistantOverlay) 12.dp else 16.dp,
                        vertical = if (assistantOverlay) 8.dp else 11.dp,
                    ),
            ) {
                if (message.images.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        message.images.forEach { dataUrl ->
                            val bitmap = rememberDataUrlBitmap(dataUrl)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
                if (visiblePrompt.references.isNotEmpty()) {
                    SentFileReferenceFlow(
                        references = visiblePrompt.references,
                        modifier = Modifier.padding(
                            bottom = if (visiblePrompt.request.isNotBlank()) 8.dp else 0.dp
                        ),
                    )
                }
                if (visiblePrompt.request.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = visiblePrompt.request,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (message.isEdited) {
                    Text(
                        text = stringResource(R.string.ui_edited_c36776),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageTooltipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// ── 上下文压缩：时间线中的轻量胶囊标记 ─────────────────────────────────

/**
 * Question card: a read-only record of the question and its answer.
 *
 * Answering lives in the pending-question bar above the input (PendingQuestionPrompt); the card itself is not
 * interactive,
 * so a restored conversation replays question and answer faithfully without any interaction state.
 */
@Composable
private fun UserQuestionCard(
    message: UserQuestionMessageUi,
    modifier: Modifier = Modifier,
) {
    val pulseAlpha = rememberActivePulse(active = message.running, label = "question_pulse")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ChatBubble,
                contentDescription = null,
                modifier = Modifier
                    .size(13.dp)
                    .graphicsLayer(alpha = if (message.running) pulseAlpha else 1f),
                tint = if (message.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.user_question_title),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message.question,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (message.options.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.options.joinToString(separator = " · "),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message.answer?.takeIf(String::isNotBlank)
                ?: stringResource(
                    when {
                        message.timedOut -> R.string.user_question_timed_out
                        message.cancelled -> R.string.user_question_cancelled
                        else -> R.string.user_question_waiting
                    },
                ),
            style = MiuixTheme.textStyles.footnote1,
            color = if (message.answer.isNullOrBlank()) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.primary
            },
        )
    }
}

/**
 * 压缩不是一轮对话结果，而是上下文维护事件；用居中胶囊标记与助手正文区分，
 * 进行中通过图标脉冲反馈，结束后保留压缩前后的 token 信息。
 */
@Composable
private fun ContextCompactionMarker(
    message: SystemNoticeMessageUi,
    modifier: Modifier = Modifier,
) {
    val pulseAlpha = rememberActivePulse(active = message.running, label = "compaction_pulse")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MiuixTheme.colorScheme.surface)
                .border(
                    0.5.dp,
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Compress,
                contentDescription = null,
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer(alpha = if (message.running) pulseAlpha else 1f),
                tint = if (message.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message.detail?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.context_compaction),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Agent 结果 ───────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit = {},
    onSelectCandidate: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    var streamingRevealComplete by remember(message.id) {
        mutableStateOf(!keepStreamingMarkdown)
    }
    // 渲染会话由列表层按 message.id 持有，item 滚出视口被销毁后滑回时复用同一
    // 会话；没有外部持有者时（如嵌套条目）退回组合内 remember，行为与之前一致。
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedMarkdownState = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedStateFor(message.content)
    val revealComplete = streamingRevealComplete && !message.isStreaming &&
        (streamingState == null || completedMarkdownState != null)
    LaunchedEffect(retainedStreamingState, revealComplete, message.content) {
        retainedStreamingState?.revealedContent = message.content.takeIf { revealComplete }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (assistantOverlay) 16.dp else 20.dp,
                vertical = if (assistantOverlay) 8.dp else 7.dp,
            ),
    ) {
        when {
            message.content.isBlank() && message.isStreaming -> {
                AITypingIndicator(
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            streamingState != null && !revealComplete -> {
                StreamingMarkdown(
                    state = streamingState,
                    content = message.content,
                    isStreaming = message.isStreaming,
                    onRevealCompleteChange = { streamingRevealComplete = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            message.renderMarkdown -> {
                // 历史中间消息不启用文本选择：SelectionContainer 会为每条消息维护
                // 一套选择管理器，长列表滚动时开销明显；最终回复仍保留复制/选择能力。
                if (showCopyAction) {
                    SelectionContainer {
                        StableMarkdown(
                            content = message.content,
                            parsedState = completedMarkdownState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    StableMarkdown(
                        content = message.content,
                        parsedState = completedMarkdownState,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            message.content.isNotBlank() -> {
                if (showCopyAction) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Text(
                        text = message.content,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (
            showCopyAction &&
            !message.isStreaming &&
            message.content.isNotBlank() &&
            revealComplete
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        copied = true
                    },
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check
                            else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(
                            if (copied) R.string.copy_copied else R.string.copy_answer,
                        ),
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                    )
                }
                if (showMessageActions) {
                    if (message.characterEditable) {
                        IconButton(onClick = onEdit, enabled = messageActionsEnabled, minWidth = 30.dp, minHeight = 30.dp) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.roleplay_edit_reply_action),
                                modifier = Modifier.size(15.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                            )
                        }
                    }
                    TooltipBox(text = stringResource(R.string.ui_regenerate_2e1905), enabled = messageActionsEnabled) {
                        IconButton(
                            onClick = onRegenerate,
                            enabled = messageActionsEnabled,
                            minWidth = 30.dp,
                            minHeight = 30.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                                modifier = Modifier.size(15.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                            )
                        }
                    }
                    TooltipBox(text = stringResource(R.string.ui_delete_3755f5), enabled = messageActionsEnabled) {
                        IconButton(
                            onClick = onDelete,
                            enabled = messageActionsEnabled,
                            minWidth = 30.dp,
                            minHeight = 30.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.ui_delete_this_conversation_3f351b),
                                modifier = Modifier.size(15.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                            )
                        }
                    }
                    if (message.characterEditable && message.candidateCount > 1) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        ) {
                            IconButton(
                                onClick = { onSelectCandidate(message.selectedCandidate - 1) },
                                enabled = messageActionsEnabled && message.selectedCandidate > 0,
                                minWidth = 28.dp, minHeight = 28.dp,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronLeft,
                                    contentDescription = stringResource(R.string.roleplay_previous_candidate_action),
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                text = "${message.selectedCandidate + 1}/${message.candidateCount}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 30.dp),
                            )
                            IconButton(
                                onClick = { onSelectCandidate(message.selectedCandidate + 1) },
                                enabled = messageActionsEnabled && message.selectedCandidate < message.candidateCount - 1,
                                minWidth = 28.dp, minHeight = 28.dp,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = stringResource(R.string.roleplay_next_candidate_action),
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
private fun StableMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
    markdownState: MarkdownState? = null,
    parsedState: State.Success? = null,
) {
    // 流式终态已有完整 AST，直接复用，避免新解析器的 Loading 原文先撑高页面再缩回。
    val state = parsedState ?: (markdownState ?: rememberMarkdownState(
        content = content,
        retainState = true,
    )).state.collectAsState().value
    val components = remember { chatMarkdownComponents() }
    Markdown(
        state = state,
        colors = chatMarkdownColors(tone),
        typography = chatMarkdownTypography(tone),
        padding = chatMarkdownPadding(),
        dimens = chatMarkdownDimens(),
        components = components,
        modifier = modifier,
        loading = {
            // 解析中只测量有界预览：直接排版整段长正文会在展开首帧造成严重卡顿。
            MarkdownPendingText(content = content, tone = tone, modifier = it)
        },
        error = {
            MarkdownPendingText(content = content, tone = tone, modifier = it)
        },
        success = { state, successComponents, successModifier ->
            ChatMarkdownDocument(
                root = state.node,
                content = state.content,
                components = successComponents,
                modifier = successModifier,
            )
        },
    )
}

@Composable
private fun MarkdownPendingText(
    content: String,
    tone: ChatMarkdownTone,
    modifier: Modifier,
) {
    val preview = remember(content) {
        boundedTextPreview(content, MAX_MARKDOWN_PENDING_CHARS, MAX_MARKDOWN_PENDING_LINES)
    }
    Column(modifier) {
        Text(
            text = preview.text,
            style = chatMarkdownBodyStyle(tone),
            color = chatMarkdownTextColor(tone),
            maxLines = MAX_MARKDOWN_PENDING_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (preview.truncated) {
            Text(
                text = stringResource(R.string.linux_files_truncated_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = chatMarkdownTextColor(tone).copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    onRevealCompleteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
) {
    val revealCoordinator = state.revealCoordinator
    // 思考紧跟已收到的增量，避免高速思考先排版占位、再受正文逐字速度限制而积压。
    val animateReveal = tone == ChatMarkdownTone.Answer
    val components = remember(revealCoordinator, isStreaming, animateReveal) {
        chatMarkdownComponents(
            revealCoordinator = revealCoordinator.takeIf { animateReveal },
            suppressEmptyListMarkers = isStreaming && animateReveal,
        )
    }
    val parseTargets = state.parseTargets
    val currentRevealCompleteCallback by rememberUpdatedState(onRevealCompleteChange)
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    val currentIsStreaming by rememberUpdatedState(isStreaming)
    val restoreGeneration = state.restoreState.generation

    LifecycleResumeEffect(state) {
        revealCoordinator.pauseAnimationsAndCatchUp()
        state.restoreState.begin(currentContent)
        onPauseOrDispose {
            state.restoreState.pause()
            revealCoordinator.pauseAnimationsAndCatchUp()
        }
    }

    LaunchedEffect(revealCoordinator, animateReveal) {
        if (animateReveal) revealCoordinator.runFrameClock()
    }

    LaunchedEffect(content, isStreaming) {
        parseTargets.trySend(
            StreamingMarkdownTarget(
                content = content,
                isStreaming = isStreaming,
            )
        )
        if (isStreaming) currentRevealCompleteCallback(false)
    }

    LaunchedEffect(state) {
        state.parseUpdates()
    }

    LaunchedEffect(content, isStreaming, snapshot?.originalSource, snapshot?.isComplete, revealCoordinator) {
        val currentSnapshot = snapshot
        if (!isStreamingMarkdownTargetComplete(
                content = content,
                isStreaming = isStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(false)
            return@LaunchedEffect
        }

        // 等这一版 AST 完成组合与排版后，再等待尾部字符的透明度动画收口。
        withFrameNanos { }
        if (!revealCoordinator.drained.value) {
            revealCoordinator.drained.filter { it }.first()
        }
        if (isStreamingMarkdownTargetComplete(
                content = currentContent,
                isStreaming = currentIsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(true)
        }
    }

    snapshot?.let { parsed ->
        Markdown(
            state = parsed.state,
            colors = chatMarkdownColors(tone),
            typography = chatMarkdownTypography(tone),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = components,
            animations = markdownAnimations(animateTextSize = { this }),
            modifier = modifier.onGloballyPositioned {
                // 恢复基线对应的 AST 真正排版后才开放增量动画，解析耗时不受帧数限制。
                if (state.restoreState.completeLayout(
                        generation = restoreGeneration,
                        renderedContent = parsed.originalSource,
                        currentContent = currentContent,
                    )
                ) {
                    revealCoordinator.resumeAnimationsAfterCatchUp()
                }
            },
            success = { state, successComponents, successModifier ->
                StreamingGfmSuccess(
                    state = state,
                    components = successComponents,
                    revealCoordinator = revealCoordinator,
                    modifier = successModifier,
                )
            },
        )
    }
}

/**
 * 顶层节点以源码位置和语法类型作为稳定身份。完整重解析只替换真正发生类型变化的
 * 当前块，前面已经稳定的段落、表格和代码块不会因新 chunk 到达而重新挂载。
 */
@Composable
private fun StreamingGfmSuccess(
    state: State.Success,
    components: MarkdownComponents,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val activeRevealBlocks = remember(state.node) {
        state.revealBlockKeys()
    }
    SideEffect {
        revealCoordinator.retainBlocks(activeRevealBlocks)
    }

    ChatMarkdownDocument(
        root = state.node,
        content = state.content,
        components = components,
        modifier = modifier,
    )
}

/**
 * 空行只负责切分 Markdown 块，不直接占据布局高度；可见块之间按语义分配留白，
 * 避免统一 block padding 让标题、正文、列表和表格失去层级。
 */
@Composable
private fun ChatMarkdownDocument(
    root: ASTNode,
    content: String,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(root) { topLevelMarkdownBlocks(root) }
    val density = LocalDensity.current
    Column(modifier) {
        blocks.forEachIndexed { index, node ->
            val previousType = blocks.getOrNull(index - 1)?.type
            val gap = with(density) {
                markdownBlockSpacing(previousType, node.type).toDp()
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            key(node.startOffset, node.type.name) {
                MarkdownElement(
                    node = node,
                    components = components,
                    content = content,
                    includeSpacer = false,
                )
            }
        }
    }
}

internal fun topLevelMarkdownBlocks(root: ASTNode): List<ASTNode> =
    root.children.filterNot { node -> node.type == MarkdownTokenTypes.EOL }

internal fun markdownBlockSpacing(previous: IElementType?, current: IElementType): TextUnit {
    if (previous == null) return 0.sp
    if (previous.isMarkdownHeading() && current.isMarkdownHeading()) return 12.sp
    if (current.isMarkdownHeading()) {
        return if (current == MarkdownElementTypes.ATX_1 ||
            current == MarkdownElementTypes.SETEXT_1 ||
            current == MarkdownElementTypes.ATX_2 ||
            current == MarkdownElementTypes.SETEXT_2
        ) {
            24.sp
        } else {
            20.sp
        }
    }
    if (previous.isMarkdownHeading()) return 10.sp
    if (previous.isMarkdownParagraph() && current.isMarkdownParagraph()) return 16.sp
    if (previous.isMarkdownStructuredBlock() || current.isMarkdownStructuredBlock()) return 16.sp
    return 14.sp
}

private fun IElementType.isMarkdownHeading(): Boolean = when (this) {
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> true

    else -> false
}

private fun IElementType.isMarkdownParagraph(): Boolean =
    this == MarkdownElementTypes.PARAGRAPH || this == MarkdownTokenTypes.TEXT

private fun IElementType.isMarkdownStructuredBlock(): Boolean = when (this) {
    MarkdownElementTypes.ORDERED_LIST,
    MarkdownElementTypes.UNORDERED_LIST,
    MarkdownElementTypes.BLOCK_QUOTE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    TABLE,
    -> true

    else -> false
}

internal fun streamingMarkdownBatchSize(backlogChars: Int): Int = when {
    backlogChars >= 384 -> 96
    backlogChars >= 160 -> 64
    backlogChars >= 64 -> 40
    else -> 24
}

internal fun streamingMarkdownBatchEnd(
    content: String,
    start: Int,
    maxGraphemes: Int,
): Int {
    return AppendOnlyGraphemeIndex().apply { update(content) }.endAfter(start, maxGraphemes)
}

// ── Markdown 样式：克制的聊天排版，标题只作强调不作页面标题 ─────────────

private enum class ChatMarkdownTone {
    Answer,
    Thinking,
}

@Composable
private fun chatMarkdownTypography(tone: ChatMarkdownTone) = markdownTypography(
    h1 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 21.sp else 16.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 29.sp else 22.sp,
        fontWeight = FontWeight.Bold,
    ),
    h2 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 19.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 27.sp else 21.sp,
        fontWeight = FontWeight.Bold,
    ),
    h3 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 18.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h4 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 17.sp else 13.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 25.sp else 19.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h5 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 13.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 19.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h6 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 13.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 23.sp else 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    text = chatMarkdownBodyStyle(tone),
    paragraph = chatMarkdownBodyStyle(tone),
    ordered = chatMarkdownBodyStyle(tone),
    bullet = chatMarkdownBodyStyle(tone),
    list = chatMarkdownBodyStyle(tone),
    quote = MiuixTheme.textStyles.body2.copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 13.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 18.sp,
        color = chatMarkdownTextColor(ChatMarkdownTone.Thinking),
    ),
    code = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace,
        color = chatMarkdownTextColor(tone),
    ),
    inlineCode = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 14.sp else 12.sp,
        fontFamily = FontFamily.Monospace,
    ),
    table = MiuixTheme.textStyles.body2.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = chatMarkdownTextColor(tone),
    ),
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        ),
    ),
)

@Composable
private fun chatMarkdownBodyStyle(tone: ChatMarkdownTone) =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.textStyles.body1.copy(
            fontSize = 16.sp,
            lineHeight = 26.sp,
            color = chatMarkdownTextColor(tone),
        )
    } else {
        MiuixTheme.textStyles.body2.copy(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = chatMarkdownTextColor(tone),
        )
    }

@Composable
private fun chatMarkdownTextColor(tone: ChatMarkdownTone): Color =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

@Composable
private fun chatMarkdownColors(tone: ChatMarkdownTone) = markdownColor(
    text = chatMarkdownTextColor(tone),
    // 代码块与表格的底色、描边由自定义组件绘制，这里只保留行内代码底色与分隔线。
    codeBackground = MiuixTheme.colorScheme.surface,
    inlineCodeBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    dividerColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    tableBackground = Color.Transparent,
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 3.dp,
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    // 顶层块由 ChatMarkdownDocument 按语义分配留白，库的统一前置间距保持关闭。
    block = 0.dp,
    list = 3.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 14.dp,
    codeBlock = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 3.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 2.dp, top = 3.dp, right = 0.dp, bottom = 3.dp),
)

private fun chatMarkdownComponents(
    revealCoordinator: SmoothTextRevealCoordinator? = null,
    suppressEmptyListMarkers: Boolean = false,
) = markdownComponents(
    text = { model ->
        if (revealCoordinator == null) {
            MarkdownText(
                content = model.node.getUnescapedTextInNode(model.content),
                node = model.node,
                style = model.typography.text,
            )
        } else {
            ChatRevealRawText(model, revealCoordinator)
        }
    },
    paragraph = { model ->
        if (revealCoordinator == null || model.node.containsMarkdownImage()) {
            MarkdownParagraph(
                content = model.content,
                node = model.node,
                style = model.typography.paragraph,
            )
        } else {
            ChatRevealMarkdownText(
                model = model,
                style = model.typography.paragraph,
                revealCoordinator = revealCoordinator,
            )
        }
    },
    orderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = true,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    unorderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = false,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    heading1 = { ChatHeadingBlock(it, it.typography.h1, revealCoordinator = revealCoordinator) },
    heading2 = { ChatHeadingBlock(it, it.typography.h2, revealCoordinator = revealCoordinator) },
    heading3 = { ChatHeadingBlock(it, it.typography.h3, revealCoordinator = revealCoordinator) },
    heading4 = { ChatHeadingBlock(it, it.typography.h4, revealCoordinator = revealCoordinator) },
    heading5 = { ChatHeadingBlock(it, it.typography.h5, revealCoordinator = revealCoordinator) },
    heading6 = { ChatHeadingBlock(it, it.typography.h6, revealCoordinator = revealCoordinator) },
    setextHeading1 = {
        ChatHeadingBlock(
            it,
            it.typography.h1,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    setextHeading2 = {
        ChatHeadingBlock(
            it,
            it.typography.h2,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    codeFence = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
            )
        }
    },
    codeBlock = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
            )
        }
    },
    table = { model ->
        ChatMarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            revealCoordinator = revealCoordinator,
        )
    },
    blockQuote = { model ->
        ChatBlockQuote(model)
    },
)

/**
 * 流式列表不能直接使用库的默认实现：默认实现会立即绘制 marker，而正文还在显现动画中。
 * 这里把每一项作为稳定的组合单元，并让 marker 与该项首个正文块共享开始时机。
 */
@Composable
private fun ChatMarkdownList(
    model: MarkdownComponentModel,
    ordered: Boolean,
    revealCoordinator: SmoothTextRevealCoordinator?,
    suppressEmptyMarker: Boolean,
    depth: Int = model.listDepth,
) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val items = remember(model.node) {
        model.node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    if (items.isEmpty()) return

    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val initialListNumber = items.first()
        .getUnescapedTextInNode(model.content)
        .takeWhile(Char::isDigit)
        .toIntOrNull()
        ?: 1

    Column(
        modifier = Modifier.padding(
            start = padding.listIndent * depth,
            top = padding.list,
            bottom = padding.list,
        ),
    ) {
        items.forEachIndexed { index, item ->
            key(item.startOffset, item.type.name) {
                val firstRevealKey = remember(item) { item.firstRevealBlockKey() }
                val checkboxNode = remember(item) {
                    item.children.firstOrNull { child -> child.type == CHECK_BOX }
                }
                val markerVisible = streamingListMarkerVisible(
                    coordinatorActive = suppressEmptyMarker,
                    firstRevealKey = firstRevealKey,
                    startedRevealKeys = startedRevealKeys,
                    containsImage = item.containsMarkdownImage(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { isTraversalGroup = true }
                        .padding(
                            top = padding.listItemTop,
                            bottom = padding.listItemBottom,
                        ),
                ) {
                    Box(
                        modifier = Modifier.graphicsLayer(
                            // 隐藏 marker 但保留它的测量宽度，避免正文横向跳动。
                            alpha = if (markerVisible) 1f else 0f,
                        ),
                    ) {
                        if (checkboxNode != null) {
                            components.checkbox(
                                MarkdownComponentModel(
                                    content = model.content,
                                    node = checkboxNode,
                                    typography = model.typography,
                                ),
                            )
                        } else if (ordered) {
                            Text(
                                text = "${initialListNumber + index}.",
                                style = model.typography.ordered.copy(
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        } else {
                            // Compose 单行 Text 在默认 Trim.Both 下忽略 lineHeight，行框即字体自然行高；
                            // marker 必须与正文同 fontSize/lineHeight 才能共享度规对齐，
                            // 层级差异只通过字形与颜色表达。
                            val bulletDepth = depth % 3
                            Text(
                                text = when (bulletDepth) {
                                    0 -> "•"
                                    1 -> "◦"
                                    else -> "▪"
                                },
                                style = model.typography.bullet.copy(
                                    color = if (bulletDepth == 2) {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    } else {
                                        MiuixTheme.colorScheme.primary
                                    },
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        item.children.forEach { child ->
                            when (child.type) {
                                MarkdownElementTypes.ORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = true,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                MarkdownElementTypes.UNORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = false,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                else -> MarkdownElement(
                                    node = child,
                                    components = components,
                                    content = model.content,
                                    includeSpacer = false,
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
private fun rememberStartedRevealKeys(
    coordinator: SmoothTextRevealCoordinator?,
): Set<RevealBlockKey> = if (coordinator == null) {
    emptySet()
} else {
    coordinator.started.collectAsState().value
}

internal fun streamingListMarkerVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    containsImage: Boolean,
): Boolean = !coordinatorActive ||
    firstRevealKey?.let(startedRevealKeys::contains) == true ||
    (firstRevealKey == null && containsImage)

@Composable
private fun ChatRevealRawText(
    model: MarkdownComponentModel,
    revealCoordinator: SmoothTextRevealCoordinator,
) {
    val text = remember(model.content, model.node) {
        AnnotatedString(model.node.getUnescapedTextInNode(model.content))
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = model.typography.text,
        revealCoordinator = revealCoordinator,
    )
}

@Composable
private fun ChatRevealMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
) {
    val annotatorSettings = annotatorSettings()
    val contentNode = remember(model.node, contentChildType) {
        contentChildType?.let(model.node::findChildOfType) ?: model.node
    }
    val text = remember(model.content, contentNode, style, annotatorSettings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = model.content,
                node = contentNode,
                annotatorSettings = annotatorSettings,
            )
            pop()
        }
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = style,
        revealCoordinator = revealCoordinator,
        modifier = modifier,
    )
}

@Composable
private fun ChatRevealAnnotatedText(
    text: AnnotatedString,
    node: ASTNode,
    sourceContent: String,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(node.startOffset),
        coordinator = revealCoordinator,
    )
    MarkdownText(
        content = text,
        node = node,
        modifier = modifier.smoothTextReveal(revealState),
        style = style.copy(textMotion = TextMotion.Animated),
        onTextLayout = { layoutResult, _ ->
            revealState.onTextLayout(text.text, layoutResult)
        },
        sourceContent = sourceContent,
    )
}

/**
 * 标题自身只负责文字样式；与相邻块的距离由文档级排版统一决定。
 */
@Composable
private fun ChatHeadingBlock(
    model: MarkdownComponentModel,
    style: TextStyle,
    setext: Boolean = false,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val contentChildType = if (setext) {
        MarkdownTokenTypes.SETEXT_CONTENT
    } else {
        MarkdownTokenTypes.ATX_CONTENT
    }
    if (revealCoordinator == null || model.node.containsMarkdownImage()) {
        MarkdownHeader(
            content = model.content,
            node = model.node,
            style = style,
            contentChildType = contentChildType,
        )
    } else {
        ChatRevealMarkdownText(
            model = model,
            style = style,
            revealCoordinator = revealCoordinator,
            contentChildType = contentChildType,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * 代码块：顶栏显示语言标签并提供一键复制，正文等宽字体、超出横向滚动。
 */
@Composable
private fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    revealState: SmoothTextRevealState? = null,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "code",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_code,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        val codeModifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 13.dp, vertical = 11.dp)
            .let { base ->
                if (revealState != null) base.smoothTextReveal(revealState) else base
            }
        Text(
            text = code,
            style = if (revealState != null) {
                style.copy(textMotion = TextMotion.Animated)
            } else {
                style
            },
            color = MiuixTheme.colorScheme.onSurface,
            modifier = codeModifier,
            onTextLayout = revealState?.let { state ->
                { layoutResult -> state.onTextLayout(code, layoutResult) }
            },
        )
    }
}

private val ChatTableCellWidth = 112.dp

/**
 * 表格：细描边容器 + 表头浅底加粗 + 行间发丝分隔线；列宽不足时整体横向滚动。
 */
@Composable
private fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val headerCells = remember(node) {
        node.findChildOfType(HEADER)?.children?.filter { it.type == CELL }.orEmpty()
    }
    val bodyRows = remember(node) {
        node.children.filter { it.type == ROW }
            .map { row -> row.children.filter { it.type == CELL } }
    }
    if (headerCells.isEmpty()) return

    val borderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        val tableWidth = ChatTableCellWidth * headerCells.size
        val scrollable = maxWidth <= tableWidth
        Column(
            modifier = (if (scrollable) {
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .requiredWidth(tableWidth)
            } else {
                Modifier.fillMaxWidth()
            })
                .clip(RoundedCornerShape(10.dp))
                .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
                    .height(IntrinsicSize.Max),
            ) {
                headerCells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        ChatMarkdownTableCell(
                            content = content,
                            cell = cell,
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            revealCoordinator = revealCoordinator,
                        )
                    }
                }
            }
            bodyRows.forEach { rowCells ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(borderColor.copy(alpha = 0.6f)),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            ChatMarkdownTableCell(
                                content = content,
                                cell = cell,
                                style = style,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                revealCoordinator = revealCoordinator,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMarkdownTableCell(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    revealCoordinator: SmoothTextRevealCoordinator?,
) {
    if (revealCoordinator == null || cell.containsMarkdownImage()) {
        MarkdownTableBasicText(
            content = content,
            cell = cell,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    val annotatorSettings = annotatorSettings()
    val text = remember(content, cell, style, annotatorSettings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = content,
                node = cell,
                annotatorSettings = annotatorSettings,
            )
            pop()
        }
    }
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(cell.startOffset),
        coordinator = revealCoordinator,
    )
    Text(
        text = text,
        style = style.copy(textMotion = TextMotion.Animated),
        color = MiuixTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = overflow,
        modifier = Modifier.smoothTextReveal(revealState),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * 引用块：圆角浅色竖条 + 弱化文字。
 * 库默认实现把竖条颜色绑死在 quote 文字颜色上，无法分别控制，因此竖条自绘；
 * 子节点仍交给 ambient components，流式显现与嵌套引用行为不变。
 */
@Composable
private fun ChatBlockQuote(model: MarkdownComponentModel) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val dimens = LocalMarkdownDimens.current
    val a11yLabels = LocalMarkdownA11yLabels.current
    val barColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
    val emptyLineHeight = with(LocalDensity.current) {
        model.typography.quote.lineHeight.takeOrElse { 22.sp }.toDp()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yLabels.blockquote }
            .drawBehind {
                val thickness = dimens.blockQuoteThickness.toPx()
                val x = padding.blockQuoteBar
                    .calculateStartPadding(LayoutDirection.Ltr).toPx() + thickness / 2
                drawLine(
                    color = barColor,
                    strokeWidth = thickness,
                    start = Offset(x, padding.blockQuoteBar.calculateTopPadding().toPx()),
                    end = Offset(
                        x,
                        size.height - padding.blockQuoteBar.calculateBottomPadding().toPx(),
                    ),
                    cap = StrokeCap.Round,
                )
            }
            .padding(padding.blockQuote),
    ) {
        model.node.children.forEach { child ->
            key(child.startOffset) {
                when (child.type) {
                    MarkdownElementTypes.BLOCK_QUOTE -> ChatBlockQuote(
                        MarkdownComponentModel(
                            content = model.content,
                            node = child,
                            typography = model.typography,
                        ),
                    )

                    MarkdownTokenTypes.EOL -> Spacer(Modifier.height(emptyLineHeight))

                    else -> MarkdownElement(
                        node = child,
                        components = components,
                        content = model.content,
                        includeSpacer = false,
                    )
                }
            }
        }
    }
}

private fun ASTNode.containsMarkdownImage(): Boolean =
    type == MarkdownElementTypes.IMAGE || children.any { child -> child.containsMarkdownImage() }

/** 找到列表项中首个会被显现协调器管理的块，marker 以它作为显示时机。 */
private fun ASTNode.firstRevealBlockKey(): RevealBlockKey? = when (type) {
    MarkdownTokenTypes.TEXT -> RevealBlockKey(startOffset)

    MarkdownElementTypes.PARAGRAPH,
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> if (!containsMarkdownImage()) RevealBlockKey(startOffset) else null

    MarkdownElementTypes.CODE_FENCE ->
        if (children.size >= 3) RevealBlockKey(startOffset) else null

    MarkdownElementTypes.CODE_BLOCK ->
        if (children.isNotEmpty()) RevealBlockKey(startOffset) else null

    TABLE -> children.asSequence()
        .flatMap { it.depthFirstSequence() }
        .firstOrNull { it.type == CELL && !it.containsMarkdownImage() }
        ?.let { RevealBlockKey(it.startOffset) }

    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    -> null

    else -> children.asSequence().mapNotNull(ASTNode::firstRevealBlockKey).firstOrNull()
}

private fun ASTNode.depthFirstSequence(): Sequence<ASTNode> = sequence {
    yield(this@depthFirstSequence)
    children.forEach { child -> yieldAll(child.depthFirstSequence()) }
}

private fun State.Success.revealBlockKeys(): Set<RevealBlockKey> = buildSet {
    node.children.forEach { child -> collectRevealBlockKeys(child) }
}

private fun MutableSet<RevealBlockKey>.collectRevealBlockKeys(node: ASTNode) {
    when (node.type) {
        MarkdownTokenTypes.TEXT -> add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        -> if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.CODE_FENCE -> {
            if (node.children.size >= 3) add(RevealBlockKey(node.startOffset))
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            if (node.children.isNotEmpty()) add(RevealBlockKey(node.startOffset))
        }

        TABLE -> collectTableCellRevealKeys(node)

        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        -> Unit

        else -> node.children.forEach { child -> collectRevealBlockKeys(child) }
    }
}

private fun MutableSet<RevealBlockKey>.collectTableCellRevealKeys(node: ASTNode) {
    if (node.type == CELL) {
        if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))
        return
    }
    node.children.forEach { child -> collectTableCellRevealKeys(child) }
}

// ── 思考过程 ─────────────────────────────────────────────────────────

@Composable
private fun ThinkingRow(
    message: ThinkingMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    collapseResetKey: Long = 0L,
    onThinkingToggle: () -> Unit = {},
    thinkingViewportHeight: androidx.compose.ui.unit.Dp? = null,
) {
    var expansionOverride by rememberSaveable(message.id, message.isStreaming) {
        mutableStateOf<Boolean?>(null)
    }
    val expanded = expansionOverride ?: message.isStreaming
    var startedStreaming by remember(message.id) { mutableStateOf(message.isStreaming) }
    if (message.isStreaming) startedStreaming = true
    // 思考过程保持同一种文本布局，流式增长时不会跨过阈值突然切换渲染器。
    // 分段测量避免一条巨大的 Text 在每次增量时重新排版全文。
    val thinkingChunkAccumulator = remember(message.id) { ThinkingTextChunkAccumulator() }
    val thinkingChunks = remember(message.id, message.content) {
        thinkingChunkAccumulator.update(message.content)
    }

    // The enclosing work-process panel owns expansion and scrolling.
    if (compact) {
        Column(modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp)) {
            thinkingChunks.forEachIndexed { index, chunk ->
                key(index) {
                    Text(
                        text = chunk.ifEmpty { " " },
                        style = chatMarkdownBodyStyle(ChatMarkdownTone.Thinking),
                        color = chatMarkdownTextColor(ChatMarkdownTone.Thinking),
                    )
                }
            }
        }
        return
    }

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming,
        label = "thinking_pulse",
    )

    val thinkingScrollState = rememberScrollState()
    val density = LocalDensity.current.density
    var followThinking by rememberSaveable(message.id) { mutableStateOf(true) }
    var collapsedHeightPx by rememberSaveable(message.id) { mutableStateOf(0) }
    var appliedCollapseResetKey by rememberSaveable(message.id) { mutableStateOf(0L) }
    LaunchedEffect(collapseResetKey) {
        if (resetThinkingCollapseSlots(appliedCollapseResetKey, collapseResetKey)) {
            collapsedHeightPx = 0
            appliedCollapseResetKey = collapseResetKey
        }
    }
    val thinkingDragging by thinkingScrollState.interactionSource.collectIsDraggedAsState()
    val thinkingAtBottom by remember(thinkingScrollState) {
        derivedStateOf { thinkingScrollState.value >= thinkingScrollState.maxValue - 4 }
    }
    val currentFollowThinking by rememberUpdatedState(followThinking)
    val currentThinkingDragging by rememberUpdatedState(thinkingDragging)

    // 用户上滑离开底部 -> 脱离；重新滑到底部并松手 -> 恢复；重新展开 -> 恢复。
    LaunchedEffect(thinkingDragging, thinkingAtBottom) {
        when {
            thinkingDragging && !thinkingAtBottom -> {
                followThinking = false
            }
            thinkingAtBottom && !thinkingDragging -> followThinking = true
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) followThinking = true
    }
    // 独立思考块同样用连续帧时钟追底；历史内容布局稳定后退出。
    LaunchedEffect(compact, followThinking, message.isStreaming) {
        if (compact || !followThinking) return@LaunchedEffect
        var previousFrameNanos = 0L
        var stableFrames = 0
        while (currentFollowThinking) {
            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos
            if (currentThinkingDragging) continue
            val distance = (thinkingScrollState.maxValue - thinkingScrollState.value).toFloat()
            if (thinkingScrollState.viewportSize > 0 &&
                distance > thinkingScrollState.viewportSize * 2f
            ) {
                thinkingScrollState.scroll {
                    scrollBy(distance - thinkingScrollState.viewportSize)
                }
                previousFrameNanos = 0L
                continue
            }
            if (distance <= 0f) {
                if (!message.isStreaming && ++stableFrames > 60) break
                continue
            }
            stableFrames = 0
            val step = smoothBottomFollowStep(distance, elapsedSeconds, density)
            if (step > 0f) thinkingScrollState.scroll { scrollBy(step) }
        }
    }

    // compact 模式渲染在工作过程卡片内部，不再携带自己的卡片外壳，避免卡中卡。
    val containerModifier = if (compact) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .testTag("thinking-card-${message.id}")
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f),
                shape = RoundedCornerShape(14.dp),
            )
    }

    val contentVisible = expanded && message.content.isNotBlank()
    val expansionProgress = rememberThinkingExpansionProgress(contentVisible)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (compact) Modifier else Modifier.padding(horizontal = 20.dp, vertical = 4.dp)),
    ) {
    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    onThinkingToggle()
                    expansionOverride = !expanded
                }
                .padding(horizontal = if (compact) 4.dp else 13.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lightbulb,
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer(alpha = if (message.isStreaming) pulseAlpha else 1f),
                tint = if (message.isStreaming) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (message.isStreaming) {
                    stringResource(R.string.reasoning_in_progress)
                } else {
                    message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
                        pluralStringResource(
                            R.plurals.reasoning_completed_seconds,
                            seconds,
                            seconds,
                        )
                    } ?: stringResource(R.string.reasoning_completed)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (message.isStreaming) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        ThinkingCollapseBody(
            expanded = contentVisible,
            progress = expansionProgress,
            measuredHeightPx = collapsedHeightPx,
            onHeightMeasured = { collapsedHeightPx = it },
        ) {
            if (!compact) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                )
            }
            val contentContainerModifier = if (compact) {
                // 工作过程内部已经是限高 LazyColumn，思考内容直接参与其懒组合，
                // 避免再嵌一层 verticalScroll 造成手势竞争与全量测量。
                Modifier.fillMaxWidth()
            } else {
                // 独立思考块限高并在内部跟底，不把长文本全部撑进外层列表。
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (startedStreaming && thinkingViewportHeight != null) {
                            Modifier.height(thinkingBodyHeightDp(thinkingViewportHeight.value).dp)
                        } else Modifier.heightIn(max = 280.dp)
                    )
                    .verticalScroll(thinkingScrollState)
            }
            Column(modifier = contentContainerModifier) {
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 27.dp else 13.dp,
                        end = 13.dp,
                        top = if (compact) 2.dp else 8.dp,
                        bottom = if (compact) 8.dp else 12.dp,
                    )
                Column(modifier = contentModifier) {
                    thinkingChunks.forEachIndexed { index, chunk ->
                        key(index) {
                            Text(
                                text = chunk.ifEmpty { " " },
                                style = chatMarkdownBodyStyle(ChatMarkdownTone.Thinking),
                                color = chatMarkdownTextColor(ChatMarkdownTone.Thinking),
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

// ── 工具调用：优雅极简时间线 ─────────────────────────────────────────

@Composable
private fun ToolActivityInline(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var isExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var previousStatus by remember(message.id) { mutableStateOf(message.status) }
    LaunchedEffect(message.status) {
        // 工具结束后回到紧凑时间线行；用户可以再次点击查看完整结果。
        if (previousStatus == ToolActivityStatusUi.Running &&
            message.status != ToolActivityStatusUi.Running
        ) {
            isExpanded = false
        }
        previousStatus = message.status
    }
    // 子代理行点击直接打开独立详情窗口；普通工具行展开内联详情。
    val isSubagentRow = message.toolName == SUBAGENT_TOOL_NAME
    var showSubagentDetail by rememberSaveable(message.id) { mutableStateOf(false) }
    // 只有「当前浏览器」卡片订阅实时会话快照，避免每个工具行都跟随快照重组
    val browserSnapshot = if (showBrowserShortcut) {
        AgentBrowserSession.snapshots.collectAsState().value
    } else {
        null
    }

    val pulseAlpha = rememberActivePulse(
        active = message.status == ToolActivityStatusUi.Running,
        label = "tool_pulse",
    )

    val toolLabel = toolDisplayName(message.toolName)
    val title = message.argumentsSummary
        .takeIf(String::isNotBlank)
        ?.let { summary ->
            if (summary.startsWith(toolLabel)) summary else "$toolLabel · $summary"
        }
        ?: toolLabel
    val browserSubtitle = browserSnapshot?.let { snapshot ->
        when {
            snapshot.isLoading ->
                stringResource(R.string.tool_browser_loading, snapshot.progress)
            snapshot.host.isNotBlank() && snapshot.title.isNotBlank() ->
                "${snapshot.host} · ${snapshot.title}"
            snapshot.host.isNotBlank() -> snapshot.host
            else -> null
        }
    }
    // 失败原因直接显示在折叠行，不必展开卡片；剥离去重「失败」前缀与日志用的 code= 尾巴
    val failureSubtitle = if (message.status == ToolActivityStatusUi.Failed) {
        message.resultSummary
            ?.lineSequence()?.firstOrNull()
            ?.removePrefix("失败 · ")
            ?.substringBefore(" · code=")
            ?.takeIf { it.isNotBlank() && it != "失败" }
    } else {
        null
    }
    // 子代理行折叠态展示最新一条步骤/终态文案，打开详情窗口查看完整过程。
    val subagentSubtitle = if (isSubagentRow) {
        message.resultSummary
            ?.lineSequence()
            ?.map(String::trim)
            ?.lastOrNull { it.isNotBlank() }
    } else {
        null
    }
    // 工具卡只负责布局，不在 UI 层截断工具正文；超长内容在展开区域内滚动查看。
    val detailPreview = message.detail?.takeIf(String::isNotBlank)
    val resultPreview = message.resultSummary?.takeIf(String::isNotBlank)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (isSubagentRow) {
                    showSubagentDetail = true
                } else {
                    isExpanded = !isExpanded
                }
            }
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = if (compact) 3.dp else 5.dp),
        ) {
            // 工作过程卡内不再画独立时间线 marker：卡片本身已有缩进，状态由工具图标的
            // 颜色/脉冲承担，行的信息密度更接近 VS Code 的嵌套工具行。
            if (!compact) {
                ToolTimelineMarker(
                    message = message,
                    pulseAlpha = pulseAlpha,
                )

                Spacer(modifier = Modifier.width(8.dp))
            }

            Icon(
                imageVector = iconForTool(message.toolName),
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(if (compact) 14.dp else 15.dp)
                    .graphicsLayer(
                        alpha = if (message.status == ToolActivityStatusUi.Running) pulseAlpha else 1f,
                    ),
                tint = if (isSubagentRow) {
                    MiuixTheme.colorScheme.secondary
                } else {
                    message.status.statusColor()
                },
            )

            Spacer(modifier = Modifier.width(if (compact) 6.dp else 7.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    color = if (message.status == ToolActivityStatusUi.Running) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 紧凑行不显示通用「状态 · 摘要」行，只保留失败原因与子代理最新步骤；
                // 状态本身由左侧图标颜色/脉冲表达。
                val subtitle = when {
                    isSubagentRow -> subagentSubtitle
                    failureSubtitle != null -> failureSubtitle
                    compact -> null
                    browserSubtitle != null -> browserSubtitle
                    else -> message.resultSummary?.lineSequence()?.firstOrNull()
                }
                if (subtitle != null || !compact) {
                    Text(
                        text = buildString {
                            if (!compact) append(message.status.statusLabel())
                            subtitle?.takeIf { it.isNotBlank() }?.let { line ->
                                if (isNotEmpty()) append(" · ")
                                append(line)
                            }
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (message.status == ToolActivityStatusUi.Failed) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.82f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (isSubagentRow) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.subagent_detail_title),
                        modifier = Modifier.size(13.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    )
                } else {
                    ExpandChevron(
                        expanded = isExpanded,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = panelExpandEnter(),
            exit = panelCollapseExit(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 20.dp else 34.dp,
                        end = 4.dp,
                        top = 2.dp,
                        bottom = 8.dp,
                    )
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (!message.command.isNullOrBlank()) {
                    ToolCommandBlock(
                        command = message.command,
                        context = message.argumentsSummary,
                        modifier = Modifier.padding(
                            bottom = if (message.resultSummary.isNullOrBlank() &&
                                message.detail.isNullOrBlank()
                            ) {
                                0.dp
                            } else {
                                10.dp
                            },
                        ),
                    )
                }
                val detail = detailPreview
                if (detail != null && !isSubagentRow) {
                    Text(
                        text = stringResource(R.string.tool_detail_label),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    ToolDetailText(
                        text = detail,
                        monospace = message.toolName !in setOf("web_search", "browser_use"),
                    )
                } else if (resultPreview != null) {
                    Text(
                        text = stringResource(R.string.ui_result_0a2c91),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    ToolDetailText(
                        text = resultPreview,
                        monospace = message.toolName !in setOf("web_search", "browser_use"),
                    )
                }
                if (showBrowserShortcut) {
                    browserSnapshot?.takeIf { it.available }?.let { snapshot ->
                        BrowserPagePreview(
                            snapshot = snapshot,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(R.string.ui_open_current_browser_58358e),
                            onClick = onOpenBrowser,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 36.dp,
                            textStyle = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }

    if (isSubagentRow && showSubagentDetail) {
        SubagentDetailDialog(
            message = message,
            onDismiss = { showSubagentDetail = false },
        )
    }
}

@Composable
private fun ToolTimelineMarker(
    message: ToolActivityMessageUi,
    pulseAlpha: Float,
) {
    Column(
        modifier = Modifier
            .width(18.dp)
            .heightIn(min = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer {
                    alpha = if (message.status == ToolActivityStatusUi.Running) pulseAlpha else 1f
                },
            contentAlignment = Alignment.Center,
        ) {
            when (message.status) {
                ToolActivityStatusUi.Success -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.tool_status_success),
                    modifier = Modifier.size(14.dp),
                    tint = StatusSuccess,
                )
                ToolActivityStatusUi.Failed -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = stringResource(R.string.tool_status_failed),
                    modifier = Modifier.size(15.dp),
                    tint = StatusError,
                )
                else -> Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(message.status.statusColor()),
                )
            }
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.34f)),
        )
    }
}

/** 展开态完整显示已返回的工具详情；高度受控但内容可滚动，不使用字符或行数截断。 */
@Composable
private fun ToolDetailText(
    text: String,
    monospace: Boolean,
) {
    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = text,
                style = if (monospace) {
                    MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MiuixTheme.textStyles.body2
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 浏览器工具的实时页面预览：迷你地址条 + 当前视口截图。
 *
 * 截图在页面加载中按快节拍刷新，页面稳定后再抓取一帧确认即停；
 * 组合销毁即停止，不做后台轮询。截图不可用时退化为图标占位。
 */
@Composable
private fun BrowserPagePreview(
    snapshot: BrowserSessionSnapshot,
    modifier: Modifier = Modifier,
) {
    var preview by remember(snapshot.url) { mutableStateOf<ImageBitmap?>(null) }
    // 预览轮询只在界面处于前台时进行：退到后台立即停表，避免持续抓屏与解码。
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(snapshot.url, snapshot.isLoading, resumed) {
        if (!resumed) return@LaunchedEffect
        suspend fun capturePreviewFrame() {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    AgentBrowserSession.capturePreview()?.let { decodeDataUrlBitmap(it.dataUrl) }
                }.getOrNull()
            }
            if (image != null) preview = image
        }
        if (snapshot.isLoading) {
            // 加载中：保持 1.2s 快节拍刷新，直到 isLoading 变化重启本 effect。
            while (true) {
                capturePreviewFrame()
                delay(1_200L)
            }
        } else {
            // 页面已稳定：立即抓取一帧，再隔 4s 确认一帧后退出循环，
            // 避免页面不再变化时仍无限抓屏；url / isLoading / resumed 变化时本 effect 重启。
            capturePreviewFrame()
            delay(4_000L)
            capturePreviewFrame()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (snapshot.isLoading) StatusRunning else StatusSuccess),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = snapshot.host.ifBlank { snapshot.displayUrl },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.tool_browser_preview),
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.outline,
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (snapshot.title.isNotBlank()) {
                Text(
                    text = snapshot.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.displayUrl.isNotBlank()) {
                Text(
                    text = snapshot.displayUrl,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ToolCommandBlock(
    command: String,
    context: String,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 10.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.ifBlank { stringResource(R.string.shell_command) },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(command))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_command,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        SelectionContainer {
            Text(
                text = command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

// ── Run trace：轻量入口行 ─────────────────────────────────────────────

internal data class BoundedText(
    val text: String,
    val truncated: Boolean,
)

/**
 * 把可能异常大的工具正文限制在可测量范围内。
 * 先按字符截断再按行截断，确保单个超长行不会让 Text 在展开首帧全量排版。
 */
internal fun boundedTextPreview(
    raw: String,
    maxChars: Int,
    maxLines: Int,
): BoundedText {
    val charLimited = if (raw.length <= maxChars) raw else raw.take(maxChars)
    val lineLimited = charLimited.lineSequence().take(maxLines).joinToString("\n")
    return BoundedText(
        text = lineLimited,
        truncated = raw.length > maxChars || charLimited.count { it == '\n' } >= maxLines,
    )
}

private const val MAX_MARKDOWN_PENDING_CHARS = 4_000
private const val MAX_MARKDOWN_PENDING_LINES = 40

@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

// ── 工具摘要 ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = iconForTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = toolDisplayName(tool),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ── 建议语 ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSuggestionClick(prompt) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = prompt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── 辅助 ──────────────────────────────────────────────────────────────

@Composable
internal fun ToolActivityStatusUi.statusColor() = when (this) {
    ToolActivityStatusUi.Running -> StatusRunning
    ToolActivityStatusUi.Success -> StatusSuccess
    ToolActivityStatusUi.Failed -> StatusError
    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
internal fun ToolActivityStatusUi.statusLabel(): String = when (this) {
    ToolActivityStatusUi.Running -> stringResource(R.string.tool_status_running)
    ToolActivityStatusUi.Success -> stringResource(R.string.tool_status_success)
    ToolActivityStatusUi.Failed -> stringResource(R.string.tool_status_failed)
    ToolActivityStatusUi.Unknown -> stringResource(R.string.tool_status_unknown)
}
