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
