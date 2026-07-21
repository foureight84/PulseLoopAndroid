package com.pulseloop.service

import com.pulseloop.data.entity.ActivityGpsPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.PI

/**
 * Pure-logic tests for the shared route distance/splits engine. Tracks are synthetic: northbound
 * latitude steps of known size (1° latitude ≈ 111.32 km everywhere). Ported 1:1 from
 * PulseLoopTests/RouteDistanceEngineTests.swift (iOS #57b).
 */
class RouteDistanceEngineTest {
    private val sessionId = "session-1"
    private val start = 1_750_000_000_000L
    /** Degrees of latitude per metre on the engine's sphere (R = 6 371 000 m), so northbound
     *  steps measure exactly their nominal length under the engine's haversine. */
    private val degPerMeter = 180.0 / (PI * 6_371_000.0)

    /** [count] accepted points heading due north, [stepMeters] apart, [stepSeconds] apart. */
    private fun track(
        count: Int, stepMeters: Double, stepSeconds: Double,
        originLat: Double = 37.0, originLon: Double = -122.0,
        startAt: Long = start, accepted: Boolean = true,
    ): List<ActivityGpsPointEntity> =
        (0 until count).map { i ->
            ActivityGpsPointEntity(
                sessionId = sessionId,
                latitude = originLat + i * stepMeters * degPerMeter,
                longitude = originLon,
                timestamp = startAt + (i * stepSeconds * 1000).toLong(),
                accepted = accepted,
            )
        }

    @Test
    fun `straight kilometer`() {
        // 101 points, 10 m apart, 5 s cadence (2 m/s walk).
        val points = track(count = 101, stepMeters = 10.0, stepSeconds = 5.0)
        val distance = RouteDistanceEngine.distanceMeters(points, ActivityTrackingProfile.profile("walk"))
        assertEquals(1000.0, distance, 10.0)
    }

    @Test
    fun `rejected points excluded`() {
        val points = track(count = 51, stepMeters = 10.0, stepSeconds = 5.0).toMutableList()
        // A burst of rejected jitter fixes mid-route must not add distance.
        val jitterStart = start + 1000 * 1000
        points += track(count = 20, stepMeters = 2.0, stepSeconds = 1.0, originLat = 37.1, startAt = jitterStart, accepted = false)
        val distance = RouteDistanceEngine.distanceMeters(points, ActivityTrackingProfile.profile("walk"))
        assertEquals(500.0, distance, 5.0)
    }

    @Test
    fun `pause teleport is not counted`() {
        // Walk 200 m, pause 10 minutes while moving 2 km away (drive), walk 200 m more.
        val leg1 = track(count = 21, stepMeters = 10.0, stepSeconds = 5.0)
        val resumeAt = leg1.last().timestamp + 600_000
        val leg2 = track(count = 21, stepMeters = 10.0, stepSeconds = 5.0, originLat = 37.0 + 2000 * degPerMeter, startAt = resumeAt)
        val distance = RouteDistanceEngine.distanceMeters(leg1 + leg2, ActivityTrackingProfile.profile("walk"))
        // The 2 km teleport spans 600 s (3.3 m/s — plausible walking speed!), so only the gap
        // rule can catch it.
        assertEquals(400.0, distance, 5.0)
    }

    @Test
    fun `speed spike dropped`() {
        val points = track(count = 21, stepMeters = 10.0, stepSeconds = 5.0).toMutableList()
        // One glitch fix 300 m off-route 2 s after the last point (150 m/s), then back on route.
        val last = points.last()
        points += ActivityGpsPointEntity(
            sessionId = sessionId,
            latitude = last.latitude + 300 * degPerMeter,
            longitude = last.longitude,
            timestamp = last.timestamp + 2_000,
        )
        points += ActivityGpsPointEntity(
            sessionId = sessionId,
            latitude = last.latitude + 10 * degPerMeter,
            longitude = last.longitude,
            timestamp = last.timestamp + 7_000,
        )
        val distance = RouteDistanceEngine.distanceMeters(points, ActivityTrackingProfile.profile("walk"))
        // Both the 300 m outbound (150 m/s) and 290 m return (58 m/s) segments are dropped.
        assertEquals(200.0, distance, 5.0)
    }

    @Test
    fun `unsorted input matches sorted`() {
        val points = track(count = 51, stepMeters = 10.0, stepSeconds = 5.0)
        val profile = ActivityTrackingProfile.profile("run")
        assertEquals(
            RouteDistanceEngine.distanceMeters(points.shuffled(), profile),
            RouteDistanceEngine.distanceMeters(points, profile),
            0.001,
        )
    }

