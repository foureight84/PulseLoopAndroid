package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.dao.MeasurementDeletionDao
import com.pulseloop.data.entity.ActivityDailyEntity

/**
 * Deleting an individual activity bucket (issue #70).
 *
 * The sibling of [MeasurementDeletion], and it exists for the same reason: a ring logs activity in
 * intraday blocks, so a day's step total is the sum of a couple of dozen rows rather than one
 * figure, and anything that inflates one of them — the ring carried rather than worn, a rough car
 * journey — is stuck in that total for good. The vitals side got a delete in #60; this is the rest
 * of the same request.
 *
 * Two rules make it stick, and both live here rather than in any caller:
 *
 *  * **Tombstone it.** `activity_buckets` is keyed by the bucket's start time and upserted, so the
 *    next sync of that day writes the bucket straight back. `MeasurementDeletionDao.recordActivity`
 *    remembers it and [com.pulseloop.service.EventPersistenceSubscriber] checks before writing.
 *  * **Recompute the day without the ratchet.** A day's total is the sum of its buckets, but *today*
 *    is ratcheted against the existing row, because the live cumulative step count legitimately
 *    leads the bucket history and a plain recompute would make today's count visibly drop on every
 *    reconnect. A deletion is the one case where the total must be allowed to fall — ratcheting
 *    would delete the row and leave the number it contributed sitting in the headline, which reads
 *    as the delete having silently failed.
 */
object ActivityBucketDeletion {

    /**
     * Delete the bucket starting at [startEpoch], remember it, and restate its day.
     *
     * Returns true when a bucket was actually removed.
     */
    suspend fun delete(db: PulseLoopDatabase, startEpoch: Long): Boolean {
        return db.withTransaction {
            val day = com.pulseloop.util.TimeUtil.startOfDayLocal(startEpoch)
            val existed = db.activityBucketDao().byDay(day).any { it.startEpoch == startEpoch }
            if (!existed) return@withTransaction false

            db.measurementDeletionDao().recordActivity(listOf(startEpoch))
            db.activityBucketDao().deleteByStart(startEpoch)
            restateDay(db, day)
            true
        }
    }

    /**
     * Rewrite [day]'s totals as the sum of the buckets it still has.
     *
     * Deliberately unconditional — see the class note on the ratchet. A day whose every bucket has
     * been deleted keeps its row at zero rather than being removed, so the day still reads as
     * "synced, nothing recorded" rather than reverting to whatever a later partial sync writes.
     */
    private suspend fun restateDay(db: PulseLoopDatabase, day: Long) {
        val buckets = db.activityBucketDao().byDay(day)
        val existing = db.activityDailyDao().byDay(day)
        db.activityDailyDao().upsert(
            (existing ?: ActivityDailyEntity(date = day, source = "ring_history")).copy(
                steps = buckets.sumOf { it.steps },
                distanceMeters = buckets.sumOf { it.distanceMeters },
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** The tombstone key for [startEpoch] — exposed so the write path and tests name it one way. */
    fun tombstoneId(startEpoch: Long): String = MeasurementDeletionDao.activityBucketId(startEpoch)
}
