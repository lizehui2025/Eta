package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentContextBreakdown
import io.github.mangi.eta.agent.model.AgentContextBreakdownCounter
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
)

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
)

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
    val estimated: Boolean = false,
    /**
     * 当前消息流按“对话 / 工具调用 / 思考链 / 代码数据 / 图片”分类的窗口内容估算。
     * 与压缩判据同一 token 口径；读文件抓到的内容落在“代码”项，可直接对账。
     */
    val breakdown: AgentContextBreakdown? = null,
    /** 最近一轮实际请求的输入 token 数；没有实测数据时为 null。 */
    val lastInputTokens: Int? = null,
    /** 最近一轮输入中被提示缓存的 token 数；没有实测数据时为 null。 */
    val lastCachedTokens: Int? = null,
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)

    /** 缓存命中率（百分比，0..100）：input/cached 缺失或数值无效时返回 null。 */
    val cacheHitPercent: Int?
        get() {
            val input = lastInputTokens ?: return null
            val cached = lastCachedTokens ?: return null
            if (input <= 0 || cached < 0) return null
            return (cached * 100.0 / input).roundToInt().coerceIn(0, 100)
        }
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider
            ?.models
            ?.firstOrNull { it.id == selectedModelId && it.isEnabled }
            ?.let { model -> selectedProvider.toOption(model) }
            ?: enabledProviders.asSequence()
                .flatMap { provider ->
                    provider.models.asSequence()
                        .filter { it.isEnabled }
                        .map { model -> provider.toOption(model) }
                }
                .firstOrNull { it.id == selectedModelId }
        val groups = enabledProviders
            .asSequence()
            .filter { it.apiKey.isNotBlank() }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = provider.models
                    .asSequence()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
        )
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi =
        AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = ProviderSourceRegistry.resolve(this),
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow,
        )
}

internal fun defaultExpandedModelProviderIds(selectedModel: AgentModelOptionUi?): Set<String> =
    selectedModel?.providerId?.let(::setOf).orEmpty()

/** 最近一条被采纳的实测用量：contextTokens 决定是否采纳，input/cached 与它同源。 */
private data class LastContextUsage(
    val contextTokens: Int,
    val estimated: Boolean,
    val inputTokens: Int?,
    val cachedTokens: Int?,
)

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi {
    val lastUsage = messages.asReversed().asSequence().mapNotNull { message ->
        when (message) {
            is AgentMessageUi -> message.usage?.let { usage ->
                usage.contextTokens?.let { contextTokens ->
                    LastContextUsage(
                        contextTokens = contextTokens,
                        estimated = false,
                        inputTokens = usage.inputTokens,
                        cachedTokens = usage.cachedTokens,
                    )
                }
            }
            is SystemNoticeMessageUi -> message.contextTokens?.let { contextTokens ->
                LastContextUsage(
                    contextTokens = contextTokens,
                    estimated = true,
                    inputTokens = null,
                    cachedTokens = null,
                )
            }
            else -> null
        }
    }.firstOrNull()
    return AgentContextUsageUi(
        contextTokens = lastUsage?.contextTokens,
        contextWindow = selectedModel?.contextWindow,
        estimated = lastUsage?.estimated ?: false,
        breakdown = contextUiBreakdown(messages),
        lastInputTokens = lastUsage?.inputTokens,
        lastCachedTokens = lastUsage?.cachedTokens,
    )
}

/**
 * 消息流窗口分类：对话（用户+模型正文）/ 工具调用 / 思考链 / 代码数据（文件类工具行）
 * / 图片，与 [AgentContextBreakdownCounter] 同一 token 口径。系统通知是 Eta 自己
 * 生成的提示语，不进模型窗口，只计 usage，不计分类。
 */
internal fun contextUiBreakdown(messages: List<AgentChatMessageUi>): AgentContextBreakdown {
    var dialogue = 0
    var toolCalls = 0
    var thinking = 0
    var codeData = 0
    var images = 0
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                dialogue += AgentContextBudget.textTokens(message.content)
                images += message.images.size * 4096
            }
            is AgentMessageUi -> dialogue += AgentContextBudget.textTokens(message.content)
            is ThinkingMessageUi -> thinking += AgentContextBudget.textTokens(message.content)
            is ToolActivityMessageUi -> {
                val text = listOfNotNull(
                    message.argumentsSummary.takeIf { it.isNotBlank() },
                    message.command?.takeIf { it.isNotBlank() },
                    message.resultSummary?.takeIf { it.isNotBlank() },
                    message.detail?.takeIf { it.isNotBlank() },
                ).joinToString("\n") + message.steps.joinToString("\n") { it.summary + "\n" + it.detail }
                val tokens = AgentContextBudget.textTokens(text)
                if (message.toolName in AgentContextBreakdownCounter.CODE_DATA_TOOLS) codeData += tokens
                else toolCalls += tokens
                images += message.imageCount * 4096
            }
            else -> Unit
        }
    }
    return AgentContextBreakdown(
        dialogueTokens = dialogue,
        toolCallTokens = toolCalls,
        thinkingTokens = thinking,
        codeDataTokens = codeData,
        imageTokens = images,
    )
}

/** 分类明细行：只列非零项，数字按 K/M 紧凑格式，便于用量提示展示。 */
internal fun formatContextBreakdown(
    breakdown: AgentContextBreakdown,
    locale: Locale = Locale.getDefault(),
): String =
    listOf(
        "对话" to breakdown.dialogueTokens,
        "工具调用" to breakdown.toolCallTokens,
        "思考链" to breakdown.thinkingTokens,
        "代码数据" to breakdown.codeDataTokens,
        "图片" to breakdown.imageTokens,
    ).filter { (_, tokens) -> tokens > 0 }
        .joinToString(" · ") { (label, tokens) -> "$label ${formatCompactTokenCount(tokens, locale)}" }
        .ifBlank { "暂无窗口内容" }

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No usage data yet",
    noLimitText: String = "This model has no context limit",
    locale: Locale = Locale.getDefault(),
): String = when {
    usage.contextTokens == null -> noUsageText
    usage.contextWindow == null || usage.contextWindow <= 0 ->
        "${formatCompactTokenCount(usage.contextTokens, locale)} tokens\n$noLimitText"
    else -> {
        val percent = usage.contextTokens.toDouble() / usage.contextWindow.toDouble() * 100.0
        val percentFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        "${formatCompactTokenCount(usage.contextTokens, locale)} / " +
            "${formatCompactTokenCount(usage.contextWindow, locale)} tokens · " +
            "${percentFormat.format(percent)}%"
    }
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}
