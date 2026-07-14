package com.pulseloop.coach.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the curated model-preset slugs ported from CoachSettings.swift
 * (incl. the iOS PR #40 DeepSeek slug correction).
 */
class CoachModelPresetsTest {

    @Test
    fun testDeepSeekPresetUsesV4FlashSlug() {
        assertEquals("deepseek/deepseek-v4-flash", OpenRouterModel.DEEPSEEK_V4.slug)
    }

    @Test
    fun testOpenRouterDefaultIsClaudeSonnet() {
        assertEquals("anthropic/claude-sonnet-4.6", OpenRouterModel.DEFAULT.slug)
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
    fun testOpenAIDefaultMatchesLegacyStoreDefault() {
        // ApiKeyStore.model defaults to "gpt-5.4"; the preset default must agree.
        assertEquals("gpt-5.4", OpenAIModel.DEFAULT.slug)
    }
}
