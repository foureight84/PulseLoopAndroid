package com.pulseloop.coach.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Ported from [CoachSettingsStore] in CoachSettings.swift (provider subset).
 * Persists the provider mode, the Gemini/OpenRouter API keys, and the
 * provider-specific options. Uses the same `pulseloop_secure`
 * EncryptedSharedPreferences file as `ApiKeyStore` (the app's existing secure
 * storage idiom) — the OpenAI key and model stay in `ApiKeyStore` untouched;
 * this store only adds the new multi-provider keys alongside them.
 */
class CoachProviderSettingsStore(context: Context) {
    private val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "pulseloop_secure",
        masterKey,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Which provider runs the coach. Defaults to OpenAI, matching iOS. */
    var providerMode: CoachProviderMode
        get() = CoachProviderMode.fromRaw(prefs.getString(KEY_PROVIDER_MODE, null))
        set(value) { prefs.edit().putString(KEY_PROVIDER_MODE, value.rawValue).apply() }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply() }

    val hasGeminiKey: Boolean get() = geminiApiKey.isNotBlank()

    var openRouterApiKey: String
        get() = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_OPENROUTER_API_KEY, value).apply() }

    val hasOpenRouterKey: Boolean get() = openRouterApiKey.isNotBlank()

    /** Gemini model slug (see [GeminiModel] presets). Retired 2.x slugs are remapped to the
     *  current rolling alias on read so no one stays stuck on a deprecated model (issue #22). */
    var geminiModel: String
        get() = GeminiModel.migrateSlug(prefs.getString(KEY_GEMINI_MODEL, GeminiModel.DEFAULT.slug) ?: GeminiModel.DEFAULT.slug)
        set(value) { prefs.edit().putString(KEY_GEMINI_MODEL, value).apply() }

    /** OpenRouter `vendor/model` slug. Free-form; blank falls back to the
     *  default preset at resolve time (see [CoachProviderSettings.resolvedOpenRouterModel]). */
    var openRouterModel: String
        get() = prefs.getString(KEY_OPENROUTER_MODEL, OpenRouterModel.DEFAULT.slug) ?: OpenRouterModel.DEFAULT.slug
        set(value) { prefs.edit().putString(KEY_OPENROUTER_MODEL, value).apply() }

    /** OpenRouter-only: exclude providers that log/train on prompts. */
    var orPrivacyRouting: Boolean
        get() = prefs.getBoolean(KEY_OR_PRIVACY_ROUTING, false)
        set(value) { prefs.edit().putBoolean(KEY_OR_PRIVACY_ROUTING, value).apply() }

    /** OpenRouter-only: "price" | "throughput" | "latency"; null = default routing. */
    var orProviderSort: String?
        get() = prefs.getString(KEY_OR_PROVIDER_SORT, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            if (value.isNullOrEmpty()) prefs.edit().remove(KEY_OR_PROVIDER_SORT).apply()
            else prefs.edit().putString(KEY_OR_PROVIDER_SORT, value).apply()
        }

    /** Optional reasoning effort hint ("low"/"medium"/"high"); null = omit. */
    var reasoningEffort: String?
        get() = prefs.getString(KEY_REASONING_EFFORT, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            if (value.isNullOrEmpty()) prefs.edit().remove(KEY_REASONING_EFFORT).apply()
            else prefs.edit().putString(KEY_REASONING_EFFORT, value).apply()
        }

    /** Master toggle for the coach composer's attach-image button. */
    var imageInputEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_INPUT_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_IMAGE_INPUT_ENABLED, value).apply() }

    /** Immutable snapshot for [CoachClientResolver] (and tests). */
    fun snapshot(): CoachProviderSettings = CoachProviderSettings(
        providerMode = providerMode,
        geminiModel = geminiModel,
        openRouterModel = openRouterModel,
        orPrivacyRouting = orPrivacyRouting,
        orProviderSort = orProviderSort,
        reasoningEffort = reasoningEffort,
        imageInputEnabled = imageInputEnabled,
    )

    companion object {
        private const val KEY_PROVIDER_MODE = "coach_provider_mode"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_OR_PRIVACY_ROUTING = "openrouter_privacy_routing"
        private const val KEY_OR_PROVIDER_SORT = "openrouter_provider_sort"
        private const val KEY_REASONING_EFFORT = "coach_reasoning_effort"
        private const val KEY_IMAGE_INPUT_ENABLED = "coach_image_input_enabled"
    }
}
