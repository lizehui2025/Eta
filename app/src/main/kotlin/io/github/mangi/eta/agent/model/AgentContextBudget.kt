package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** usage 只校准同一模型的请求估算，不把累计计费用量当作窗口占用。 */
internal class AgentContextBudget(private val window: Int?) {
    private var calibration = 1.0
    /** 上轮服务商回传的真实 input tokens：实时窗口锚点，判压缩时优先使用。 */
    private var lastRealInput: Int? = null
    /** 产生上轮真实值的那次请求的本地估算（raw 口径），用于计算本轮新增增量。 */
    private var lastEstimateAtObserve = 0

    fun observe(usage: AgentTokenUsage?, requestEstimate: Int) {
        val input = usage?.inputTokens ?: usage?.contextTokens ?: return
        if (input > 0 && requestEstimate > 0) {
            lastRealInput = input
            lastEstimateAtObserve = requestEstimate
            val sample = (input.toDouble() / requestEstimate).coerceIn(1.0, 8.0)
            // 平滑校准：单轮异常值只按 1/3 权重吸收，并封顶 4 倍。
            // 此前单次采样直接覆盖（最高 8 倍），一轮 provider 计数口径差异就会让后续估算虚高、
            // 提前触发本不该发生的上下文压缩；多轮后仍会收敛到真实比例。
            calibration = ((calibration * 2 + sample) / 3).coerceIn(1.0, 4.0)
        }
    }

    fun estimate(messages: JSONArray, tools: JSONArray): Int =
        ceil(rawEstimate(messages, tools) * calibration).toInt()

    /**
     * 实时窗口投影：有真实值时以“上轮真实 input + 本轮新增增量”为准，
     * 无真实值（如首轮、服务商不回 usage）才回退到校准估算。
     * 增量用同一 raw 口径相减，只取正向增长；压缩后窗口变小则以上轮真实为上限，
     * 避免压缩刚完成又因旧锚点虚高而反复触发。
     */
    fun effectiveTokens(messages: JSONArray, tools: JSONArray): Int {
        val raw = rawEstimate(messages, tools)
        val real = lastRealInput ?: return ceil(raw * calibration).toInt()
        val projected = real + (raw - lastEstimateAtObserve)
        return projected.coerceIn(0, maxOf(real, ceil(raw * calibration).toInt()))
    }

    /** 当前校准倍率与实时锚点，供日志与用量提示对账（解释“显示 50% 却压缩”）。 */
    fun calibrationFactor(): Double = calibration

    fun lastRealInputTokens(): Int? = lastRealInput

    fun shouldCompact(tokens: Int): Boolean {
        val w = window?.takeIf { it > 0 } ?: FALLBACK_WINDOW_TOKENS
        return tokens >= w * TRIGGER_RATIO
    }

    fun exceedsWindow(tokens: Int): Boolean {
        val w = window?.takeIf { it > 0 } ?: FALLBACK_WINDOW_TOKENS
        return tokens >= w
    }

    companion object {
        const val TRIGGER_RATIO = 0.85
        const val RECENT_MESSAGES = 4
        const val RECENT_RATIO = 0.20
        const val MAX_OVERFLOW_ATTEMPTS = 3
        /**
         * window 未知（null）时的回退窗口：避免永不压缩导致 messages 无界增长。
         * 取常见 100k 档，主窗口容量仍由服务商真实 input 锚点 + 压缩统一裁决。
         */
        const val FALLBACK_WINDOW_TOKENS = 100_000

        fun textTokens(text: String): Int {
            var ascii = 0
            var other = 0
            text.codePoints().forEach { if (it < 128) ascii++ else other++ }
            return (ascii + 2) / 3 + other
        }

        fun rawEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
            var tokens = textTokens(tools.toString()) + 16
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val copy = JSONObject()
                message.keys().forEach { key -> if (key != "content") copy.put(key, message.get(key)) }
                val parts = message.optJSONArray("content")
                if (parts != null) {
                    val text = JSONArray()
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") in setOf("image_url", "input_image", "image")) {
                            tokens += 4096
                        } else text.put(part)
                    }
                    copy.put("content", text)
                } else copy.put("content", message.opt("content"))
                tokens += textTokens(copy.toString()) + 8
            }
            return tokens
        }
    }
}
