package com.pulseloop.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.pulseloop.health.HealthConnectTypeMappings.GpsRoutePoint
import com.pulseloop.health.HealthConnectTypeMappings.WorkoutSelection
import com.pulseloop.ui.components.ActivityMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 (docs/health-connect-integration.md): the workout identity scheme, the exercise-type
 * map, the per-session export guards, route sanitisation and the 1 MB decimation math. All pure
 * — no database, no HealthConnectClient (HealthConnectTypeMappings is pure on purpose).
 */
class HealthConnectWorkoutMappingTest {

    private val s0 = 1_700_000_000_000L // an arbitrary fixed start

    // ── id builders (plan §3 identity table) ──

    @Test
    fun workoutRecordIdsFollowThePlanScheme() {
        assertEquals("pl-wk-abc-123", HealthConnectTypeMappings.workoutRecordId("abc-123"))
        assertEquals(
            "pl-wk-abc-123-energy", HealthConnectTypeMappings.workoutChildRecordId("abc-123", HealthConnectTypeMappings.WK_ENERGY),
        )
        assertEquals(
            "pl-wk-abc-123-dist", HealthConnectTypeMappings.workoutChildRecordId("abc-123", HealthConnectTypeMappings.WK_DIST),
        )
    }

    // ── exercise type map (plan Phase 4) ──

