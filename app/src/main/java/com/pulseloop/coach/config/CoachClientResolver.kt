package com.pulseloop.coach.config

import com.pulseloop.coach.gemini.GeminiClient
import com.pulseloop.coach.minimax.MiniMaxClient
import com.pulseloop.coach.openai.OpenAIResponsesClient
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openrouter.OpenRouterClient
import com.pulseloop.coach.tools.CoachSettings
import com.pulseloop.settings.ApiKeyStore

/**
 * Ported from [CoachClientResolver] in CoachClientResolver.swift.
 * Single source of truth for "which `ResponsesClient` runs, given the user's
 * settings + stored keys." Shared by the chat orchestrator wiring and the
 * summary service so provider logic lives in exactly one place.
 *
 * The returned `key` is a readiness sentinel: non-null means the provider can
 * run (used to gate `CoachFeatureFlags.coachEnabled`). A client is returned
 * even when the key is absent (`key == null`); the feature-flags gate prevents
 * an empty-key call.
 */
object CoachClientResolver {

    data class Resolution(val key: String?, val client: ResponsesClient)

    /** Pure-JVM core: resolves from a settings snapshot + raw key strings. */
    fun resolve(
        settings: CoachProviderSettings,
        openAIKey: String?,
        geminiKey: String?,
        openRouterKey: String?,
        minimaxKey: String? = null,
        openAIClientFactory: (String) -> ResponsesClient = { OpenAIResponsesClient(it) },
    ): Resolution = when (settings.providerMode) {
        CoachProviderMode.USER_GEMINI_KEY -> {
            val key = geminiKey?.takeIf { it.isNotBlank() }
            Resolution(key, GeminiClient(apiKey = key ?: "", model = settings.geminiModel))
        }
        CoachProviderMode.USER_OPENROUTER_KEY -> {
            val key = openRouterKey?.takeIf { it.isNotBlank() }
            Resolution(key, OpenRouterClient(
                apiKey = key ?: "",
                model = settings.resolvedOpenRouterModel,
                privacyRouting = settings.orPrivacyRouting,
                providerSort = settings.orProviderSort))
        }
        CoachProviderMode.USER_MINIMAX_KEY -> {
            val key = minimaxKey?.takeIf { it.isNotBlank() }
            Resolution(key, MiniMaxClient(apiKey = key ?: "", model = settings.resolvedMinimaxModel))
        }
        else -> {
            // USER_OPENAI_KEY / OFFLINE_STUB / BACKEND_PROXY all use the OpenAI
            // key + factory, mirroring the iOS resolver.
            val key = openAIKey?.takeIf { it.isNotBlank() }
            Resolution(key, openAIClientFactory(key ?: ""))
        }
    }

    /** Convenience overload reading the app's persisted stores. */
    fun resolve(
        store: CoachProviderSettingsStore,
        apiKeyStore: ApiKeyStore,
        openAIClientFactory: (String) -> ResponsesClient = { OpenAIResponsesClient(it) },
    ): Resolution = resolve(
        settings = store.snapshot(),
        openAIKey = apiKeyStore.apiKey,
        geminiKey = store.geminiApiKey,
        openRouterKey = store.openRouterApiKey,
        minimaxKey = store.minimaxApiKey,
        openAIClientFactory = openAIClientFactory,
    )

    /**
     * A per-turn client factory for `CoachOrchestrator`. Gemini/OpenRouter
     * clients are stateful (they accumulate the conversation across `send`
     * calls within one agent turn), so the orchestrator must get a FRESH client
     * per turn — this factory re-resolves on every invocation, which also picks
     * up settings changes without rebuilding the orchestrator.
     */
    fun clientFactory(
        store: CoachProviderSettingsStore,
        apiKeyStore: ApiKeyStore,
    ): () -> ResponsesClient = { resolve(store, apiKeyStore).client }

    /**
     * The per-request settings for the active provider, shared by the chat
     * flags provider and the summary service. `reasoningEffort` keeps the
     * store's null=omit contract: null (or no settings) sends no `reasoning`
     * field, which models like gpt-4o require.
     */
    fun coachSettings(settings: CoachProviderSettings?): CoachSettings =
        CoachSettings(reasoningEffort = settings?.reasoningEffort)

    /**
     * The model slug the orchestrator/services should put in the request body
     * (`CoachFeatureFlags.model`) for the active provider. `openAIModel` is the
     * existing `ApiKeyStore.model` value (also used by the offline/proxy modes).
     */
    fun activeModel(settings: CoachProviderSettings, openAIModel: String): String =
        when (settings.providerMode) {
            CoachProviderMode.USER_GEMINI_KEY -> settings.geminiModel
            CoachProviderMode.USER_OPENROUTER_KEY -> settings.resolvedOpenRouterModel
            CoachProviderMode.USER_MINIMAX_KEY -> settings.resolvedMinimaxModel
            else -> openAIModel.ifEmpty { OpenAIModel.DEFAULT.slug }
        }
}
