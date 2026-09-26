package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 模型级可配置的底层 LLM 请求参数；null 表示不覆盖，使用服务端默认值。
 */
@Serializable
data class ModelRequestOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val seed: Long? = null,
) {
    val isEmpty: Boolean
        get() = temperature == null &&
            topP == null &&
            topK == null &&
            maxOutputTokens == null &&
            presencePenalty == null &&
            frequencyPenalty == null &&
            seed == null
}
