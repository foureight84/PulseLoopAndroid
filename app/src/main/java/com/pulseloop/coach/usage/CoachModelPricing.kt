package com.pulseloop.coach.usage

/**
 * Ported from [CoachModelPrice] in CoachModelPricing.swift (iOS #65).
 * USD-per-1M-token rates for one model. `cachedInputPer1M` null means the model
 * isn't cache-priced, so cached input bills at the normal input rate.
 */
data class CoachModelPrice(
    val inputPer1M: Double,
    val cachedInputPer1M: Double?,
    val outputPer1M: Double,
)

/**
 * Ported from [CoachPricingCatalog] in CoachModelPricing.swift (iOS #65).
 * Estimates a turn's USD cost from token usage when the provider doesn't bill it
 * back (only OpenRouter reports exact cost). Keys are the provider model strings
 * used as `CoachFeatureFlags.model`; lookup is normalized (lowercase, strip the
 * OpenRouter `:online` web-search suffix), tries an exact match first, then the
 * longest matching key prefix, so a custom slug like `openai/gpt-5.5-preview`
 * still prices off `openai/gpt-5.5`.
 *
 * Unlike iOS there's no Apple on-device provider on Android, so [freeModels] only
 * carries `offline-stub`.
 */
object CoachPricingCatalog {
    /** Normalized-key → published list price, standard (non-batch) tier, as of
     *  July 2026. Sources: platform OpenAI/Google/DeepSeek/MiniMax pricing pages,
     *  platform.claude.com pricing docs, and openrouter.ai model pages. Estimates
     *  only — OpenRouter's API-reported exact cost always takes precedence. */
    private val table: Map<String, CoachModelPrice> = mapOf(
        // OpenAI (native)
        "gpt-5.4-mini" to CoachModelPrice(0.75, 0.075, 4.50),
        "gpt-5.4" to CoachModelPrice(2.50, 0.25, 15.00),
        "gpt-5.5" to CoachModelPrice(5.00, 0.50, 30.00),
        // Gemini (native). gemini-2.0-flash was shut down June 1 2026 and has no
        // published price; it intentionally has no entry so the UI reports
        // "cost unavailable".
        "gemini-2.5-flash" to CoachModelPrice(0.30, 0.03, 2.50),
        "gemini-2.5-pro" to CoachModelPrice(1.25, 0.125, 10.00), // <=200k-token tier; >200k bills 2x
        // OpenRouter (vendor/model slugs). List prices match the vendors';
        // cached rates use the vendor cache-read price.
        "anthropic/claude-sonnet-4.6" to CoachModelPrice(3.00, 0.30, 15.00),
        "anthropic/claude-opus-4.7" to CoachModelPrice(5.00, 0.50, 25.00),
        "openai/gpt-5.5" to CoachModelPrice(5.00, 0.50, 30.00),
        "openai/gpt-5.4-mini" to CoachModelPrice(0.75, 0.075, 4.50),
        "google/gemini-2.5-flash" to CoachModelPrice(0.30, 0.03, 2.50),
        "google/gemini-2.5-pro" to CoachModelPrice(1.25, 0.125, 10.00),
        "deepseek/deepseek-v4-flash" to CoachModelPrice(0.09, null, 0.18), // OpenRouter routes below DeepSeek's direct $0.14/$0.28
        // MiniMax. No published cache-read rate; -highspeed variants bill 2x
        // the standard rate.
        "minimax-m3" to CoachModelPrice(0.30, null, 1.20), // <=512k-token tier; >512k bills 2x
        "minimax-m2.7" to CoachModelPrice(0.30, null, 1.20),
        "minimax-m2.7-highspeed" to CoachModelPrice(0.60, null, 2.40),
        "minimax-m2.5" to CoachModelPrice(0.30, null, 1.20),
        "minimax-m2.5-highspeed" to CoachModelPrice(0.60, null, 2.40),
        "minimax-m2.1" to CoachModelPrice(0.30, null, 1.20),
        "minimax-m2.1-highspeed" to CoachModelPrice(0.60, null, 2.40),
        "minimax-m2" to CoachModelPrice(0.30, null, 1.20),
    )

    /** The model strings that always cost $0 (local / scripted). */
    private val freeModels: Set<String> = setOf("offline-stub")

    /** The estimated USD cost of [usage] for [model]. Returns 0 for offline
     *  models, `null` for an unrecognized model, else the priced estimate. */
    fun cost(model: String, usage: CoachTokenUsage): Double? {
        val price = price(model) ?: return null
        val cachedRate = price.cachedInputPer1M ?: price.inputPer1M
        val uncachedInput = maxOf(0, usage.inputTokens - usage.cachedInputTokens)
        return (uncachedInput * price.inputPer1M
            + usage.cachedInputTokens * cachedRate
            + usage.outputTokens * price.outputPer1M) / 1_000_000
    }

    /** Resolves a model string to its price. Free models resolve to an all-zero
     *  price; unknown models return `null`. */
    fun price(model: String): CoachModelPrice? {
        val key = normalize(model)
        if (key in freeModels) return CoachModelPrice(0.0, 0.0, 0.0)
        table[key]?.let { return it }
        // Longest-prefix fallback: a custom slug like `openai/gpt-5.5-preview`
        // prices off the longest catalog key that it starts with.
        return table.entries.filter { key.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    }

    /** Lowercases and strips the OpenRouter `:online` web-search suffix so both
     *  the web-search and plain variants of a slug price the same. */
    private fun normalize(model: String): String {
        val key = model.lowercase().trim()
        return key.removeSuffix(":online")
    }
}
