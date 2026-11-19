package com.pulseloop.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Issue #102: vital detail charts cover the calendar period named in the header. */
class VitalPeriodsTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, paris).toInstant().toEpochMilli()

    @Test
    fun `day is midnight to midnight, not the 24 h before now`() {
        // The reporter's case: at 10:04 on Oct 7, the previous day is all of Oct 6.
        val now = at(2026, 10, 7, 10, 4)
        val today = VitalPeriods.latestAnchor(Period.DAY, now, paris)
        assertEquals(at(2026, 10, 7), today)
        val yesterday = VitalPeriods.step(Period.DAY, today, -1, paris)
        assertEquals(at(2026, 10, 6) to at(2026, 10, 7), VitalPeriods.window(Period.DAY, yesterday, paris))
    }

    @Test
    fun `a day across a DST change is still midnight to midnight`() {
        // Europe/Paris falls back on Oct 25 2026: that day is 25 h long.
        val (start, end) = VitalPeriods.window(Period.DAY, at(2026, 10, 25), paris)
        assertEquals(at(2026, 10, 26), end)
        assertEquals(25 * 3_600_000L, end - start)
    }

    @Test
    fun `the latest week is the 7 days ending today`() {
        val anchor = VitalPeriods.latestAnchor(Period.WEEK, at(2026, 10, 7, 10, 4), paris)
        assertEquals(at(2026, 10, 1) to at(2026, 10, 8), VitalPeriods.window(Period.WEEK, anchor, paris))
    }

    @Test
    fun `a month runs from the 1st to the last day, even when the ring was off on the 30th`() {
        val sept = VitalPeriods.step(Period.MONTH, VitalPeriods.latestAnchor(Period.MONTH, at(2026, 10, 7), paris), -1, paris)
        assertEquals(at(2026, 9, 1) to at(2026, 10, 1), VitalPeriods.window(Period.MONTH, sept, paris))
    }
}
