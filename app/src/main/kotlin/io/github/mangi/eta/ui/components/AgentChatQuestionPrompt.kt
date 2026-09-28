package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.UserQuestionMessageUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Pending-question bar above the input: where the user answers an agent question.
 *
 * A single choice submits on tap, multi-select checks then confirms, and free-form input goes through the
 * input bar's send key
 * (see the onSubmit forwarding in AgentChatBody), so this bar holds no second text field.
 * The timeline's UserQuestionCard is a record only: interaction lives here and nowhere else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingQuestionPrompt(
    question: UserQuestionMessageUi,
    onAnswer: (answer: String, selectedOptions: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(question.questionId) { mutableStateOf(emptyList<String>()) }
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Text(
            text = question.question,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (question.options.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                question.options.forEach { option ->
                    val isSelected = option in selected
                    Text(
                        text = option,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(shape)
                            .background(MiuixTheme.colorScheme.surface)
                            .border(
                                0.5.dp,
                                if (isSelected) {
                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.5f)
                                } else {
                                    MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
                                },
                                shape,
                            )
                            .clickable {
                                if (question.multiSelect) {
                                    selected = if (isSelected) selected - option else selected + option
                                } else {
                                    onAnswer(option, listOf(option))
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (question.multiSelect) {
                    Text(
                        text = stringResource(R.string.user_question_submit),
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (selected.isEmpty()) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        modifier = Modifier
                            .clip(shape)
                            .border(
                                0.5.dp,
                                MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape,
                            )
                            .clickable(enabled = selected.isNotEmpty()) {
                                onAnswer(selected.joinToString(separator = "、"), selected)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (question.allowFreeform) {
            Text(
                text = stringResource(R.string.user_question_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
