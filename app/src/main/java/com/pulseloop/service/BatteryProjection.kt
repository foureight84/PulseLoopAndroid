package com.pulseloop.service

/**
 * How long the ring has left, from its own battery history (issue #65).
 *
 * A least-squares fit over the samples, which is what the reporter asked for — but fitted over the
 * **current discharge run only**, not the whole window. A 7-day window almost always contains a
 * charge, and a line through "40 % falling, then 100 %, then falling again" has a slope that is an
 * artefact of when the user happened to plug in: it can come out flat, or positive, and either way
 * it describes nothing. The run since the last charge is the only stretch whose slope is a drain
 * rate.
 *
 * Deliberately conservative about saying anything at all. An estimate carries more authority than
 * it earns — a number on screen is read as a promise — so it is withheld unless the fit has enough
 * samples, enough elapsed time and enough of a fall to mean something.
 */
object BatteryProjection {

    /** One battery reading: epoch millis and percent. */
    data class Sample(val timestampMs: Long, val percent: Double)

    /**
     * @param percentPerHour how fast it is falling now (positive = draining)
     * @param hoursRemaining time until empty at that rate, from the last reading
     */
    data class Estimate(val percentPerHour: Double, val hoursRemaining: Double) {
        /** "2d 6h" / "18h" / "45m" — the shape a runtime is actually read in. */
        val label: String
            get() {
                val totalMinutes = (hoursRemaining * 60).toInt().coerceAtLeast(0)
                val days = totalMinutes / (24 * 60)
                val hours = (totalMinutes % (24 * 60)) / 60
                val minutes = totalMinutes % 60
                return when {
                    days > 0 -> "${days}d ${hours}h"
                    hours > 0 -> "${hours}h"
                    else -> "${minutes}m"
                }
            }
    }

    /**
     * A rise of more than this many points is a charge, not sensor noise — the boundary of the
     * current discharge run. Ring battery reporting is coarse (whole percent, and some firmware
     * reports in 5 % steps), so a 1–2 point wobble at a plateau is normal and must not split a run.
     */
    private const val CHARGE_RISE_POINTS = 3.0

    /** Below this the fit is describing noise rather than a trend. */
    private const val MIN_SAMPLES = 4
    private const val MIN_SPAN_HOURS = 1.5
    private const val MIN_FALL_POINTS = 2.0

    /**
     * The samples since the ring was last charged, oldest first.
     *
     * Returns the whole list when no charge is visible in it — which is the common case for a 24 h
     * window and the reason the 24 h view usually has an estimate while the 7 d one may not.
     */
    fun currentDischargeRun(samples: List<Sample>): List<Sample> {
        if (samples.size < 2) return samples
        val ordered = samples.sortedBy { it.timestampMs }
        var runStart = 0
        for (i in 1 until ordered.size) {
            if (ordered[i].percent - ordered[i - 1].percent >= CHARGE_RISE_POINTS) runStart = i
        }
        return ordered.subList(runStart, ordered.size)
    }

    /**
     * Estimate remaining runtime, or null when the data cannot support one.
     *
     * Null rather than a shrug of a number: "not enough data yet" is honest and a wrong estimate of
     * battery life is the kind of thing someone plans a trip around.
     */
    fun estimate(samples: List<Sample>): Estimate? {
        val run = currentDischargeRun(samples)
        if (run.size < MIN_SAMPLES) return null

        val spanHours = (run.last().timestampMs - run.first().timestampMs) / 3_600_000.0
        if (spanHours < MIN_SPAN_HOURS) return null
        if (run.first().percent - run.last().percent < MIN_FALL_POINTS) return null

        // Least squares against hours since the run's start, so the slope is directly %/hour.
        val t0 = run.first().timestampMs
        val xs = run.map { (it.timestampMs - t0) / 3_600_000.0 }
        val ys = run.map { it.percent }
        val n = run.size
        val meanX = xs.average()
        val meanY = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            sxy += dx * (ys[i] - meanY)
            sxx += dx * dx
        }
        if (sxx <= 0.0) return null
        val slope = sxy / sxx
        if (slope >= 0) return null      // flat or rising: no depletion to project

        val drainPerHour = -slope
        val current = run.last().percent
        if (current <= 0) return null
        return Estimate(
            percentPerHour = drainPerHour,
            hoursRemaining = current / drainPerHour,
        )
    }

    /**
     * Gridline instants across [startMs]..[endMs], for the chart behind the line.
     *
     * Local midnights when the window spans more than two days, otherwise every [hourStep] hours on
     * the hour — the reporter asked for a grid that says "which day" or "which hour", and a grid at
     * arbitrary offsets answers neither.
     */
    fun gridlines(
        startMs: Long,
        endMs: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        hourStep: Int = 6,
    ): List<Long> {
        if (endMs <= startMs) return emptyList()
        val spanHours = (endMs - startMs) / 3_600_000.0
        val start = java.time.Instant.ofEpochMilli(startMs).atZone(zone)
        val marks = mutableListOf<Long>()
        if (spanHours > 48) {
            var day = start.toLocalDate().plusDays(1).atStartOfDay(zone)
            while (day.toInstant().toEpochMilli() <= endMs) {
                marks += day.toInstant().toEpochMilli()
                day = day.plusDays(1)
            }
        } else {
            var mark = start.withMinute(0).withSecond(0).withNano(0)
            // Advance to the next multiple of hourStep so the grid lands on 00:00/06:00/12:00/18:00
            // rather than wherever the window happened to open.
            while (mark.hour % hourStep != 0 || mark.toInstant().toEpochMilli() <= startMs) {
                mark = mark.plusHours(1)
            }
            while (mark.toInstant().toEpochMilli() <= endMs) {
                marks += mark.toInstant().toEpochMilli()
                mark = mark.plusHours(hourStep.toLong())
            }
        }
        return marks
    }
}
