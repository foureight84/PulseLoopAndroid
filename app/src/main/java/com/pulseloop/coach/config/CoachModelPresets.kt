package com.pulseloop.coach.config

/**
 * Ported from [GeminiModel] in CoachSettings.swift.
 * Preset Gemini model choices surfaced in Settings.
 */
enum class GeminiModel(val slug: String, val blurb: String) {
    FLASH_25("gemini-2.5-flash", "Fast & capable (default)"),
    FLASH_20("gemini-2.0-flash", "Previous generation"),
    PRO_25("gemini-2.5-pro", "Best reasoning");

    val label: String get() = slug

    companion object {
        val DEFAULT = FLASH_25
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
