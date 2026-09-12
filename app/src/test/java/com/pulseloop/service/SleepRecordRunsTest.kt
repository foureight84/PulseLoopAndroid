package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #68: a split night's individual ring records, recovered from the merged session's blocks.
 */
class SleepRecordRunsTest {

    private val base = 1_725_408_720_000L   // 00:12

    /** A block with no record stamp — a row written before the column existed (the gap fallback). */
    private fun block(startMinute: Int, minutes: Int, stage: SleepStage = SleepStage.LIGHT) =
        SleepStageBlockEntity(
            id = "b$startMinute",
            sessionId = "s",
            startAt = base + startMinute * 60_000L,
            startMinute = startMinute,
            durationMinutes = minutes,
            stageRaw = stage.name,
        )

    /** A block that knows which ring record it arrived in, named by that record's start minute. */
    private fun stamped(
        startMinute: Int,
        minutes: Int,
        recordStartMinute: Int,
        stage: SleepStage = SleepStage.LIGHT,
    ) = block(startMinute, minutes, stage).copy(recordStartAt = base + recordStartMinute * 60_000L)

    /** The reporter's night: two records nine minutes apart, merged into one stored session. */
    @Test
    fun `a night split by a wake comes back as two records`() {
        val first = listOf(block(0, 124), block(124, 62, SleepStage.DEEP))       // 00:12–03:18
        val second = listOf(block(195, 119), block(314, 124, SleepStage.DEEP))   // 03:27–08:05

        val runs = sleepRecordRuns(first + second)

        assertEquals(2, runs.size)
        assertEquals(base, runs[0].startAt)
        assertEquals(186, runs[0].spanMinutes)
        assertEquals(base + 195 * 60_000L, runs[1].startAt)
        assertEquals(243, runs[1].spanMinutes)
        assertEquals(186, runs[0].asleepMinutes)
    }

    /**
     * The case that would break every unsplit night: blocks rounded onto the minute grid can leave
     * a one-minute seam, and that is not a record boundary.
     */
    @Test
    fun `a one-minute seam does not split a record`() {
        val blocks = listOf(block(0, 60), block(61, 60), block(122, 60))

        val runs = sleepRecordRuns(blocks)

        assertEquals(1, runs.size)
        assertEquals(3, runs.single().blocks.size)
    }

    @Test
    fun `an unsplit night is a single run and an empty night is none`() {
        assertEquals(1, sleepRecordRuns(listOf(block(0, 120), block(120, 60))).size)
        assertEquals(0, sleepRecordRuns(emptyList()).size)
    }

    /** Awake stretches inside a record belong to it; only an unclaimed gap divides records. */
    @Test
    fun `an awake block inside a record keeps it whole`() {
        val blocks = listOf(block(0, 100), block(100, 20, SleepStage.AWAKE), block(120, 100))

        val runs = sleepRecordRuns(blocks)

        assertEquals(1, runs.size)
        assertEquals("awake does not count as asleep", 200, runs.single().asleepMinutes)
        assertEquals(220, runs.single().spanMinutes)
    }

    /** Three records, which the reporter's Sept 6 night actually had. */
    @Test
    fun `three records split on both gaps`() {
        val runs = sleepRecordRuns(
            listOf(block(0, 60), block(80, 60), block(160, 60)),
        )
        assertEquals(3, runs.size)
    }

    // ── The stored record boundary (issue #68, rc1 feedback) ────────────────────────────────
    //
    // These two are the pair that no gap threshold can satisfy at once: the same one-minute gap
    // means "next record" in the first and "rounding seam" in the second. Only the stamp tells
    // them apart, which is why the boundary is stored at import rather than inferred here.

    /**
     * The reporter's Sept 9: `00:35 → 05:57` then `05:58 → 08:29`. One minute apart, and genuinely
     * two records. The gap heuristic merged it and the card disappeared.
     */
    @Test
    fun `two records one minute apart are two records`() {
        val first = listOf(stamped(0, 200, recordStartMinute = 0), stamped(200, 122, 0, SleepStage.DEEP))
        val second = listOf(stamped(323, 151, recordStartMinute = 323))

        val runs = sleepRecordRuns(first + second)

        assertEquals(2, runs.size)
        assertEquals(base, runs[0].startAt)
        assertEquals(322, runs[0].spanMinutes)
        assertEquals(base + 323 * 60_000L, runs[1].startAt)
        assertEquals(151, runs[1].spanMinutes)
    }

    /** The same one-minute gap inside a single record stays one record. */
    @Test
    fun `a one-minute seam within one record is still one record`() {
        val blocks = listOf(
            stamped(0, 60, recordStartMinute = 0),
            stamped(61, 60, recordStartMinute = 0),
            stamped(122, 60, recordStartMinute = 0),
        )

        val runs = sleepRecordRuns(blocks)

        assertEquals(1, runs.size)
        assertEquals(3, runs.single().blocks.size)
    }

    @Test
    fun `records come back in time order however the blocks arrive`() {
        val runs = sleepRecordRuns(
            listOf(stamped(323, 151, 323), stamped(0, 200, 0)),
        )

        assertEquals(2, runs.size)
        assertEquals(base, runs[0].startAt)
    }

    /**
     * A night half-written before the column existed has no consistent boundary to read: one
     * stamped record beside a legacy block would look like two records whatever the truth, so the
     * whole night falls back to the gap rule.
     */
    @Test
    fun `a night with any unstamped block falls back to the gap rule`() {
        val blocks = listOf(stamped(0, 60, recordStartMinute = 0), block(61, 60))

        val runs = sleepRecordRuns(blocks)

        assertEquals("the one-minute seam does not split under the fallback", 1, runs.size)
    }
}
