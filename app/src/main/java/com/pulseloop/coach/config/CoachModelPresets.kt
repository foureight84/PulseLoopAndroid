package com.pulseloop.coach.config

/**
 * Ported from [ReasoningEffort] in CoachSettings.swift (iOS #140).
 * A `reasoning.effort` level. Which levels a model accepts varies — GPT-6 Sol/Astra reject `none`,
 * DeepSeek has no `medium`, Gemini stops at `high` — and an unsupported level is an HTTP 400 on
 * OpenAI, so each preset lists its own. On Gemini the level is sent as
 * `thinkingConfig.thinkingLevel`.
 */
enum class ReasoningEffort(val rawValue: String, val label: String) {
    NONE("none", "None"),
    MINIMAL("minimal", "Minimal"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    XHIGH("xhigh", "Extra high"),
    MAX("max", "Max");

    companion object {
        /** Levels every reasoning-capable model accepts — offered for a model we know nothing about. */
        val COMMON = listOf(LOW, MEDIUM, HIGH)
        /** GPT-6 Sol/Astra and Claude 5.5. */
        val LOW_TO_MAX = listOf(LOW, MEDIUM, HIGH, XHIGH, MAX)

        fun fromRaw(raw: String?): ReasoningEffort? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Ported from [GeminiModel] in CoachSettings.swift.
 * Preset Gemini model choices surfaced in Settings.
 */
enum class GeminiModel(val slug: String, val blurb: String, val reasoningEfforts: List<ReasoningEffort>) {
    // Rolling `-latest` aliases (docs: ai.google.dev/gemini-api/docs/models) point at the most
    // recent release for each variation and are updated with a two-week notice — so the presets
    // never go stale again (issue #22). Current generation behind them: 3.5 Flash / 3.1 Flash-Lite.
    // Since iOS #140 the targets are 3.8 Flash, 3.5 Flash-Lite and 4 Argon; only Flash-Lite
    // accepts `minimal` as a `thinkingLevel`.
    FLASH_LATEST("gemini-flash-latest", "Fast & capable (default)", ReasoningEffort.COMMON),
    FLASH_LITE_LATEST("gemini-flash-lite-latest", "Fastest & lowest cost", listOf(ReasoningEffort.MINIMAL) + ReasoningEffort.COMMON),
    PRO_LATEST("gemini-pro-latest", "Best reasoning", ReasoningEffort.COMMON);

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
enum class OpenRouterModel(val slug: String, val blurb: String, val reasoningEfforts: List<ReasoningEffort>) {
    // Levels are each model's `reasoning.supported_efforts` in OpenRouter's /api/v1/models.
    CLAUDE_SONNET("anthropic/claude-sonnet-5.5", "Balanced, great for coaching (default)", ReasoningEffort.LOW_TO_MAX),
    CLAUDE_OPUS("anthropic/claude-opus-5.5", "Most capable Claude", ReasoningEffort.LOW_TO_MAX),
    CLAUDE_HAIKU("anthropic/claude-haiku-5.5", "Fastest, lowest-cost Claude", ReasoningEffort.LOW_TO_MAX),
    GPT_SOL("openai/gpt-6.1-sol", "OpenAI balanced flagship", ReasoningEffort.LOW_TO_MAX),
    GPT_LUNA("openai/gpt-6-luna", "Lower cost & latency", listOf(ReasoningEffort.NONE) + ReasoningEffort.LOW_TO_MAX),
    GEMINI_PRO("google/gemini-4-argon", "Deep reasoning", ReasoningEffort.COMMON),
    GEMINI_FLASH("google/gemini-3.8-flash", "Fast & capable", ReasoningEffort.COMMON),
    DEEPSEEK("deepseek/deepseek-v4.1-flash", "Strong open reasoning, low cost", listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX));

    val label: String get() = slug

    companion object {
        /** Sensible default when switching to OpenRouter (strong
         *  instruction-following and structured-output support via OpenRouter). */
        val DEFAULT = CLAUDE_SONNET
    }
}

/**
 * Ported from [MiniMaxModel] in CoachSettings.swift (iOS #54).
 * Preset MiniMax model slugs surfaced in Settings. MiniMax's OpenAI-compatible
 * endpoint takes the model name verbatim; these are the curated picks.
 *
 * The M2.5 and M2.7 families are omitted (iOS #140): MiniMax closes them to new users on Oct 19 2026
 * and retires them on Nov 18 2026. A stored M2.5/M2.7 choice keeps working until then, and the
 * live model list flags it once MiniMax stops listing it.
 */
enum class MiniMaxModel(val slug: String, val blurb: String) {
    M3("MiniMax-M3", "Latest — agentic reasoning, tool use, 1M context (default)"),
    M21("MiniMax-M2.1", "Strong multi-language programming (~60 tps)"),
    M21_HIGHSPEED("MiniMax-M2.1-highspeed", "M2.1 — faster (~100 tps)"),
    M2("MiniMax-M2", "Agentic capabilities, advanced reasoning");

    val label: String get() = slug

    companion object {
        /** Sensible default when switching to MiniMax — the latest agentic model,
         *  best suited to the coach's multi-round tool loop. */
        val DEFAULT = M3
    }
}

/**
 * Ported from [CoachModel] in CoachSettings.swift.
 * Preset OpenAI model choices. The stored model (`ApiKeyStore.model`) is a free
 * string (so a new model can be typed/served without a code change); these are
 * just the curated picks surfaced in Settings.
 */
enum class OpenAIModel(val slug: String, val blurb: String, val reasoningEfforts: List<ReasoningEffort>) {
    // Sol and Astra reject `none` (HTTP 400).
    LUNA("gpt-6-luna", "Lower cost & latency", listOf(ReasoningEffort.NONE) + ReasoningEffort.LOW_TO_MAX),
    SOL("gpt-6.1-sol", "Balanced (default)", ReasoningEffort.LOW_TO_MAX),
    ASTRA("gpt-6-astra", "Best reasoning", ReasoningEffort.LOW_TO_MAX);

    val label: String get() = slug

    companion object {
        val DEFAULT = SOL

        /** Slugs the Settings picker used to offer and no longer does (issue #77). A stored
         *  selection of one would keep failing with no visible way out, so it reads as [DEFAULT]. */
        val RETIRED_SLUGS = setOf("gpt-4o", "gpt-4o-mini", "o4-mini")

        /** [slug], or [DEFAULT]'s slug when it is blank or retired. */
        fun normalize(slug: String): String =
            if (slug.isBlank() || slug in RETIRED_SLUGS) DEFAULT.slug else slug
    }
}
