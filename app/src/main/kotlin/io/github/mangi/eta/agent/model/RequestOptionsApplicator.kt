package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelRequestOptions
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.json.JSONObject

/**
 * 把模型配置里的 typed 请求参数（[ModelRequestOptions]）写入请求体。
 *
 * 三条请求构建路径都在 extraBody/customBody 合并之前调用：typed 参数最先落盘，
 * 用户原始覆盖（extraBody、customBody）随后合并因而优先级更高；推理运行时字段
 * （thinking/reasoning 等）仍由 [ProviderReasoning] 在最后写入，现有优先级不变。
 * 只有非 null 的字段才会写入，null 一律不产生键。
 *
 * 编码模式（codingMode=true）在 typed 参数之后强制写入 temperature=0.1：覆盖 typed
 * 温度（含未配置的情况）；随后合并的 extraBody/customBody 与推理运行时仍可继续覆盖。
 */
internal object RequestOptionsApplicator {

    /** 编码模式下所有请求统一使用的 temperature。 */
    const val CODING_TEMPERATURE = 0.1

    fun applyChatCompletions(
        body: JSONObject,
        options: ModelRequestOptions?,
        sourceType: String?,
        codingMode: Boolean = false,
    ) {
        if (options != null) {
            options.temperature?.let { body.put("temperature", it) }
            options.topP?.let { body.put("top_p", it) }
            options.presencePenalty?.let { body.put("presence_penalty", it) }
            options.frequencyPenalty?.let { body.put("frequency_penalty", it) }
            options.seed?.let { body.put("seed", it) }
            options.maxOutputTokens?.let { maxOutputTokens ->
                // OpenAI 官方网关（来源常量 ProviderSourceTypes.OPENAI，值 "openai"）只接受
                // max_completion_tokens；兼容服务沿用 max_tokens。
                val key = if (sourceType == ProviderSourceTypes.OPENAI) {
                    "max_completion_tokens"
                } else {
                    "max_tokens"
                }
                body.put(key, maxOutputTokens)
            }
            // Chat Completions 没有统一支持的 top_k 语义，这里刻意不发送。
        }
        // 编码模式在 typed 之后强制覆盖温度；extraBody/customBody 由调用方随后合并，仍可覆盖。
        if (codingMode) body.put("temperature", CODING_TEMPERATURE)
    }

    fun applyAnthropic(body: JSONObject, options: ModelRequestOptions?, codingMode: Boolean = false) {
        if (options != null) {
            options.temperature?.let { body.put("temperature", it) }
            options.topP?.let { body.put("top_p", it) }
            options.topK?.let { body.put("top_k", it) }
            // 覆盖请求构建时的默认 max_tokens；之后的推理逻辑（如 XHIGH/MAX）仍可继续提升。
            options.maxOutputTokens?.let { body.put("max_tokens", it) }
        }
        // 编码模式在 typed 之后强制覆盖温度；customBody 由调用方随后合并，仍可覆盖。
        if (codingMode) body.put("temperature", CODING_TEMPERATURE)
    }

    fun applyResponses(body: JSONObject, options: ModelRequestOptions?, codingMode: Boolean = false) {
        if (options != null) {
            options.temperature?.let { body.put("temperature", it) }
            options.topP?.let { body.put("top_p", it) }
            options.maxOutputTokens?.let { body.put("max_output_tokens", it) }
        }
        // 编码模式在 typed 之后强制覆盖温度；extraBody/customBody 由调用方随后合并，仍可覆盖。
        if (codingMode) body.put("temperature", CODING_TEMPERATURE)
    }
}
