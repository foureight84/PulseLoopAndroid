package com.pulseloop.coach.config

/**
 * Ported from `CoachSettings.knownReasoningEfforts` / `reasoningEffortOptions` /
 * `resolvedReasoningEffort` in CoachSettings.swift (iOS #140), extended with the live
 * [CoachModelCatalog] listing so a model outside the presets still gets its real levels.
 *
 * The stored effort is one global setting, but which levels are valid depends on the model: an
 * OpenAI request with a level the model rejects is an HTTP 400 on every turn (`none` left over after
 * switching from Luna to Sol), and Gemini 2.5 rejects `thinkingLevel` altogether. So the stored hint
 * is checked against the selected model before it is sent, and dropped back to the model's default
 * when it isn't one the model accepts.
 */
object ReasoningEfforts {

    /**
     * The levels [model] is known to accept, or null when nothing says (a Custom OpenAI or
     * OpenRouter slug the listing hasn't covered). Empty means the model takes no effort hint.
     */
    fun known(provider: CoachProviderMode, model: String, live: CoachModelCatalog.Entry? = null): List<ReasoningEffort>? {
        val slug = model.trim()
        return when (provider) {
            CoachProviderMode.USER_OPENAI_KEY ->
                OpenAIModel.entries.firstOrNull { it.slug == slug }?.reasoningEfforts
            CoachProviderMode.USER_OPENROUTER_KEY ->
                OpenRouterModel.entries.firstOrNull { it.slug == slug }?.reasoningEfforts ?: live?.reasoningEfforts
            CoachProviderMode.USER_GEMINI_KEY ->
                GeminiModel.entries.firstOrNull { it.slug == slug }?.reasoningEfforts ?: geminiLevels(slug)
            // Neither client sends a `reasoning` field (MiniMax reasons inline; see the local
            // client's header), so there is nothing to choose.
            CoachProviderMode.USER_MINIMAX_KEY, CoachProviderMode.LOCAL_OPENAI_COMPAT -> emptyList()
            // Offline / backend proxy send OpenAI requests (CoachClientResolver's fallback branch),
            // and Settings shows the OpenAI picker for them.
            else -> known(CoachProviderMode.USER_OPENAI_KEY, slug, live)
        }
    }

    /** The levels the Reasoning picker offers; empty hides it. */
    fun options(provider: CoachProviderMode, model: String, live: CoachModelCatalog.Entry? = null): List<ReasoningEffort> =
        known(provider, model, live) ?: ReasoningEffort.COMMON

    /**
     * The effort to send: the stored [stored] hint when [model] accepts it, else null (the model's
     * own default). An unknown model gets the hint unchanged — it may well accept it, and blocking
     * it would make a new model's effort unsettable.
     */
    fun resolve(provider: CoachProviderMode, model: String, stored: String?, live: CoachModelCatalog.Entry? = null): String? {
        val effort = stored?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val supported = known(provider, model, live) ?: return effort
        return effort.takeIf { e -> supported.any { it.rawValue == e } }
    }

    /**
     * Gemini's `thinkingLevel` arrived with Gemini 3; a 2.x model rejects it, and a `-latest` alias
     * we don't preset might point anywhere. So only a versioned 3+ slug gets levels.
     */
    internal fun geminiLevels(slug: String): List<ReasoningEffort> {
        val major = GEMINI_VERSION.find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
        if (major < 3) return emptyList()
        return if ("flash-lite" in slug) listOf(ReasoningEffort.MINIMAL) + ReasoningEffort.COMMON else ReasoningEffort.COMMON
    }

    private val GEMINI_VERSION = Regex("^gemini-(\\d+)")
}
