package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the vitals zone value types ported from Services/VitalsZoneModel.swift: half-open zone
 * membership, severity ordering, baseline statistics, and physiology-profile defaults.
 */
class VitalsZoneModelTest {

    // ── MetricZone half-open interval ────────────────────────────────────

    private val zone = MetricZone(
        id = "test", label = "Normal", lower = 60.0, upper = 101.0,
        severity = ZoneSeverity.NORMAL, colorToken = VitalColorToken.Cyan, explanation = "",
    )

    @Test
    fun zoneContainsIsHalfOpen() {
        // `[lower, upper)` so adjacent zones don't both claim a boundary value.
        assertFalse(zone.contains(59.9))
        assertTrue(zone.contains(60.0))
        assertTrue(zone.contains(100.9))
        assertFalse(zone.contains(101.0))
    }

    @Test
    fun openEndedZonesContainExtremes() {
        val low = zone.copy(lower = null, upper = 60.0)
        val high = zone.copy(lower = 120.0, upper = null)
        assertTrue(low.contains(-100.0))
        assertFalse(low.contains(60.0))
        assertTrue(high.contains(10_000.0))
        assertFalse(high.contains(119.9))
    }

    // ── ZoneSeverity ordering ────────────────────────────────────────────

    @Test
    fun severityWorstPicksHigherRank() {
        assertEquals(ZoneSeverity.HIGH, ZoneSeverity.worst(ZoneSeverity.NORMAL, ZoneSeverity.HIGH))
        assertEquals(ZoneSeverity.CRITICAL, ZoneSeverity.worst(ZoneSeverity.CRITICAL, ZoneSeverity.WATCH))
        assertEquals(ZoneSeverity.OPTIMAL, ZoneSeverity.worst(ZoneSeverity.OPTIMAL, ZoneSeverity.OPTIMAL))
    }

    @Test
    fun severityWorstNeverLetsUnknownWin() {
        // UNKNOWN means "no information", so a real category always wins over it.
        assertEquals(ZoneSeverity.NORMAL, ZoneSeverity.worst(ZoneSeverity.UNKNOWN, ZoneSeverity.NORMAL))
        assertEquals(ZoneSeverity.WATCH, ZoneSeverity.worst(ZoneSeverity.WATCH, ZoneSeverity.UNKNOWN))
        assertEquals(ZoneSeverity.UNKNOWN, ZoneSeverity.worst(ZoneSeverity.UNKNOWN, ZoneSeverity.UNKNOWN))
    }

    // ── UserPhysiologyProfile ────────────────────────────────────────────

    @Test
    fun maxHeartRateUsesAgePredictedFormula() {
        assertEquals(190.0, UserPhysiologyProfile(age = 30).maxHeartRate, 0.0)
        assertEquals(160.0, UserPhysiologyProfile(age = 60).maxHeartRate, 0.0)
        // Unknown or invalid age falls back to 190.
        assertEquals(190.0, UserPhysiologyProfile.UNKNOWN.maxHeartRate, 0.0)
        assertEquals(190.0, UserPhysiologyProfile(age = 0).maxHeartRate, 0.0)
    }

    @Test
    fun biologicalSexParsesProfileStrings() {
        assertEquals(BiologicalSex.FEMALE, BiologicalSex.fromProfileSex("Female"))
        assertEquals(BiologicalSex.MALE, BiologicalSex.fromProfileSex("male"))
        assertEquals(BiologicalSex.UNSPECIFIED, BiologicalSex.fromProfileSex("other"))
        assertEquals(BiologicalSex.UNSPECIFIED, BiologicalSex.fromProfileSex(null))
    }

    @Test
    fun fromProfileDefaultsPhysiologyToNoAdjustment() {
        // Existing callers pass only age/sex — the physiology refinements (iOS #35) must default off.
        val p = UserPhysiologyProfile.fromProfile(age = 30, sex = "male")
        assertFalse(p.athleteMode)
        assertNull(p.altitudeMeters)
        assertFalse(p.usesBetaBlockers)
        assertFalse(p.hasKnownLungCondition)
        assertEquals(GlucoseUnit.MGDL, p.preferredGlucoseUnit)
    }

