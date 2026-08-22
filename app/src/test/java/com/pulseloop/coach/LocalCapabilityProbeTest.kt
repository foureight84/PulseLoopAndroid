package com.pulseloop.coach.local

import com.pulseloop.coach.config.LocalStructuredOutput
import org.junit.Assert.*
import org.junit.Test

/**
 * The pure decision logic of self-discovery — model choice and how a probe result maps onto a
 * setting. The network sequencing in `run` is covered by the on-device check in
 * `docs/local-llm-coach.md` §5a, not here.
 */
class LocalCapabilityProbeTest {

    private fun report(
        tools: LocalCapabilityProbe.Support = LocalCapabilityProbe.Support.UNKNOWN,
        schema: LocalCapabilityProbe.Support = LocalCapabilityProbe.Support.UNKNOWN,
        obj: LocalCapabilityProbe.Support = LocalCapabilityProbe.Support.UNKNOWN,
    ) = LocalCapabilityProbe.Report(
        engine = LocalCapabilityProbe.Engine.VLLM,
        version = "0.27.1",
        models = listOf("qwen3.8-27b-int8-w8a16-mtp"),
        suggestedModel = "qwen3.8-27b-int8-w8a16-mtp",
        toolCalling = tools, jsonSchema = schema, jsonObject = obj,
    )

    // ── Model choice ─────────────────────────────────────────────────────

    @Test
    fun `a sole served model is chosen automatically`() {
        assertEquals("only", LocalCapabilityProbe.pickModel(listOf("only"), currentModel = ""))
    }

    @Test
    fun `an existing choice is kept when the server still lists it`() {
        assertEquals("b", LocalCapabilityProbe.pickModel(listOf("a", "b", "c"), currentModel = "b"))
    }

    @Test
    fun `several models and no valid current pick leaves the choice to the user`() {
        // Guessing here would silently move a working setup onto a different model.
        assertEquals("", LocalCapabilityProbe.pickModel(listOf("a", "b"), currentModel = ""))
        assertEquals("", LocalCapabilityProbe.pickModel(listOf("a", "b"), currentModel = "gone"))
    }

    @Test
    fun `an empty catalog yields no model`() {
        assertEquals("", LocalCapabilityProbe.pickModel(emptyList(), currentModel = "x"))
    }

    // ── Capability → setting ─────────────────────────────────────────────

    @Test
    fun `tool calling turns off only on an explicit refusal`() {
        assertFalse(report(tools = LocalCapabilityProbe.Support.NO).suggestedToolCalling)
        // An inconclusive probe must not strip the coach of its ability to read the user's data.
        assertTrue(report(tools = LocalCapabilityProbe.Support.UNKNOWN).suggestedToolCalling)
        assertTrue(report(tools = LocalCapabilityProbe.Support.YES).suggestedToolCalling)
    }

    @Test
    fun `the strongest accepted response format wins`() {
        assertEquals(LocalStructuredOutput.JSON_SCHEMA,
            report(schema = LocalCapabilityProbe.Support.YES).suggestedStructuredOutput)
        assertEquals(LocalStructuredOutput.JSON_OBJECT,
            report(schema = LocalCapabilityProbe.Support.NO, obj = LocalCapabilityProbe.Support.YES)
                .suggestedStructuredOutput)
        assertEquals(LocalStructuredOutput.OFF,
            report(schema = LocalCapabilityProbe.Support.NO, obj = LocalCapabilityProbe.Support.NO)
                .suggestedStructuredOutput)
    }

    @Test
    fun `an inconclusive format probe falls back to the mode that needs nothing`() {
        assertEquals(LocalStructuredOutput.OFF, report().suggestedStructuredOutput)
    }

    // ── Whether a suggestion may overwrite a hand-set value ──────────────

    @Test
    fun `an unrun probe is not conclusive, so Detect leaves the setting alone`() {
        // The state after a blank model pick or a failed baseline request. `suggestedToolCalling`
        // is still true here — that default is for a first-time setup, not for a re-detect over a
        // user who deliberately turned tools off for a vLLM server without --enable-auto-tool-choice.
        val r = report()
        assertTrue(r.suggestedToolCalling)
        assertFalse(r.toolCallingConclusive)
        assertEquals(LocalStructuredOutput.OFF, r.suggestedStructuredOutput)
        assertFalse(r.structuredOutputConclusive)
    }

    @Test
    fun `a refusal is conclusive`() {
        val r = report(
            tools = LocalCapabilityProbe.Support.NO,
            schema = LocalCapabilityProbe.Support.NO,
            obj = LocalCapabilityProbe.Support.NO,
        )
        assertTrue(r.toolCallingConclusive)
        assertTrue(r.structuredOutputConclusive)
    }

    @Test
    fun `a strict-schema yes is conclusive even though JSON mode goes untested`() {
        // The weaker mode is deliberately skipped once the stronger one is accepted.
        val r = report(schema = LocalCapabilityProbe.Support.YES)
        assertTrue(r.structuredOutputConclusive)
        assertEquals(LocalStructuredOutput.JSON_SCHEMA, r.suggestedStructuredOutput)
    }

    @Test
    fun `a context window the server never reported suggests nothing`() {
        // 0 means "not detected", which must not clear a Max tokens value the user typed.
        assertEquals(0, report().suggestedMaxTokens)
    }

    @Test
    fun `the summary names the engine, model and both capabilities`() {
        val s = report(
            tools = LocalCapabilityProbe.Support.YES,
            schema = LocalCapabilityProbe.Support.YES,
        ).summary
        assertTrue(s, s.contains("vLLM 0.27.1"))
        assertTrue(s, s.contains("qwen3.8-27b-int8-w8a16-mtp"))
        assertTrue(s, s.contains("tools yes"))
        assertTrue(s, s.contains("strict schema"))
    }

    @Test
    fun `the summary says unknown rather than implying a negative`() {
        val s = report(tools = LocalCapabilityProbe.Support.UNKNOWN).summary
        assertTrue(s, s.contains("tools unknown"))
    }
}
