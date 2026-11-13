package com.pulseloop.settings

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * The opt-in quiet-hours gate behind issue #79: ignore sleep the ring opens outside a daily
 * window. A still wrist reads as sleep, so an evening on the sofa lands as a phantom session and
 * the night runs an hour high; the wearer knows their bedtime, the sensor guesses.
 *
 * Two sources, each opt-in and independent of the other (issue #126): the daily clock window
 * ([enabled]) and the wearer's recorded Android Bedtime / DND windows ([gateByZenMode], issue #83,
 * which needs Notification access). [resolveKeptMinutes] is how they combine.
 *
 * **Minute-level, not record-level.** A record is trimmed to the minutes that fall inside the
 * window ([keptMinutes]) rather than accepted or declined by its start minute. The ring often
 * opens one record on the sofa and runs it straight on into the real night; judging that record
 * by its 23:08 start would either keep the sofa hour or throw the whole night away.
 *
 * **The filter is drop-on-import, and that is not recoverable.** Minutes the gate trims are
 * never written, so widening the window later cannot resurrect what was skipped while it was
 * narrower — the same one-way property the deletion tombstones have, chosen for the same reason:
 * the alternative (import everything, hide outside the window) makes the ring's own figure and
 * the displayed one permanently disagree. Off by default for exactly this reason.
 */
class QuietHoursPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The daily clock schedule ("Sleep quiet hours"). Off by default: the ring's own behaviour is
     * the right default for everyone else. Independent of [gateByZenMode] since issue #126.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** Window start, minutes of local day (default 22:00). */
    var startMinutes: Int
        get() = prefs.getInt(KEY_START, DEFAULT_START)
        set(value) { prefs.edit().putInt(KEY_START, value.coerceIn(0, 24 * 60 - 1)).apply() }

    /** Window end, minutes of local day (default 07:00). */
    var endMinutes: Int
        get() = prefs.getInt(KEY_END, DEFAULT_END)
        set(value) { prefs.edit().putInt(KEY_END, value.coerceIn(0, 24 * 60)).apply() }

    /**
     * Opt-in: trim to Android Bedtime / DND windows (issue #83). Works on its own since issue #126
     * — before that it was read only while [enabled] was on, and the settings row was hidden with it.
     */
    var gateByZenMode: Boolean
        get() = prefs.getBoolean(KEY_GATE_BY_ZEN_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_GATE_BY_ZEN_MODE, value).apply() }

    fun keptMinutesForRecord(
        ts: Long,
        minutes: Int,
        context: Context,
        zone: ZoneId = ZoneId.systemDefault(),
    ): IntRange? = keptMinutesForRecord(ts, minutes, this, context, zone)

    companion object {
        private const val PREFS_NAME = "sleep_quiet_hours"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START = "startMinutes"
        private const val KEY_END = "endMinutes"
        private const val KEY_GATE_BY_ZEN_MODE = "gate_by_zen_mode"
        private const val KEY_INDEPENDENT_BEDTIME_MIGRATED = "independentBedtime.v1"
        const val DEFAULT_START = 22 * 60
        const val DEFAULT_END = 7 * 60

        /**
         * Is the night that ended (so far) at [sessionEndAt] still open right now (#140)? True
         * while Bedtime/DND tracking is on and a Mode window that began by the night's end is
         * still active, or while the clock schedule is on and the time is inside it. Both are
         * opt-in; with neither on this is always false.
         */
        fun nightInProgress(context: Context, sessionEndAt: Long, now: Long = System.currentTimeMillis()): Boolean {
            val prefs = QuietHoursPrefs(context)
            val zenActive = isZenTrackingActive(context) && ZenModeTracker.isZenModeActive(context)
            val openZenStart = if (zenActive) ZenModeTracker.getWindows(context).lastOrNull { it.end == null }?.start else null
            return nightOpen(
                zenActive = zenActive, openZenStart = openZenStart, sessionEndAt = sessionEndAt,
                scheduleEnabled = prefs.enabled, start = prefs.startMinutes, end = prefs.endMinutes,
                minuteOfDay = minuteOfDay(now),
            )
        }

        /**
         * The pure half of [nightInProgress]. An active Mode counts only if its window began
         * before the night's stored end plus an hour: DND switched on for an afternoon meeting
         * is not the Bedtime that night belongs to. A Mode with no recorded window still counts.
         */
        fun nightOpen(
            zenActive: Boolean,
            openZenStart: Long?,
            sessionEndAt: Long,
            scheduleEnabled: Boolean,
            start: Int,
            end: Int,
            minuteOfDay: Int,
        ): Boolean {
            if (zenActive && (openZenStart == null || openZenStart <= sessionEndAt + 3600_000L)) return true
            return scheduleEnabled && scheduleOpen(start, end, minuteOfDay)
        }

        /** [covers], except a start==end window (which [covers] reads as "gate off") is never open. */
        fun scheduleOpen(start: Int, end: Int, minuteOfDay: Int): Boolean =
            start != end && covers(start, end, minuteOfDay)

        /** Bedtime/DND gating is on and the [ZenModeListener] has Notification access. */
        fun isZenTrackingActive(context: Context): Boolean =
            QuietHoursPrefs(context).gateByZenMode && ZenModeListener.isAccessGranted(context)

        /**
         * The recorded Bedtime/DND windows when that tracking is active, else null — the
         * `bedtimeWindows` argument of [resolveKeptMinutes] and [com.pulseloop.service.SleepGapBridge.forWindows].
         */
        fun activeBedtimeWindows(prefs: QuietHoursPrefs, context: Context): List<ZenWindow>? =
            if (prefs.gateByZenMode && ZenModeListener.isAccessGranted(context)) {
                ZenModeTracker.getWindows(context)
            } else {
                null
            }

        /**
         * One-time upgrade step for issue #126, run from [com.pulseloop.PulseLoopApplication] before
         * anything reads these prefs. Bedtime=on with the schedule off was reachable before #126
         * (turn both on, then the schedule off: the Bedtime row hid but kept its value) and meant
         * *no trimming at all*. Read under the new rules it would mean "Bedtime only" and start
         * trimming on the first sync after the update — and trimmed minutes are never imported.
         * So that state is cleared to "neither", which is what it did; the wearer re-enables
         * Bedtime tracking if they want it. Every other state already means what it meant.
         */
        fun migrateIndependentBedtime(context: Context) {
            val prefs = QuietHoursPrefs(context)
            val raw = prefs.prefs
            if (raw.getBoolean(KEY_INDEPENDENT_BEDTIME_MIGRATED, false)) return
            val gate = migratedGateByZenMode(prefs.enabled, prefs.gateByZenMode)
            if (gate != prefs.gateByZenMode) {
                prefs.gateByZenMode = gate
                ZenModeListener.setEnabled(context, false)
            }
            raw.edit().putBoolean(KEY_INDEPENDENT_BEDTIME_MIGRATED, true).apply()
        }

        /** The stored Bedtime flag after the #126 upgrade: kept only while the schedule is on. */
        fun migratedGateByZenMode(scheduleEnabled: Boolean, gateByZenMode: Boolean): Boolean =
            gateByZenMode && scheduleEnabled

        /** Which minutes of a ring record to keep under the user's current settings; see [resolveKeptMinutes]. */
        fun keptMinutesForRecord(
            ts: Long,
            minutes: Int,
            prefs: QuietHoursPrefs,
            context: Context,
            zone: ZoneId = ZoneId.systemDefault(),
        ): IntRange? = resolveKeptMinutes(
            ts,
            minutes,
            scheduleEnabled = prefs.enabled,
            bedtimeWindows = activeBedtimeWindows(prefs, context),
            startMinutes = prefs.startMinutes,
            endMinutes = prefs.endMinutes,
            zone = zone,
        )

        /**
         * Which minutes of a ring record to keep (issue #126: the two sources are independent).
         * [bedtimeWindows] is null when Bedtime tracking is off or lacks Notification access.
         *
         * - **Neither:** the whole record.
         * - **Schedule only:** trimmed to the clock window ([keptMinutes]).
         * - **Bedtime only:** trimmed to the Bedtime/DND windows it overlaps; a record that overlaps
         *   none — a night Bedtime was forgotten — is kept whole. Forgetting Bedtime must not cost
         *   the night: the #126 reporter lost 07:00–08:10 to the clock fallback, permanently.
         *   "Overlaps", not "the waking day has a window": the filter cannot tell Bedtime from a
         *   DND meeting the next afternoon (same waking day), and dropping the night over that
         *   would be unrecoverable. The cost is a separate sofa record before Bedtime is kept; the
         *   hour gap rule still keeps it apart from the night.
         * - **Both:** the Bedtime windows when the night has any and they overlap the record;
         *   otherwise the clock window (unchanged from #83).
         *
         * Null drops the record.
         */
        fun resolveKeptMinutes(
            ts: Long,
            minutes: Int,
            scheduleEnabled: Boolean,
            bedtimeWindows: List<ZenWindow>?,
            startMinutes: Int,
            endMinutes: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): IntRange? {
            if (minutes <= 0) return null
            val night = bedtimeWindows?.let { nightWindows(it, ts, zone) }.orEmpty()
            if (night.isNotEmpty()) {
                val kept = keptMinutesByWindows(ts, minutes, night)
                if (kept != null) return kept
            }
            if (scheduleEnabled) return keptMinutes(ts, minutes, startMinutes, endMinutes, zone)
            return 0 until minutes
        }

        /**
         * The windows of the waking day [ts] falls in — the span both the import gate and
         * [com.pulseloop.service.SleepGapBridge] judge a night by.
         */
        fun nightWindows(windows: List<ZenWindow>, ts: Long, zone: ZoneId = ZoneId.systemDefault()): List<ZenWindow> {
            val wakingDay = com.pulseloop.util.TimeUtil.wakingDayLocal(ts, zone)
            val dayStart = wakingDay - 12 * 3_600_000L
            val dayEnd = wakingDay + 36 * 3_600_000L
            return windows.filter { w -> effectiveEnd(w) > dayStart && w.start < dayEnd }
        }

        /** A still-open window counts as running for at most [ZenModeTracker.MAX_WINDOW_DURATION_MS]. */
        fun effectiveEnd(w: ZenWindow): Long = w.end ?: (w.start + ZenModeTracker.MAX_WINDOW_DURATION_MS)

        /**
         * Longest contiguous stretch of minutes falling inside any of [windows].
         */
        fun keptMinutesByWindows(
            ts: Long,
            minutes: Int,
            windows: List<ZenWindow>,
        ): IntRange? {
            if (minutes <= 0 || windows.isEmpty()) return null
            var best: IntRange? = null
            var runStart = -1
            for (i in 0..minutes) {
                val minuteTs = ts + i * 60_000L
                val inside = i < minutes && windows.any { w -> minuteTs >= w.start && minuteTs < effectiveEnd(w) }
                if (inside && runStart < 0) runStart = i
                if (!inside && runStart >= 0) {
                    val run = runStart until i
                    if (best == null || run.count() > best.count()) best = run
                    runStart = -1
                }
            }
            return best
        }

        /**
         * Does a record opened at [minuteOfDay] (local) fall inside the window [start]→[end]?
         * Inclusive at the start, exclusive at the end — a 07:00 record belongs to the day.
         * A window whose start equals its end covers the whole day, which disables the gate
         * without a second flag.
         */
        fun covers(start: Int, end: Int, minuteOfDay: Int): Boolean {
            if (start == end) return true
            return if (start < end) minuteOfDay in start until end
            else minuteOfDay >= start || minuteOfDay < end
        }

        /**
         * Which minutes of a ring record opened at [ts] with [minutes] stage minutes to keep: the
         * longest contiguous stretch whose minutes fall inside [start]→[end] (see [covers]), as
         * indices into the record's stages. Null when no minute is inside — the record is dropped.
         *
         * Longest-stretch rather than every in-window minute because the window is one daily
         * interval: a record longer than the gap between two windows (a nap run through to the
         * evening) could touch two of them, and keeping both would bridge the hole with a record
         * that was never contiguous.
         */
        fun keptMinutes(
            ts: Long,
            minutes: Int,
            start: Int,
            end: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): IntRange? {
            if (minutes <= 0) return null
            var best: IntRange? = null
            var runStart = -1
            for (i in 0..minutes) {
                val inside = i < minutes && covers(start, end, minuteOfDay(ts + i * 60_000L, zone))
                if (inside && runStart < 0) runStart = i
                if (!inside && runStart >= 0) {
                    val run = runStart until i
                    if (best == null || run.count() > best.count()) best = run
                    runStart = -1
                }
            }
            return best
        }

        /** Local minute-of-day of an epoch-millis instant. */
        fun minuteOfDay(ts: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
            val time = Instant.ofEpochMilli(ts).atZone(zone)
            return time.hour * 60 + time.minute
        }
    }
}