    @Test
    fun fromProfilePassesThroughPhysiologyInputs() {
        val p = UserPhysiologyProfile.fromProfile(
            age = 40, sex = "female",
            athleteMode = true,
            altitudeMeters = 2500.0,
            usesBetaBlockers = true,
            hasKnownLungCondition = true,
            preferredGlucoseUnit = GlucoseUnit.MMOL,
        )
        assertTrue(p.athleteMode)
        assertEquals(2500.0, p.altitudeMeters!!, 1e-9)
        assertTrue(p.usesBetaBlockers)
        assertTrue(p.hasKnownLungCondition)
        assertEquals(GlucoseUnit.MMOL, p.preferredGlucoseUnit)
    }

    // ── BaselineStats ────────────────────────────────────────────────────

    private fun samples(values: List<Double>, stepMs: Long = 3_600_000L): List<VitalSample> =
        values.mapIndexed { i, v -> VitalSample(timestampMs = i * stepMs, value = v) }

    @Test
    fun baselineNeedsAtLeastTwoPositiveValues() {
        assertNull(BaselineStats.compute(emptyList()))
        assertNull(BaselineStats.compute(samples(listOf(50.0))))
        // Zero/negative readings are dropped before the count check.
        assertNull(BaselineStats.compute(samples(listOf(0.0, 0.0, 42.0))))
        assertNotNull(BaselineStats.compute(samples(listOf(40.0, 60.0))))
    }

    @Test
    fun baselineComputesMeanMedianAndSd() {
        val stats = BaselineStats.compute(samples(listOf(40.0, 50.0, 60.0)))!!
        assertEquals(50.0, stats.mean, 1e-9)
        assertEquals(50.0, stats.median, 1e-9)
        // Population sd of {40, 50, 60} = sqrt(200/3).
        assertEquals(kotlin.math.sqrt(200.0 / 3.0), stats.standardDeviation, 1e-9)
        assertEquals(45.0, stats.p25, 1e-9)
        assertEquals(55.0, stats.p75, 1e-9)
        assertEquals(3, stats.sampleCount)
    }

    @Test
    fun baselineSpanDaysComesFromTimestamps() {
        // Two samples exactly 2 days apart.
        val stats = BaselineStats.compute(
            listOf(VitalSample(0L, 50.0), VitalSample(2 * 86_400_000L, 60.0)),
        )!!
        assertEquals(2.0, stats.spanDays, 1e-9)
    }

    @Test
    fun baselineEstablishedNeedsAWeekAndTwentySamples() {
        fun stats(count: Int, spanDays: Double) = BaselineStats(
            mean = 50.0, median = 50.0, standardDeviation = 5.0, p25 = 45.0, p75 = 55.0,
            sampleCount = count, spanDays = spanDays,
        )
        assertTrue(stats(20, 7.0).isEstablished)
        assertFalse("too few samples", stats(19, 7.0).isEstablished)
        assertFalse("too short a span", stats(50, 6.9).isEstablished)
    }

    // ── MetricKind mapping ───────────────────────────────────────────────

    @Test
    fun bloodPressureCardReadsSystolicStorageKind() {
        assertEquals(com.pulseloop.ring.MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, MetricKind.BLOOD_PRESSURE.measurementKind)
        assertEquals(com.pulseloop.ring.MeasurementKind.BLOOD_SUGAR, MetricKind.GLUCOSE.measurementKind)
        assertEquals(com.pulseloop.ring.MeasurementKind.HEART_RATE, MetricKind.HEART_RATE.measurementKind)
    }

    @Test
    fun sourceQualityEstimatedTreatment() {
        assertTrue(SourceQuality.ESTIMATED.isEstimated)
        assertTrue(SourceQuality.NEEDS_CALIBRATION.isEstimated)
        assertFalse(SourceQuality.GOOD.isEstimated)
        assertFalse(SourceQuality.STALE.isEstimated)
    }
}
