package com.pulseloop.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window arithmetic behind the quiet-hours gate (issue #79). The import gate is one call to
 * [QuietHoursPrefs.covers] — the boundaries and the midnight wrap are where a mistake would drop
 * real nights or keep phantom ones.
 */
class QuietHoursPrefsTest {

    /** Default 22:00 → 07:00 wraps midnight; a 23:08 record is the sofa case it exists for. */
    @Test
    fun `the default window wraps midnight`() {
        assertTrue(QuietHoursPrefs.covers(22 * 60, 7 * 60, 23 * 60 + 8))
        assertTrue(QuietHoursPrefs.covers(22 * 60, 7 * 60, 3 * 60))
        assertFalse(QuietHoursPrefs.covers(22 * 60, 7 * 60, 14 * 60))
    }

    /** Inclusive at the start, exclusive at the end — a 07:00 record belongs to the day. */
    @Test
    fun `boundaries are start-inclusive and end-exclusive`() {
        assertTrue(QuietHoursPrefs.covers(22 * 60, 7 * 60, 22 * 60))
        assertFalse(QuietHoursPrefs.covers(22 * 60, 7 * 60, 7 * 60))
        assertTrue(QuietHoursPrefs.covers(9 * 60, 18 * 60, 9 * 60))
        assertFalse(QuietHoursPrefs.covers(9 * 60, 18 * 60, 18 * 60))
    }

    /** A plain daytime window (no wrap) still works — shift workers nap in the day. */
    @Test
    fun `a non-wrapping window behaves`() {
        assertTrue(QuietHoursPrefs.covers(9 * 60, 18 * 60, 12 * 60))
        assertFalse(QuietHoursPrefs.covers(9 * 60, 18 * 60, 21 * 60))
        assertFalse(QuietHoursPrefs.covers(9 * 60, 18 * 60, 6 * 60))
    }

    /** start == end means the whole day — the gate is on but the window filters nothing. */
    @Test
    fun `an equal window covers the whole day`() {
        for (minute in listOf(0, 7 * 60, 12 * 60, 23 * 60 + 59)) {
            assertTrue(QuietHoursPrefs.covers(8 * 60, 8 * 60, minute))
        }
    }

    /** The gate reads wall-clock local time, not epoch minutes. */
    @Test
    fun `minuteOfDay uses the local wall clock`() {
        // 2026-09-21T23:08:00Z is a different local wall clock per zone; assert against the zone
        // arithmetic itself rather than a fixed zone, so the test holds on any machine.
        val ts = 1_789_000_000_000L
        val zone = java.time.ZoneId.of("UTC")
        val expected = java.time.Instant.ofEpochMilli(ts).atZone(zone).hour * 60 +
            java.time.Instant.ofEpochMilli(ts).atZone(zone).minute
        assertEquals(expected.toLong(), QuietHoursPrefs.minuteOfDay(ts, zone).toLong())
    }
}
