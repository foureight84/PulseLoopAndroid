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
        get() = prefs.getString(KEY_STEP_SOURCE, SOURCE_RING) ?: SOURCE_RING
        set(value) { prefs.edit().putString(KEY_STEP_SOURCE, value).apply() }

    /**
     * True when the user has chosen the phone as the step source. The read-time column selection
     * (Path B, PR #98) branches on this: [ActivityDailyEntity.displaySteps] returns
     * [ActivityDailyEntity.phoneSteps] when this is true and a phone value exists for the day,
     * and [ActivityDailyEntity.steps] otherwise.
     */
    fun preferPhone(): Boolean = stepSource == SOURCE_PHONE

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
     * True once any Health Connect source has been observed writing steps for this device —
     * either a foreign origin seen in a today-read ([com.pulseloop.PhoneStepManager.refresh])
     * or one seen in a backfill window ([com.pulseloop.PhoneStepManager.refreshHistoricalDays]).
     *
     * Review item 3, PR #98. Without this flag the phone reader cannot tell "today is 0
     * because it's 3 a.m. and the phone has counted nothing yet" (a real state — publish 0 so
     * phone mode shows 0) from "today is 0 because no app on this phone writes steps to Health
     * Connect at all" (not a real state — publishing 0 would paint a false zero over a real
     * ring day). With it: `refresh()` publishes 0 only when [hasEverSeenPhoneSteps] is true.
     * When false, the day's `phoneSteps` stays null and the display falls back to the ring.
     *
     * Never reset. Whether a source has ever existed for this install is installation-level
     * history, not current-preference state — a user who toggles to Ring and back still expects
     * Phone mode to show real numbers on a phone that has been tracking steps all along.
     */
    var hasEverSeenPhoneSteps: Boolean
        get() = prefs.getBoolean(KEY_HAS_EVER_SEEN_PHONE_STEPS, false)
        set(value) { prefs.edit().putBoolean(KEY_HAS_EVER_SEEN_PHONE_STEPS, value).apply() }

    companion object {
        /**
         * The two valid values of [stepSource]. Every call site that reads or writes the
         * preference — or that compares an `activity_daily.source` value it wrote — refers
         * to one of these rather than a bare `"ring"` / `"phone"` literal.
         */
        const val SOURCE_RING = "ring"
        const val SOURCE_PHONE = "phone"

        private const val KEY_STEP_SOURCE = "step_source"
        private const val KEY_LAST_BACKFILL_DAY = "last_backfill_day"
        private const val KEY_HAS_EVER_SEEN_PHONE_STEPS = "has_ever_seen_phone_steps"
    }
}
