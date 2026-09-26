package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ModelRequestOptions
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestOptionsDraftTest {

    @Test
    fun parseFormatRoundTripPreservesAllValues() {
        val options = ModelRequestOptions(
            temperature = 0.7,
            topP = 0.9,
            topK = 40,
            maxOutputTokens = 4096,
            presencePenalty = -0.5,
            frequencyPenalty = 0.25,
            seed = 42L,
        )

        val draft = formatModelRequestOptions(options)

        assertEquals(
            ModelRequestOptionsDraft(
                temperature = "0.7",
                topP = "0.9",
                topK = "40",
                maxOutputTokens = "4096",
                presencePenalty = "-0.5",
                frequencyPenalty = "0.25",
                seed = "42",
            ),
            draft,
        )
        val result = parseModelRequestOptions(draft)
        assertTrue(result.isValid)
        assertEquals(options, result.options)
    }

    @Test
    fun formatNullOptionsProducesBlankDraft() {
        assertEquals(ModelRequestOptionsDraft(), formatModelRequestOptions(null))
    }

    @Test
    fun blankDraftProducesNullOptions() {
        val result = parseModelRequestOptions(ModelRequestOptionsDraft())

        assertTrue(result.isValid)
        assertNull(result.options)
    }

    @Test
    fun whitespaceOnlyFieldsAreTreatedAsBlank() {
        val result = parseModelRequestOptions(
            ModelRequestOptionsDraft(temperature = "  ", presencePenalty = " "),
        )

        assertTrue(result.isValid)
        assertNull(result.options)
    }

    @Test
    fun onlyFilledFieldsAreIncluded() {
        val result = parseModelRequestOptions(ModelRequestOptionsDraft(topK = "40"))

        assertTrue(result.isValid)
        assertEquals(
            ModelRequestOptions(
                temperature = null,
                topP = null,
                topK = 40,
                maxOutputTokens = null,
                presencePenalty = null,
                frequencyPenalty = null,
                seed = null,
            ),
            result.options,
        )
    }

    @Test
    fun nonNumericFieldsAreReportedPerField() {
        val result = parseModelRequestOptions(
            ModelRequestOptionsDraft(
                temperature = "abc",
                topK = "1.5",
                seed = "12x",
            ),
        )

        assertFalse(result.isValid)
        assertNull(result.options)
        assertEquals(
            mapOf(
                ModelRequestOptionField.TEMPERATURE to ModelRequestOptionError.NOT_A_NUMBER,
                ModelRequestOptionField.TOP_K to ModelRequestOptionError.NOT_A_NUMBER,
                ModelRequestOptionField.SEED to ModelRequestOptionError.NOT_A_NUMBER,
            ),
            result.fieldErrors,
        )
    }

    @Test
    fun outOfRangeFieldsAreReportedPerField() {
        val result = parseModelRequestOptions(
            ModelRequestOptionsDraft(
                temperature = "2.5",
                topP = "-0.1",
                topK = "0",
                maxOutputTokens = "-3",
                presencePenalty = "2.1",
                frequencyPenalty = "-2.1",
                seed = "-1",
            ),
        )

        assertFalse(result.isValid)
        assertNull(result.options)
        assertEquals(
            mapOf(
                ModelRequestOptionField.TEMPERATURE to ModelRequestOptionError.OUT_OF_RANGE,
                ModelRequestOptionField.TOP_P to ModelRequestOptionError.OUT_OF_RANGE,
                ModelRequestOptionField.TOP_K to ModelRequestOptionError.OUT_OF_RANGE,
                ModelRequestOptionField.MAX_OUTPUT_TOKENS to ModelRequestOptionError.OUT_OF_RANGE,
                ModelRequestOptionField.PRESENCE_PENALTY to ModelRequestOptionError.OUT_OF_RANGE,
                ModelRequestOptionField.FREQUENCY_PENALTY to ModelRequestOptionError.OUT_OF_RANGE,
            ),
            result.fieldErrors,
        )
    }

    @Test
    fun boundaryValuesAreAccepted() {
        val result = parseModelRequestOptions(
            ModelRequestOptionsDraft(
                temperature = "0",
                topP = "1",
                topK = "1",
                maxOutputTokens = "1",
                presencePenalty = "-2",
                frequencyPenalty = "2",
                seed = "-9223372036854775808",
            ),
        )

        assertTrue(result.isValid)
        assertEquals(
            ModelRequestOptions(
                temperature = 0.0,
                topP = 1.0,
                topK = 1,
                maxOutputTokens = 1,
                presencePenalty = -2.0,
                frequencyPenalty = 2.0,
                seed = Long.MIN_VALUE,
            ),
            result.options,
        )
    }

    @Test
    fun parsesCustomBodyTopLevelKeys() {
        assertEquals(
            CustomBodyParseResult.Success(
                listOf(
                    CustomBody("temperature", JsonPrimitive(0.7)),
                    CustomBody("metadata", buildJsonObject { put("tag", JsonPrimitive("x")) }),
                    CustomBody("stream", JsonPrimitive(true)),
                ),
            ),
            parseCustomBodyJson("""{"temperature": 0.7, "metadata": {"tag": "x"}, "stream": true}"""),
        )
    }

    @Test
    fun blankCustomBodyTextIsAnEmptyList() {
        assertEquals(CustomBodyParseResult.Success(emptyList()), parseCustomBodyJson(""))
        assertEquals(CustomBodyParseResult.Success(emptyList()), parseCustomBodyJson("  \n "))
    }

    @Test
    fun emptyCustomBodyFormatsToBlankText() {
        assertEquals("", formatCustomBodyJson(emptyList()))
    }

    @Test
    fun customBodyJsonRoundTripsThroughFormatting() {
        val body = listOf(
            CustomBody("temperature", JsonPrimitive(0.7)),
            CustomBody("metadata", buildJsonObject { put("tag", JsonPrimitive("x")) }),
            CustomBody("stream", JsonPrimitive(true)),
        )

        assertEquals(
            CustomBodyParseResult.Success(body),
            parseCustomBodyJson(formatCustomBodyJson(body)),
        )
    }

    @Test
    fun nonObjectJsonIsRejected() {
        assertEquals(CustomBodyParseResult.Invalid, parseCustomBodyJson("[1, 2]"))
        assertEquals(CustomBodyParseResult.Invalid, parseCustomBodyJson("\"text\""))
        assertEquals(CustomBodyParseResult.Invalid, parseCustomBodyJson("42"))
    }

    @Test
    fun malformedJsonIsRejected() {
        assertEquals(CustomBodyParseResult.Invalid, parseCustomBodyJson("""{"a": }"""))
        assertEquals(CustomBodyParseResult.Invalid, parseCustomBodyJson("{not json}"))
    }
}
