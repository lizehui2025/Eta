package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentKind
import io.github.mangi.eta.agent.model.AgentMode
import io.github.mangi.eta.agent.model.InstructionReview
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * The small, immutable slice of app state needed by the composer controls.
 * Keeping it together prevents mode, agent and review callbacks from being
 * threaded independently through every chat layout layer.
 */
internal data class AgentControlUi(
    val mode: AgentMode = AgentMode.CHAT,
    val agent: AgentKind = AgentKind.WORK,
    val review: InstructionReview = InstructionReview.MANUAL,
    val onAgentChange: (AgentKind) -> Unit = {},
    val onReviewChange: (InstructionReview) -> Unit = {},
)

private data class ChoiceOption(
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

private val PlanColor = Color(0xFF4F8DFF)
private val BuildColor = Color(0xFF2FA36B)
private val GoalColor = Color(0xFFE2A52D)
private val AutoColor = Color(0xFF9B6DDB)
private val AskColor = Color(0xFF28A9B8)
private val WorkColor = Color(0xFFE0783D)
private val ManualReviewColor = Color(0xFF4F8DFF)
private val AutomaticReviewColor = Color(0xFF2FA36B)
private val BypassReviewColor = Color(0xFFD65353)

@Composable
internal fun AgentControlBar(
    mode: AgentMode,
    agent: AgentKind,
    review: InstructionReview,
    onAgentChange: (AgentKind) -> Unit,
    onReviewChange: (InstructionReview) -> Unit,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val agents = if (mode == AgentMode.CODING) {
        listOf(
            AgentKind.PLAN to ImageVector.vectorResource(R.drawable.ic_atom),
            AgentKind.BUILD to Icons.Rounded.Build,
            AgentKind.GOAL to Icons.Rounded.AdsClick,
            AgentKind.AUTO to Icons.Rounded.AutoAwesome,
        )
    } else {
        listOf(
            AgentKind.ASK to Icons.Rounded.ChatBubble,
            AgentKind.WORK to Icons.Rounded.SupportAgent,
        )
    }
    val agentOptions = agents.map { (kind, icon) ->
        ChoiceOption(agentLabel(kind), icon, agentColor(kind))
    }
    val selectedAgentIndex = agents.indexOfFirst { it.first == agent }.coerceAtLeast(0)
    val reviewOptions = InstructionReview.entries.map { review ->
        ChoiceOption(reviewLabel(review), reviewIcon(review), reviewColor(review))
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AgentChoicePopup(
            categoryDescription = stringResource(R.string.agent_controls_agent),
            options = agentOptions,
            selectedIndex = selectedAgentIndex,
            popupAnchorTopPx = popupAnchorTopPx,
            popupMaxHeight = popupMaxHeight,
            enabled = enabled,
            onSelect = { onAgentChange(agents[it].first) },
        )
        AgentChoicePopup(
            categoryDescription = stringResource(R.string.agent_controls_review),
            options = reviewOptions,
            selectedIndex = review.ordinal,
            popupAnchorTopPx = popupAnchorTopPx,
            popupMaxHeight = popupMaxHeight,
            enabled = enabled,
            onSelect = { onReviewChange(InstructionReview.entries[it]) },
        )
    }
}

@Composable
private fun AgentChoicePopup(
    categoryDescription: String,
    options: List<ChoiceOption>,
    selectedIndex: Int,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var showPopup by remember { mutableStateOf(false) }
    val selected = options[selectedIndex]
    val iconColor by animateColorAsState(
        targetValue = if (enabled) selected.color else selected.color.copy(alpha = 0.45f),
        animationSpec = tween(180),
        label = "agent_choice_color",
    )
    val containerColor by animateColorAsState(
        targetValue = selected.color.copy(alpha = if (enabled) 0.14f else 0.07f),
        animationSpec = tween(180),
        label = "agent_choice_container",
    )
    Box {
        TooltipBox(text = selected.label, enabled = enabled) {
            IconButton(
                onClick = { showPopup = true },
                enabled = enabled,
                minWidth = 30.dp,
                minHeight = 30.dp,
                modifier = Modifier
                    .squircleSurface(
                        color = containerColor,
                        cornerRadius = 10.dp,
                    )
                    .semantics { this.contentDescription = "$categoryDescription: ${selected.label}" },
            ) {
                AnimatedContent(
                    targetState = selected.icon,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.75f))
                            .togetherWith(fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.75f))
                    },
                    label = "agent_choice_icon",
                ) { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = iconColor,
                    )
                }
            }
        }
        WindowListPopup(
            show = showPopup && enabled && popupAnchorTopPx > 0,
            popupPositionProvider = remember(popupAnchorTopPx) { InputPopupPositionProvider(popupAnchorTopPx) },
            alignment = PopupPositionProvider.Align.TopStart,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val dismiss = LocalDismissState.current
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .squircleSurface(
                                color = if (index == selectedIndex) {
                                    MiuixTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                cornerRadius = 8.dp,
                            )
                            .clickable {
                                onSelect(index)
                                showPopup = false
                                dismiss?.invoke()
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = option.color,
                        )
                        Text(
                            text = option.label,
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun agentLabel(kind: AgentKind): String = when (kind) {
    AgentKind.ASK -> stringResource(R.string.agent_ask)
    AgentKind.WORK -> stringResource(R.string.agent_work)
    AgentKind.PLAN -> stringResource(R.string.agent_plan)
    AgentKind.BUILD -> stringResource(R.string.agent_build)
    AgentKind.GOAL -> stringResource(R.string.agent_goal)
    AgentKind.AUTO -> stringResource(R.string.agent_auto)
}

@Composable
private fun reviewLabel(review: InstructionReview): String = when (review) {
    InstructionReview.MANUAL -> stringResource(R.string.agent_review_manual)
    InstructionReview.AUTOMATIC -> stringResource(R.string.agent_review_automatic)
    InstructionReview.BYPASS -> stringResource(R.string.agent_review_bypass)
}

private fun agentColor(kind: AgentKind): Color = when (kind) {
    AgentKind.ASK -> AskColor
    AgentKind.WORK -> WorkColor
    AgentKind.PLAN -> PlanColor
    AgentKind.BUILD -> BuildColor
    AgentKind.GOAL -> GoalColor
    AgentKind.AUTO -> AutoColor
}

private fun reviewIcon(review: InstructionReview): ImageVector = when (review) {
    InstructionReview.MANUAL -> Icons.Rounded.Security
    InstructionReview.AUTOMATIC -> Icons.Rounded.AutoAwesome
    InstructionReview.BYPASS -> Icons.Rounded.Shield
}

private fun reviewColor(review: InstructionReview): Color = when (review) {
    InstructionReview.MANUAL -> ManualReviewColor
    InstructionReview.AUTOMATIC -> AutomaticReviewColor
    InstructionReview.BYPASS -> BypassReviewColor
}
