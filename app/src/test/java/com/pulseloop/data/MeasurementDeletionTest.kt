package com.pulseloop.data

import com.pulseloop.data.dao.MeasurementDeletionDao
import com.pulseloop.data.entity.MeasurementDeletionEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.service.historyMeasurementId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tombstone rule behind deleting a reading (issue #60), tested against the DAO's own default
 * implementation — Room only supplies the queries, so `record`'s decision about *what* is worth
 * remembering is plain logic and is where the bug would be.
 */
class MeasurementDeletionTest {

    private class FakeDeletionDao : MeasurementDeletionDao {
        val rows = mutableMapOf<String, MeasurementDeletionEntity>()
        override suspend fun isDeleted(id: String) = id in rows
        override suspend fun isSpotDeleted(kind: String, from: Long, to: Long) = rows.values.any {
            it.kindRaw == kind &&
                it.measurementId.startsWith(MeasurementDeletionDao.SPOT_ID_PREFIX) &&
                it.timestamp in from..to
        }
        override suspend fun insertAll(rows: List<MeasurementDeletionEntity>) {
            rows.forEach { this.rows[it.measurementId] = it }
        }
    }

    private fun measurement(id: String, kind: MeasurementKind, timestamp: Long, source: String) =
        MeasurementEntity(
            id = id, kindRaw = kind.name, value = 46.0, unit = "bpm",
            timestamp = timestamp, sourceRaw = source,
        )

    /**
     * A history reading is written with `upsert` under a deterministic id, so the next sync of that
     * day would restore it. That is exactly the row the tombstone exists for.
     */
    @Test
    fun `a history reading is remembered as deleted`() = runTest {
        val dao = FakeDeletionDao()
        val id = historyMeasurementId(MeasurementKind.HEART_RATE, 1_700_000_000_000L)

        dao.record(listOf(measurement(id, MeasurementKind.HEART_RATE, 1_700_000_000_000L, "history")))

        assertTrue(dao.isDeleted(id))
        assertEquals(MeasurementKind.HEART_RATE.name, dao.rows.getValue(id).kindRaw)
        assertEquals(1_700_000_000_000L, dao.rows.getValue(id).timestamp)
    }

    /**
     * A live reading's id is a fresh UUID that nothing regenerates. Tombstoning it would grow the
     * table forever for a row that can never come back on its own.
     */
    @Test
    fun `a live reading is deleted without a tombstone`() = runTest {
        val dao = FakeDeletionDao()
        val id = java.util.UUID.randomUUID().toString()

        dao.record(listOf(measurement(id, MeasurementKind.HEART_RATE, 1_700_000_000_000L, "live")))

        assertEquals("nothing to remember for a one-off id", 0, dao.rows.size)
    }

    @Test
    fun `a mixed batch remembers only the regenerable rows`() = runTest {
        val dao = FakeDeletionDao()
        val historyId = historyMeasurementId(MeasurementKind.SPO2, 42L)

        dao.record(
            listOf(
                measurement(historyId, MeasurementKind.SPO2, 42L, "history"),
                measurement(java.util.UUID.randomUUID().toString(), MeasurementKind.SPO2, 42L, "live"),
            )
        )

        assertEquals(1, dao.rows.size)
        assertTrue(dao.isDeleted(historyId))
    }

    /**
     * A `spot` row is a UUID like any live row, but the reading in it *is* regenerable: the ring
     * logged that measurement itself and hands it back under a `history:` id on the next sync. With
     * only an id tombstone the ring's copy walked straight back in and the reading had to be deleted
     * twice. The tombstone records a range instead, because the ring stamps its log to the minute
     * rather than to our settled instant.
     */
    @Test
    fun `a deleted spot reading suppresses the ring's copy of it`() = runTest {
        val dao = FakeDeletionDao()
        val at = 1_700_000_000_000L

        dao.record(listOf(measurement(java.util.UUID.randomUUID().toString(), MeasurementKind.HEART_RATE, at, "spot")))

        assertEquals(1, dao.rows.size)
        assertTrue("the ring's copy 40 s later", dao.isSpotDeleted(MeasurementKind.HEART_RATE.name, at - 50_000, at + 50_000))
        assertEquals(
            "a different kind at the same moment is a different reading",
            false, dao.isSpotDeleted(MeasurementKind.SPO2.name, at - 50_000, at + 50_000),
        )
        assertEquals(
            "an all-day sample an hour away is not that measurement",
            false, dao.isSpotDeleted(MeasurementKind.HEART_RATE.name, at + 3_600_000, at + 3_700_000),
        )
    }

    /** Re-deleting the same spot reading must replace its tombstone, not add one. */
    @Test
    fun `a spot tombstone is keyed deterministically`() = runTest {
        val dao = FakeDeletionDao()
        val at = 1_700_000_000_000L

        repeat(3) {
            dao.record(listOf(measurement(java.util.UUID.randomUUID().toString(), MeasurementKind.SPO2, at, "spot")))
        }

        assertEquals(1, dao.rows.size)
    }

    /**
     * `record` matches the source string the service layer writes. The two constants are declared
     * apart so the DAO does not depend on the service package, which makes this the tripwire: if
     * they drift, a deleted spot reading silently comes back on the next sync.
     */
    @Test
    fun `the spot source the DAO matches is the one the service writes`() {
        assertEquals(
            com.pulseloop.service.EventPersistenceSubscriber.SOURCE_SPOT,
            MeasurementDeletionDao.SPOT_SOURCE,
        )
    }

    /**
     * The id scheme lives in `EventPersistenceSubscriber` and the prefix that recognises it lives
     * on the DAO. If those two ever drift, deletes of history rows silently stop sticking and the
     * readings come back on the next sync — with nothing failing. This is the tripwire.
     */
    @Test
    fun `every history id carries the prefix the tombstone rule matches on`() {
        for (kind in MeasurementKind.entries) {
            val id = historyMeasurementId(kind, 1_700_000_000_000L)
            assertTrue(
                "$kind history id must start with ${MeasurementDeletionDao.HISTORY_ID_PREFIX}: $id",
                id.startsWith(MeasurementDeletionDao.HISTORY_ID_PREFIX),
            )
        }
    }
}
