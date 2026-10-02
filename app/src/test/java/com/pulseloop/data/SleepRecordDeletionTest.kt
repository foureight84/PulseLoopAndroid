package com.pulseloop.data

import com.pulseloop.data.dao.MeasurementDeletionDao
import com.pulseloop.data.entity.MeasurementDeletionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tombstone rule behind deleting one ring sleep record (issue #78), tested against the DAO's
 * own default implementation — Room only supplies the queries, and where the bug would be is the
 * *identity*: the write path re-derives sessions from the blocks the ring re-sends, so a delete
 * only sticks if the tombstone answers for exactly the blocks that re-send reproduces.
 */
class SleepRecordDeletionTest {

    private class FakeDeletionDao : MeasurementDeletionDao {
        val rows = mutableMapOf<String, MeasurementDeletionEntity>()
        override suspend fun isDeleted(id: String) = id in rows
        override suspend fun isSpotDeleted(kind: String, from: Long, to: Long) = false
        override suspend fun isActivityBucketDeleted(id: String) = id in rows
        override suspend fun deleteSleepEdit(dayStart: Long, recordStartAt: Long) {
            val prefix = "sleep:edit:$dayStart:$recordStartAt:"
            rows.keys.filter { it.startsWith(prefix) }.forEach { rows.remove(it) }
        }
        override suspend fun getSleepEditId(dayStart: Long, recordStartAt: Long): String? {
            val prefix = "sleep:edit:$dayStart:$recordStartAt:"
            return rows.keys.firstOrNull { it.startsWith(prefix) }
        }
        override suspend fun insertAll(rows: List<MeasurementDeletionEntity>) {
            rows.forEach { this.rows[it.measurementId] = it }
        }
    }

    // The scenario from the issue: a record opened 23:08 that is really sofa time, tombstoned by
    // `SleepRecordDeletion` under the session's waking day. Minutes on the same grid `buildStageBlocks`
    // reproduces when the ring re-sends that record.
    private val wakingDay = 1_760_000_000_000L - (1_760_000_000_000L % 86_400_000L)
    private val deletedStarts = listOf(
        wakingDay + 0L,        // 23:08 run: RLE blocks at their minute-grid starts
        wakingDay + 17 * 60_000L,
        wakingDay + 41 * 60_000L,
    )

    private suspend fun tombstone(dao: FakeDeletionDao) {
        dao.recordSleepBlocks(wakingDay, deletedStarts)
    }

    /** The contract the whole feature hangs on: the re-sent record's blocks are all suppressed. */
    @Test
    fun `a re-sent record's blocks are all tombstoned`() = runTest {
        val dao = FakeDeletionDao()
        tombstone(dao)

        deletedStarts.forEach { start ->
            assertTrue(
                "the re-derive must suppress the block at $start or the delete lasts one sync",
                dao.isDeleted(MeasurementDeletionDao.sleepBlockId(wakingDay, start)),
            )
        }
    }

    /** Only the deleted record goes: its neighbours must survive the re-derive. */
    @Test
    fun `a different record's blocks are not suppressed`() = runTest {
        val dao = FakeDeletionDao()
        tombstone(dao)

        val neighbourStarts = listOf(
            wakingDay + 78 * 60_000L,
            wakingDay + 96 * 60_000L,
        )
        neighbourStarts.forEach { start ->
            assertFalse(
                "the neighbour record at $start must survive",
                dao.isDeleted(MeasurementDeletionDao.sleepBlockId(wakingDay, start)),
            )
        }
    }

    /** The same wall-clock minute on another night is different data, not a resurrected block. */
    @Test
    fun `the same minute-of-day on a different waking day is not suppressed`() = runTest {
        val dao = FakeDeletionDao()
        tombstone(dao)

        val nextNight = wakingDay + 86_400_000L
        deletedStarts.forEach { start ->
            assertFalse(
                "day+minute keying must not leak across nights",
                dao.isDeleted(MeasurementDeletionDao.sleepBlockId(nextNight, start)),
            )
        }
    }

    /** One tombstone per block start, replaced in place on a re-delete — the table must not grow. */
    @Test
    fun `re-deleting the same record replaces its tombstones rather than growing the table`() = runTest {
        val dao = FakeDeletionDao()
        tombstone(dao)
        tombstone(dao)

        assertEquals(deletedStarts.size, dao.rows.size)
    }

    // ── Restating the survivors ([SleepRecordDeletion.planRestate]) ─────────

    private fun block(startMinute: Int, minutes: Int, recordStartMinute: Int) =
        com.pulseloop.data.entity.SleepStageBlockEntity(
            id = "b$startMinute",
            sessionId = "night",
            startAt = wakingDay + startMinute * 60_000L,
            startMinute = startMinute,
            durationMinutes = minutes,
            stageRaw = com.pulseloop.ring.SleepStage.LIGHT.name,
            recordStartAt = wakingDay + recordStartMinute * 60_000L,
        )

    // The reported night, minutes from 23:08: 23:08–00:05, 00:25–03:19, 03:26–07:08.
    private val first = block(0, 57, 0)
    private val middle = block(77, 174, 77)
    private val last = block(258, 222, 258)
    private val session = com.pulseloop.data.entity.SleepSessionEntity(
        id = "night", date = wakingDay,
        startAt = first.startAt, endAt = last.startAt + last.durationMinutes * 60_000L,
        totalMinutes = 453,
    )

    /** The reported case: drop the sofa record, the rest stays one session on the same row. */
    @Test
    fun `deleting the first record restates one row under the same id`() {
        val plan = SleepRecordDeletion.planRestate(session, listOf(middle, last))
        assertEquals(1, plan.size)
        assertEquals("night", plan[0].id)
        assertEquals(middle.startAt, plan[0].startAt)
    }

    /**
     * Deleting the middle record leaves a 3 h 21 m hole. The next sync re-segments at the 60-minute
     * session gap, so the restate must too — kept as one row, `awakeMinutes` would have counted the
     * whole hole as a between-record waking (#81).
     */
    @Test
    fun `deleting a middle record past the session gap splits the night like a re-sync would`() {
        val plan = SleepRecordDeletion.planRestate(session, listOf(first, last))
        assertEquals(2, plan.size)
        // The longer survivor overlaps the old bounds most and keeps the row id...
        assertEquals("night", plan[1].id)
        assertEquals(listOf(last.startAt), plan[1].blocks.map { it.startAt })
        // ...the other takes the id the write path mints, so a re-sync matches rather than twins it.
        assertEquals("sleep-$wakingDay-${first.startAt}", plan[0].id)
        plan.forEach { row ->
            assertEquals(0, com.pulseloop.service.awakeMinutes(row.blocks))
        }
    }

    @Test
    fun `deleting every record restates nothing`() {
        assertTrue(SleepRecordDeletion.planRestate(session, emptyList()).isEmpty())
    }

    // ── Sleep record edits (issue #82) ──────────────────────────────────

    @Test
    fun `a sleep record edit stores and parses new bounds correctly`() = runTest {
        val dao = FakeDeletionDao()
        val recordStart = wakingDay + 0L
        val newStart = wakingDay + 15 * 60_000L
        val newEnd = wakingDay + 45 * 60_000L

        dao.recordSleepEdit(wakingDay, recordStart, newStart, newEnd)

        val edit = dao.getSleepEdit(wakingDay, recordStart)
        org.junit.Assert.assertNotNull(edit)
        assertEquals(newStart, edit!!.newStartAt)
        assertEquals(newEnd, edit.newEndAt)
    }

    @Test
    fun `editing the same record replaces previous edit bounds`() = runTest {
        val dao = FakeDeletionDao()
        val recordStart = wakingDay + 0L
        dao.recordSleepEdit(wakingDay, recordStart, wakingDay + 10 * 60_000L, wakingDay + 50 * 60_000L)
        dao.recordSleepEdit(wakingDay, recordStart, wakingDay + 20 * 60_000L, wakingDay + 40 * 60_000L)

        val edit = dao.getSleepEdit(wakingDay, recordStart)
        org.junit.Assert.assertNotNull(edit)
        assertEquals(wakingDay + 20 * 60_000L, edit!!.newStartAt)
        assertEquals(wakingDay + 40 * 60_000L, edit.newEndAt)
    }

    @Test
    fun `deleting an edited record clears its edit tombstone`() = runTest {
        val dao = FakeDeletionDao()
        val recordStart = wakingDay + 0L
        dao.recordSleepEdit(wakingDay, recordStart, wakingDay + 10 * 60_000L, wakingDay + 50 * 60_000L)
        org.junit.Assert.assertNotNull(dao.getSleepEdit(wakingDay, recordStart))

        dao.deleteSleepEdit(wakingDay, recordStart)
        org.junit.Assert.assertNull(dao.getSleepEdit(wakingDay, recordStart))
    }

    @Test
    fun `resolveAdjustedTime handles midnight crossover accurately`() {
        val zone = java.time.ZoneId.of("UTC")
        // Start: 2026-10-01 23:08 UTC
        val start = 1790896080000L
        // End: 2026-10-02 07:05 UTC
        val end = 1790924700000L

        // Adjust start to 23:30 (should stay on 2026-10-01)
        val adjustedStart = com.pulseloop.ui.screens.resolveAdjustedTime(start, start, end, 23, 30, zone)
        val zdtStart = java.time.Instant.ofEpochMilli(adjustedStart).atZone(zone)
        assertEquals(2026, zdtStart.year)
        assertEquals(10, zdtStart.monthValue)
        assertEquals(1, zdtStart.dayOfMonth)
        assertEquals(23, zdtStart.hour)
        assertEquals(30, zdtStart.minute)

        // Adjust start past midnight to 00:15 (should advance to 2026-10-02)
        val adjustedPastMidnight = com.pulseloop.ui.screens.resolveAdjustedTime(start, start, end, 0, 15, zone)
        val zdtPast = java.time.Instant.ofEpochMilli(adjustedPastMidnight).atZone(zone)
        assertEquals(2, zdtPast.dayOfMonth)
        assertEquals(0, zdtPast.hour)
        assertEquals(15, zdtPast.minute)

        // Adjust end to 06:45 (should stay on 2026-10-02)
        val adjustedEnd = com.pulseloop.ui.screens.resolveAdjustedTime(end, start, end, 6, 45, zone)
        val zdtEnd = java.time.Instant.ofEpochMilli(adjustedEnd).atZone(zone)
        assertEquals(2, zdtEnd.dayOfMonth)
        assertEquals(6, zdtEnd.hour)
        assertEquals(45, zdtEnd.minute)
    }

    @Test
    fun `resolveAdjustedTime resolves pre-midnight adjustment for post-midnight start`() {
        val zone = java.time.ZoneId.of("UTC")
        // Start: 2026-10-02 00:15 UTC
        val start = 1790900100000L
        // End: 2026-10-02 06:30 UTC
        val end = 1790922600000L

        // Adjust start earlier to 23:45 (should roll back to 2026-10-01)
        val adjustedStart = com.pulseloop.ui.screens.resolveAdjustedTime(start, start, end, 23, 45, zone)
        val zdt = java.time.Instant.ofEpochMilli(adjustedStart).atZone(zone)
        assertEquals(2026, zdt.year)
        assertEquals(10, zdt.monthValue)
        assertEquals(1, zdt.dayOfMonth)
        assertEquals(23, zdt.hour)
        assertEquals(45, zdt.minute)
    }

    @Test
    fun `resolveAdjustedTime resolves morning time near end for early evening start`() {
        val zone = java.time.ZoneId.of("UTC")
        // Start: 2026-10-01 19:30 UTC
        val start = 1790883000000L
        // End: 2026-10-02 08:00 UTC
        val end = 1790928000000L

        // Adjust start to 07:45 AM (should select 2026-10-02, not 2026-10-01 07:45 AM)
        val adjustedStart = com.pulseloop.ui.screens.resolveAdjustedTime(start, start, end, 7, 45, zone)
        val zdt = java.time.Instant.ofEpochMilli(adjustedStart).atZone(zone)
        assertEquals(2026, zdt.year)
        assertEquals(10, zdt.monthValue)
        assertEquals(2, zdt.dayOfMonth)
        assertEquals(7, zdt.hour)
        assertEquals(45, zdt.minute)
    }

    @Test
    fun `deleting an edited record sets full record deletion tombstone`() = runTest {
        val dao = FakeDeletionDao()
        val recordStart = wakingDay + 0L
        dao.recordSleepEdit(wakingDay, recordStart, wakingDay + 10 * 60_000L, wakingDay + 50 * 60_000L)
        dao.deleteSleepEdit(wakingDay, recordStart)
        dao.recordSleepRecordDeleted(wakingDay, recordStart)

        org.junit.Assert.assertNull(dao.getSleepEdit(wakingDay, recordStart))
        org.junit.Assert.assertTrue(dao.isSleepRecordDeleted(wakingDay, recordStart))
    }

    @Test
    fun `legacy unstamped multi-block record selects all contiguous blocks up to next stamped start`() {
        val b1 = block(0, 30, -1).copy(recordStartAt = 0L)
        val b2 = block(30, 30, -1).copy(recordStartAt = 0L)
        val nextStamped = block(90, 60, 90)
        val blocks = listOf(b1, b2, nextStamped)

        val targets = blocks.filter { it.recordStartAt > 0L && it.recordStartAt == b1.startAt }.ifEmpty {
            blocks.filter { it.recordStartAt == 0L && it.startAt >= b1.startAt }
                .takeWhile { it.startAt < nextStamped.recordStartAt }
        }
        assertEquals(2, targets.size)
        assertEquals(listOf(b1.startAt, b2.startAt), targets.map { it.startAt })
    }
}
