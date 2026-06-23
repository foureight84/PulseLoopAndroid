package com.pulseloop.coach.orchestration

import com.pulseloop.coach.schema.CoachResponse
import com.pulseloop.coach.schema.CoachResponseType
import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from CoachSchemaTests in CoachTests.swift.
 * Tests coach response parsing, JSON repair, and schema validation.
 */
class CoachResponseParserTest {

    private fun validResponseJSON(title: String = "Today"): String = """
        {"response_type":"insight","title":"$title","summary":"You did well.","bullets":["A","B"],"chart":null,"safety_note":null,"data_quality_note":null,"sources":[],"follow_up_chips":["More"],"actions_taken":[],"confidence":"high"}
    """.trimIndent()

    @Test
    fun testDecodeValidResponse() {
        val json = validResponseJSON()
        val result = CoachResponseParser.parse(json)
        assertNotNull("Valid JSON should parse", result)
        assertEquals(CoachResponseType.INSIGHT, result!!.responseType)
        assertEquals("Today", result.title)
        assertEquals(listOf("A", "B"), result.bullets)
        assertEquals("high", result.confidence.name.lowercase())
    }

    @Test
    fun testRejectsMissingRequiredField() {
        val result = CoachResponseParser.parse("""{"title":"x"}""")
        assertNull("JSON missing required fields should fail to parse", result)
    }

    @Test
    fun testParserStripsCodeFenceAndProse() {
        val fenced = "```json\n${validResponseJSON()}\n```"
        assertNotNull("Code-fenced JSON should parse", CoachResponseParser.parse(fenced))

        val prose = "Here you go:\n${validResponseJSON()}\nHope that helps!"
        assertNotNull("JSON embedded in prose should parse", CoachResponseParser.parse(prose))
    }

    @Test
    fun testParserHandlesWhitespace() {
        val withWhitespace = "  \n${validResponseJSON()}\n  "
        assertNotNull("JSON with surrounding whitespace should parse", CoachResponseParser.parse(withWhitespace))
    }

    @Test
    fun testEmptyStringReturnsNull() {
        assertNull(CoachResponseParser.parse(""))
        assertNull(CoachResponseParser.parse("   "))
    }

    @Test
    fun testJsonArrayReturnsNull() {
        assertNull(CoachResponseParser.parse("""[{"title":"x"}]"""))
    }

    // ── CoachFallbacks ──────────────────────────────────────────────────

    @Test
    fun testFallbackHasMeaningfulContent() {
        val result = CoachFallbacks.fallback()
        assertEquals(CoachResponseType.ERROR_RECOVERY, result.responseType)
        assertTrue(result.summary.isNotEmpty())
        assertTrue(result.followUpChips.isNotEmpty())
    }

    @Test
    fun testParseErrorHasMeaningfulContent() {
        val result = CoachFallbacks.parseError()
        assertEquals(CoachResponseType.ERROR_RECOVERY, result.responseType)
        assertTrue(result.title.isNotEmpty())
        assertTrue(result.followUpChips.isNotEmpty())
    }
}
