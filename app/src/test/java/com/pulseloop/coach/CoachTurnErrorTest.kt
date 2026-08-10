package com.pulseloop.coach.orchestration

import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.ResponsesHttp
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the error taxonomy ported from CoachTurnError.swift: mapping thrown
 * `ResponsesError`s to displayable code/reason pairs plus the JSON persistence.
 */
class CoachTurnErrorTest {

    @Test
    fun testMissingApiKeyMapsToNoApiKeyCode() {
        val e = CoachTurnError.from(ResponsesError.MissingAPIKey)
        assertEquals("No API key", e.code)
        assertTrue(e.reason.contains("Settings"))
    }

    @Test
    fun testHttpErrorExtractsProviderMessage() {
        // OpenAI/OpenRouter shape: {"error":{"message":"..."}}
        val body = """{"error":{"message":"Rate limit exceeded","type":"rate_limit"}}"""
        val e = CoachTurnError.from(ResponsesError.Http(429, body))
        assertEquals("HTTP 429", e.code)
        assertEquals("Rate limit exceeded", e.reason)
    }

    @Test
    fun testHttpErrorHandlesStringErrorAndTopLevelMessage() {
        assertEquals("boom", CoachTurnError.from(ResponsesError.Http(500, """{"error":"boom"}""")).reason)
        assertEquals("nope", CoachTurnError.from(ResponsesError.Http(500, """{"message":"nope"}""")).reason)
    }

    @Test
    fun testHttpErrorFallsBackToRawBodyAndEmptyPlaceholder() {
        assertEquals("plain text failure",
            CoachTurnError.from(ResponsesError.Http(502, "plain text failure")).reason)
        assertEquals("The provider returned HTTP 502 with no details.",
            CoachTurnError.from(ResponsesError.Http(502, "   ")).reason)
    }

    @Test
    fun testDecodingAndEmptyOutputMapping() {
        val decoding = CoachTurnError.from(ResponsesError.Decoding("bad json"))
        assertEquals("Response error", decoding.code)
        assertEquals("bad json", decoding.reason)

        val empty = CoachTurnError.from(ResponsesError.EmptyOutput)
        assertEquals("No output", empty.code)
    }

    @Test
    fun testTransportMapping() {
        val e = CoachTurnError.from(ResponsesError.Transport(RuntimeException("timeout")))
        assertEquals("Network", e.code)
        assertEquals("timeout", e.reason)
    }

    /**
     * The real-world failure this copy exists for: a Pixel with an active VPN produced
     * `Unable to resolve host "generativelanguage.googleapis.com": No address associated with
     * hostname` verbatim in the chat bubble. The bubble must name the host and point at the cause.
     */
    @Test
    fun testUnknownHostNamesTheHostAndSuggestsVpnOrDns() {
        val underlying = java.net.UnknownHostException(
            "Unable to resolve host \"generativelanguage.googleapis.com\": " +
                "No address associated with hostname",
        )
        val e = CoachTurnError.from(ResponsesError.Transport(underlying))
        assertEquals("Network", e.code)
        assertTrue(e.reason.contains("generativelanguage.googleapis.com"))
        assertTrue(e.reason.contains("VPN"))
        assertTrue(e.reason.contains("Private DNS"))
        // The raw text survives for bug reports.
        assertTrue(e.reason.contains("No address associated with hostname"))
    }

    @Test
    fun testSocketTimeoutIsNotJustTheWordTimeout() {
        val e = CoachTurnError.from(ResponsesError.Transport(java.net.SocketTimeoutException("timeout")))
        assertEquals("Network", e.code)
        assertNotEquals("timeout", e.reason)
        assertTrue(e.reason.contains("took too long to answer"))
    }

    /**
     * OkHttp reports connect and read timeouts as the same exception, so the raw message is the
     * only thing that tells a bug report which one happened. Every other transport branch appends
     * it; this one used to drop it.
     */
    @Test
    fun testSocketTimeoutKeepsTheRawTextForBugReports() {
        val connect = CoachTurnError.from(
            ResponsesError.Transport(
                java.net.SocketTimeoutException("failed to connect to api.openai.com after 30000ms"),
            ),
        )
        assertTrue(connect.reason.contains("failed to connect to api.openai.com after 30000ms"))

        val read = CoachTurnError.from(ResponsesError.Transport(java.net.SocketTimeoutException("timeout")))
        assertTrue(read.reason.contains("(timeout)"))
    }

    @Test
    fun testUnknownHostWithoutAQuotedHostStillReads() {
        val e = CoachTurnError.from(ResponsesError.Transport(java.net.UnknownHostException("")))
        assertTrue(e.reason.contains("the provider"))
    }

    /** DNS and TCP connect never reached the provider, so re-sending is free. A read timeout may
     *  have already billed a generation — retrying it would charge the user twice. */
    @Test
    fun testOnlyProvablyUnsentFailuresAreRetryable() {
        assertTrue(ResponsesHttp.isProvablyUnsent(java.net.UnknownHostException("dns")))
        assertTrue(ResponsesHttp.isProvablyUnsent(java.net.ConnectException("refused")))
        assertFalse(ResponsesHttp.isProvablyUnsent(java.net.SocketTimeoutException("timeout")))
        assertFalse(ResponsesHttp.isProvablyUnsent(java.io.IOException("closed")))
    }

    @Test
    fun testUnknownErrorFallsBackToMessage() {
        val e = CoachTurnError.from(IllegalStateException("weird"))
        assertEquals("Error", e.code)
        assertEquals("weird", e.reason)
    }

    @Test
    fun testJsonRoundTripAndPlainText() {
        val original = CoachTurnError(code = "HTTP 401", reason = "Invalid key")
        val json = original.encodedJSON()
        assertNotNull(json)
        assertEquals(original, CoachTurnError.decode(json))
        assertEquals("Coach error · HTTP 401\nInvalid key", original.plainText)
        assertNull(CoachTurnError.decode(null))
        assertNull(CoachTurnError.decode("garbage"))
    }
}