    @Test
    fun `splits use moving time across gap`() {
        // 1.04 km at 2 m/s, a 5-minute pause in place, then 1.04 km at 2 m/s: two completed
        // 1 km splits with the same *moving* time; the pause contributes neither time nor
        // distance. Legs overshoot the split mark so float rounding can't miss a completion.
        val leg1 = track(count = 105, stepMeters = 10.0, stepSeconds = 5.0)
        val resumeAt = leg1.last().timestamp + 300_000
        val leg2 = track(count = 105, stepMeters = 10.0, stepSeconds = 5.0, originLat = 37.0 + 1040 * degPerMeter, startAt = resumeAt)
        val splits = RouteDistanceEngine.splits(leg1 + leg2, splitMeters = 1000.0, profile = ActivityTrackingProfile.profile("walk"))
        assertEquals(2, splits.completedSeconds.size)
        assertEquals(500.0, splits.completedSeconds[0], 15.0)
        assertEquals(500.0, splits.completedSeconds[1], 15.0)
        assertEquals(80.0, splits.partialMeters, 15.0)
    }

    @Test
    fun `profile lookup falls back to default`() {
        assertEquals(ActivityTrackingProfile.default, ActivityTrackingProfile.profile("gym"))
        assertNotEquals(ActivityTrackingProfile.profile("cycle"), ActivityTrackingProfile.profile("run"))
    }

    // MARK: - Incremental accumulator (live screen) must match the batch engine exactly

    /** Feed points one-by-one and assert distance + splits equal the batch computation. */
    private fun assertAccumulatorMatchesBatch(
        points: List<ActivityGpsPointEntity>, profile: ActivityTrackingProfile, splitMeters: Double = 1000.0,
    ) {
        val acc = RouteDistanceEngine.Accumulator(profile, splitMeters)
        for (p in points.filter { it.accepted }.sortedBy { it.timestamp }) {
            acc.add(p.latitude, p.longitude, p.timestamp)
        }
        val batchDistance = RouteDistanceEngine.distanceMeters(points, profile)
        val batchSplits = RouteDistanceEngine.splits(points, splitMeters, profile)
        assertEquals(batchDistance, acc.distanceMeters, 0.001)
        assertEquals(batchSplits.completedSeconds.size, acc.splits.completedSeconds.size)
        for (i in acc.splits.completedSeconds.indices) {
            assertEquals(batchSplits.completedSeconds[i], acc.splits.completedSeconds[i], 0.001)
        }
        assertEquals(batchSplits.partialMeters, acc.splits.partialMeters, 0.001)
        assertEquals(batchSplits.partialSeconds, acc.splits.partialSeconds, 0.001)
    }

    @Test
    fun `accumulator matches batch straight route`() {
        assertAccumulatorMatchesBatch(track(count = 250, stepMeters = 10.0, stepSeconds = 5.0), ActivityTrackingProfile.profile("walk"))
    }

    @Test
    fun `accumulator matches batch across gap`() {
        val leg1 = track(count = 105, stepMeters = 10.0, stepSeconds = 5.0)
        val resumeAt = leg1.last().timestamp + 300_000
        val leg2 = track(count = 105, stepMeters = 10.0, stepSeconds = 5.0, originLat = 37.0 + 1040 * degPerMeter, startAt = resumeAt)
        assertAccumulatorMatchesBatch(leg1 + leg2, ActivityTrackingProfile.profile("walk"))
    }

    @Test
    fun `accumulator matches batch with speed spike`() {
        val points = track(count = 21, stepMeters = 10.0, stepSeconds = 5.0).toMutableList()
        val last = points.last()
        points += ActivityGpsPointEntity(
            sessionId = sessionId,
            latitude = last.latitude + 300 * degPerMeter,
            longitude = last.longitude,
            timestamp = last.timestamp + 2_000,
        )
        points += ActivityGpsPointEntity(
            sessionId = sessionId,
            latitude = last.latitude + 10 * degPerMeter,
            longitude = last.longitude,
            timestamp = last.timestamp + 7_000,
        )
        assertAccumulatorMatchesBatch(points, ActivityTrackingProfile.profile("walk"))
    }

    @Test
    fun `accumulator skips out of order fix`() {
        val points = track(count = 20, stepMeters = 10.0, stepSeconds = 5.0)
        val acc = RouteDistanceEngine.Accumulator(ActivityTrackingProfile.profile("walk"), 1000.0)
        for (p in points) acc.add(p.latitude, p.longitude, p.timestamp)
        val before = acc.distanceMeters
        // A stale fix older than the last one must be ignored, not subtract/teleport.
        acc.add(37.0, -122.0, start - 10_000)
        assertEquals(before, acc.distanceMeters, 0.001)
    }

    @Test
    fun `accumulator seed matches incremental feed`() {
        val points = track(count = 100, stepMeters = 10.0, stepSeconds = 5.0)
        val incremental = RouteDistanceEngine.Accumulator(ActivityTrackingProfile.profile("run"), 1000.0)
        for (p in points) incremental.add(p.latitude, p.longitude, p.timestamp)
        val seeded = RouteDistanceEngine.Accumulator(ActivityTrackingProfile.profile("run"), 1000.0)
        seeded.seed(points.shuffled()) // seed sorts + filters internally
        assertEquals(incremental.distanceMeters, seeded.distanceMeters, 0.001)
        assertEquals(incremental.splits.completedSeconds, seeded.splits.completedSeconds)
    }
}
