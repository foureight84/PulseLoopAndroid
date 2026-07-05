package com.pulseloop.coach.config

import com.pulseloop.coach.gemini.GeminiClient
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

    // ── Active model ────────────────────────────────────────────────────

    @Test
    fun testActiveModelFollowsProvider() {
        val s = CoachProviderSettings(
            providerMode = CoachProviderMode.USER_GEMINI_KEY,
            geminiModel = "gemini-2.5-pro",
            openRouterModel = "openai/gpt-5.5",
        )
        assertEquals("gemini-2.5-pro", CoachClientResolver.activeModel(s, "gpt-5.4"))
        assertEquals("openai/gpt-5.5", CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.USER_OPENROUTER_KEY), "gpt-5.4"))
        assertEquals("gpt-5.4", CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.USER_OPENAI_KEY), "gpt-5.4"))
        assertEquals(OpenAIModel.DEFAULT.slug, CoachClientResolver.activeModel(
            s.copy(providerMode = CoachProviderMode.USER_OPENAI_KEY), ""))
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
