package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Activity-specific calories: Keytel HR model when coverage + profile allow, MET fallback
 * otherwise. Ported 1:1 from PulseLoopTests/WorkoutMetricsEngineTests.swift (iOS #57a).
 */
class WorkoutMetricsEngineTest {
    private val start = 1_750_000_000_000L

    /** One HR sample per minute for [minutes] minutes at a constant bpm. */
    private fun steadySamples(minutes: Int, bpm: Double): List<Pair<Long, Double>> =
        (0 until minutes).map { (start + it * 60_000L + 1000L) to bpm }

    private fun assertApprox(expected: Double, actual: Double, tolerance: Double) {
        assertEquals(expected, actual, tolerance + 1e-9)
    }

    @Test
    fun `keytel male known value`() {
        // Keytel (male): (-55.0969 + 0.6309*140 + 0.1988*70 + 0.2017*30) / 4.184 ~= 12.71 kcal per min.
        val kcal = WorkoutMetricsEngine.calories(
            type = "run", durationSeconds = 3600, distanceMeters = null,
            hrSamples = steadySamples(minutes = 60, bpm = 140.0),
            profile = MetricsProfileValues(sex = "male", age = 30, weightKg = 70.0),
        )
        assertApprox(762.9, kcal, 2.0)
    }

    @Test
    fun `keytel female known value`() {
        // Keytel (female): (-20.4022 + 0.4472*150 - 0.1263*60 + 0.074*30) / 4.184 ~= 9.88 kcal per min.
        val kcal = WorkoutMetricsEngine.calories(
            type = "run", durationSeconds = 1800, distanceMeters = null,
            hrSamples = steadySamples(minutes = 30, bpm = 150.0),
            profile = MetricsProfileValues(sex = "female", age = 30, weightKg = 60.0),
        )
        assertApprox(9.876 * 30, kcal, 2.0)
    }

    @Test
    fun `low coverage falls back to MET`() {
        // Only 10 of 60 minutes covered (< 60%) - MET path: run default 9.8 x 70 kg x 1 h = 686.
        val kcal = WorkoutMetricsEngine.calories(
            type = "run", durationSeconds = 3600, distanceMeters = null,
            hrSamples = steadySamples(minutes = 10, bpm = 140.0),
            profile = MetricsProfileValues(sex = "male", age = 30, weightKg = 70.0),
        )
        assertApprox(686.0, kcal, 1.0)
    }

    @Test
    fun `missing profile uses MET with default weight`() {
        // Gym 30 min, no profile: 5.0 MET x 70 kg default x 0.5 h = 175.
        val kcal = WorkoutMetricsEngine.calories(
            type = "gym", durationSeconds = 1800, distanceMeters = null,
            hrSamples = steadySamples(minutes = 30, bpm = 130.0),
            profile = MetricsProfileValues(),
        )
        assertApprox(175.0, kcal, 1.0)
    }

    @Test
    fun `speed tiered METs`() {
        assertEquals(5.8, WorkoutMetricsEngine.metValue("cycle", 3.0), 0.0)
        assertEquals(10.0, WorkoutMetricsEngine.metValue("cycle", 7.0), 0.0)
        assertEquals(8.3, WorkoutMetricsEngine.metValue("run", 2.0), 0.0)
        assertEquals(12.3, WorkoutMetricsEngine.metValue("run", 3.5), 0.0)
        assertEquals(2.5, WorkoutMetricsEngine.metValue("yoga", null), 0.0)
    }
}
