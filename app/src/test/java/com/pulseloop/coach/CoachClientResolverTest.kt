package com.pulseloop.coach.config

import com.pulseloop.coach.gemini.GeminiClient
import com.pulseloop.coach.local.LocalOpenAICompatClient
import com.pulseloop.coach.minimax.MiniMaxClient
import com.pulseloop.coach.openai.OpenAIResponse
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openrouter.OpenRouterClient
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the multi-provider selection logic ported from
 * CoachClientResolver.swift + CoachSettings.swift.
 */
class CoachClientResolverTest {

    private class FakeClient(val key: String) : ResponsesClient {
        override suspend fun send(requestBody: ByteArray) = OpenAIResponse()
    }

    private fun settings(mode: CoachProviderMode) = CoachProviderSettings(providerMode = mode)

    // ── Provider selection ──────────────────────────────────────────────

    @Test
    fun testOpenAIModeUsesFactoryAndKey() {
        val res = CoachClientResolver.resolve(
            settings(CoachProviderMode.USER_OPENAI_KEY),
            openAIKey = "sk-test", geminiKey = "g", openRouterKey = "or",
            openAIClientFactory = { FakeClient(it) },
        )
        assertEquals("sk-test", res.key)
        assertEquals("sk-test", (res.client as FakeClient).key)
    }

    @Test
    fun testOfflineStubAndBackendProxyFallBackToOpenAIFactory() {
        for (mode in listOf(CoachProviderMode.OFFLINE_STUB, CoachProviderMode.BACKEND_PROXY)) {
            val res = CoachClientResolver.resolve(
                settings(mode), openAIKey = "sk", geminiKey = null, openRouterKey = null,
                openAIClientFactory = { FakeClient(it) },
            )
            assertTrue("$mode should use the OpenAI factory", res.client is FakeClient)
            assertEquals("sk", res.key)
        }
    }

    @Test
    fun testGeminiModeReturnsGeminiClient() {
        val res = CoachClientResolver.resolve(
            settings(CoachProviderMode.USER_GEMINI_KEY),
            openAIKey = "sk", geminiKey = "gm-key", openRouterKey = null,
        )
        assertEquals("gm-key", res.key)
        assertTrue(res.client is GeminiClient)
    }

    @Test
    fun testOpenRouterModeReturnsOpenRouterClient() {
        val res = CoachClientResolver.resolve(
            settings(CoachProviderMode.USER_OPENROUTER_KEY),
            openAIKey = "sk", geminiKey = null, openRouterKey = "or-key",
        )
        assertEquals("or-key", res.key)
        assertTrue(res.client is OpenRouterClient)
    }

    @Test
    fun testMiniMaxModeReturnsMiniMaxClient() {
        val res = CoachClientResolver.resolve(
            settings(CoachProviderMode.USER_MINIMAX_KEY),
            openAIKey = "sk", geminiKey = null, openRouterKey = null, minimaxKey = "mm-key",
        )
        assertEquals("mm-key", res.key)
        assertTrue(res.client is MiniMaxClient)
    }

    @Test
    fun testMiniMaxMissingKeyYieldsNullSentinelButStillAClient() {
        val res = CoachClientResolver.resolve(
            settings(CoachProviderMode.USER_MINIMAX_KEY),
            openAIKey = "sk", geminiKey = null, openRouterKey = null, minimaxKey = " ",
        )
        assertNull(res.key)
        assertTrue(res.client is MiniMaxClient)
    }

    @Test
    fun testMissingKeyYieldsNullSentinelButStillAClient() {
        // A client is always returned; key == null signals "not ready" so the
        // feature-flags gate can degrade to scripted content.
        val gemini = CoachClientResolver.resolve(
            settings(CoachProviderMode.USER_GEMINI_KEY),
            openAIKey = "sk", geminiKey = "  ", openRouterKey = null,
        )
        assertNull(gemini.key)
        assertTrue(gemini.client is GeminiClient)

        val openRouter = CoachClientResolver.resolve(
            settings(CoachProviderMode.USER_OPENROUTER_KEY),
            openAIKey = "sk", geminiKey = null, openRouterKey = null,
        )
        assertNull(openRouter.key)
        assertTrue(openRouter.client is OpenRouterClient)
    }

    // ── Local / self-hosted (docs/local-llm-coach.md) ───────────────────

