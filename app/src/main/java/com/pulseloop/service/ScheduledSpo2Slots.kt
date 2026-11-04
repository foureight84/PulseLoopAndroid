package com.pulseloop.service

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * When the app starts its own spot SpO₂ (issue #103).
 *
 * The ring runs its own all-day reading on a grid from local midnight at the configured interval
 * (hourly on the reporter's ring: 06:00:42, 07:00:39, 08:00:26) and serves one measurement at a
 * time: a second start dropped the first. So the app aims at the **midpoint** between two of the
 * ring's readings — 60 min → :30, 30 min → :15 and :45 — the slot furthest from both.
 *
 * WorkManager runs late, and overnight Doze can hold a run for a long time. [isClear] is the guard
 * for that: a run that would start within a few minutes of a ring reading, or still be measuring
 * when the next one starts, is skipped instead.
 *
 * Local wall-clock seconds rather than epoch arithmetic, so a DST day keeps its slots on the half
 * hour instead of shifting them by the hour the clock jumped.
 */
internal object ScheduledSpo2Slots {

    /** The ring ends a spot SpO₂ with `04 0e` at 50–63 s (issue #59 captures); the coordinator's
     *  ceiling for it is 75 s. */
    const val MEASUREMENT_SECONDS = 75

    /** How far a run must start from the ring's own reading: five minutes, or a quarter of a
     *  shorter interval so a 15-minute grid still has room. */
    fun marginSeconds(intervalMinutes: Int): Int = minOf(5 * 60, intervalMinutes * 60 / 4)

    /**
     * The first slot strictly after [now]. [intervalMinutes] is the app's own cadence;
     * [ringIntervalMinutes] is the interval the ring reads SpO₂ on — slots start half of it past
     * local midnight and repeat every [intervalMinutes]. At the ring's own cadence that is the
     * midpoint between two of its readings; on a longer app interval (120/180 min) the grid keeps
     * the same half-interval offset, which holds it clear of the ring's readings whenever the
     * intervals divide evenly — and [isClear] skips any slot that lands close regardless.
     */
    fun nextSlot(
        now: ZonedDateTime,
        intervalMinutes: Int,
        ringIntervalMinutes: Int = intervalMinutes,
    ): ZonedDateTime {
        val interval = intervalMinutes * 60
        val half = when {
            ringIntervalMinutes == 120 && intervalMinutes == 120 -> 60 * 60
            ringIntervalMinutes >= 60 -> 30 * 60
            else -> ringIntervalMinutes * 60 / 2
        }
        val secs = now.toLocalTime().toSecondOfDay()
        val k = if (secs < half) 0 else (secs - half) / interval + 1
        val slot = half + k * interval
        val day = if (slot >= SECONDS_PER_DAY) now.toLocalDate().plusDays(1) else now.toLocalDate()
        val slotOfDay = if (slot >= SECONDS_PER_DAY) half else slot
        return day.atTime(LocalTime.ofSecondOfDay(slotOfDay.toLong())).atZone(now.zone)
    }

    /** Whether a measurement starting at [now] stays clear of the ring's readings either side. */
    fun isClear(now: ZonedDateTime, intervalMinutes: Int): Boolean {
        val interval = intervalMinutes * 60
        val margin = marginSeconds(intervalMinutes)
        val secs = now.toLocalTime().toSecondOfDay()
        val sincePrevious = secs % interval
        // The grid restarts at midnight, so an interval that doesn't divide the day has a short
        // last step before it.
        val untilNext = minOf(interval - sincePrevious, SECONDS_PER_DAY - secs)
        return sincePrevious >= margin && untilNext >= margin + MEASUREMENT_SECONDS
    }

    private const val SECONDS_PER_DAY = 24 * 60 * 60
}
