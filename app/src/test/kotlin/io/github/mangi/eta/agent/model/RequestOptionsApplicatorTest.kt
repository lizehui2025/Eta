package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelRequestOptions
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestOptionsApplicatorTest {
    @Test
    fun chatCompletionsMapsTypedFieldsAndDropsTopK() {
        val body = JSONObject()

        RequestOptionsApplicator.applyChatCompletions(
            body = body,
            options = requestOptions(
                temperature = 0.35,
                topP = 0.9,
                topK = 40,
                maxOutputTokens = 512,
                presencePenalty = 0.1,
                frequencyPenalty = 0.2,
                seed = 42L,
            ),
            sourceType = ProviderSourceTypes.CUSTOM,
        )

        assertEquals(0.35, body.getDouble("temperature"), 1e-9)
        assertEquals(0.9, body.getDouble("top_p"), 1e-9)
        assertEquals(0.1, body.getDouble("presence_penalty"), 1e-9)
        assertEquals(0.2, body.getDouble("frequency_penalty"), 1e-9)
        assertEquals(42L, body.getLong("seed"))
        assertEquals(512, body.getInt("max_tokens"))
        assertFalse(body.has("top_k"))
        assertFalse(body.has("max_completion_tokens"))
    }

    @Test
    fun chatCompletionsUsesMaxCompletionTokensForOfficialOpenAi() {
        val body = JSONObject()

        RequestOptionsApplicator.applyChatCompletions(
            body = body,
            options = requestOptions(maxOutputTokens = 1024),
            sourceType = ProviderSourceTypes.OPENAI,
        )

        assertEquals(1024, body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun nullOptionsLeaveBodyUntouched() {
        val body = JSONObject().put("model", "test-model")

        RequestOptionsApplicator.applyChatCompletions(body, null, null)
        RequestOptionsApplicator.applyAnthropic(body, null)
        RequestOptionsApplicator.applyResponses(body, null)

        assertEquals(1, body.length())
        assertEquals("test-model", body.getString("model"))
    }

    @Test
    fun nullFieldsDoNotWriteKeys() {
        val body = JSONObject()

        RequestOptionsApplicator.applyChatCompletions(
            body = body,
            options = requestOptions(temperature = 0.5),
            sourceType = ProviderSourceTypes.CUSTOM,
        )

        assertEquals(1, body.length())
        assertTrue(body.has("temperature"))
        assertFalse(body.has("top_p"))
        assertFalse(body.has("seed"))
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun anthropicMapsTemperatureTopPTopKAndMaxTokens() {
        val body = JSONObject().put("max_tokens", 4096)

        RequestOptionsApplicator.applyAnthropic(
            body = body,
            options = requestOptions(
                temperature = 0.4,
                topP = 0.8,
                topK = 48,
                maxOutputTokens = 8192,
            ),
        )

        assertEquals(0.4, body.getDouble("temperature"), 1e-9)
        assertEquals(0.8, body.getDouble("top_p"), 1e-9)
        assertEquals(48, body.getInt("top_k"))
        assertEquals(8192, body.getInt("max_tokens"))
        assertFalse(body.has("max_output_tokens"))
    }

    @Test
    fun responsesMapsTemperatureTopPAndMaxOutputTokens() {
        val body = JSONObject()

        RequestOptionsApplicator.applyResponses(
            body = body,
            options = requestOptions(
                temperature = 0.2,
                topP = 0.7,
                maxOutputTokens = 4096,
            ),
        )

        assertEquals(0.2, body.getDouble("temperature"), 1e-9)
        assertEquals(0.7, body.getDouble("top_p"), 1e-9)
        assertEquals(4096, body.getInt("max_output_tokens"))
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("top_k"))
    }

    @Test
    fun chatCompletionsCodingModeOverridesTemperatureAndKeepsTypedFields() {
        val body = JSONObject()

        RequestOptionsApplicator.applyChatCompletions(
            body = body,
            options = requestOptions(
                temperature = 0.7,
                topP = 0.9,
                seed = 42L,
            ),
            sourceType = ProviderSourceTypes.CUSTOM,
            codingMode = true,
        )

        assertEquals(RequestOptionsApplicator.CODING_TEMPERATURE, body.getDouble("temperature"), 1e-9)
        assertEquals(0.9, body.getDouble("top_p"), 1e-9)
        assertEquals(42L, body.getLong("seed"))
    }

    @Test
    fun chatCompletionsCodingModeWritesTemperatureWithoutOptions() {
        val body = JSONObject()

        RequestOptionsApplicator.applyChatCompletions(
            body = body,
            options = null,
            sourceType = ProviderSourceTypes.CUSTOM,
            codingMode = true,
        )

        assertEquals(1, body.length())
        assertEquals(RequestOptionsApplicator.CODING_TEMPERATURE, body.getDouble("temperature"), 1e-9)
    }

    @Test
    fun anthropicCodingModeForcesLowTemperatureAndKeepsTopK() {
        val body = JSONObject()

        RequestOptionsApplicator.applyAnthropic(
            body = body,
            options = requestOptions(topK = 48),
            codingMode = true,
        )

        assertEquals(RequestOptionsApplicator.CODING_TEMPERATURE, body.getDouble("temperature"), 1e-9)
        assertEquals(48, body.getInt("top_k"))
    }

    @Test
    fun responsesCodingModeForcesLowTemperature() {
        val body = JSONObject()

        RequestOptionsApplicator.applyResponses(
            body = body,
            options = requestOptions(topP = 0.7),
            codingMode = true,
        )

        assertEquals(RequestOptionsApplicator.CODING_TEMPERATURE, body.getDouble("temperature"), 1e-9)
        assertEquals(0.7, body.getDouble("top_p"), 1e-9)
    }

    private fun requestOptions(
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        maxOutputTokens: Int? = null,
        presencePenalty: Double? = null,
        frequencyPenalty: Double? = null,
        seed: Long? = null,
    ): ModelRequestOptions = ModelRequestOptions(
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        seed = seed,
    )
}
