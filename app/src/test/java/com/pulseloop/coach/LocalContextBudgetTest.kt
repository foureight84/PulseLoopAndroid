package com.pulseloop.coach.local

import org.junit.Assert.*
import org.junit.Test

/**
 * The context-window → Max-tokens derivation. A context window is prompt + completion, so it can
 * never be copied into `max_tokens` directly: the server checks `max_tokens` against what's LEFT
 * after the prompt and rejects the request when the two together overflow.
 */
class LocalContextBudgetTest {

    private fun report(ctx: Int?) = LocalCapabilityProbe.Report(
        engine = LocalCapabilityProbe.Engine.VLLM,
        models = listOf("m"), suggestedModel = "m", contextWindow = ctx,
    )

    @Test
    fun `a huge context is capped, not copied`() {
        // The real server in docs/local-llm-coach.md reports 262144.
        val r = report(262_144)
        assertEquals(LocalCapabilityProbe.MAX_SUGGESTED_TOKENS, r.suggestedMaxTokens)
        assertNotEquals(262_144, r.suggestedMaxTokens)
        assertFalse(r.contextTooSmall)
    }

    @Test
    fun `a mid-size context reserves room for the prompt`() {
        // 16384 - 6144 reserve = 10240 headroom, under the cap so it's used as-is.
        assertEquals(10_240, report(16_384).suggestedMaxTokens)
    }

    @Test
    fun `prompt plus suggested budget always fits the context`() {
        for (ctx in listOf(8_192, 16_384, 32_768, 131_072, 262_144)) {
            val r = report(ctx)
            assertTrue(
                "ctx=$ctx budget=${r.suggestedMaxTokens}",
                LocalCapabilityProbe.PROMPT_RESERVE_TOKENS + r.suggestedMaxTokens <= ctx,
            )
        }
    }

    @Test
    fun `Ollama's 2048 default is flagged rather than budgeted`() {
        // Smaller than the coach's own prompt — the fix is on the server, not in the app.
        val r = report(2_048)
        assertEquals(0, r.suggestedMaxTokens)
        assertTrue(r.contextTooSmall)
    }

    @Test
    fun `an unreported context leaves the field blank and warns about nothing`() {
        val r = report(null)
        assertEquals(0, r.suggestedMaxTokens)
        assertFalse(r.contextTooSmall)
    }

    @Test
    fun `the summary reports the context window`() {
        assertTrue(report(262_144).summary.contains("262k ctx"))
        assertFalse(report(null).summary.contains("ctx"))
    }

    @Test
    fun `token formatting stays literal for small values`() {
        assertEquals("2048", LocalCapabilityProbe.formatTokens(2_048))
        assertEquals("262k", LocalCapabilityProbe.formatTokens(262_144))
    }
}
