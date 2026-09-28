package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server that states "this protocol is unsupported" must land on ENPOINT_PROTOCOL_MISMATCH:
 * that is the only route turning an HTTP 400 into "retry the other protocol" (see
 * ProviderEndpointFallback for the rationale). The boundary is locked here as well — an ordinary
 * bad request must never be read as "wrong endpoint", or a request that could have succeeded
 * would be routed elsewhere.
 */
class AgentModelFailureProtocolTest {
    /** The exact body a gateway returned in the field, which motivated the detection. */
    private val explicitProtocolRejection =
        """{"type":"error","error":{"type":"ModelProtocolUnsupported","message":"Model does not support this protocol."}}"""

    @Test
    fun explicitProtocolRejectionIsSwitchable() {
        val failure = AgentModelFailure.http(400, explicitProtocolRejection)
        assertEquals(AgentModelFailure.CODE_ENDPOINT_PROTOCOL_MISMATCH, failure.code)
        assertFalse("same endpoint would fail again, so it must not be retried", failure.retryable)
        assertTrue(ProviderEndpointFallback.isSwitchable(failure))
    }

    @Test
    fun unprocessableEntityWithTheSameMarkerIsAlsoSwitchable() {
        val failure = AgentModelFailure.http(422, explicitProtocolRejection)
        assertEquals(AgentModelFailure.CODE_ENDPOINT_PROTOCOL_MISMATCH, failure.code)
        assertTrue(ProviderEndpointFallback.isSwitchable(failure))
    }

    @Test
    fun ordinaryBadRequestStaysOrdinary() {
        val failure = AgentModelFailure.http(
            400,
            """{"error":{"type":"invalid_request_error","message":"Unsupported parameter: 'temperature' is not supported with this model."}}""",
        )
        assertEquals("HTTP_400", failure.code)
        assertFalse("a plain bad request must not trigger an endpoint switch", ProviderEndpointFallback.isSwitchable(failure))
    }
}
