package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.UserQuestionMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Pending-question bar above the input: where the user answers an agent question.
 *
 * 手机优先：选项是整行可点的单选/多选列表（触摸区 ≥ 44dp），单选项点按即提交，
 * 多选勾选后点底部主按钮提交；自由文本仍走输入条发送键。
 * The timeline's UserQuestionCard is a record only: interaction lives here and nowhere else.
 */
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
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (question.options.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                question.options.forEach { option ->
                    val isSelected = option in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(
                                if (isSelected) {
                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                                } else {
                                    MiuixTheme.colorScheme.surface
                                },
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.5.dp,
                                color = if (isSelected) {
                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.6f)
                                } else {
                                    MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
                                },
                                shape = shape,
                            )
                            // 单选=RadioButton、多选=Checkbox，读屏可播报选中状态并在
                            // 多选时随勾选更新；单选项点按即提交，故其 selected 恒为 false。
                            .then(
                                if (question.multiSelect) {
                                    Modifier.toggleable(
                                        value = isSelected,
                                        role = Role.Checkbox,
                                    ) { checked ->
                                        selected = if (checked) {
                                            selected + option
                                        } else {
                                            selected - option
                                        }
                                    }
                                } else {
                                    Modifier.selectable(
                                        selected = false,
                                        role = Role.RadioButton,
                                    ) {
                                        onAnswer(option, listOf(option))
                                    }
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        OptionIndicator(selected = isSelected, multiSelect = question.multiSelect)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = option,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (question.multiSelect) {
                    val canSubmit = selected.isNotEmpty()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(
                                if (canSubmit) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.surfaceContainerHigh
                                },
                            )
                            .clickable(enabled = canSubmit, role = Role.Button) {
                                onAnswer(selected.joinToString(separator = "、"), selected)
                            }
                            .padding(vertical = 11.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.user_question_submit),
                            style = MiuixTheme.textStyles.body2,
                            color = if (canSubmit) {
                                MiuixTheme.colorScheme.onPrimary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (question.allowFreeform) {
            Text(
                text = stringResource(R.string.user_question_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 单选/多选指示器：未选中为空心圆/圆角方框，选中填充主色并显示对勾。 */
@Composable
private fun OptionIndicator(selected: Boolean, multiSelect: Boolean) {
    val shape = if (multiSelect) RoundedCornerShape(5.dp) else CircleShape
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(shape)
            .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.outline
                },
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MiuixTheme.colorScheme.onPrimary,
            )
        }
    }
}
