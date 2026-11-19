package com.pulseloop.service

import androidx.health.connect.client.records.SleepSessionRecord
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Extending a sleep record over time the ring missed (issue #106).
 *
 * The reporter's night: the ring recorded 12:51–5:44 and 6:56–8:07, they were asleep again from
 * about 5:46, and the app counted the hole as 1 h 12 awake. The added minutes are asleep but
 * unstaged — in time asleep, out of the breakdown and the score — and the edit path and the import
 * path both go through [editedRecordBlocks], so a re-sync writes back exactly what the edit did.
 */
class SleepRecordExtensionTest {

    private val zone = ZoneId.of("UTC")
    private val wakingDay = ZonedDateTime.of(2026, 10, 8, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int, s: Int = 0) = wakingDay + ((h * 60L + m) * 60 + s) * 1000

    // Records start on :23 s, as a real ring's do — so the grid is not the wall-clock minute.
    private val aStart = at(0, 51, 23)
    private val bStart = at(6, 56, 23)

    /** Record A, 293 minutes: light/deep/light/rem as the ring sends them, one stage per minute. */
    private val aStages = List(100) { SleepStage.LIGHT } + List(80) { SleepStage.DEEP } +
        List(70) { SleepStage.LIGHT } + List(43) { SleepStage.REM }
    /** Record B, 71 minutes. */
    private val bStages = List(30) { SleepStage.LIGHT } + List(20) { SleepStage.DEEP } + List(21) { SleepStage.LIGHT }

    private fun ring(start: Long, stages: List<SleepStage>) =
        buildStageBlocks("s", start, stages, recordStartAt = start)

    private val a get() = ring(aStart, aStages)
    private val b get() = ring(bStart, bStages)

    private fun end(blocks: List<SleepStageBlockEntity>) = blocks.maxOf { it.startAt + it.durationMinutes * 60_000L }

    /** What identity and the restate care about — ids are re-minted on every write. */
    private fun shape(blocks: List<SleepStageBlockEntity>) =
        blocks.sortedBy { it.startAt }.map { listOf(it.startAt, it.durationMinutes.toLong(), it.stageRaw, it.recordStartAt) }

    private fun session(blocks: List<SleepStageBlockEntity>) = SleepSessionEntity(
        id = "s", date = wakingDay,
        startAt = blocks.minOf { it.startAt }, endAt = end(blocks),
        totalMinutes = asleepMinutes(blocks), score = null,
    )

    // ── The shared function ─────────────────────────────────────────────

    @Test
    fun `extending the start adds one unstaged block before the ring's own`() {
        val edited = editedRecordBlocks(b, bStart, at(5, 46, 23), end(b))!!

        val added = edited.first()
        assertEquals(SleepStage.ADDED.name, added.stageRaw)
        assertEquals(at(5, 46, 23), added.startAt)
        assertEquals(70, added.durationMinutes)
        assertEquals("added minutes belong to the ring record", bStart, added.recordStartAt)
        assertEquals("the ring's blocks are untouched", shape(b), shape(edited.drop(1)))
    }

    @Test
    fun `extending the end adds one unstaged block after the ring's own`() {
        val edited = editedRecordBlocks(b, bStart, bStart, at(8, 30, 23))!!

        assertEquals(shape(b), shape(edited.dropLast(1)))
        val added = edited.last()
        assertEquals(SleepStage.ADDED.name, added.stageRaw)
        assertEquals(end(b), added.startAt)
        assertEquals(23, added.durationMinutes)
    }

    @Test
    fun `one edit can shorten one side and extend the other`() {
        val edited = editedRecordBlocks(b, bStart, bStart + 10 * 60_000L, at(8, 17, 23))!!

        assertEquals(bStart + 10 * 60_000L, edited.first().startAt)
        assertEquals(SleepStage.LIGHT.name, edited.first().stageRaw)
        assertEquals(20, edited.first().durationMinutes)
        assertEquals(SleepStage.ADDED.name, edited.last().stageRaw)
        assertEquals(10, edited.last().durationMinutes)
        assertEquals(61, measuredAsleepMinutes(edited))
        assertEquals(71, asleepMinutes(edited))
    }

    @Test
    fun `shortening alone works exactly as before, adding nothing`() {
        val edited = editedRecordBlocks(a, aStart, aStart + 30 * 60_000L, end(a) - 13 * 60_000L)!!

        assertEquals(0, addedMinutes(edited))
        assertEquals(293 - 30 - 13, asleepMinutes(edited))
    }

    @Test
    fun `a whole-minute pick snaps inwards onto the record's grid`() {
        // An edit stored before #106 holds the picker's whole minutes, not the record's :23 s.
        val edited = editedRecordBlocks(b, bStart, at(5, 46), at(8, 30))!!

        assertEquals("start rounds up into the picked minute", at(5, 46, 23), edited.first().startAt)
        assertEquals("end rounds down, never past the bound", at(8, 29, 23), end(edited))
    }

    @Test
    fun `an edit keeping none of the ring's minutes is refused`() {
        assertNull(editedRecordBlocks(b, bStart, at(5, 0, 23), bStart))
        assertNull(editedRecordBlocks(b, bStart, end(b), at(8, 30, 23)))
    }

    @Test
    fun `editing back inside the ring's bounds removes the added minutes`() {
        val extended = editedRecordBlocks(b, bStart, at(5, 46, 23), end(b))!!
        val undone = editedRecordBlocks(extended, bStart, bStart, end(b))!!

        assertEquals(shape(b), shape(undone))
    }

    // ── Re-sync: the import reproduces the edit ───────────────────────────

    @Test
    fun `re-importing the night re-creates the edit's blocks exactly`() {
        val editStart = at(5, 46, 23)
        val editEnd = end(b) - 5 * 60_000L
        // What `SleepRecordDeletion.edit` stores: the stored record through the shared function.
        val afterEdit = editedRecordBlocks(b, bStart, editStart, editEnd)!!
        // What the next sync builds: the ring's raw stages, then the stored edit re-applied.
        val reimported = editedRecordBlocks(ring(bStart, bStages), bStart, editStart, editEnd)!!

        assertEquals(shape(afterEdit), shape(reimported))
        // Re-applying to its own output is a no-op, so a second edit pass changes nothing either.
        assertEquals(shape(afterEdit), shape(editedRecordBlocks(afterEdit, bStart, editStart, editEnd)!!))

        // And the day's merge is idempotent: replacing the edited record's interval with the
        // re-import leaves the stored night exactly as it was, record A included.
        val stored = a + afterEdit
        val merged = replaceOverlappingSleepBlocks(
            existing = stored,
            replacements = reimported,
            replacementStart = reimported.minOf { it.startAt },
            replacementEnd = end(reimported),
        )
        assertEquals(shape(stored), shape(merged))
    }

    @Test
    fun `a stored edit gives way to a neighbour record at import`() {
        // The stored edit reaches back to 5:30, but record A ends 5:44: the ring's minutes win.
        val (nightStart, nightEnd) = nightWindow(wakingDay, zone)
        val (start, endAt) = clampEditToNeighbours(
            at(5, 30, 23), end(b), bStart, end(b), others = a, nightStart = nightStart, nightEnd = nightEnd,
        )
        assertEquals(end(a), start)
        assertEquals(end(b), endAt)
    }

    // ── Limits and the dialog's picks ────────────────────────────────────

    private fun limitsForB() = recordEditLimits(
        run = sleepRecordRuns(a + b)[1],
        previous = sleepRecordRuns(a + b)[0],
        next = null,
        wakingDay = wakingDay,
        zone = zone,
    )

    @Test
    fun `extending into the gap stops at the previous record's end`() {
        val limits = limitsForB()
        assertEquals(end(a), limits.earliestStart)
        assertEquals(RecordEditLimit.PREVIOUS_RECORD, limits.earliestStartLimit)

        val pick = clampStartPick(at(5, 30), limits)
        assertEquals(end(a), pick.at)
        assertEquals(RecordEditLimit.PREVIOUS_RECORD, pick.limit)

        // The reporter's pick: 5:46, inside the gap, no message.
        val ok = clampStartPick(at(5, 46), limits)
        assertEquals(at(5, 46, 23), ok.at)
        assertNull(ok.limit)
    }

    @Test
    fun `extending outward is capped at two hours past the ring's bounds`() {
        val limits = limitsForB()
        assertEquals(RecordEditLimit.OUTWARD_CAP, limits.latestEndLimit)
        assertEquals(end(b) + 120 * 60_000L, limits.latestEnd)

        val pick = clampEndPick(at(11, 0), limits)
        assertEquals(end(b) + 120 * 60_000L, pick.at)
        assertEquals(RecordEditLimit.OUTWARD_CAP, pick.limit)

        val first = recordEditLimits(sleepRecordRuns(a + b)[0], null, sleepRecordRuns(a + b)[1], wakingDay, zone)
        assertEquals(aStart - 120 * 60_000L, first.earliestStart)
        assertEquals(RecordEditLimit.OUTWARD_CAP, first.earliestStartLimit)
        assertEquals(bStart, first.latestEnd)
        assertEquals(RecordEditLimit.NEXT_RECORD, first.latestEndLimit)
    }

    @Test
    fun `the cap counts from the ring's bounds, not a previous extension`() {
        val extended = editedRecordBlocks(b, bStart, bStart, end(b) + 60 * 60_000L)!!
        val limits = recordEditLimits(sleepRecordRuns(extended).single(), null, null, wakingDay, zone)
        assertEquals(end(b) + 120 * 60_000L, limits.latestEnd)
    }

    @Test
    fun `an extension never crosses the night's 7 PM turnover`() {
        // A record starting 8:10 PM the evening before: two hours back would be 6:10 PM.
        val start = at(-4, 10, 23) // 20:10:23 on Oct 7
        val record = ring(start, List(60) { SleepStage.LIGHT })
        val limits = recordEditLimits(sleepRecordRuns(record).single(), null, null, wakingDay, zone)
        assertEquals(RecordEditLimit.NIGHT_BOUNDARY, limits.earliestStartLimit)
        assertEquals(at(-5, 0, 23), limits.earliestStart) // 19:00:23, the first grid point inside
    }

    @Test
    fun `picking the minute the button shows is not a change and not a message`() {
        val limits = limitsForB()
        val start = clampStartPick(at(6, 56), limits)
        assertEquals(bStart, start.at)
        assertNull(start.limit)
        val endPick = clampEndPick(at(8, 7), limits)
        assertEquals(end(b), endPick.at)
        assertNull(endPick.limit)
    }

    @Test
    fun `shortening must keep a minute the ring measured`() {
        val limits = limitsForB()
        val pick = clampStartPick(at(9, 0), limits)
        assertEquals(RecordEditLimit.RING_MINUTES, pick.limit)
        assertEquals(end(b) - 60_000L, pick.at)
    }

    // ── Asleep, but unstaged and unscored ────────────────────────────────

    @Test
    fun `added minutes count as asleep but not in the score or the stage shares`() {
        val extended = a + editedRecordBlocks(b, bStart, end(a), end(b))!!
        val ringOnly = a + b

        assertEquals(asleepMinutes(ringOnly) + 72, asleepMinutes(extended))
        assertEquals(asleepMinutes(ringOnly), measuredAsleepMinutes(extended))
        assertEquals(72, addedMinutes(extended))

        val withAdded = SleepScore.calculate(session(extended), extended)
        val without = SleepScore.calculate(session(ringOnly), ringOnly)
        assertEquals(without.deepPct, withAdded.deepPct)
        assertEquals(without.lightPct, withAdded.lightPct)
        assertEquals(without.score, withAdded.score)
        assertEquals(
            sleepStageScore(100, measuredAsleepMinutes(ringOnly)),
            sleepStageScore(100, measuredAsleepMinutes(extended)),
        )
    }

    @Test
    fun `covering the gap removes it from awake and from the hypnogram`() {
        val ringOnly = a + b
        assertEquals(72, awakeMinutes(ringOnly))

        val extended = a + editedRecordBlocks(b, bStart, end(a), end(b))!!
        assertEquals("still two records", 2, sleepRecordRuns(extended).size)
        assertEquals(0, awakeMinutes(extended))
        assertEquals(extended.size, hypnogramBlocks(extended).size)

        // A partial cover leaves the rest of the gap as a waking.
        val partial = a + editedRecordBlocks(b, bStart, at(5, 46, 23), end(b))!!
        assertEquals(2, awakeMinutes(partial))
    }

    @Test
    fun `closing an hour-plus gap makes the two records one night`() {
        assertEquals(2, SleepSegmentation.segment(a + b).size)
        val extended = a + editedRecordBlocks(b, bStart, end(a), end(b))!!
        assertEquals(1, SleepSegmentation.segment(extended).size)
    }

    @Test
    fun `added minutes export to Health Connect as generic asleep`() {
        assertEquals(
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            HealthConnectTypeMappings.sleepStageType(SleepStage.ADDED.name),
        )
        assertTrue(SleepSessionRecord.STAGE_TYPE_SLEEPING != SleepSessionRecord.STAGE_TYPE_UNKNOWN)
    }
}
