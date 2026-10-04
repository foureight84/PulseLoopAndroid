package com.pulseloop.settings

import android.content.Context

/**
 * The user's step-source preference ("ring" or "phone").
 *
 * Deliberately uses plain (unencrypted) [android.content.SharedPreferences] rather than
 * [ApiKeyStore]'s EncryptedSharedPreferences: this is a non-sensitive UI preference, and the
 * encryption layer is expensive enough that callers reading it on every ring activity event
 * previously needed an in-memory cache. Plain prefs are cheap to read, so the cache is gone.
 *
 * Also holds the phone-step backfill throttle: [lastBackfillDay] is the local-midnight epoch
 * millis of the last successful `PhoneStepManager.refreshHistoricalDays()` run, so the app
 * backfills once per day instead of on every foreground (see
 * [com.pulseloop.MainActivity.refreshPhoneSteps]).
 */
class StepSourcePrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pulseloop_prefs", Context.MODE_PRIVATE)

    var stepSource: String
        get() = prefs.getString(KEY_STEP_SOURCE, "ring") ?: "ring"
        set(value) { prefs.edit().putString(KEY_STEP_SOURCE, value).apply() }

    /**
     * Local-midnight epoch millis of the last day on which the phone step reader ran a
     * successful 30-day backfill. 0 means "never run". Read/written only from
     * [com.pulseloop.MainActivity.refreshPhoneSteps] — the Settings toggle intentionally
     * does not consult it, because an explicit user action should always run.
     */
    var lastBackfillDay: Long
        get() = prefs.getLong(KEY_LAST_BACKFILL_DAY, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_BACKFILL_DAY, value).apply() }

    companion object {
        private const val KEY_STEP_SOURCE = "step_source"
        private const val KEY_LAST_BACKFILL_DAY = "last_backfill_day"
    }
}
