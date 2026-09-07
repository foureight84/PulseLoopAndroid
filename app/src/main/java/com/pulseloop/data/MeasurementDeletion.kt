package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.ring.MeasurementKind

/**
 * Deleting individual readings (issue #60).
 *
 * A measurement can be wrong in ways nothing downstream can detect — a ring worn loosely, a
 * measurement started by mistake, a reading taken mid-movement — and until now there was no way to
 * remove one, so a bad value stayed in the record forever and dragged every average computed over
 * it. Deletion only: a recorded health value may be removed, never edited into a different number.
 *
 * Two rules make a delete actually stick, and both live here so no caller has to remember them:
 *
 *  * **Tombstone anything the ring can re-send.** History rows are keyed `history:<kind>:<ts>` and
 *    written with `upsert` so a re-synced day is idempotent; without a tombstone the next sync
 *    would restore exactly the reading the user removed.
 *  * **Delete a blood-pressure reading as a pair.** One reading is stored as two rows (systolic and
 *    diastolic) sharing a timestamp. Removing one would leave a half reading that charts as a
 *    systolic with no diastolic.
 */
object MeasurementDeletion {

    /** The two rows one blood-pressure reading is stored as. */
    private val BLOOD_PRESSURE_KINDS = listOf(
        MeasurementKind.BLOOD_PRESSURE_SYSTOLIC,
        MeasurementKind.BLOOD_PRESSURE_DIASTOLIC,
    )

    /**
     * Delete [measurements] and remember the ones a later sync could rewrite. Atomic: a delete that
     * lost its tombstone half would come back on the next sync, which is the bug this exists to
     * prevent.
     *
     * Returns the number of rows removed.
     */
    suspend fun delete(db: PulseLoopDatabase, measurements: List<MeasurementEntity>): Int {
        if (measurements.isEmpty()) return 0
        return db.withTransaction {
            db.measurementDeletionDao().record(measurements)
            db.measurementDao().deleteByIds(measurements.map { it.id })
            measurements.size
        }
    }

    /** [delete], resolving ids back to rows first — what the readings list has to hand. */
    suspend fun deleteByIds(db: PulseLoopDatabase, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        return delete(db, db.measurementDao().byIds(ids))
    }

    /**
     * Delete the blood-pressure reading taken at [timestamp] — both of its rows, whichever of them
     * the caller happened to be looking at.
     */
    suspend fun deleteBloodPressureAt(db: PulseLoopDatabase, timestamp: Long): Int {
        val rows = BLOOD_PRESSURE_KINDS.flatMap { kind ->
            db.measurementDao().range(kind.name, timestamp, timestamp)
        }
        return delete(db, rows)
    }
}
