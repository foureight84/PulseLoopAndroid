package com.pulseloop.coach

import com.pulseloop.coach.config.CoachModelCatalog
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.ReasoningEffort
import com.pulseloop.coach.config.ReasoningEfforts
import org.junit.Assert.*
import org.junit.Test

/** The live model listings (OpenAI / Gemini / OpenRouter / MiniMax) and the per-model reasoning levels. */
class CoachModelCatalogTest {

    // ── OpenAI ──────────────────────────────────────────────────────────

    @Test
    fun `openai keeps chat models and drops everything else`() {
        val body = """{"object":"list","data":[
            {"id":"gpt-6.1-sol"},{"id":"gpt-6-luna"},{"id":"o5"},{"id":"o5-mini"},
            {"id":"gpt-6.1-sol-2026-09-30"},{"id":"text-embedding-4-large"},{"id":"gpt-6-realtime"},
            {"id":"gpt-image-2"},{"id":"gpt-6-mini-tts"},{"id":"whisper-1"},{"id":"omni-moderation-latest"},
            {"id":"gpt-4o"},{"id":"dall-e-3"},{"id":"gpt-6-luna"}
        ]}"""
        assertEquals(
            listOf("gpt-6-luna", "gpt-6.1-sol", "o5", "o5-mini"),
            CoachModelCatalog.parseOpenAI(body).map { it.id },
        )
    }

    // ── Gemini ──────────────────────────────────────────────────────────

    @Test
    fun `gemini keeps generateContent models and strips the models prefix`() {
        val body = """{"models":[
            {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-flash-latest","displayName":"Gemini Flash Latest","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-embedding-002","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-3.8-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/imagen-5","supportedGenerationMethods":["predict"]}
        ],"nextPageToken":"abc"}"""
        val (entries, next) = CoachModelCatalog.parseGeminiPage(body)
        assertEquals(listOf("gemini-3.8-flash", "gemini-flash-latest"), entries.map { it.id })
        assertEquals("Gemini 3.8 Flash", entries.first().name)
        assertEquals("abc", next)
        assertNull(CoachModelCatalog.parseGeminiPage("""{"models":[]}""").second)
    }

    // ── OpenRouter ──────────────────────────────────────────────────────

    /** Trimmed from openrouter.ai/api/v1/models on 2026-10-14. */
    private val openRouterBody = """{"data":[
        {"id":"anthropic/claude-haiku-5.5","name":"Anthropic: Claude Haiku 5.5",
         "supported_parameters":["reasoning","reasoning_effort","tools","structured_outputs"],
         "reasoning":{"mandatory":false,"supported_efforts":["max","xhigh","high","medium","low"],"default_effort":"medium"},
         "expiration_date":null},
        {"id":"anthropic/claude-haiku-5.5:batch","name":"batch","supported_parameters":["tools"]},
        {"id":"deepseek/deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","supported_parameters":["reasoning","tools"],
         "reasoning":{"supported_efforts":["max","high","low"]}},
        {"id":"xiaomi/mimo-v2.5","name":"MiMo V2.5","supported_parameters":["tools"],"expiration_date":"2026-10-21"},
        {"id":"some/vision-only","name":"No tools","supported_parameters":["max_tokens"]}
    ]}"""

