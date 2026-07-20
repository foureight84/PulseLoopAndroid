package com.pulseloop.coach.usage

/**
 * Ported from [CoachTokenUsage] in CoachTokenUsage.swift (iOS #65).
 * Token accounting for one model call (or a summed agent turn). Populated by
 * each provider client from its own usage block; zero/null fields mean the
 * provider didn't report that figure. `reportedCostUSD` is set only when the
 * provider returns an exact cost (OpenRouter) — otherwise the catalog estimates it.
 */
data class CoachTokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Cached (prompt-cache-hit) input tokens, a subset of [inputTokens]. 0 when
     *  the provider doesn't report caching. */
    val cachedInputTokens: Int = 0,
    /** Exact USD cost when the provider bills it back (OpenRouter). `null` → estimate
     *  from [CoachPricingCatalog]. */
    val reportedCostUSD: Double? = null,
) {
    /** Sums another call's usage into this one. Costs add when both sides report
     *  one (one side `null` keeps the other); tokens always add. */
    fun add(other: CoachTokenUsage): CoachTokenUsage = CoachTokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
        reportedCostUSD = when {
            reportedCostUSD != null && other.reportedCostUSD != null -> reportedCostUSD + other.reportedCostUSD
            other.reportedCostUSD != null -> other.reportedCostUSD
            else -> reportedCostUSD
        },
    )
}
