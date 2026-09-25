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

    /** Default 22:00 → 07:00 wraps midnight. Note it *keeps* 23:08 — the reported sofa start —
     *  which is why the settings copy tells the wearer to set the start to their real bedtime. */
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

    // ── Minute-level trim ([QuietHoursPrefs.keptMinutes]) ────────────────────

    private val utc = java.time.ZoneId.of("UTC")
    /** 2026-09-17 at [h]:[m] UTC, epoch millis. */
    private fun at(h: Int, m: Int) =
        java.time.ZonedDateTime.of(2026, 9, 17, h, m, 0, 0, utc).toInstant().toEpochMilli()

    /**
     * The reported night as one unsplit record: 23:08 on the sofa straight through to 07:08. Judged
     * by its start the whole night would stand or fall together; trimmed, the sofa minutes before
     * a 00:05 start and the minutes after 07:00 go, and the real night stays.
     */
    @Test
    fun `a sofa start running into the night is trimmed, not dropped`() {
        val minutes = 8 * 60   // 23:08 → 07:08
        val kept = QuietHoursPrefs.keptMinutes(at(23, 8), minutes, 0 * 60 + 5, 7 * 60, utc)
        assertEquals(57 until 57 + (6 * 60 + 55), kept)   // 00:05 → 07:00
    }

    @Test
    fun `a record wholly outside the window is dropped`() {
        assertEquals(null, QuietHoursPrefs.keptMinutes(at(14, 0), 45, 22 * 60, 7 * 60, utc))
    }

    @Test
    fun `a record wholly inside the window is kept whole`() {
        assertEquals(0 until 300, QuietHoursPrefs.keptMinutes(at(23, 30), 300, 22 * 60, 7 * 60, utc))
    }

    @Test
    fun `an equal window keeps every minute`() {
        assertEquals(0 until 90, QuietHoursPrefs.keptMinutes(at(15, 0), 90, 8 * 60, 8 * 60, utc))
    }

    /** A record touching two windows keeps the longer stretch, never both across the hole. */
    @Test
    fun `a record touching two windows keeps the longer stretch`() {
        // Window 12:00 → 13:00 daily; a record 12:30 → next day 12:10 (MAX timeline allows it in
        // principle): 30 minutes today vs 10 tomorrow.
        val kept = QuietHoursPrefs.keptMinutes(at(12, 30), 24 * 60 - 20, 12 * 60, 13 * 60, utc)
        assertEquals(0 until 30, kept)
    }

    @Test
    fun `an empty record keeps nothing`() {
        assertEquals(null, QuietHoursPrefs.keptMinutes(at(23, 0), 0, 22 * 60, 7 * 60, utc))
    }
}
