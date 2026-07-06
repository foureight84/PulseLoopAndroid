package com.pulseloop.util

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Day-boundary helpers anchored to the device's **local** timezone, so daily stats
 * roll over at local midnight rather than UTC midnight.
 *
 * Using UTC truncation (`Instant.truncatedTo(DAYS)`) puts the rollover at 00:00 UTC,
 * which for most users lands in the middle of their day. All "today"/per-day keys
 * (e.g. `activity_daily.date`, the Today dashboard window) must go through here so
 * writers and readers agree on the same local-day boundary.
 */
object TimeUtil {
    /** Local midnight (00:00 device-local) for the day containing [ts], as epoch millis. */
    fun startOfDayLocal(ts: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(ts).atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()

    /** Local midnight for the current day, as epoch millis. */
    fun startOfTodayLocal(zone: ZoneId = ZoneId.systemDefault()): Long =
        startOfDayLocal(System.currentTimeMillis(), zone)

    /**
     * Hour-of-day boundary between "belongs to last night" and "belongs to the coming night."
     * Sleep starting at or after this hour rolls onto the *next* morning's waking day; anything
     * earlier (including small-hours and daytime naps) stays on the current day.
     * Mirrors `Calendar.sleepEveningBoundaryHour` in the iOS reference (PulseServices.swift).
     */
    const val SLEEP_EVENING_BOUNDARY_HOUR = 19  // 7 PM

    /**
     * The waking-morning day key (local midnight, epoch millis) for a sleep session starting at
     * [ts]. Sleep that begins at or after 7 PM belongs to the *next* day's waking morning (you
     * fall asleep tonight, wake tomorrow), so a night that crosses midnight groups onto the single
     * morning it ends on. Sleep before 7 PM — early-morning hours or a daytime nap — stays on the
     * current day / last night's session. Mirrors `Calendar.wakingDay(forSleepStart:)` on iOS.
     */
    fun wakingDayLocal(ts: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val zdt = Instant.ofEpochMilli(ts).atZone(zone)
        val base = zdt.truncatedTo(ChronoUnit.DAYS)
        val day = if (zdt.hour >= SLEEP_EVENING_BOUNDARY_HOUR) base.plusDays(1) else base
        return day.toInstant().toEpochMilli()
    }

    /**
     * The night to show on "today" views (Today sleep tile, Sleep day view, widgets): before
     * 4 AM local it is still *last* night (mirrors PulseServices' day-reference rule). Shared by
     * SleepViewModel and the widget snapshot publisher so both read the same session.
     */
    fun referenceNightLocal(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
        val startOfToday = startOfDayLocal(nowMs, zone)
        return if (hour < 4) startOfToday - 86_400_000L else startOfToday
    }
}
