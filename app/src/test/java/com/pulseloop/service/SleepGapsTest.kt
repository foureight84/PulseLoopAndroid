package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.ZenWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The gap between two ring records of one night (issues #111, #105): how long it is, how it is
 * drawn, and when an hour-plus one still belongs to the night.
 */
class SleepGapsTest {

    private val utc = ZoneOffset.UTC

    /** Epoch millis for an Oct 11 2026 UTC wall-clock time (day offset for times after midnight). */
    private fun at(hour: Int, minute: Int, second: Int = 0, dayOffset: Int = 0): Long =
        ZonedDateTime.of(2026, 10, 11 + dayOffset, hour, minute, second, 0, utc).toInstant().toEpochMilli()

    private fun block(
        startAt: Long,
        minutes: Int,
        recordStartAt: Long,
        sessionStart: Long,
        stage: SleepStage = SleepStage.LIGHT,
    ) = SleepStageBlockEntity(
        id = "b$startAt",
        sessionId = "s",
        startAt = startAt,
        startMinute = ((startAt - sessionStart) / 60_000L).toInt(),
        durationMinutes = minutes,
        stageRaw = stage.name,
        recordStartAt = recordStartAt,
    )

    // ── #111 point 2: the gap is rounded, not truncated ──────────────────────

    /**
     * The reporter's Oct 10–11 waking: the ring ended the first record at 07:08:53 and opened the
     * next at 07:11:00 — 2m07. Its start (00:30:14) plus whole minutes stores the end at 07:09:14,
     * leaving 1m46, which truncation turned into 1 and dropped under the 2-minute threshold.
     */
    @Test
    fun `a 2m07 waking is not lost to the stored end's rounding`() {
        val firstStart = at(0, 30, 14)
        val secondStart = at(7, 11, 0)
        val blocks = listOf(
            block(firstStart, 399, firstStart, firstStart),                 // ends 07:09:14
            block(secondStart, 60, secondStart, firstStart),
        )
        val runs = sleepRecordRuns(blocks)
        assertEquals(2, betweenRecordAwakeMinutes(runs[0], runs[1]))
        assertEquals(2, awakeMinutes(blocks))
    }

    /**
     * The reporter's Sep 20 get-up: one record ended 04:33:09 and the next opened 04:34:01, 52 s
     * later. Gaps that short used to be dropped as the #63 "reopen seam"; the reporter's 19 logged
     * gaps showed no reopen without waking, and this one was a get-up written down at the time.
     */
    @Test
    fun `a 52 s get-up between records counts one awake minute`() {
        val firstStart = at(0, 30, 9)
        val secondStart = at(4, 34, 1)
        val blocks = listOf(
            block(firstStart, 243, firstStart, firstStart),                 // ends 04:33:09
            block(secondStart, 60, secondStart, firstStart),
        )
        val runs = sleepRecordRuns(blocks)
        assertEquals(1, betweenRecordAwakeMinutes(runs[0], runs[1]))
        assertEquals(1, awakeMinutes(blocks))
    }

    /** Records that touch exactly, as a Jring's 15-minute packets do, have no waking between them. */
    @Test
    fun `back-to-back records count no awake time`() {
        val firstStart = at(2, 0)
        val secondStart = at(2, 15)
        val blocks = listOf(
            block(firstStart, 15, firstStart, firstStart),
            block(secondStart, 15, secondStart, firstStart),
        )
        val runs = sleepRecordRuns(blocks)
        assertEquals(0, betweenRecordAwakeMinutes(runs[0], runs[1]))
        assertEquals(0, awakeMinutes(blocks))
    }

    // ── #111 point 1: the hypnogram draws the counted gap ────────────────────

    @Test
    fun `the hypnogram gets an awake block over a counted gap, and only for display`() {
        val firstStart = at(0, 0)
        val secondStart = at(1, 15)
        val blocks = listOf(
            block(firstStart, 60, firstStart, firstStart, SleepStage.DEEP),
            block(secondStart, 30, secondStart, firstStart),
        )
        val drawn = hypnogramBlocks(blocks)
        val gap = drawn - blocks.toSet()
        assertEquals(1, gap.size)
        assertEquals(SleepStage.AWAKE.name, gap.single().stageRaw)
        assertEquals(60, gap.single().startMinute)
        assertEquals(15, gap.single().durationMinutes)
        // The Awake figure counts the gap itself; it must not see the drawn block too.
        assertEquals(15, awakeMinutes(blocks))
    }

    @Test
    fun `the hypnogram draws nothing between back-to-back records`() {
        val firstStart = at(0, 0)
        val secondStart = at(1, 0)
        val blocks = listOf(
            block(firstStart, 60, firstStart, firstStart),
            block(secondStart, 30, secondStart, firstStart),
        )
        assertEquals(blocks, hypnogramBlocks(blocks))
    }

    @Test
    fun `the hypnogram draws a one-minute gap`() {
        val firstStart = at(0, 0)
        val secondStart = at(1, 1)
        val blocks = listOf(
            block(firstStart, 60, firstStart, firstStart),
            block(secondStart, 30, secondStart, firstStart),
        )
        val gap = hypnogramBlocks(blocks) - blocks.toSet()
        assertEquals(1, gap.single().durationMinutes)
    }

    /**
     * #140: a record opened mid-minute (23:04:40) runs 63 minutes and the next opens at 00:08:01,
     * so both sides round onto minute 63 and the sub-minute gap leaves no hole on the grid.
     */
    @Test
    fun `the hypnogram draws a sub-minute gap the cards count`() {
        val firstStart = at(23, 4, 40, dayOffset = -1)
        val secondStart = at(0, 8, 1)
        val blocks = listOf(
            block(firstStart, 63, firstStart, firstStart),
            block(secondStart, 30, secondStart, firstStart),
        )
        // Both sides land on minute 63 of the session.
        assertEquals(63, blocks[1].startMinute)
        val gap = hypnogramBlocks(blocks) - blocks.toSet()
        assertEquals(1, gap.single().durationMinutes)
        assertEquals(63, gap.single().startMinute)
        assertEquals(SleepStage.AWAKE.name, gap.single().stageRaw)
        assertEquals(1, awakeMinutes(blocks))
    }

    // ── #105: a gap inside the Bedtime window keeps the night whole ──────────

    /** The reporter's night: records 00:51–05:44 and 06:56–08:07, Bedtime 00:25–08:06. */
    private val bedtime = ZenWindow(start = at(0, 25), end = at(8, 6))
    private val gapStart = at(5, 44)
    private val gapEnd = at(6, 56)

    @Test
    fun `a gap inside one Bedtime window is bridged`() {
        val bridge = SleepGapBridge.forWindows(true, listOf(bedtime), 22 * 60, 7 * 60, utc)
        assertTrue(bridge.bridges(gapStart, gapEnd))
    }

    @Test
    fun `nothing is bridged with neither the schedule nor Bedtime tracking on`() {
        val bridge = SleepGapBridge.forWindows(false, null, 22 * 60, 7 * 60, utc)
        assertFalse(bridge.bridges(gapStart, gapEnd))
    }

    /** Bedtime tracking works without the schedule (issue #126), for bridging as for importing. */
    @Test
    fun `Bedtime only bridges a gap inside the Bedtime window`() {
        val bridge = SleepGapBridge.forWindows(false, listOf(bedtime), 22 * 60, 7 * 60, utc)
        assertTrue(bridge.bridges(gapStart, gapEnd))
    }

    /** No Bedtime that night and no schedule: the hour rule alone decides, as the import keeps all. */
    @Test
    fun `Bedtime only bridges nothing on a night without a Bedtime window`() {
        val bridge = SleepGapBridge.forWindows(false, emptyList(), 22 * 60, 7 * 60, utc)
        assertFalse(bridge.bridges(gapStart, gapEnd))                      // 22–07 would cover it
    }

    /**
     * The bridge and the import gate pick the same source for each of the four settings (issue
     * #126): a gap the bridge keeps inside the night is one the import keeps whole as a record.
     */
    @Test
    fun `the bridge and the import gate agree on the source for every combination`() {
        val gapMinutes = ((gapEnd - gapStart) / 60_000L).toInt()
        // A gap inside Bedtime (00:25–08:06) but past a 22:00–06:00 schedule tells the sources apart.
        val cases = listOf(
            Triple(false, null, false),                                     // neither
            Triple(true, null, false),                                      // schedule only: past 06:00
            Triple(false, listOf(bedtime), true),                           // Bedtime only
            Triple(true, listOf(bedtime), true),                            // both: Bedtime decides
        )
        for ((schedule, windows, bridged) in cases) {
            val bridge = SleepGapBridge.forWindows(schedule, windows, 22 * 60, 6 * 60, utc)
            assertEquals("schedule=$schedule windows=$windows", bridged, bridge.bridges(gapStart, gapEnd))
            val kept = QuietHoursPrefs.resolveKeptMinutes(gapStart, gapMinutes, schedule, windows, 22 * 60, 6 * 60, utc)
            val active = schedule || windows != null
            assertEquals("schedule=$schedule windows=$windows", bridged, active && kept == 0 until gapMinutes)
        }
    }

    @Test
    fun `a gap that runs past the end of Bedtime is not bridged`() {
        val earlyEnd = ZenWindow(start = at(0, 25), end = at(6, 0))
        val bridge = SleepGapBridge.forWindows(true, listOf(earlyEnd), 22 * 60, 9 * 60, utc)
        // The clock window (22:00–09:00) would cover it, but this night has a Bedtime record.
        assertFalse(bridge.bridges(gapStart, gapEnd))
    }

    @Test
    fun `without Bedtime windows the clock window decides`() {
        val covering = SleepGapBridge.forWindows(true, null, 22 * 60, 7 * 60, utc)
        assertTrue(covering.bridges(gapStart, gapEnd))
        val endsEarlier = SleepGapBridge.forWindows(true, null, 22 * 60, 6 * 60, utc)
        assertFalse(endsEarlier.bridges(gapStart, gapEnd))
    }

    @Test
    fun `a nap after the night is not bridged by the clock window`() {
        val bridge = SleepGapBridge.forWindows(true, null, 22 * 60, 7 * 60, utc)
        assertFalse(bridge.bridges(at(6, 30), at(14, 0)))
    }

    @Test
    fun `segmentation keeps a bridged night as one session and splits it otherwise`() {
        val first = at(0, 51)
        val second = at(6, 56)
        val blocks = listOf(
            block(first, 293, first, first),                                // 00:51–05:44
            block(second, 71, second, first),                               // 06:56–08:07
        )
        assertEquals(2, SleepSegmentation.segment(blocks).size)
        val bridge = SleepGapBridge.forWindows(true, listOf(bedtime), 22 * 60, 7 * 60, utc)
        val whole = SleepSegmentation.segment(blocks, bridge)
        assertEquals(1, whole.size)
        // The 72 minutes between the records then count as awake, as shorter gaps already do.
        assertEquals(72, awakeMinutes(whole.single()))
    }

    // ── Review follow-ups ─────────────────────────────────────────────────

    /** A clock window whose start equals its end means "gate off", not "asleep all day". */
    @Test
    fun `an all-day clock window bridges nothing`() {
        val bridge = SleepGapBridge.forWindows(true, null, 22 * 60, 22 * 60, utc)
        assertFalse(bridge.bridges(at(15, 0), at(23, 0)))
        assertFalse(SleepGapBridge.forWindows(true, null, 0, 24 * 60, utc).bridges(at(15, 0), at(23, 0)))
    }

    /**
     * A delete or edit long after the night was written must not split a gap the night already
     * held, even though the Bedtime window that bridged it has aged out; a new gap it opens is still
     * judged by the bridge.
     */
    @Test
    fun `a restate keeps the gaps the night already held and judges new ones`() {
        val first = at(0, 51)
        val middle = at(6, 56)
        val last = at(8, 30)
        val before = listOf(
            block(first, 293, first, first),                                // 00:51–05:44
            block(middle, 71, middle, first),                               // 06:56–08:07
            block(last, 30, last, first),                                   // 08:30–09:00
        )
        val kept = SleepGapBridge.NONE.keepingGapsOf(before)
        assertTrue(kept.bridges(at(5, 44), at(6, 56)))                     // already open
        assertFalse(kept.bridges(at(5, 44), at(8, 30)))                    // opened by deleting the middle
    }
}
