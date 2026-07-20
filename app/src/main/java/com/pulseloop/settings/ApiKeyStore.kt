package com.pulseloop.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.pulseloop.service.GlucoseUnit

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

    /** Opt-in city-level location + weather context for the coach (iOS #65d). Off by default —
     *  turning it on triggers a location-permission request from the Settings toggle. */
    var enableEnvironmentContext: Boolean
        get() = prefs.getBoolean(KEY_ENVIRONMENT_CONTEXT, false)
        set(value) { prefs.edit().putBoolean(KEY_ENVIRONMENT_CONTEXT, value).apply() }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply() }

    /** Fire a local notification when the ring battery crosses 20% / 10% (iOS #61a). Default ON —
     *  matches iOS, where an absent key means enabled; independent of the coach master toggle. */
    var batteryAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_ALERTS, true)
        set(value) { prefs.edit().putBoolean(KEY_BATTERY_ALERTS, value).apply() }

    var morningHour: Int
        get() = prefs.getInt(KEY_MORNING_HOUR, 8)
        set(value) { prefs.edit().putInt(KEY_MORNING_HOUR, value).apply() }

    var eveningHour: Int
        get() = prefs.getInt(KEY_EVENING_HOUR, 20)
        set(value) { prefs.edit().putInt(KEY_EVENING_HOUR, value).apply() }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDING, value).apply() }

    /** Unlocked by tapping the version 7× in About (iOS #49); shows the Developer settings row. */
    var developerUnlocked: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_UNLOCKED, false)
        set(value) { prefs.edit().putBoolean(KEY_DEVELOPER_UNLOCKED, value).apply() }

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

    // ── Physiology inputs (iOS #35 PhysiologySettingsView) ──
    // Optional refinements that shift the vitals reference ranges via UserPhysiologyProfile →
    // VitalsThresholdEngine. Stored here alongside the other app-side display/tuning prefs (units,
    // glucose/BP calibration) rather than on the Room UserProfile, matching where `unitSystem` and
    // the calibration offsets already live.

    /** Treat a low resting HR as fitness rather than a concern (relaxes the low-HR threshold). */
    var athleteMode: Boolean
        get() = prefs.getBoolean(KEY_ATHLETE_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_ATHLETE_MODE, value).apply() }

    /** Typical/home altitude in metres; null = not set. Above ~2000 m relaxes low-SpO₂ warnings. */
    var altitudeMeters: Double?
        get() = if (prefs.contains(KEY_ALTITUDE_M)) prefs.getFloat(KEY_ALTITUDE_M, 0f).toDouble() else null
        set(value) {
            val e = prefs.edit()
            if (value != null && value > 0) e.putFloat(KEY_ALTITUDE_M, value.toFloat()) else e.remove(KEY_ALTITUDE_M)
            e.apply()
        }

    /** Beta-blocker use lowers resting HR. Tri-state: null = not specified (no adjustment). */
    var usesBetaBlockers: Boolean?
        get() = triState(KEY_BETA_BLOCKERS)
        set(value) { setTriState(KEY_BETA_BLOCKERS, value) }

    /** Known lung condition lowers expected SpO₂. Tri-state: null = not specified (no adjustment). */
    var hasKnownLungCondition: Boolean?
        get() = triState(KEY_LUNG_CONDITION)
        set(value) { setTriState(KEY_LUNG_CONDITION, value) }

    /** Preferred glucose display unit (iOS #43 §3). Defaults to mg/dL. */
    var preferredGlucoseUnit: GlucoseUnit
        get() = prefs.getString(KEY_GLUCOSE_UNIT, null)
            ?.let { runCatching { GlucoseUnit.valueOf(it) }.getOrNull() } ?: GlucoseUnit.MGDL
        set(value) { prefs.edit().putString(KEY_GLUCOSE_UNIT, value.name).apply() }

    /** SharedPreferences has no nullable Boolean — an absent key means unspecified (null). */
    private fun triState(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    private fun setTriState(key: String, value: Boolean?) {
        val e = prefs.edit()
        if (value != null) e.putBoolean(key, value) else e.remove(key)
        e.apply()
    }

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
        private const val KEY_ENVIRONMENT_CONTEXT = "environment_context_enabled"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_BATTERY_ALERTS = "battery_alerts_enabled"
        private const val KEY_MORNING_HOUR = "morning_hour"
        private const val KEY_EVENING_HOUR = "evening_hour"
        private const val KEY_ONBOARDING = "onboarding_completed"
        private const val KEY_DEVELOPER_UNLOCKED = "developer_unlocked"
        private const val KEY_UNIT_SYSTEM = "unit_system"
        private const val KEY_BP_ADJ_SYS = "bp_adjust_systolic"
        private const val KEY_BP_ADJ_DIA = "bp_adjust_diastolic"
        private const val KEY_GLUCOSE_OFFSET = "glucose_offset_mgdl"
        private const val KEY_GLUCOSE_REF = "glucose_ref_mgdl"
        private const val KEY_RING_APP_ID = "ring_app_id"
        private const val KEY_ATHLETE_MODE = "athlete_mode"
        private const val KEY_ALTITUDE_M = "altitude_meters"
        private const val KEY_BETA_BLOCKERS = "uses_beta_blockers"
        private const val KEY_LUNG_CONDITION = "has_known_lung_condition"
        private const val KEY_GLUCOSE_UNIT = "preferred_glucose_unit"
    }
}
