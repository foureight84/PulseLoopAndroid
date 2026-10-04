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

    @Test
    fun glucoseUnitConvertsFromMgdl() {
        // mg/dL is identity; mmol/L = mg/dL ÷ 18.0182 (iOS #43 §3).
        assertEquals(99.0, GlucoseUnit.MGDL.fromMgdl(99.0), 1e-9)
        assertEquals(5.494, GlucoseUnit.MMOL.fromMgdl(99.0), 1e-3)
    }

    // ── BaselineStats ────────────────────────────────────────────────────

    private fun samples(values: List<Double>, stepMs: Long = 3_600_000L): List<VitalSample> =
        values.mapIndexed { i, v -> VitalSample(timestampMs = i * stepMs, value = v) }

    /**
     * Epoch millis at [hour]:[minute] on the given local calendar date. Using an explicit
     * local date-time (rather than raw millis) keeps the tests' calendar-date arithmetic
     * stable regardless of the JVM's default timezone, which the assertions depend on because
     * [BaselineStats.compute] resolves dates via `ZoneId.systemDefault()`.
     */
    private fun epochMs(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        java.time.LocalDateTime.of(year, month, day, hour, minute)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

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
        // Two readings two calendar dates apart touch three dates: start, the day between,
        // end. Before the +1 fix this reported 2 — the *difference* between the endpoint
        // dates — which undercounted and made a full week of daily readings report 6 and fail
        // `isEstablished`'s `spanDays >= 7` gate. See the KDoc on [BaselineStats.spanDays].
        val stats = BaselineStats.compute(
            listOf(
                VitalSample(epochMs(2026, 1, 1), 50.0),
                VitalSample(epochMs(2026, 1, 3), 60.0),
            ),
        )!!
        assertEquals(3.0, stats.spanDays, 1e-9)
    }

    @Test
    fun baselineSpanDaysIsOneForReadingsOnTheSameCalendarDate() {
        // Morning and evening on the same date touch one date, not zero.
        val stats = BaselineStats.compute(
            listOf(
                VitalSample(epochMs(2026, 1, 1, hour = 8), 50.0),
                VitalSample(epochMs(2026, 1, 1, hour = 20), 55.0),
            ),
        )!!
        assertEquals(1.0, stats.spanDays, 1e-9)
    }

    @Test
    fun baselineSpanDaysAcrossMidnightCountsBothDates() {
        // 23:59 → 00:01: two distinct calendar dates, even though only two minutes elapsed.
        val stats = BaselineStats.compute(
            listOf(
                VitalSample(epochMs(2026, 1, 1, hour = 23, minute = 59), 50.0),
                VitalSample(epochMs(2026, 1, 2, hour = 0, minute = 1), 55.0),
            ),
        )!!
        assertEquals(2.0, stats.spanDays, 1e-9)
    }

    @Test
    fun baselineSpanDaysIsSevenForAWeekOfDailyReadings() {
        // The case that used to read 6 and fail the gate: seven consecutive local dates.
        val week = (1..7).map { day ->
            VitalSample(epochMs(2026, 1, day), 50.0 + day)
        }
        val stats = BaselineStats.compute(week)!!
        assertEquals(7.0, stats.spanDays, 1e-9)
    }

    @Test
    fun baselineSpanDaysIsSixForSixDailyReadings() {
        // Six consecutive dates still read 6 — the fix must not over-correct into calling
        // a six-day window a full week.
        val days = (1..6).map { day ->
            VitalSample(epochMs(2026, 1, day), 50.0 + day)
        }
        val stats = BaselineStats.compute(days)!!
        assertEquals(6.0, stats.spanDays, 1e-9)
    }

    @Test
    fun baselineSpanDaysIsEightForTwoDatesSevenApart() {
        // Two readings seven calendar dates apart touch eight dates: the endpoints plus the
        // six full days between them. Elapsed time is ~7 days; calendar dates touched is 8.
        val stats = BaselineStats.compute(
            listOf(
                VitalSample(epochMs(2026, 1, 1), 50.0),
                VitalSample(epochMs(2026, 1, 8), 60.0),
            ),
        )!!
        assertEquals(8.0, stats.spanDays, 1e-9)
    }

    @Test
    fun baselineEstablishedOnAWeekOfContinuousWear() {
        // 28 readings over 7 local dates (4/day): spanDays = 7, sampleCount = 28, gate opens.
        // The pair (7, 20) is exactly what `isEstablished` requires.
        val readings = (1..7).flatMap { day ->
            (0 until 4).map { hourOffset ->
                VitalSample(epochMs(2026, 1, day, hour = 6 + hourOffset * 4), 50.0 + day)
            }
        }
        val stats = BaselineStats.compute(readings)!!
        assertEquals(7.0, stats.spanDays, 1e-9)
        assertEquals(28, stats.sampleCount)
        assertTrue(stats.isEstablished)
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
