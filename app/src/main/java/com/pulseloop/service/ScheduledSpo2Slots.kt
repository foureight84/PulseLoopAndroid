package com.pulseloop.service

import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * When the app starts its own spot SpO₂ (issue #103).
 *
 * The ring runs its own all-day reading on a grid from local midnight at the configured interval
 * (hourly on the reporter's ring: 06:00:42, 07:00:39, 08:00:26) and serves one measurement at a
 * time: a second start dropped the first. So the app aims at the **midpoint** between two of the
 * ring's readings — 60 min → :30, 30 min → :15 and :45 — the slot furthest from both.
 *
 * WorkManager runs late, and overnight Doze can hold a run for a long time. [isClear] is the guard
 * for that: a run that would start within a few minutes of a ring reading, or whose connect-and-
 * sync plus measurement window would still be running when the next one starts, is skipped instead.
 * [isClearToMeasure] is the narrower guard for the moment the connection is already up and startup
 * sync has drained, right before [measureSpO2Only] — at that point only the measurement itself
 * still has to fit before the ring's next reading.
 *
 * Local wall-clock seconds rather than epoch arithmetic, so a DST day keeps its slots on the half
 * hour instead of shifting them by the hour the clock jumped.
 */
internal object ScheduledSpo2Slots {

    /** The ring ends a spot SpO₂ with `04 0e` at 50–63 s (issue #59 captures); the coordinator's
     *  ceiling for it is 75 s. */
    const val MEASUREMENT_SECONDS = 75

    /** Reserved for opening GATT and draining the startup sync before the 75 s measurement
     *  window: 30 + 75 = 105 s total, so a Doze-delayed wakeup landing at :57 or :58 on an hourly
     *  ring grid still fits a full run while :59 is skipped. */
    const val CONNECT_AND_SYNC_SECONDS = 30

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

    /**
     * Whether a full run starting at [now] — connect, startup sync, then measurement — stays clear
     * of the ring's readings either side: it must start past the margin after the previous reading
     * and leave room for the connect-and-sync reserve plus the whole measurement window before the
     * next one.
     */
    fun isClear(now: ZonedDateTime, intervalMinutes: Int): Boolean =
        isClearWithPreMargin(now, intervalMinutes, CONNECT_AND_SYNC_SECONDS + MEASUREMENT_SECONDS)

    /**
     * Whether a measurement started at [now] stays clear of the ring's next reading, for the check
     * made right before calling `measureSpO2Only()` — connect and startup sync have already
     * completed at that point, so only the measurement window itself has to fit.
     */
    fun isClearToMeasure(now: ZonedDateTime, intervalMinutes: Int): Boolean =
        isClearWithPreMargin(now, intervalMinutes, MEASUREMENT_SECONDS)

    /** Shared guard behind [isClear] and [isClearToMeasure]: [preMarginSeconds] is the window that
     *  must still fit before the ring's next reading, on top of the margin after its previous one. */
    private fun isClearWithPreMargin(
        now: ZonedDateTime,
        intervalMinutes: Int,
        preMarginSeconds: Int,
    ): Boolean {
        val interval = intervalMinutes * 60
        val margin = marginSeconds(intervalMinutes)
        val secs = now.toLocalTime().toSecondOfDay()
        val sincePrevious = secs % interval
        // The grid restarts at midnight, so an interval that doesn't divide the day has a short
        // last step before it.
        val untilNext = minOf(interval - sincePrevious, SECONDS_PER_DAY - secs)
        return sincePrevious >= margin && untilNext >= preMarginSeconds
    }

    /**
     * The earliest time strictly after [now] at which [isClear] holds — i.e. a full run (connect,
     * sync, measure) would fit. Used to reschedule a run that [isClear] skipped.
     */
    fun nextClear(now: ZonedDateTime, intervalMinutes: Int): ZonedDateTime {
        val interval = intervalMinutes * 60
        val margin = marginSeconds(intervalMinutes)
        val t = if (isClear(now, intervalMinutes)) now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1) else now
        val secs = t.toLocalTime().toSecondOfDay()
        val sincePrevious = secs % interval
        val gridStart = secs - sincePrevious
        if (t.isAfter(now) && isClear(t, intervalMinutes)) return t
        val candidateSecs = if (sincePrevious < margin) {
            gridStart + margin
        } else {
            minOf(gridStart + interval, SECONDS_PER_DAY) + margin
        }
        if (candidateSecs < SECONDS_PER_DAY) {
            val candidate = t.toLocalDate()
                .atTime(LocalTime.ofSecondOfDay(candidateSecs.toLong()))
                .atZone(now.zone)
            val resolved = if (candidate.isAfter(now)) candidate else candidate.withLaterOffsetAtOverlap()
            if (isClear(resolved, intervalMinutes)) return resolved
        }
        // Midnight resets the ring's grid to 00:00, so anything rolling past it — or a
        // non-dividing grid's short tail before midnight — clears at tomorrow's first margin.
        return t.toLocalDate().plusDays(1)
            .atTime(LocalTime.ofSecondOfDay(margin.toLong()))
            .atZone(now.zone)
    }

    private const val SECONDS_PER_DAY = 24 * 60 * 60
}
