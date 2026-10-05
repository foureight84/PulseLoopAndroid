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
 * Also holds two markers used to keep the ring's sync from clobbering phone-owned days:
 *
 * - [lastBackfillDay] — the local-midnight epoch millis of the last successful
 *   `PhoneStepManager.refreshHistoricalDays()` run. Set by
 *   [com.pulseloop.MainActivity.refreshPhoneSteps] and read there to backfill once per day
 *   rather than on every foreground.
 * - [lastPhoneStepSuccessAt] — wall-clock millis of the last successful
 *   [com.pulseloop.PhoneStepManager.refresh] publish. **Persisted on purpose**: it used to
 *   live in memory only, so on every process start it read as `0` and
 *   `EventPersistenceSubscriber.stepSourceIsPhone` returned false until the first phone
 *   read of the new process. That window let the ring's next sync overwrite a `phone`
 *   day's stored `source` and its step total, which then needed a pull-to-refresh to
 *   repair. Reading the persisted value on construction closes the window.
 */
class StepSourcePrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pulseloop_prefs", Context.MODE_PRIVATE)

    init {
        // Seed the health marker so a "phone" preference is treated as healthy from the
        // very first construction. Without this, the first launch after install (or after
        // the `lastPhoneStepSuccessAt` key was introduced) has a window between process
        // start and the phone reader's first publish during which the ring's bucket sync
        // can claim today. The preference itself is enough evidence for that one moment;
        // if the phone reader then genuinely fails, PhoneStepSourceUnavailable resets the
        // marker within a second and the ring takes over.
        //
        // Written against `prefs` rather than the `stepSource`/`lastPhoneStepSuccessAt`
        // properties: an init block runs before property initialisers, so the properties
        // below are not in scope yet.
        if ((prefs.getString(KEY_STEP_SOURCE, SOURCE_RING) ?: SOURCE_RING) == SOURCE_PHONE &&
            prefs.getLong(KEY_LAST_PHONE_STEP_SUCCESS_AT, 0L) == 0L
        ) {
            prefs.edit().putLong(KEY_LAST_PHONE_STEP_SUCCESS_AT, System.currentTimeMillis()).apply()
        }
    }

    var stepSource: String
        get() = prefs.getString(KEY_STEP_SOURCE, SOURCE_RING) ?: SOURCE_RING
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

    /**
     * Wall-clock millis of the last successful [com.pulseloop.PhoneStepManager.refresh]
     * publish (i.e. the last time the phone reader actually produced a value).
     * **Persisted across process restarts** on purpose — see the class KDoc.
     */
    var lastPhoneStepSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_PHONE_STEP_SUCCESS_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_PHONE_STEP_SUCCESS_AT, value).apply() }

    companion object {
        /**
         * The two valid values of [stepSource]. Every call site that reads or writes the
         * preference — or that compares an `activity_daily.source` value it wrote — refers
         * to one of these rather than a bare `"ring"` / `"phone"` literal, so the string is
         * defined in exactly one place.
         */
        const val SOURCE_RING = "ring"
        const val SOURCE_PHONE = "phone"

        private const val KEY_STEP_SOURCE = "step_source"
        private const val KEY_LAST_BACKFILL_DAY = "last_backfill_day"
        private const val KEY_LAST_PHONE_STEP_SUCCESS_AT = "last_phone_step_success_at"
    }
}