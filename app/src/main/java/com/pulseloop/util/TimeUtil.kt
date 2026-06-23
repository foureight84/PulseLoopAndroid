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
}
