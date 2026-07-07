package com.pulseloop.coach.config

/**
 * Ported from [CoachProviderMode] in CoachSettings.swift.
 * Where the coach's "brain" runs. `USER_OPENAI_KEY`, `USER_GEMINI_KEY` and
 * `USER_OPENROUTER_KEY` are the shipping cloud providers; `BACKEND_PROXY` is
 * reserved for a future public build and is treated as disabled until
 * implemented. iOS's `appleOnDevice` has no Android equivalent and is not ported.
 */
enum class CoachProviderMode(val rawValue: String, val label: String) {
    OFFLINE_STUB("offlineStub", "Offline"),
    USER_OPENAI_KEY("userOpenAIKey", "OpenAI (your key)"),
    USER_GEMINI_KEY("userGeminiKey", "Gemini (your key)"),
    USER_OPENROUTER_KEY("userOpenRouterKey", "OpenRouter (your key)"),
    BACKEND_PROXY("backendProxy", "Backend proxy");

    companion object {
        /** Tolerant decode: unknown/legacy raw values fall back to [USER_OPENAI_KEY]. */
        fun fromRaw(raw: String?): CoachProviderMode =
            entries.firstOrNull { it.rawValue == raw } ?: USER_OPENAI_KEY
    }
}

/**
 * Ported from [CoachSettings] in CoachSettings.swift (provider-selection subset).
 * Immutable snapshot of the provider configuration a resolver call needs. The
 * persisted source of truth is [CoachProviderSettingsStore]; this plain data
 * class keeps [CoachClientResolver] free of Android dependencies (testable on
 * the JVM).
 */
data class CoachProviderSettings(
    val providerMode: CoachProviderMode = CoachProviderMode.USER_OPENAI_KEY,
    /** Gemini model slug (see [GeminiModel] presets). */
    val geminiModel: String = GeminiModel.DEFAULT.slug,
    /** OpenRouter `vendor/model` slug. Free-form — the user may type any slug. */
    val openRouterModel: String = OpenRouterModel.DEFAULT.slug,
    /** OpenRouter-only: route only through providers that don't log/train on
     *  prompts (sends `provider.data_collection = "deny"`). */
    val orPrivacyRouting: Boolean = false,
    /** OpenRouter-only: provider selection bias ("price" | "throughput" |
     *  "latency"). null = OpenRouter's default routing. */
    val orProviderSort: String? = null,
    /** Optional reasoning effort hint ("low"/"medium"/"high") when the model
     *  supports it. null/blank = omit from requests. */
    val reasoningEffort: String? = null,
    /** When true, the coach composer shows an attach-image button. */
    val imageInputEnabled: Boolean = false,
) {
    /** The OpenRouter model slug to use; falls back to the default preset only
     *  when the stored slug is blank. */
    val resolvedOpenRouterModel: String
        get() = openRouterModel.trim().ifEmpty { OpenRouterModel.DEFAULT.slug }
}
