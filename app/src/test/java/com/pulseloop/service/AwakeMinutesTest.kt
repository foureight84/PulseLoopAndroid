package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Awake figure that includes the between-record gaps (issue #81).
 *
 * The ring catches a brief waking either by staging it (`AWAKE` block) or by closing the record and
 * opening a new one — the second form exists on screen only as the RECORDS card's "Awake Xm
 * between", so the Awake stat must count it or the two displays disagree for the same night. The
 * one-minute seam must stay out: it is the rounding artifact every unsplit night carries, not a
 * waking.
 */
class AwakeMinutesTest {

    private val base = 1_725_408_720_000L

    private fun block(
        startMinute: Int,
        minutes: Int,
        stage: SleepStage = SleepStage.LIGHT,
        recordStartMinute: Int? = null,
    ) = SleepStageBlockEntity(
        id = "b$startMinute",
        sessionId = "s",
        startAt = base + startMinute * 60_000L,
        startMinute = startMinute,
        durationMinutes = minutes,
        stageRaw = stage.name,
        recordStartAt = recordStartMinute?.let { base + it * 60_000L } ?: 0L,
    )

    @Test
    fun `an unsplit night with staged awake counts the stages and nothing else`() {
        val blocks = listOf(
            block(0, 60, SleepStage.LIGHT),
            block(60, 5, SleepStage.AWAKE),
            block(65, 120, SleepStage.DEEP),
        )

        assertEquals(5, awakeMinutes(blocks))
    }

    @Test
    fun `a one-minute record seam is not awake time`() {
        // Same record re-opened a minute later (the #63 firmware behavior): two runs, 1 min gap.
        val blocks = listOf(
            block(0, 60, SleepStage.LIGHT, recordStartMinute = 0),
            block(61, 60, SleepStage.LIGHT, recordStartMinute = 61),
        )

        assertEquals(0, awakeMinutes(blocks))
    }

    @Test
    fun `a real between-record waking counts as awake`() {
        // The sofa-adjacent case: the ring closes the record when the wearer gets up and reopens
        // when they settle — the 4 minutes in between exist as a gap, not a stage.
        val blocks = listOf(
            block(0, 120, SleepStage.LIGHT, recordStartMinute = 0),
            block(124, 240, SleepStage.DEEP, recordStartMinute = 124),
        )

        assertEquals(4, awakeMinutes(blocks))
    }

    /** The night from the issue: five wakings, four caught by reopening, one staged. */
    @Test
    fun `the reported night reconciles — gaps and the one staged waking both count`() {
        val blocks = listOf(
            // Four reopened wakings of 2-3 minutes between five records...
            block(0, 30, SleepStage.LIGHT, recordStartMinute = 0),
            block(33, 25, SleepStage.LIGHT, recordStartMinute = 33),    // gap 3
            block(60, 40, SleepStage.LIGHT, recordStartMinute = 60),    // gap 2
            block(102, 35, SleepStage.LIGHT, recordStartMinute = 102),  // gap 2
            block(140, 20, SleepStage.LIGHT, recordStartMinute = 140),  // gap 3
            // ...and the one waking the ring actually staged.
            block(160, 2, SleepStage.AWAKE, recordStartMinute = 140),
            block(162, 180, SleepStage.DEEP, recordStartMinute = 140),
        )

        assertEquals(2 + (3 + 2 + 2 + 3), awakeMinutes(blocks))
    }

    @Test
    fun `no blocks means no awake minutes`() {
        assertEquals(0, awakeMinutes(emptyList()))
    }
}