    @Test
    fun testLocalModeReadinessIsTheBaseUrlNotTheKey() {
        // The whole point of the provider: a key-less server must still enable the coach.
        val ready = CoachClientResolver.resolve(
            CoachProviderSettings(
                providerMode = CoachProviderMode.LOCAL_OPENAI_COMPAT,
                localBaseUrl = "http://192.168.1.50:11434",
                localModel = "qwen3:8b",
            ),
            openAIKey = null, geminiKey = null, openRouterKey = null, localKey = null,
        )
        assertEquals("http://192.168.1.50:11434", ready.key)
        assertTrue(ready.client is LocalOpenAICompatClient)
    }

    @Test
    fun testLocalModeWithNoBaseUrlIsNotReadyEvenWithAKey() {
        val notReady = CoachClientResolver.resolve(
            CoachProviderSettings(providerMode = CoachProviderMode.LOCAL_OPENAI_COMPAT),
            openAIKey = "sk-test", geminiKey = null, openRouterKey = null, localKey = "sk-local",
        )
        assertNull(notReady.key)
        assertTrue(notReady.client is LocalOpenAICompatClient)
    }

    @Test
    fun testLocalBaseUrlIsTrimmed() {
        val s = CoachProviderSettings(localBaseUrl = "  http://localhost:8080  ", localModel = " m ")
        assertEquals("http://localhost:8080", s.resolvedLocalBaseUrl)
        assertEquals("m", s.resolvedLocalModel)
    }

    @Test
    fun testBlankLocalModelIsNotSubstituted() {
        // llama.cpp ignores `model`; inventing a slug would 404 on the servers that read it.
        assertEquals("", CoachProviderSettings(localModel = "   ").resolvedLocalModel)
    }

    @Test
    fun testLocalStructuredOutputTolerantDecode() {
        for (mode in LocalStructuredOutput.entries) {
            assertEquals(mode, LocalStructuredOutput.fromRaw(mode.rawValue))
        }
        assertEquals(LocalStructuredOutput.OFF, LocalStructuredOutput.fromRaw(null))
        assertEquals(LocalStructuredOutput.OFF, LocalStructuredOutput.fromRaw("grammar"))
    }

    // ── Active model ────────────────────────────────────────────────────

    @Test
    fun testActiveModelFollowsProvider() {
        val s = CoachProviderSettings(
            providerMode = CoachProviderMode.USER_GEMINI_KEY,
            geminiModel = "gemini-2.5-pro",
            openRouterModel = "openai/gpt-5.5",
            minimaxModel = "MiniMax-M2",
        )
        assertEquals("gemini-2.5-pro", CoachClientResolver.activeModel(s, "gpt-5.4"))
        assertEquals("openai/gpt-5.5", CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.USER_OPENROUTER_KEY), "gpt-5.4"))
        assertEquals("MiniMax-M2", CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.USER_MINIMAX_KEY), "gpt-5.4"))
        assertEquals("qwen3:8b", CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.LOCAL_OPENAI_COMPAT, localModel = "qwen3:8b"), "gpt-5.4"))
        assertEquals("gpt-5.4", CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.USER_OPENAI_KEY), "gpt-5.4"))
        assertEquals(OpenAIModel.DEFAULT.slug, CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.USER_OPENAI_KEY), ""))
    }

    @Test
    fun testCoachSettingsKeepNullEqualsOmitContract() {
        // null store/effort must stay null so no `reasoning` field is sent.
        assertNull(CoachClientResolver.coachSettings(null).reasoningEffort)
        assertNull(CoachClientResolver.coachSettings(CoachProviderSettings()).reasoningEffort)
        assertEquals("high", CoachClientResolver.coachSettings(
            CoachProviderSettings(reasoningEffort = "high")).reasoningEffort)
    }

    @Test
    fun testBlankOpenRouterModelFallsBackToDefault() {
        val s = CoachProviderSettings(openRouterModel = "   ")
        assertEquals(OpenRouterModel.DEFAULT.slug, s.resolvedOpenRouterModel)
    }

    @Test
    fun testProviderModeRawRoundTripAndTolerantDecode() {
        for (mode in CoachProviderMode.entries) {
            assertEquals(mode, CoachProviderMode.fromRaw(mode.rawValue))
        }
        assertEquals(CoachProviderMode.USER_OPENAI_KEY, CoachProviderMode.fromRaw(null))
        assertEquals(CoachProviderMode.USER_OPENAI_KEY, CoachProviderMode.fromRaw("appleOnDevice"))
    }
}
