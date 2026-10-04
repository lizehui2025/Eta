package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRetryHintsTest {
    @Test
    fun modelFixableErrorsMapToFixArguments() {
        assertEquals(AgentToolRetryHints.FIX_ARGUMENTS, AgentToolRetryHints.forCode("INVALID_TOOL_ARGUMENTS"))
        assertEquals(AgentToolRetryHints.FIX_ARGUMENTS, AgentToolRetryHints.forCode("TRUNCATED_TOOL_CALL"))
    }

    @Test
    fun loopAndConflictErrorsMapToChangeStrategy() {
        assertEquals(AgentToolRetryHints.CHANGE_STRATEGY, AgentToolRetryHints.forCode("NO_PROGRESS_LOOP"))
        assertEquals(AgentToolRetryHints.CHANGE_STRATEGY, AgentToolRetryHints.forCode("FILE_BUSY"))
        assertEquals(AgentToolRetryHints.CHANGE_STRATEGY, AgentToolRetryHints.forCode("WRITE_NOT_DECLARED"))
    }

    @Test
    fun externalAndPermissionErrorsMapToRetryOrReport() {
        assertEquals(AgentToolRetryHints.RETRY_OR_REPORT, AgentToolRetryHints.forCode("TOOL_ERROR"))
        assertEquals(AgentToolRetryHints.RETRY_OR_REPORT, AgentToolRetryHints.forCode("ROOT_REQUIRED"))
        assertEquals(AgentToolRetryHints.RETRY_OR_REPORT, AgentToolRetryHints.forCode("TOOL_REVIEW_REJECTED"))
    }

    @Test
    fun unknownErrorsDefaultToReportOnlyWithUserFacingInstruction() {
        assertEquals(AgentToolRetryHints.REPORT_ONLY, AgentToolRetryHints.forCode("SOMETHING_NEW"))
        assertTrue(AgentToolRetryHints.instruction("SOMETHING_NEW").contains("如实报告"))
    }
}
