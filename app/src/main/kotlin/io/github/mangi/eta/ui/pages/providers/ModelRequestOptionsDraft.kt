package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ModelRequestOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * 模型编辑弹窗中「采样与输出参数」的文本草稿；空串表示该字段留空（不发送，使用服务端默认值）。
 */
internal data class ModelRequestOptionsDraft(
    val temperature: String = "",
    val topP: String = "",
    val topK: String = "",
    val maxOutputTokens: String = "",
    val presencePenalty: String = "",
    val frequencyPenalty: String = "",
    val seed: String = "",
)

/** 采样与输出参数中的单个字段，用于定位校验错误。 */
internal enum class ModelRequestOptionField {
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MAX_OUTPUT_TOKENS,
    PRESENCE_PENALTY,
    FREQUENCY_PENALTY,
    SEED,
}

/** 字段校验错误；界面据此映射为「请输入有效数字 / 超出允许范围」文案。 */
internal enum class ModelRequestOptionError {
    NOT_A_NUMBER,
    OUT_OF_RANGE,
}

/**
 * 采样与输出参数解析结果。
 *
 * [fieldErrors] 为空表示校验通过；全部字段留空时 [options] 为 null（不写入 requestOptions）。
 */
internal data class ModelRequestOptionsParseResult(
    val options: ModelRequestOptions?,
    val fieldErrors: Map<ModelRequestOptionField, ModelRequestOptionError> = emptyMap(),
) {
    val isValid: Boolean get() = fieldErrors.isEmpty()
}

private val customBodyJson = Json { prettyPrint = true }

/** 将模型现值格式化为文本框内容；null 展示为空串。 */
internal fun formatModelRequestOptions(options: ModelRequestOptions?): ModelRequestOptionsDraft =
    ModelRequestOptionsDraft(
        temperature = options?.temperature?.toString().orEmpty(),
        topP = options?.topP?.toString().orEmpty(),
        topK = options?.topK?.toString().orEmpty(),
        maxOutputTokens = options?.maxOutputTokens?.toString().orEmpty(),
        presencePenalty = options?.presencePenalty?.toString().orEmpty(),
        frequencyPenalty = options?.frequencyPenalty?.toString().orEmpty(),
        seed = options?.seed?.toString().orEmpty(),
    )

/** 解析 7 个文本框：空串→null；非数字或超出范围时按字段返回错误。 */
internal fun parseModelRequestOptions(draft: ModelRequestOptionsDraft): ModelRequestOptionsParseResult {
    val fieldErrors = linkedMapOf<ModelRequestOptionField, ModelRequestOptionError>()
    val temperature = parseOptionDouble(draft.temperature, 0.0..2.0, ModelRequestOptionField.TEMPERATURE, fieldErrors)
    val topP = parseOptionDouble(draft.topP, 0.0..1.0, ModelRequestOptionField.TOP_P, fieldErrors)
    val topK = parseOptionInt(draft.topK, 1, ModelRequestOptionField.TOP_K, fieldErrors)
    val maxOutputTokens = parseOptionInt(draft.maxOutputTokens, 1, ModelRequestOptionField.MAX_OUTPUT_TOKENS, fieldErrors)
    val presencePenalty = parseOptionDouble(draft.presencePenalty, -2.0..2.0, ModelRequestOptionField.PRESENCE_PENALTY, fieldErrors)
    val frequencyPenalty = parseOptionDouble(draft.frequencyPenalty, -2.0..2.0, ModelRequestOptionField.FREQUENCY_PENALTY, fieldErrors)
    val seed = parseOptionLong(draft.seed, ModelRequestOptionField.SEED, fieldErrors)
    if (fieldErrors.isNotEmpty()) {
        return ModelRequestOptionsParseResult(options = null, fieldErrors = fieldErrors)
    }
    val options = ModelRequestOptions(
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        seed = seed,
    )
    // 全部字段留空时不写入空对象。
    return ModelRequestOptionsParseResult(options = options.takeUnless { it.isEmpty })
}

private fun parseOptionDouble(
    raw: String,
    range: ClosedRange<Double>,
    field: ModelRequestOptionField,
    fieldErrors: MutableMap<ModelRequestOptionField, ModelRequestOptionError>,
): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    // NaN/Infinity 等非有限输入视为无效数字。
    val value = text.toDoubleOrNull()?.takeIf { it.isFinite() }
    if (value == null) {
        fieldErrors[field] = ModelRequestOptionError.NOT_A_NUMBER
        return null
    }
    if (value !in range) {
        fieldErrors[field] = ModelRequestOptionError.OUT_OF_RANGE
        return null
    }
    return value
}

private fun parseOptionInt(
    raw: String,
    minimum: Int,
    field: ModelRequestOptionField,
    fieldErrors: MutableMap<ModelRequestOptionField, ModelRequestOptionError>,
): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val value = text.toIntOrNull()
    if (value == null) {
        fieldErrors[field] = ModelRequestOptionError.NOT_A_NUMBER
        return null
    }
    if (value < minimum) {
        fieldErrors[field] = ModelRequestOptionError.OUT_OF_RANGE
        return null
    }
    return value
}

private fun parseOptionLong(
    raw: String,
    field: ModelRequestOptionField,
    fieldErrors: MutableMap<ModelRequestOptionField, ModelRequestOptionError>,
): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val value = text.toLongOrNull()
    if (value == null) {
        fieldErrors[field] = ModelRequestOptionError.NOT_A_NUMBER
        return null
    }
    return value
}

/** 自定义请求体解析结果。 */
internal sealed interface CustomBodyParseResult {
    /** 解析成功；空白输入视为空列表。 */
    data class Success(val customBody: List<CustomBody>) : CustomBodyParseResult

    /** 不是有效的 JSON 对象。 */
    data object Invalid : CustomBodyParseResult
}

/** 解析「自定义请求体」文本：空白→空列表；非 JSON 对象或语法错误→[CustomBodyParseResult.Invalid]。 */
internal fun parseCustomBodyJson(text: String): CustomBodyParseResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return CustomBodyParseResult.Success(emptyList())
    val element = runCatching { customBodyJson.parseToJsonElement(trimmed) }.getOrNull()
        ?: return CustomBodyParseResult.Invalid
    val jsonObject = element as? JsonObject ?: return CustomBodyParseResult.Invalid
    return CustomBodyParseResult.Success(
        jsonObject.map { (key, value) -> CustomBody(key, value) },
    )
}

/** 将模型现有自定义请求体格式化为美观 JSON 文本；空列表展示为空串。 */
internal fun formatCustomBodyJson(customBody: List<CustomBody>): String {
    if (customBody.isEmpty()) return ""
    val jsonObject = buildJsonObject {
        customBody.forEach { body -> put(body.key, body.value) }
    }
    return customBodyJson.encodeToString(JsonObject.serializer(), jsonObject)
}
