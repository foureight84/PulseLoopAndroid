package com.pulseloop.coach.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the curated model-preset slugs ported from CoachSettings.swift
 * (incl. the iOS PR #40 DeepSeek slug correction).
 */
class CoachModelPresetsTest {

    @Test
    fun testDeepSeekPresetUsesV41FlashSlug() {
        assertEquals("deepseek/deepseek-v4.1-flash", OpenRouterModel.DEEPSEEK.slug)
    }

    @Test
    fun testOpenRouterDefaultIsClaudeSonnet() {
        assertEquals("anthropic/claude-sonnet-5.5", OpenRouterModel.DEFAULT.slug)
    }

    /** iOS #140: MiniMax closes M2.5/M2.7 to new users Oct 19 2026 and retires them Nov 18. */
    @Test
    fun testRetiringMiniMaxFamiliesAreNotOffered() {
        MiniMaxModel.entries.forEach {
            assertFalse(it.slug, it.slug.startsWith("MiniMax-M2.5") || it.slug.startsWith("MiniMax-M2.7"))
        }
    }

    @Test
    fun testEveryReasoningPresetListsAtLeastOneLevel() {
        OpenAIModel.entries.forEach { assertTrue(it.slug, it.reasoningEfforts.isNotEmpty()) }
        OpenRouterModel.entries.forEach { assertTrue(it.slug, it.reasoningEfforts.isNotEmpty()) }
        GeminiModel.entries.forEach { assertTrue(it.slug, it.reasoningEfforts.isNotEmpty()) }
    }

    @Test
    fun testOpenRouterPresetSlugsAreVendorQualified() {
        for (preset in OpenRouterModel.entries) {
            assertTrue("${preset.slug} should be vendor/model", preset.slug.contains("/"))
        }
    }

    @Test
    fun testGeminiDefaultIsFlashLatestAlias() {
        // Issue #22: presets moved to Google's rolling `-latest` aliases so they never go stale.
        assertEquals("gemini-flash-latest", GeminiModel.DEFAULT.slug)
    }

    @Test
    fun testRetiredGeminiSlugsMigrateToCurrentAliases() {
        assertEquals("gemini-flash-latest", GeminiModel.migrateSlug("gemini-2.5-flash"))
        assertEquals("gemini-flash-latest", GeminiModel.migrateSlug("gemini-2.0-flash"))
        assertEquals("gemini-flash-lite-latest", GeminiModel.migrateSlug("gemini-2.5-flash-lite"))
        assertEquals("gemini-pro-latest", GeminiModel.migrateSlug("gemini-2.5-pro"))
    }

    @Test
    fun testUnknownOrCurrentGeminiSlugPassesThroughMigration() {
        // A current alias or a slug the user set elsewhere must not be rewritten.
        assertEquals("gemini-flash-latest", GeminiModel.migrateSlug("gemini-flash-latest"))
        assertEquals("gemini-3.5-flash", GeminiModel.migrateSlug("gemini-3.5-flash"))
    }

    @Test
    fun testOpenAIDefaultIsSol() {
        // iOS #140. ApiKeyStore.model reads a blank store as this default.
        assertEquals("gpt-6.1-sol", OpenAIModel.DEFAULT.slug)
    }

    /** Issue #77: a stored retired slug must not strand the user on a model the picker no longer offers. */
    @Test
    fun `retired OpenAI slugs normalize to the default`() {
        for (slug in listOf("gpt-4o", "gpt-4o-mini", "o4-mini", "")) {
            assertEquals(OpenAIModel.DEFAULT.slug, OpenAIModel.normalize(slug))
        }
        assertEquals("gpt-5.5", OpenAIModel.normalize("gpt-5.5"))
        // A typed/unknown slug is the user's choice — only the retired set is rewritten.
        assertEquals("gpt-6-preview", OpenAIModel.normalize("gpt-6-preview"))
    }

    @Test
    fun `no preset is a retired slug`() {
        OpenAIModel.entries.forEach { assertFalse(it.slug in OpenAIModel.RETIRED_SLUGS) }
    }
}
