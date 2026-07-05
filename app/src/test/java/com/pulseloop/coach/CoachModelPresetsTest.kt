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
    fun testGeminiDefaultIsFlash25() {
        assertEquals("gemini-2.5-flash", GeminiModel.DEFAULT.slug)
    }

    @Test
    fun testOpenAIDefaultMatchesLegacyStoreDefault() {
        // ApiKeyStore.model defaults to "gpt-5.4"; the preset default must agree.
        assertEquals("gpt-5.4", OpenAIModel.DEFAULT.slug)
    }
}
