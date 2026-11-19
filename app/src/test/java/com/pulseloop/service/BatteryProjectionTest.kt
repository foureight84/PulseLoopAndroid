package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #65: a runtime estimate from the ring's own battery history. */
class BatteryProjectionTest {

    private val t0 = 1_725_000_000_000L
    private fun hours(h: Double) = t0 + (h * 3_600_000).toLong()

    /** A steady drain: the slope is the drain rate and the projection is what's left over it. */
    @Test
    fun `a steady discharge projects the time to empty`() {
        // 100 % falling 2 %/h for 10 hours → 80 % left, 40 h remaining.
        val samples = (0..10).map { BatteryProjection.Sample(hours(it.toDouble()), 100.0 - 2.0 * it) }

        val estimate = BatteryProjection.estimate(samples)

        assertNotNull(estimate)
        assertEquals(2.0, estimate!!.percentPerHour, 0.001)
        assertEquals(40.0, estimate.hoursRemaining, 0.01)
        assertEquals("1d 16h", estimate.label)
    }

    /**
     * The case that makes a whole-window fit useless: a 7-day window almost always contains a
     * charge, and a line through "falling, then 100 %, then falling" describes when the user
     * plugged in rather than how fast the ring drains.
     */
    @Test
    fun `a charge starts a new run and only the run after it is fitted`() {
        val discharge = (0..10).map { BatteryProjection.Sample(hours(it.toDouble()), 60.0 - 2.0 * it) }
        val recharged = (0..10).map { BatteryProjection.Sample(hours(11.0 + it), 100.0 - 1.0 * it) }

        val run = BatteryProjection.currentDischargeRun(discharge + recharged)
        val estimate = BatteryProjection.estimate(discharge + recharged)

        assertEquals("the run starts at the charge", 100.0, run.first().percent, 0.001)
        assertEquals(11, run.size)
        assertNotNull(estimate)
        assertEquals("the post-charge rate, not the average of both", 1.0, estimate!!.percentPerHour, 0.001)
    }

    /** Coarse reporting wobbles by a point or two at a plateau; that is not a charge. */
    @Test
    fun `a small wobble does not split the discharge run`() {
        val samples = listOf(
            BatteryProjection.Sample(hours(0.0), 80.0),
            BatteryProjection.Sample(hours(2.0), 78.0),
            BatteryProjection.Sample(hours(4.0), 79.0),   // +1, noise
            BatteryProjection.Sample(hours(6.0), 76.0),
            BatteryProjection.Sample(hours(8.0), 74.0),
        )

        assertEquals(5, BatteryProjection.currentDischargeRun(samples).size)
        assertNotNull(BatteryProjection.estimate(samples))
    }

    @Test
    fun `no estimate from too few samples, too short a span, or a flat line`() {
        val tooFew = (0..2).map { BatteryProjection.Sample(hours(it.toDouble()), 90.0 - it) }
        assertNull(BatteryProjection.estimate(tooFew))

        val tooShort = (0..5).map { BatteryProjection.Sample(hours(it * 0.1), 90.0 - it) }
        assertNull("half an hour of readings is not a trend", BatteryProjection.estimate(tooShort))

        val flat = (0..10).map { BatteryProjection.Sample(hours(it.toDouble()), 90.0) }
        assertNull("a flat line has nothing to project", BatteryProjection.estimate(flat))
    }

    /**
     * The sample/span/fall gates are all about the run's shape and none of them bounds how *slow*
     * it may be. Firmware that reports in 5 % steps clears every one of them over a 7 d window and
     * projects a runtime no ring has.
     */
    @Test
    fun `no estimate from a drain too slow to be one`() {
        // 80 → 75 across a week: 4 samples, 168 h, a 5-point fall — and 0.03 %/h.
        val coarse = listOf(80.0, 79.0, 77.0, 75.0).mapIndexed { i, percent ->
            BatteryProjection.Sample(hours(i * 56.0), percent)
        }

        assertNull("104 days of runtime is not an estimate", BatteryProjection.estimate(coarse))
    }

    /** While charging there is no depletion to project, and guessing one would be a lie. */
    @Test
    fun `no estimate while the battery is rising`() {
        val charging = (0..10).map { BatteryProjection.Sample(hours(it * 0.5), 40.0 + 2.0 * it) }
        assertNull(BatteryProjection.estimate(charging))
    }

    @Test
    fun `a day window grids on the six-hour marks and a week window on midnights`() {
        val zone = java.time.ZoneId.of("UTC")
        val dayStart = java.time.ZonedDateTime.of(2026, 9, 10, 3, 20, 0, 0, zone).toInstant().toEpochMilli()
        val dayEnd = dayStart + 24 * 3_600_000L

        val hourly = BatteryProjection.gridlines(dayStart, dayEnd, zone)
        assertTrue(hourly.isNotEmpty())
        hourly.forEach {
            val t = java.time.Instant.ofEpochMilli(it).atZone(zone)
            assertEquals("on the hour", 0, t.minute)
            assertEquals("on a six-hour mark", 0, t.hour % 6)
        }

        val weekEnd = dayStart + 7 * 24 * 3_600_000L
        val daily = BatteryProjection.gridlines(dayStart, weekEnd, zone)
        assertEquals(7, daily.size)
        daily.forEach {
            val t = java.time.Instant.ofEpochMilli(it).atZone(zone)
            assertEquals("local midnight", 0, t.hour)
            assertEquals(0, t.minute)
        }
    }

    /** Every mark must sit inside the plotted window, or it draws off the chart. */
    @Test
    fun `gridlines stay inside the window`() {
        val start = t0
        val end = t0 + 24 * 3_600_000L
        BatteryProjection.gridlines(start, end).forEach {
            assertTrue(it > start && it <= end)
        }
        assertEquals(emptyList<Long>(), BatteryProjection.gridlines(end, start))
    }
}
