package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for the medical reference-range engine. Ported from
 * PulseLoopTests/VitalsThresholdEngineTests.swift — these lock the exact thresholds so a future
 * refactor can't silently shift a category. Pure logic — no Room/hardware IO.
 */
class VitalsThresholdEngineTest {

    private val base = UserPhysiologyProfile.UNKNOWN

    private fun athlete() = UserPhysiologyProfile(
        age = 30, sex = BiologicalSex.MALE, athleteMode = true, altitudeMeters = null,
        usesBetaBlockers = false, hasKnownLungCondition = false, preferredGlucoseUnit = GlucoseUnit.MGDL,
    )

    private fun betaBlocker() = UserPhysiologyProfile(
        age = 60, sex = BiologicalSex.FEMALE, athleteMode = false, altitudeMeters = null,
        usesBetaBlockers = true, hasKnownLungCondition = false, preferredGlucoseUnit = GlucoseUnit.MGDL,
    )

    private fun severity(
        value: Double,
        metric: MetricKind,
        profile: UserPhysiologyProfile,
        context: MetricContext = MetricContext(),
        baseline: BaselineStats? = null,
    ): ZoneSeverity =
        VitalsThresholdEngine.interpret(value, metric, profile, context, baseline).primaryZone.severity

    // ── Heart rate ───────────────────────────────────────────────────────

    @Test
    fun heartRateBoundaries() {
        assertEquals("59 is below the 60 normal floor", ZoneSeverity.WATCH, severity(59.0, MetricKind.HEART_RATE, base))
        assertEquals(ZoneSeverity.NORMAL, severity(60.0, MetricKind.HEART_RATE, base))
        assertEquals(ZoneSeverity.NORMAL, severity(100.0, MetricKind.HEART_RATE, base))
        assertEquals("101 is above the 100 normal ceiling", ZoneSeverity.WATCH, severity(101.0, MetricKind.HEART_RATE, base))
    }

    @Test
    fun athleteLowHeartRateIsOptimal() {
        assertEquals("athletes' low resting HR is fine", ZoneSeverity.OPTIMAL, severity(48.0, MetricKind.HEART_RATE, athlete()))
    }

    @Test
    fun betaBlockerLowHeartRateNotAlarming() {
        // 50 bpm on a beta-blocker should read as expected/normal, not a watch/concern.
        assertEquals(ZoneSeverity.NORMAL, severity(50.0, MetricKind.HEART_RATE, betaBlocker()))
    }

    // ── SpO₂ ─────────────────────────────────────────────────────────────

    @Test
    fun spo2Boundaries() {
        assertEquals(ZoneSeverity.NORMAL, severity(100.0, MetricKind.SPO2, base))
        assertEquals(ZoneSeverity.NORMAL, severity(95.0, MetricKind.SPO2, base))
        assertEquals(ZoneSeverity.WATCH, severity(94.0, MetricKind.SPO2, base))
        assertEquals(ZoneSeverity.HIGH, severity(92.0, MetricKind.SPO2, base))
        assertEquals(ZoneSeverity.CRITICAL, severity(88.0, MetricKind.SPO2, base))
        assertEquals(ZoneSeverity.CRITICAL, severity(87.0, MetricKind.SPO2, base))
    }

    // ── Blood pressure (worse-of-two) ────────────────────────────────────

    private fun bpSeverity(sys: Double, dia: Double): ZoneSeverity =
        VitalsThresholdEngine.interpretBloodPressure(sys, dia, base).primaryZone.severity

    @Test
    fun bloodPressureCategories() {
        assertEquals(ZoneSeverity.NORMAL, bpSeverity(119.0, 79.0))
        assertEquals("120–129/<80 is Elevated", ZoneSeverity.WATCH, bpSeverity(122.0, 78.0))
        assertEquals("systolic 130 → Stage 1", ZoneSeverity.HIGH, bpSeverity(130.0, 78.0))
        assertEquals("diastolic 85 → Stage 1 even with normal systolic", ZoneSeverity.HIGH, bpSeverity(118.0, 85.0))
        assertEquals("Stage 2", ZoneSeverity.HIGH, bpSeverity(142.0, 91.0))
        assertEquals("systolic >180 → severe", ZoneSeverity.CRITICAL, bpSeverity(181.0, 100.0))
        assertEquals("low BP", ZoneSeverity.WATCH, bpSeverity(88.0, 58.0))
    }

    @Test
    fun bloodPressureIsEstimated() {
        assertTrue(VitalsThresholdEngine.interpretBloodPressure(120.0, 80.0, base).isEstimated)
    }

    // ── Glucose ──────────────────────────────────────────────────────────

    private fun glucoseLabel(value: Double, context: MeasurementContext): String =
        VitalsThresholdEngine.interpret(
            value, MetricKind.GLUCOSE, base, MetricContext(measurement = context),
        ).displayLabel

    private fun glucoseSeverity(value: Double, context: MeasurementContext): ZoneSeverity =
        severity(value, MetricKind.GLUCOSE, base, MetricContext(measurement = context))

