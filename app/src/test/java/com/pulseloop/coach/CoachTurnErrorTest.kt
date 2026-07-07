package com.pulseloop.coach.orchestration

import com.pulseloop.coach.openai.ResponsesError
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
