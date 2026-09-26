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
        ceil(rawEstimateCached(messages, tools) * calibration).toInt()

    /**
     * 实时窗口投影：有真实值时以“上轮真实 input + 本轮新增增量”为准，
     * 无真实值（如首轮、服务商不回 usage）才回退到校准估算。
     * 增量用同一 raw 口径相减，只取正向增长；压缩后窗口变小则以上轮真实为上限，
     * 避免压缩刚完成又因旧锚点虚高而反复触发。
     */
    fun effectiveTokens(messages: JSONArray, tools: JSONArray): Int {
        val raw = rawEstimateCached(messages, tools)
        val real = lastRealInput ?: return ceil(raw * calibration).toInt()
        val projected = real + (raw - lastEstimateAtObserve)
        return projected.coerceIn(0, maxOf(real, ceil(raw * calibration).toInt()))
    }

    /** 当前校准倍率与实时锚点，供日志与用量提示对账（解释“显示 50% 却压缩”）。 */
    fun calibrationFactor(): Double = calibration

    fun lastRealInputTokens(): Int? = lastRealInput

    private val messageTokenCache = java.util.IdentityHashMap<JSONObject, CachedEstimate>()
    private val toolSchemaTokenCache = java.util.IdentityHashMap<JSONArray, CachedEstimate>()
    private var messageHits = 0
    private var messageMisses = 0
    private var toolSchemaHits = 0
    private var toolSchemaMisses = 0

    /** 估算缓存命中统计：命中率过低说明有地方在每轮重建消息对象，缓存等于没生效。 */
    data class Stats(
        val messageHits: Int,
        val messageMisses: Int,
        val toolSchemaHits: Int,
        val toolSchemaMisses: Int,
    ) {
        val messageHitRate: Double
            get() = ratio(messageHits, messageMisses)

        val toolSchemaHitRate: Double
            get() = ratio(toolSchemaHits, toolSchemaMisses)

        private fun ratio(hits: Int, misses: Int): Double =
            if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)
    }

    fun stats(): Stats = Stats(messageHits, messageMisses, toolSchemaHits, toolSchemaMisses)

    private class CachedEstimate(val signature: Int, val tokens: Int)

    /**
     * 带缓存的 rawEstimate 口径。同一份历史在一轮里会被估算 4-6 次（判压缩、窗口分类日志、
     * 压缩前后对比、溢出重试），每次都把每条消息重新序列化一遍；工具结果常有上百 KB，
     * 估算成本因此随历史总量线性放大并被重复付出，是压缩与首步延迟的主要来源之一。
     * 这里按消息对象身份缓存，并用内容签名兜底：对象被就地改写（追加标记、替换正文）时自动失效。
     */
    fun rawEstimateCached(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
        if (messageTokenCache.size > CACHE_LIMIT) messageTokenCache.clear()
        if (toolSchemaTokenCache.size > CACHE_LIMIT) toolSchemaTokenCache.clear()
        var tokens = cachedToolSchemaTokens(tools) + 16
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            tokens += cachedMessageTokens(message)
        }
        return tokens
    }

    private fun cachedToolSchemaTokens(tools: JSONArray): Int {
        // 空工具表是高频路径（压缩摘要请求）：直接返回常量，避免新建对象时的 miss 计数与 toString。
        if (tools.length() == 0) return EMPTY_TOOLS_TOKENS
        val signature = toolsSignature(tools)
        val cached = toolSchemaTokenCache[tools]
        if (cached != null && cached.signature == signature) {
            toolSchemaHits++
            return cached.tokens
        }
        toolSchemaMisses++
        val tokens = textTokens(tools.toString())
        toolSchemaTokenCache[tools] = CachedEstimate(signature, tokens)
        return tokens
    }

    /** 工具表指纹：实例身份 + 长度 + 首元素身份，O(1)，避免每轮重序列化。 */
    private fun toolsSignature(tools: JSONArray): Int {
        val first = if (tools.length() > 0) System.identityHashCode(tools.opt(0)) else 0
        return (31 * (31 * tools.length() + System.identityHashCode(tools)) + first)
    }

    private fun cachedMessageTokens(message: JSONObject): Int {
        val signature = contentSignature(message)
        val cached = messageTokenCache[message]
        if (cached != null && cached.signature == signature) {
            messageHits++
            return cached.tokens
        }
        messageMisses++
        val tokens = messageTokens(message)
        messageTokenCache[message] = CachedEstimate(signature, tokens)
        return tokens
    }

    /** 廉价的内容指纹：O(1)，不扫描正文；覆盖 role/content/tool_calls 三处可变点。 */
    private fun contentSignature(message: JSONObject): Int {
        val content = message.opt("content")
        val contentSignature = when {
            content is String -> 31 * content.length + System.identityHashCode(content)
            content is JSONArray -> 31 * (17 * content.length() + System.identityHashCode(content)) +
                content.length()
            content == null || content == JSONObject.NULL -> 0
            else -> 1
        }
        val toolCalls = message.optJSONArray("tool_calls")
        val toolSig = if (toolCalls == null) 0
        else 31 * toolCalls.length() + System.identityHashCode(toolCalls)
        // role 变更不会改变 length，必须单独计入，否则缓存返回过期 token。
        val roleSig = message.optString("role").hashCode()
        return ((contentSignature * 31 + toolSig) * 31 + roleSig) * 31 + message.length()
    }

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
        /** 消息级估算缓存的规模上限，超过即整体丢弃，避免长会话把对象图钉在内存里。 */
        const val CACHE_LIMIT = 4096

        /** 空工具表 token 常量：避免压缩摘要路径每轮 `textTokens("[]")` 的重复计算与 miss 计数。 */
        internal val EMPTY_TOOLS_TOKENS: Int = fastTextTokens("[]")

        /**
         * 快路径 token 启发式：与旧 `codePoints` 口径一致（ASCII 3 字符≈1 token，其余 1 码点≈1 token），
         * 但用 UTF-16 循环代替 `IntStream`，大字符串快 5~10 倍且零分配；代理对计为 1。
         */
        private fun fastTextTokens(text: String): Int {
            var ascii = 0
            var other = 0
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c < '\u0080') {
                    ascii++
                } else if (c.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) {
                    other++
                    i++
                } else {
                    other++
                }
                i++
            }
            return (ascii + 2) / 3 + other
        }

        fun textTokens(text: String): Int = fastTextTokens(text)

        fun rawEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
            var tokens = textTokens(tools.toString()) + 16
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                tokens += messageTokens(message)
            }
            return tokens
        }

        /**
         * 单条消息的估算口径：图片按 4096 计，其余按启发式计数。
         *
         * 零拷贝实现：旧实现把整条消息（含上百 KB 的 tool 结果）先 `copy.toString()` 再整体计数，
         * 大文本下每次估算都要转义一次大字符串。这里直接对各字段分别计数，不再物化整串；
         * 误差只有 JSON 转义符级别（每条个位数 token），压缩阈值不受影响。
         */
        fun messageTokens(message: JSONObject): Int {
            var tokens = 8
            val keys = message.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "content") continue
                if (key == "tool_calls") {
                    val calls = message.optJSONArray(key)
                    if (calls != null) tokens += fastTextTokens(calls.toString())
                    continue
                }
                // 小字段直接计数，不经过 JSONObject 拷贝。
                tokens += fastTextTokens(key) + 2
                when (val v = message.opt(key)) {
                    null, JSONObject.NULL -> Unit
                    is String -> tokens += fastTextTokens(v)
                    is JSONArray -> tokens += fastTextTokens(v.toString())
                    else -> tokens += fastTextTokens(v.toString())
                }
            }
            val parts = message.optJSONArray("content")
            if (parts != null) {
                for (partIndex in 0 until parts.length()) {
                    val part = parts.optJSONObject(partIndex) ?: continue
                    if (part.optString("type") in IMAGE_PART_TYPES) {
                        tokens += 4096
                    } else {
                        // 文本 part 只计正文 + 信封常量，不序列化整个 part。
                        tokens += fastTextTokens(part.optString("text")) + 6
                    }
                }
                tokens += 4
            } else {
                when (val c = message.opt("content")) {
                    null, JSONObject.NULL -> Unit
                    is String -> tokens += fastTextTokens(c)
                    else -> tokens += fastTextTokens(c.toString())
                }
            }
            return tokens
        }

        private val IMAGE_PART_TYPES = setOf("image_url", "input_image", "image")
    }
}
