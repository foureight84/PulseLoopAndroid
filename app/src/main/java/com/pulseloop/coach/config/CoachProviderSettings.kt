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
    USER_MINIMAX_KEY("userMiniMaxKey", "MiniMax (your key)"),
    /** Any OpenAI-Chat-Completions-compatible server the user runs themselves — Ollama,
     *  llama.cpp, vLLM, SGLang, LM Studio. The API key is optional here; the readiness sentinel
     *  is the base URL. See `docs/local-llm-coach.md`. */
    LOCAL_OPENAI_COMPAT("localOpenAICompat", "Local / self-hosted"),
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
    /** MiniMax model slug (see [MiniMaxModel] presets). */
    val minimaxModel: String = MiniMaxModel.DEFAULT.slug,
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
    /** Local-only: base URL of the self-hosted server, as typed (`http://192.168.1.50:11434`).
     *  Blank means the provider isn't configured — this, not the key, gates readiness. */
    val localBaseUrl: String = "",
    /** Local-only: model name the server expects. Free-form; `/v1/models` populates the picker. */
    val localModel: String = "",
    /** Local-only: send `tools`. Off for a server started without tool-call support (vLLM without
     *  `--enable-auto-tool-choice` returns HTTP 400) or a model that can't call them. */
    val localToolCalling: Boolean = true,
    /** Local-only: how hard to constrain the output shape. Default OFF — the only mode every
     *  backend supports. */
    val localStructuredOutput: LocalStructuredOutput = LocalStructuredOutput.OFF,
    /** Local-only: `max_tokens`; 0 = omit and let the server decide. */
    val localMaxTokens: Int = 0,
    /** Local-only: read timeout in seconds. Long, because CPU inference is slow. */
    val localTimeoutSeconds: Int = com.pulseloop.coach.local.LocalOpenAICompatClient.DEFAULT_READ_TIMEOUT_SECONDS,
) {
    /** The OpenRouter model slug to use; falls back to the default preset only
     *  when the stored slug is blank. */
    val resolvedOpenRouterModel: String
        get() = openRouterModel.trim().ifEmpty { OpenRouterModel.DEFAULT.slug }

    /** The MiniMax model slug to use; falls back to the default preset only when
     *  the stored slug is blank. */
    val resolvedMinimaxModel: String
        get() = minimaxModel.trim().ifEmpty { MiniMaxModel.DEFAULT.slug }

    /** The local base URL with surrounding whitespace gone; blank when unconfigured. There's no
     *  default to fall back to — every engine listens on a different port. */
    val resolvedLocalBaseUrl: String get() = localBaseUrl.trim()

    /** The local model name. Blank is sent as-is rather than substituted: llama.cpp ignores the
     *  field entirely, so an empty value is legitimate there, and inventing a slug would turn a
     *  working setup into a 404 on the servers that do read it. */
    val resolvedLocalModel: String get() = localModel.trim()
}

/**
 * How hard to constrain a local model's output shape — see `docs/local-llm-coach.md` §5.
 *
 * [OFF] is the default because it's the only mode implemented by every backend: the coach's shape
 * is carried by `CoachResponseSchema.promptInstruction` in the system message, with the
 * orchestrator's JSON-repair loop as the backstop. The other two are opt-in because the support
 * matrix is genuinely uneven — LM Studio implements `json_schema` but not `json_object`, and some
 * llama.cpp builds error when `json_schema` collides with a server-side `grammar`.
 */
enum class LocalStructuredOutput(val rawValue: String, val label: String, val blurb: String) {
    OFF("off", "Prompt only", "Works everywhere (default)"),
    JSON_OBJECT("jsonObject", "JSON mode", "response_format: json_object — not on LM Studio"),
    JSON_SCHEMA("jsonSchema", "Strict schema", "response_format: json_schema — best when supported");

    companion object {
        fun fromRaw(raw: String?): LocalStructuredOutput =
            entries.firstOrNull { it.rawValue == raw } ?: OFF
    }
}
