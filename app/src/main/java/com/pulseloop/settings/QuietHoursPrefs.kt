package com.pulseloop.settings

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * The opt-in quiet-hours gate behind issue #79: ignore sleep the ring opens outside a daily
 * window. A still wrist reads as sleep, so an evening on the sofa lands as a phantom session and
 * the night runs an hour high; the wearer knows their bedtime, the sensor guesses.
 *
 * Deliberately a time window rather than Android's quiet Modes (the reporter's first choice):
 * a Mode gate needs notification-policy access plus a recorded interruption-filter history to
 * compare imports against, and the sofa case is mostly caught by a plain window. The Modes
 * version stays open as a follow-up — the reporter offered to build it.
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

    /** Off by default: the ring's own behaviour is the right default for everyone else. */
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

    companion object {
        private const val PREFS_NAME = "sleep_quiet_hours"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START = "startMinutes"
        private const val KEY_END = "endMinutes"
        const val DEFAULT_START = 22 * 60
        const val DEFAULT_END = 7 * 60

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
