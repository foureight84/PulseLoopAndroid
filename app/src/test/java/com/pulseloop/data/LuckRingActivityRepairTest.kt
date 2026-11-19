package com.pulseloop.data

import com.pulseloop.data.entity.ActivityBucketEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #112: days a LuckRing inflated by storing its running totals as buckets. */
class LuckRingActivityRepairTest {

    private val day = 1_791_676_800_000L

    private fun snapshot(offsetSec: Int, steps: Int, distance: Double) =
        ActivityBucketEntity(startEpoch = day + offsetSec * 1000L, date = day, steps = steps, distanceMeters = distance)

    @Test
    fun `running-total snapshots repair to the last one`() {
        // The reporter's afternoon: 537 → 668 steps across fifteen pushes, summed to ~9,000.
        val buckets = listOf(
            snapshot(60_609, 537, 380.0), snapshot(63_473, 549, 389.0), snapshot(66_668, 584, 414.0),
            snapshot(66_780, 594, 421.0), snapshot(66_809, 640, 453.0), snapshot(67_619, 668, 473.0),
        )
        assertEquals(LuckRingActivityRepair.DayTotal(668, 473.0), LuckRingActivityRepair.plan(buckets.shuffled()))
    }

    @Test
    fun `real interval buckets that rise and fall are left alone`() {
        val buckets = listOf(snapshot(0, 300, 200.0), snapshot(900, 120, 80.0), snapshot(1800, 450, 310.0))
        assertNull(LuckRingActivityRepair.plan(buckets))
    }

    @Test
    fun `a single bucket is left alone`() {
        assertNull(LuckRingActivityRepair.plan(listOf(snapshot(0, 300, 200.0))))
    }
}
