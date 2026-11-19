package com.pulseloop.ui.viewmodels

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar window a vital detail chart shows for each [Period] (issue #102).
 *
 * The anchor is always the window's **start**: local midnight for a day, the first of the 7 days for
 * a week (the latest week ends today), the 1st for a month. Day used to be a rolling 24 h ending
 * now, so "2026-10-06" at 10:04 showed Oct 5 10:04 → Oct 6 10:04 and the evening of the 6th only
 * appeared under the 7th; Sleep and Activity already page by calendar day.
 *
 * Steps go through [LocalDate] rather than fixed milliseconds, so a day across a DST change is still
 * midnight to midnight (23 or 25 h).
 */
object VitalPeriods {

    /** The latest window's anchor: today, the week ending today, or this month. */
    fun latestAnchor(period: Period, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = when (period) {
            Period.DAY -> today
            Period.WEEK -> today.minusDays(6)
            Period.MONTH -> today.withDayOfMonth(1)
        }
        return startOf(start, zone)
    }

    /** [anchor] moved by [steps] whole periods (negative = back). */
    fun step(period: Period, anchor: Long, steps: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
        val moved = when (period) {
            Period.DAY -> date.plusDays(steps)
            Period.WEEK -> date.plusDays(7 * steps)
            Period.MONTH -> date.plusMonths(steps)
        }
        return startOf(moved, zone)
    }

    /** The window `[start, end)` an anchor covers. */
    fun window(period: Period, anchor: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        anchor to step(period, anchor, 1, zone)

    private fun startOf(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()
}