    @Test
    fun fastingGlucoseBoundaries() {
        assertEquals("below 70 is low", ZoneSeverity.HIGH, glucoseSeverity(69.0, MeasurementContext.FASTING))
        assertEquals(ZoneSeverity.NORMAL, glucoseSeverity(70.0, MeasurementContext.FASTING))
        assertEquals(ZoneSeverity.NORMAL, glucoseSeverity(99.0, MeasurementContext.FASTING))
        assertEquals(ZoneSeverity.WATCH, glucoseSeverity(100.0, MeasurementContext.FASTING))
        assertEquals(ZoneSeverity.HIGH, glucoseSeverity(126.0, MeasurementContext.FASTING))
    }

    @Test
    fun randomGlucoseBoundaries() {
        assertEquals(ZoneSeverity.WATCH, glucoseSeverity(199.0, MeasurementContext.RANDOM))
        assertEquals(ZoneSeverity.HIGH, glucoseSeverity(200.0, MeasurementContext.RANDOM))
    }

    @Test
    fun unknownGlucoseContextNeverSaysPrediabetes() {
        var value = 60.0
        while (value <= 260.0) {
            val label = glucoseLabel(value, MeasurementContext.UNKNOWN).lowercase()
            assertFalse("unknown context must stay conservative (value $value)", label.contains("prediabetes"))
            assertFalse("unknown context must stay conservative (value $value)", label.contains("diabetes"))
            value += 5.0
        }
    }

    @Test
    fun glucoseAlwaysEstimated() {
        val interp = VitalsThresholdEngine.interpret(
            100.0, MetricKind.GLUCOSE, base, MetricContext(measurement = MeasurementContext.UNKNOWN),
        )
        assertTrue(interp.isEstimated)
    }

    // ── HRV (baseline-relative) ──────────────────────────────────────────

    private fun makeBaseline(mean: Double, sd: Double, established: Boolean) = BaselineStats(
        mean = mean, median = mean, standardDeviation = sd, p25 = mean - sd, p75 = mean + sd,
        sampleCount = if (established) 50 else 3, spanDays = if (established) 14.0 else 1.0,
    )

    @Test
    fun hrvNoBaselineIsBuilding() {
        val interp = VitalsThresholdEngine.interpret(45.0, MetricKind.HRV, base, baseline = null)
        assertEquals(ZoneSeverity.UNKNOWN, interp.primaryZone.severity)
        assertEquals("Building baseline", interp.confidenceLabel)
    }

    @Test
    fun hrvUnestablishedBaselineIsBuilding() {
        val baseline = makeBaseline(mean = 50.0, sd = 10.0, established = false)
        val interp = VitalsThresholdEngine.interpret(50.0, MetricKind.HRV, base, baseline = baseline)
        assertEquals(ZoneSeverity.UNKNOWN, interp.primaryZone.severity)
    }

    @Test
    fun hrvNearBaseline() {
        val baseline = makeBaseline(mean = 50.0, sd = 10.0, established = true)
        // Within ±0.5 sd of mean → near baseline (normal).
        assertEquals(ZoneSeverity.NORMAL, severity(50.0, MetricKind.HRV, base, baseline = baseline))
    }

    @Test
    fun hrvWellBelowBaseline() {
        val baseline = makeBaseline(mean = 50.0, sd = 10.0, established = true)
        // 38 is more than 1 sd below (mean - sd = 40 boundary) → below baseline.
        assertEquals(ZoneSeverity.HIGH, severity(38.0, MetricKind.HRV, base, baseline = baseline))
    }

    @Test
    fun hrvAboveBaseline() {
        val baseline = makeBaseline(mean = 50.0, sd = 10.0, established = true)
        // 57.5 is more than 0.5 sd above (55) → above baseline (optimal).
        assertEquals(ZoneSeverity.OPTIMAL, severity(57.5, MetricKind.HRV, base, baseline = baseline))
    }

    // ── Stress / Fatigue ─────────────────────────────────────────────────

    @Test
    fun stressBoundaries() {
        assertEquals(ZoneSeverity.OPTIMAL, severity(25.0, MetricKind.STRESS, base))
        assertEquals(ZoneSeverity.NORMAL, severity(26.0, MetricKind.STRESS, base))
        assertEquals(ZoneSeverity.WATCH, severity(51.0, MetricKind.STRESS, base))
        assertEquals(ZoneSeverity.HIGH, severity(76.0, MetricKind.STRESS, base))
    }

    @Test
    fun fatigueBoundaries() {
        assertEquals(ZoneSeverity.OPTIMAL, severity(24.0, MetricKind.FATIGUE, base))
        assertEquals(ZoneSeverity.NORMAL, severity(25.0, MetricKind.FATIGUE, base))
        assertEquals(ZoneSeverity.WATCH, severity(50.0, MetricKind.FATIGUE, base))
        assertEquals(ZoneSeverity.HIGH, severity(75.0, MetricKind.FATIGUE, base))
    }

