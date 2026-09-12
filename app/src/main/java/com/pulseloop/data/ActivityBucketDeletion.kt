package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.dao.MeasurementDeletionDao
import com.pulseloop.data.entity.ActivityBucketEntity
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
 * Three rules make it stick, and all of them live here rather than in any caller:
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
 *  * **Remember what it took with it.** The tombstone only guards the history path. The ring also
 *    pushes a *live* `ActivityUpdate` carrying its own cumulative count for the day — seconds
 *    apart, and again on every reconnect — which still includes the deleted block, and
 *    `EventPersistenceSubscriber.upsertActivityDaily` ratchets the day up against it. So the
 *    restated total survived for about as long as it took the next frame to arrive, which is the
 *    same silent failure the rule above exists to prevent. `ActivityDailyEntity.deletedSteps` /
 *    `deletedDistanceMeters` carry the day's deficit so every later cumulative reading can be
 *    corrected by it. This is also why the feature is only offered on today: it is the only day a
 *    live counter is still moving.
 *
 * **Calories are dropped rather than corrected.** A bucket carries steps and distance and no
 * calorie field, so there is no figure to subtract from the ring's own daily total — and that total
 * demonstrably includes the block the user removed. The day's device-reported calories are cleared
 * instead, which makes [com.pulseloop.service.DailyCalorieEstimator.deviceReportedCalories] fall
 * through to the app's own estimate, recomputed here from the buckets that remain. An estimate
 * consistent with the restated day beats a device figure known to be wrong.
 */
object ActivityBucketDeletion {

    /**
     * Delete the bucket starting at [startEpoch], remember it, and restate its day.
     *
     * Returns true when a bucket was actually removed.
     */
    suspend fun delete(db: PulseLoopDatabase, startEpoch: Long): Boolean {
        val deleted = db.withTransaction {
            val day = com.pulseloop.util.TimeUtil.startOfDayLocal(startEpoch)
            val bucket = db.activityBucketDao().byDay(day).firstOrNull { it.startEpoch == startEpoch }
                ?: return@withTransaction null

            db.measurementDeletionDao().recordActivity(listOf(startEpoch))
            db.activityBucketDao().deleteByStart(startEpoch)
            restateDay(db, day, bucket)
            day
        }
        // Outside the transaction: the estimator reads a day's worth of HR samples and buckets of
        // its own, and re-deriving the calorie figure is not part of what must be atomic about the
        // deletion. Re-deriving it here rather than leaving it to the next completed sync is the
        // point — the headline calories would otherwise keep the deleted block's contribution for
        // however long that takes.
        if (deleted != null) recomputeCalories(db, deleted)
        return deleted != null
    }

    /**
     * Rewrite [day]'s totals as the sum of the buckets it still has, and record what [removed] took
     * with it.
     *
     * Deliberately unconditional — see the class note on the ratchet. A day whose every bucket has
     * been deleted keeps its row at zero rather than being removed, so the day still reads as
     * "synced, nothing recorded" rather than reverting to whatever a later partial sync writes.
     */
    private suspend fun restateDay(db: PulseLoopDatabase, day: Long, removed: ActivityBucketEntity) {
        val buckets = db.activityBucketDao().byDay(day)
        val existing = db.activityDailyDao().byDay(day)
        db.activityDailyDao().upsert(
            (existing ?: ActivityDailyEntity(date = day, source = "ring_history")).copy(
                steps = buckets.sumOf { it.steps },
                distanceMeters = buckets.sumOf { it.distanceMeters },
                deletedSteps = (existing?.deletedSteps ?: 0) + removed.steps,
                deletedDistanceMeters = (existing?.deletedDistanceMeters ?: 0.0) + removed.distanceMeters,
                // The ring's own figure counted the deleted block and can't be corrected for it —
                // see the class note. Zero reads as "no device figure" to the estimator.
                calories = 0.0,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun recomputeCalories(db: PulseLoopDatabase, day: Long) {
        val profile = db.userProfileDao().get() ?: return
        com.pulseloop.service.DailyCalorieEstimator.recompute(
            day, db,
            com.pulseloop.service.DailyCalorieEstimator.Profile(
                sex = profile.sex, age = profile.age,
                weightKg = profile.weightKg, heightCm = profile.heightCm,
            ),
        )
    }

    /** The tombstone key for [startEpoch] — exposed so the write path and tests name it one way. */
    fun tombstoneId(startEpoch: Long): String = MeasurementDeletionDao.activityBucketId(startEpoch)

    /**
     * What a day's stored total becomes when the ring pushes a cumulative [ringTotal] for it.
     *
     * Lives here rather than in [com.pulseloop.service.EventPersistenceSubscriber] because it is
     * the deletion rule, not the write path's: a deleted bucket changes what the ring's own counter
     * *means* for that day, and a reader who only sees the ratchet has no reason to suspect it.
     * [stored] still wins where it leads, so the counter's normal behaviour — climbing ahead of the
     * bucket history through the day — is unchanged on a day with nothing deleted.
     */
    fun ratchetAgainstRing(stored: Int, ringTotal: Int, deletedSteps: Int): Int =
        maxOf(stored, (ringTotal - deletedSteps).coerceAtLeast(0))

    /** [ratchetAgainstRing] for distance. */
    fun ratchetAgainstRing(stored: Double, ringTotal: Double, deletedMeters: Double): Double =
        maxOf(stored, (ringTotal - deletedMeters).coerceAtLeast(0.0))
}
