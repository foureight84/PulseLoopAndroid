package com.pulseloop.coach.config

/**
 * Ported from [GeminiModel] in CoachSettings.swift.
 * Preset Gemini model choices surfaced in Settings.
 */
enum class GeminiModel(val slug: String, val blurb: String) {
    // Rolling `-latest` aliases (docs: ai.google.dev/gemini-api/docs/models) point at the most
    // recent release for each variation and are updated with a two-week notice — so the presets
    // never go stale again (issue #22). Current generation behind them: 3.5 Flash / 3.1 Flash-Lite.
    FLASH_LATEST("gemini-flash-latest", "Fast & capable (default)"),
    FLASH_LITE_LATEST("gemini-flash-lite-latest", "Fastest & lowest cost"),
    PRO_LATEST("gemini-pro-latest", "Best reasoning");

    val label: String get() = slug

    companion object {
        val DEFAULT = FLASH_LATEST

        /**
         * Remap a retired 2.x preset slug to the current rolling alias so a user stored on a
         * now-deprecated model isn't stuck serving 404s (issue #22 — "model old"). Applied on
         * read in [CoachProviderSettingsStore.geminiModel]. A slug we don't recognise (a model the
         * user typed elsewhere, or one already current) passes through untouched.
         */
        fun migrateSlug(slug: String): String = when (slug) {
            "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash" -> FLASH_LATEST.slug
            "gemini-2.5-flash-lite", "gemini-2.0-flash-lite" -> FLASH_LITE_LATEST.slug
            "gemini-2.5-pro", "gemini-1.5-pro" -> PRO_LATEST.slug
            else -> slug
        }
    }
}

/**
 * Ported from [OpenRouterModel] in CoachSettings.swift.
 * Preset OpenRouter model slugs surfaced in Settings. OpenRouter routes a
 * `vendor/model` slug to the underlying provider, and its catalog is large and
 * changes often — so the stored model stays a free string and Settings also
 * exposes a "Custom…" text field where the user can type any current slug from
 * openrouter.ai/models. These are just the curated picks.
 */
enum class OpenRouterModel(val slug: String, val blurb: String) {
    CLAUDE_SONNET("anthropic/claude-sonnet-4.6", "Balanced, great for coaching (default)"),
    CLAUDE_OPUS("anthropic/claude-opus-4.7", "Most capable Claude"),
    GPT_55("openai/gpt-5.5", "OpenAI flagship"),
    GPT_54_MINI("openai/gpt-5.4-mini", "Lower cost & latency"),
    GEMINI_FLASH("google/gemini-2.5-flash", "Fast & capable"),
    GEMINI_PRO("google/gemini-2.5-pro", "Deep reasoning"),
    DEEPSEEK_V4("deepseek/deepseek-v4-flash", "Strong open reasoning, low cost");

    val label: String get() = slug

    companion object {
        /** Sensible default when switching to OpenRouter (strong
         *  instruction-following and structured-output support via OpenRouter). */
        val DEFAULT = CLAUDE_SONNET
    }
}

/**
 * Ported from [CoachModel] in CoachSettings.swift.
 * Preset OpenAI model choices. The stored model (`ApiKeyStore.model`) is a free
 * string (so a new model can be typed/served without a code change); these are
 * just the curated picks surfaced in Settings.
 */
enum class OpenAIModel(val slug: String, val blurb: String) {
    GPT_54_MINI("gpt-5.4-mini", "Lower cost & latency"),
    GPT_54("gpt-5.4", "Balanced (default)"),
    GPT_55("gpt-5.5", "Best reasoning");

    val label: String get() = slug

    companion object {
        val DEFAULT = GPT_54
    }
}