    // ── Per-zone color palette ───────────────────────────────────────────

    /** The colorToken the engine assigns to the zone a value lands in. */
    private fun token(value: Double, metric: MetricKind, baseline: BaselineStats? = null): VitalColorToken =
        VitalsThresholdEngine.colorToken(value, metric, base, baseline = baseline)

    @Test
    fun heartRateZoneColors() {
        assertEquals(VitalColorToken.Blue, token(45.0, MetricKind.HEART_RATE))                                   // low
        assertEquals(VitalColorToken.MetricAccent(MetricKind.HEART_RATE), token(72.0, MetricKind.HEART_RATE))    // normal = pink accent
        assertEquals(VitalColorToken.Amber, token(110.0, MetricKind.HEART_RATE))                                 // elevated
        assertEquals(VitalColorToken.BrightRed, token(130.0, MetricKind.HEART_RATE))                             // high (deeper red)
    }

    @Test
    fun spo2ZoneColors() {
        assertEquals(VitalColorToken.Cyan, token(98.0, MetricKind.SPO2))     // normal
        assertEquals(VitalColorToken.Amber, token(94.0, MetricKind.SPO2))    // slightly low
        assertEquals(VitalColorToken.Orange, token(91.0, MetricKind.SPO2))   // low
        assertEquals(VitalColorToken.Red, token(86.0, MetricKind.SPO2))      // very low
    }

    @Test
    fun hrvZoneColorsUseAccentForNearBaseline() {
        val baseline = makeBaseline(mean = 50.0, sd = 10.0, established = true)
        assertEquals(VitalColorToken.MetricAccent(MetricKind.HRV), token(50.0, MetricKind.HRV, baseline)) // near = purple
        assertEquals(VitalColorToken.Mint, token(58.0, MetricKind.HRV, baseline))                         // above
        assertEquals(VitalColorToken.Amber, token(38.0, MetricKind.HRV, baseline))                        // below
    }

    @Test
    fun stressAndFatigueZoneColors() {
        assertEquals(VitalColorToken.Mint, token(10.0, MetricKind.STRESS))
        assertEquals(VitalColorToken.Cyan, token(40.0, MetricKind.STRESS))
        assertEquals(VitalColorToken.Amber, token(60.0, MetricKind.STRESS))
        assertEquals(VitalColorToken.Red, token(90.0, MetricKind.STRESS))
        assertEquals(VitalColorToken.Red, token(90.0, MetricKind.FATIGUE))
    }

    @Test
    fun bloodPressureZoneColors() {
        // Via the systolic zones used by the gauge/legend.
        val zones = VitalsThresholdEngine.zones(MetricKind.BLOOD_PRESSURE, base)
        fun colorAt(v: Double): VitalColorToken? = zones.firstOrNull { it.contains(v) }?.colorToken
        assertEquals(VitalColorToken.Mint, colorAt(110.0))    // normal
        assertEquals(VitalColorToken.Amber, colorAt(125.0))   // elevated
        assertEquals(VitalColorToken.Orange, colorAt(135.0))  // stage 1
        assertEquals(VitalColorToken.Red, colorAt(150.0))     // stage 2
    }

    /**
     * The line color at a value MUST equal the color of the zone that value falls in (line ↔ legend
     * agreement) — including normal values, where the accent could otherwise diverge.
     */
    @Test
    fun lineColorMatchesContainingZoneEverywhere() {
        var value = 40.0
        while (value <= 160.0) {
            val zones = VitalsThresholdEngine.zones(MetricKind.HEART_RATE, base)
            val zoneToken = zones.firstOrNull { it.contains(value) }?.colorToken
            assertEquals("line vs legend mismatch at HR $value", zoneToken, token(value, MetricKind.HEART_RATE))
            value += 1.0
        }
    }

    @Test
    fun zoneThresholdsAreSortedBoundaries() {
        val thresholds = VitalsThresholdEngine.zoneThresholds(MetricKind.HEART_RATE, base)
        assertEquals(listOf(60.0, 101.0, 120.0), thresholds)   // the finite upper bounds, sorted
    }

    // ── Android-specific additions ───────────────────────────────────────

    @Test
    fun altitudeShiftsSpO2WatchBoundary() {
        val highAltitude = base.copy(altitudeMeters = 2500.0)
        // 93.5 is "Slightly low" at sea level but "Normal" at altitude (watch upper drops to 93).
        assertEquals(ZoneSeverity.WATCH, severity(93.5, MetricKind.SPO2, base))
        assertEquals(ZoneSeverity.NORMAL, severity(93.5, MetricKind.SPO2, highAltitude))
    }

    @Test
    fun valuesOutsideAllBandsClampToEndZones() {
        // Stress/fatigue values can't go below 0/above 100, but the clamp still must hold.
        assertEquals(ZoneSeverity.OPTIMAL, severity(-5.0, MetricKind.STRESS, base))
        assertEquals(ZoneSeverity.HIGH, severity(150.0, MetricKind.STRESS, base))
    }
}
