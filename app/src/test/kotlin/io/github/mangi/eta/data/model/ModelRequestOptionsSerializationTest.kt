package io.github.mangi.eta.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestOptionsSerializationTest {
    // 与 ProviderJson 存储 requestOptions 时的配置保持一致：null 字段省略。
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun optionsRoundTripThroughJson() {
        val options = ModelRequestOptions(
            temperature = 0.7,
            topP = 0.9,
            topK = 40,
            maxOutputTokens = 4096,
            presencePenalty = 0.1,
            frequencyPenalty = 0.2,
            seed = 42L,
        )

        val encoded = json.encodeToString(options)

        assertEquals(options, json.decodeFromString<ModelRequestOptions>(encoded))
    }

    @Test
    fun nullFieldsAreOmittedFromJson() {
        val encoded = json.encodeToString(ModelRequestOptions(temperature = 0.7))

        assertEquals("{\"temperature\":0.7}", encoded)
        assertEquals(
            ModelRequestOptions(seed = 7L),
            json.decodeFromString<ModelRequestOptions>("{\"seed\":7}"),
        )
    }

    @Test
    fun isEmptyReflectsUnsetFields() {
        assertTrue(ModelRequestOptions().isEmpty)
        assertFalse(ModelRequestOptions(temperature = 0.0).isEmpty)
        assertFalse(ModelRequestOptions(seed = 0L).isEmpty)
    }
}