    @Test
    fun `openrouter keeps tool-capable models with their levels and expiry`() {
        val entries = CoachModelCatalog.parseOpenRouter(openRouterBody).associateBy { it.id }
        assertEquals(setOf("anthropic/claude-haiku-5.5", "deepseek/deepseek-v4.1-flash", "xiaomi/mimo-v2.5"), entries.keys)
        assertEquals(ReasoningEffort.LOW_TO_MAX, entries.getValue("anthropic/claude-haiku-5.5").reasoningEfforts)
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            entries.getValue("deepseek/deepseek-v4.1-flash").reasoningEfforts,
        )
        // No `reasoning` parameter at all: the model takes no effort hint.
        assertEquals(emptyList<ReasoningEffort>(), entries.getValue("xiaomi/mimo-v2.5").reasoningEfforts)
        assertEquals("2026-10-21", entries.getValue("xiaomi/mimo-v2.5").expiresOn)
        assertNull(entries.getValue("anthropic/claude-haiku-5.5").expiresOn)
    }

    // ── Unlisted (retired) models ───────────────────────────────────────

    @Test
    fun `a stored model the listing omits is flagged, except aliases and snapshots`() {
        val openAI = CoachModelCatalog.Result.Success(CoachModelCatalog.parseOpenAI("""{"data":[{"id":"gpt-6.1-sol"}]}"""), 0)
        assertTrue(CoachModelCatalog.isUnlisted(CoachProviderMode.USER_OPENAI_KEY, openAI, "gpt-5.4"))
        assertFalse(CoachModelCatalog.isUnlisted(CoachProviderMode.USER_OPENAI_KEY, openAI, "gpt-6.1-sol"))
        assertFalse(CoachModelCatalog.isUnlisted(CoachProviderMode.USER_OPENAI_KEY, openAI, "gpt-6.1-sol-2026-09-30"))
        val gemini = CoachModelCatalog.Result.Success(listOf(CoachModelCatalog.Entry("gemini-3.8-flash")), 0)
        assertFalse(CoachModelCatalog.isUnlisted(CoachProviderMode.USER_GEMINI_KEY, gemini, "gemini-flash-latest"))
        assertTrue(CoachModelCatalog.isUnlisted(CoachProviderMode.USER_GEMINI_KEY, gemini, "gemini-2.0-flash"))
    }

    @Test
    fun `minimax listing reads the openai envelope`() {
        assertEquals(
            listOf("MiniMax-M2.1", "MiniMax-M3"),
            CoachModelCatalog.parseIds("""{"data":[{"id":"MiniMax-M3"},{"id":"MiniMax-M2.1"}]}""").map { it.id },
        )
    }

    // ── Reasoning levels ────────────────────────────────────────────────

    @Test
    fun `presets decide the levels, and an unsupported stored level is dropped`() {
        val openAI = CoachProviderMode.USER_OPENAI_KEY
        assertEquals("none", ReasoningEfforts.resolve(openAI, "gpt-6-luna", "none"))
        assertNull(ReasoningEfforts.resolve(openAI, "gpt-6.1-sol", "none"))
        assertEquals("xhigh", ReasoningEfforts.resolve(openAI, "gpt-6.1-sol", "xhigh"))
        // Unknown model: pass the hint through, offer the common levels.
        assertEquals("xhigh", ReasoningEfforts.resolve(openAI, "gpt-7-preview", "xhigh"))
        assertEquals(ReasoningEffort.COMMON, ReasoningEfforts.options(openAI, "gpt-7-preview"))
        assertNull(ReasoningEfforts.resolve(openAI, "gpt-6.1-sol", null))
    }

    @Test
    fun `openrouter uses the live levels for a model outside the presets`() {
        val live = CoachModelCatalog.parseOpenRouter(openRouterBody).first { it.id == "xiaomi/mimo-v2.5" }
        val or = CoachProviderMode.USER_OPENROUTER_KEY
        assertTrue(ReasoningEfforts.options(or, live.id, live).isEmpty())
        assertNull(ReasoningEfforts.resolve(or, live.id, "high", live))
        // DeepSeek has no `medium`.
        assertNull(ReasoningEfforts.resolve(or, "deepseek/deepseek-v4.1-flash", "medium"))
    }

    @Test
    fun `gemini levels only for version 3 and up`() {
        val gemini = CoachProviderMode.USER_GEMINI_KEY
        assertNull(ReasoningEfforts.resolve(gemini, "gemini-2.5-flash", "high"))
        assertTrue(ReasoningEfforts.options(gemini, "gemini-2.5-pro").isEmpty())
        assertEquals("high", ReasoningEfforts.resolve(gemini, "gemini-4-argon", "high"))
        assertEquals("minimal", ReasoningEfforts.resolve(gemini, "gemini-flash-lite-latest", "minimal"))
        assertNull(ReasoningEfforts.resolve(gemini, "gemini-flash-latest", "minimal"))
        assertNull(ReasoningEfforts.resolve(gemini, "gemini-flash-latest", "xhigh"))
    }

    /** Review: offline / proxy send OpenAI requests, so they keep the OpenAI rules. */
    @Test
    fun `offline and proxy modes resolve like openai`() {
        assertEquals("high", ReasoningEfforts.resolve(CoachProviderMode.OFFLINE_STUB, "gpt-6.1-sol", "high"))
        assertNull(ReasoningEfforts.resolve(CoachProviderMode.BACKEND_PROXY, "gpt-6.1-sol", "none"))
    }

    @Test
    fun `minimax presets are never flagged, a retired family is`() {
        val listing = CoachModelCatalog.Result.Success(listOf(CoachModelCatalog.Entry("MiniMax-M3")), 0)
        assertFalse(CoachModelCatalog.isUnlisted(CoachProviderMode.USER_MINIMAX_KEY, listing, "MiniMax-M2.1-highspeed"))
        assertTrue(CoachModelCatalog.isUnlisted(CoachProviderMode.USER_MINIMAX_KEY, listing, "MiniMax-M2.7"))
    }

    @Test
    fun `providers that send no reasoning field hide the picker`() {
        assertTrue(ReasoningEfforts.options(CoachProviderMode.USER_MINIMAX_KEY, "MiniMax-M3").isEmpty())
        assertTrue(ReasoningEfforts.options(CoachProviderMode.LOCAL_OPENAI_COMPAT, "qwen3:8b").isEmpty())
    }
}
