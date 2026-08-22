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