    @Test
    fun exerciseTypeMapsEveryPulseLoopType() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, HealthConnectTypeMappings.exerciseType("walk"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, HealthConnectTypeMappings.exerciseType("run"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, HealthConnectTypeMappings.exerciseType("cycle"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, HealthConnectTypeMappings.exerciseType("gym"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_SQUASH, HealthConnectTypeMappings.exerciseType("squash"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_YOGA, HealthConnectTypeMappings.exerciseType("yoga"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_DANCING, HealthConnectTypeMappings.exerciseType("dance"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, HealthConnectTypeMappings.exerciseType("hike"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, HealthConnectTypeMappings.exerciseType("sport"))
    }

    @Test
    fun recordTitleUsesTheActivityMetaLabel() {
        // The exporter sets ExerciseSessionRecord.title = ActivityMeta.label(type) — pin the
        // titles the user will see in Health Connect for every type the map covers.
        assertEquals("Walking", ActivityMeta.label("walk"))
        assertEquals("Running", ActivityMeta.label("run"))
        assertEquals("Cycling", ActivityMeta.label("cycle"))
        assertEquals("Gym", ActivityMeta.label("gym"))
        assertEquals("Squash", ActivityMeta.label("squash"))
        assertEquals("Yoga", ActivityMeta.label("yoga"))
        assertEquals("Dance", ActivityMeta.label("dance"))
        assertEquals("Hiking", ActivityMeta.label("hike"))
        assertEquals("Sport", ActivityMeta.label("sport"))
        assertEquals("Other", ActivityMeta.label("other"))
    }

    @Test
    fun exerciseTypeFallsBackToOtherWorkout() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, HealthConnectTypeMappings.exerciseType("other"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, HealthConnectTypeMappings.exerciseType("something-new"))
    }

    // ── per-session guards (plan Phase 4: endedAt > startedAt, not future) ──

    @Test
    fun selectWorkoutSessionAcceptsAFinishedSession() {
        assertEquals(WorkoutSelection.EXPORT, HealthConnectTypeMappings.selectWorkoutSession(s0, s0 + 60_000L, s0 + 3_600_000L))
        // endedAt == now is not future.
        assertEquals(WorkoutSelection.EXPORT, HealthConnectTypeMappings.selectWorkoutSession(s0, s0 + 60_000L, s0 + 60_000L))
    }

    @Test
    fun selectWorkoutSessionRejectsZeroAndNegativeDurations() {
        assertEquals(WorkoutSelection.INVALID, HealthConnectTypeMappings.selectWorkoutSession(s0, s0, s0 + 60_000L))
        assertEquals(WorkoutSelection.INVALID, HealthConnectTypeMappings.selectWorkoutSession(s0 + 60_000L, s0, s0 + 120_000L))
        assertEquals(WorkoutSelection.INVALID, HealthConnectTypeMappings.selectWorkoutSession(s0, null, s0 + 60_000L))
    }

    @Test
    fun selectWorkoutSessionFlagsFutureDatedEnds() {
        assertEquals(WorkoutSelection.FUTURE, HealthConnectTypeMappings.selectWorkoutSession(s0, s0 + 60_000L, s0 + 59_999L))
        assertEquals(WorkoutSelection.FUTURE, HealthConnectTypeMappings.selectWorkoutSession(s0, s0 + 3_600_000L, s0 + 60_000L))
    }

    // ── route sanitisation (plan Phase 4; Gadgetbridge buildSanitisedRoute) ──

    private fun point(timeMs: Long, lat: Double = 37.0, lon: Double = -122.0) =
        GpsRoutePoint(timeMs = timeMs, latitude = lat, longitude = lon)

    @Test
    fun routeSanitisationKeepsTheWindowInclusive() {
        val out = HealthConnectTypeMappings.sanitizeRoutePoints(s0, s0 + 100_000L, listOf(
            point(s0 - 1), // before the window
            point(s0),     // boundary — kept
            point(s0 + 50_000L),
            point(s0 + 100_000L), // boundary — kept
            point(s0 + 100_001L), // after the window
        ))
        assertEquals(listOf(s0, s0 + 50_000L, s0 + 100_000L), out.map { it.timeMs })
    }

    @Test
    fun routeSanitisationDropsNonFiniteAndOutOfRangeCoordinates() {
        val out = HealthConnectTypeMappings.sanitizeRoutePoints(s0, s0 + 100_000L, listOf(
            point(s0, Double.NaN, -122.0),
            point(s0 + 1_000L, 37.0, Double.POSITIVE_INFINITY),
            point(s0 + 2_000L, 90.5, -122.0),
            point(s0 + 3_000L, -90.1, -122.0),
            point(s0 + 4_000L, 37.0, 180.5),
            point(s0 + 5_000L, 37.0, -181.0),
            point(s0 + 6_000L, 90.0, 180.0), // exact bounds — kept
            point(s0 + 7_000L, -90.0, -180.0), // exact bounds — kept
        ))
        assertEquals(listOf(s0 + 6_000L, s0 + 7_000L), out.map { it.timeMs })
    }

    @Test
    fun routeSanitisationDropsDuplicateTimestampsKeepingTheFirst() {
        val out = HealthConnectTypeMappings.sanitizeRoutePoints(s0, s0 + 100_000L, listOf(
            point(s0 + 10_000L, lat = 1.0),
            point(s0 + 10_000L, lat = 2.0), // same timestamp — dropped
            point(s0 + 20_000L, lat = 3.0),
            point(s0 + 20_000L, lat = 4.0), // same timestamp — dropped
        ))
        assertEquals(listOf(1.0, 3.0), out.map { it.latitude })
    }

    @Test
    fun routeSanitisationSortsUnsortedInput() {
        val out = HealthConnectTypeMappings.sanitizeRoutePoints(s0, s0 + 100_000L, listOf(
            point(s0 + 30_000L), point(s0 + 10_000L), point(s0 + 20_000L),
        ))
        assertEquals(listOf(s0 + 10_000L, s0 + 20_000L, s0 + 30_000L), out.map { it.timeMs })
    }

    @Test
    fun routeSanitisationRejectsAnInvertedWindow() {
        assertTrue(HealthConnectTypeMappings.sanitizeRoutePoints(s0 + 100, s0, listOf(point(s0 + 50))).isEmpty())
    }

    @Test
    fun routeSanitisationCanEmptyARoute() {
        // One clean point is not a route (≥ 2 required) — the exporter treats that as "no route".
        val out = HealthConnectTypeMappings.sanitizeRoutePoints(s0, s0 + 100_000L, listOf(point(s0 + 10_000L)))
        assertEquals(1, out.size)
        // ...and everything-but-one dropped:
        assertTrue(
            HealthConnectTypeMappings.sanitizeRoutePoints(s0, s0 + 100_000L,
                listOf(point(s0 + 10_000L), point(s0 - 1), point(s0 + 10_000L))).size == 1,
        )
    }

    // ── 1 MB single-record limit (plan §3 robustness constants) ──

    @Test
    fun recordSizeLimitParsesThePlatformMessage() {
        // Gadgetbridge's production format.
        assertEquals(1_000_000L to 1_700_644L,
            HealthConnectTypeMappings.parseRecordSizeLimit(
                "Failed to insert records: single record size limit: 1000000, was: 1700644",
            ))
        assertEquals(1_000_000L to 2_000_000L,
            HealthConnectTypeMappings.parseRecordSizeLimit("single record size limit: 1000000, was: 2000000"))
        assertNull(HealthConnectTypeMappings.parseRecordSizeLimit("some other insert error"))
        assertNull(HealthConnectTypeMappings.parseRecordSizeLimit(null))
        // was <= limit is not an oversize — nothing to shrink.
        assertNull(HealthConnectTypeMappings.parseRecordSizeLimit("single record size limit: 1000000, was: 999999"))
        assertNull(HealthConnectTypeMappings.parseRecordSizeLimit("single record size limit: 1000000, was: 1000000"))
    }

    @Test
    fun decimationPreservesFirstAndLast() {
        val points = (0 until 100).map { it.toLong() }
        val out = HealthConnectTypeMappings.decimateToSize(points, 10)
        assertEquals(10, out.size)
        assertEquals(0L, out.first())
        assertEquals(99L, out.last())
        // Strictly increasing — no duplicated timestamp, which HC would reject.
        assertTrue(out.zipWithNext { a, b -> a < b }.all { it })
    }

    @Test
    fun decimationIsNoOpAtOrBelowTarget() {
        val points = listOf(1L, 2L, 3L)
        assertEquals(points, HealthConnectTypeMappings.decimateToSize(points, 3))
        assertEquals(points, HealthConnectTypeMappings.decimateToSize(points, 5))
    }

    @Test
    fun decimationClampsToTwoPoints() {
        val points = (0 until 50).map { it.toLong() }
        val out = HealthConnectTypeMappings.decimateToSize(points, 1) // target < 2 clamps to 2
        assertEquals(listOf(0L, 49L), out)
    }

    @Test
    fun decimationNeverDuplicatesTheLastIndex() {
        // The coerceAtMost(lastIndex - 1) clamp exists for exactly this: a stride that would
        // land on (or past) the last index must not add the last point twice.
        val points = (0 until 20).map { it.toLong() }
        for (target in 2..19) {
            val out = HealthConnectTypeMappings.decimateToSize(points, target)
            assertEquals(target, out.size)
            assertTrue("target=$target produced duplicates", out.distinct().size == out.size)
            assertEquals(points.last(), out.last())
        }
    }
}
