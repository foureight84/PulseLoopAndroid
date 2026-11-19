package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.ring.RingDeviceType

/**
 * Undoes the step and distance inflation a LuckRing/TK18 left behind before issue #112's fix.
 *
 * The ring's sport records are the day's running total, but they were stored as activity buckets,
 * whose day total is the *sum* of distinct buckets — so every push of the total added the whole day
 * again (668 real steps stored as ~9,000). The decoder now sends them as day totals; the days already
 * written still hold the sum, and the live path's max ratchet can never bring a day back down.
 *
 * A day is repaired only when its buckets carry the running-total signature: two or more, with steps
 * and distance never falling from one to the next. Its total becomes the last (largest) snapshot and
 * the snapshots are removed — they were never intervals, so the Records card's list of them was as
 * wrong as the sum. Interval buckets from a real bucketed ring rise and fall, so they don't match.
 */
object LuckRingActivityRepair {

    data class DayTotal(val steps: Int, val distanceMeters: Double)

    /** The day's true total if [buckets] are running-total snapshots, else null (leave the day alone). */
    fun plan(buckets: List<ActivityBucketEntity>): DayTotal? {
        if (buckets.size < 2) return null
        val ordered = buckets.sortedBy { it.startEpoch }
        val cumulative = ordered.zipWithNext().all { (a, b) ->
            b.steps >= a.steps && b.distanceMeters >= a.distanceMeters
        }
        if (!cumulative) return null
        val last = ordered.last()
        return DayTotal(last.steps, last.distanceMeters)
    }

    /**
     * Repair every stored day if the paired ring is a LuckRing. Returns the days fixed, or null when
     * no ring is paired — the caller retries then, since a wearer who tapped Forget (as on #112)
     * still has the inflated days.
     */
    suspend fun repair(db: PulseLoopDatabase): Int? = db.withTransaction {
        val device = db.deviceDao().currentReal() ?: return@withTransaction null
        if (device.deviceTypeRaw != RingDeviceType.LUCK_RING.name) return@withTransaction 0
        var fixed = 0
        for (day in db.activityBucketDao().daysBefore(Long.MAX_VALUE)) {
            val buckets = db.activityBucketDao().byDay(day)
            val total = plan(buckets) ?: continue
            buckets.forEach { db.activityBucketDao().deleteByStart(it.startEpoch) }
            db.activityDailyDao().byDay(day)?.let { row ->
                db.activityDailyDao().upsert(
                    row.copy(
                        steps = total.steps,
                        distanceMeters = total.distanceMeters,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            fixed++
        }
        fixed
    }
}
