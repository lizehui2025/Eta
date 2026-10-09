package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDataContentParserTest {
    @Test
    fun `provider exception is not treated as an empty result`() {
        assertTrue(
            PersonalDataContentParser.hasProviderFailure(
                stdout = "",
                stderr = "Error while accessing provider:media\njava.lang.IllegalArgumentException: Invalid column display_name",
            ),
        )
        assertFalse(
            PersonalDataContentParser.hasProviderFailure(
                stdout = "No result found.",
                stderr = "",
            ),
        )
    }

    @Test
    fun classifiesPermissionDenialAndMissingSourceInsteadOfOneUnavailableCode() {
        assertEquals(
            "PERMISSION_DENIED",
            PersonalDataContentParser.classifyFailure(
                stdout = "",
                stderr = "Error while accessing provider:sms\n" +
                    "java.lang.SecurityException: Permission Denial: reading content://sms",
            ),
        )
        assertEquals(
            "SOURCE_MISSING",
            PersonalDataContentParser.classifyFailure(
                stdout = "",
                stderr = "Error while accessing provider:media\n" +
                    "java.lang.IllegalArgumentException: Unknown URL content://media/external/file",
            ),
        )
        // 识别不出原因时返回 null，由调用方回退旧码；空结果（No result found.）不算失败。
        assertNull(PersonalDataContentParser.classifyFailure("No result found.", ""))
    }

    @Test
    fun parsesOnlyDeclaredColumnsWithoutSplittingMessageBody() {
        val rows = PersonalDataContentParser.parseRows(
            "Row: 0 _id=7, address=1069, body=会议地点改到 A, B 两区, date=123",
            listOf("_id", "address", "body", "date"),
        )

        assertEquals(1, rows.size)
        assertEquals("会议地点改到 A, B 两区", rows.single().getString("body"))
        assertEquals("123", rows.single().getString("date"))
        assertFalse(rows.single().has("unknown"))
    }
}
