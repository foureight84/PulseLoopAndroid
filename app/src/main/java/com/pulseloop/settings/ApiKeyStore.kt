package com.pulseloop.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Ported from OpenAIKeychainStore in the iOS app.
 * Stores the OpenAI API key securely using EncryptedSharedPreferences
 * (Android equivalent of iOS Keychain).
 */
class ApiKeyStore(context: Context) {
    private val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "pulseloop_secure",
        masterKey,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_API_KEY, value).apply() }

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    var model: String
        get() = prefs.getString(KEY_MODEL, "gpt-5.4") ?: "gpt-5.4"
        set(value) { prefs.edit().putString(KEY_MODEL, value).apply() }

    var coachEnabled: Boolean
        get() = prefs.getBoolean(KEY_COACH_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_COACH_ENABLED, value).apply() }

    var webSearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_SEARCH, false)
        set(value) { prefs.edit().putBoolean(KEY_WEB_SEARCH, value).apply() }

    var writeToolsEnabled: Boolean
        get() = prefs.getBoolean(KEY_WRITE_TOOLS, false)
        set(value) { prefs.edit().putBoolean(KEY_WRITE_TOOLS, value).apply() }

    var liveMeasurementsEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_MEASUREMENTS, false)
        set(value) { prefs.edit().putBoolean(KEY_LIVE_MEASUREMENTS, value).apply() }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply() }

    var morningHour: Int
        get() = prefs.getInt(KEY_MORNING_HOUR, 8)
        set(value) { prefs.edit().putInt(KEY_MORNING_HOUR, value).apply() }

    var eveningHour: Int
        get() = prefs.getInt(KEY_EVENING_HOUR, 20)
        set(value) { prefs.edit().putInt(KEY_EVENING_HOUR, value).apply() }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDING, value).apply() }

    /** Unit system: null = auto-detect from locale, "metric" or "imperial" = manual */
    var unitSystem: String?
        get() = if (prefs.contains(KEY_UNIT_SYSTEM)) prefs.getString(KEY_UNIT_SYSTEM, null) else null
        set(value) {
            if (value != null) prefs.edit().putString(KEY_UNIT_SYSTEM, value).apply()
            else prefs.edit().remove(KEY_UNIT_SYSTEM).apply()
        }

    /** Blood-pressure calibration reference (from a cuff). 0 = not set / disabled. */
    var bpAdjustSystolic: Int
        get() = prefs.getInt(KEY_BP_ADJ_SYS, 0)
        set(value) { prefs.edit().putInt(KEY_BP_ADJ_SYS, value).apply() }

    var bpAdjustDiastolic: Int
        get() = prefs.getInt(KEY_BP_ADJ_DIA, 0)
        set(value) { prefs.edit().putInt(KEY_BP_ADJ_DIA, value).apply() }

    /** Blood-sugar display calibration offset, in mg/dL, applied to the ring's
     *  profile-derived glucose value. The ring has no glucose calibration command
     *  (only `setSugarMode` on/off), so the official app applies a "Sugar Offset"
     *  app-side — this mirrors that. 0 = not calibrated. */
    var glucoseOffsetMgdl: Double
        get() = prefs.getFloat(KEY_GLUCOSE_OFFSET, 0f).toDouble()
        set(value) { prefs.edit().putFloat(KEY_GLUCOSE_OFFSET, value.toFloat()).apply() }

    /** The last reference reading (mg/dL) the user entered to calibrate glucose.
     *  Persisted only so the calibration field stays populated. 0 = none. */
    var glucoseRefMgdl: Double
        get() = prefs.getFloat(KEY_GLUCOSE_REF, 0f).toDouble()
        set(value) { prefs.edit().putFloat(KEY_GLUCOSE_REF, value.toFloat()).apply() }

    /** Stable per-install id sent to the ring (0x48 setAppId) so it streams data to us.
     *  Generated once and reused; ≤18 ASCII chars to fit the command payload. */
    val ringAppId: String
        get() {
            val existing = prefs.getString(KEY_RING_APP_ID, null)
            if (!existing.isNullOrEmpty()) return existing
            val id = "PL" + java.util.UUID.randomUUID().toString().replace("-", "").take(14)
            prefs.edit().putString(KEY_RING_APP_ID, id).apply()
            return id
        }

    /** Resolved unit system: manual preference or auto-detected from locale */
    val resolvedUnitSystem: UnitSystem
        get() {
            val stored = unitSystem
            return if (stored != null) {
                try { UnitSystem.valueOf(stored) } catch (_: Exception) { UnitSystem.fromLocale() }
            } else {
                UnitSystem.fromLocale()
            }
        }

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_MODEL = "coach_model"
        private const val KEY_COACH_ENABLED = "coach_enabled"
        private const val KEY_WEB_SEARCH = "web_search_enabled"
        private const val KEY_WRITE_TOOLS = "write_tools_enabled"
        private const val KEY_LIVE_MEASUREMENTS = "live_measurements_enabled"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_MORNING_HOUR = "morning_hour"
        private const val KEY_EVENING_HOUR = "evening_hour"
        private const val KEY_ONBOARDING = "onboarding_completed"
        private const val KEY_UNIT_SYSTEM = "unit_system"
        private const val KEY_BP_ADJ_SYS = "bp_adjust_systolic"
        private const val KEY_BP_ADJ_DIA = "bp_adjust_diastolic"
        private const val KEY_GLUCOSE_OFFSET = "glucose_offset_mgdl"
        private const val KEY_GLUCOSE_REF = "glucose_ref_mgdl"
        private const val KEY_RING_APP_ID = "ring_app_id"
    }
}
