package com.pulseloop.widgets

import com.pulseloop.service.MetricKind
import com.pulseloop.service.MetricZone
import com.pulseloop.service.UserPhysiologyProfile
import com.pulseloop.service.VitalColorToken
import com.pulseloop.service.VitalsThresholdEngine
import com.pulseloop.service.ZoneSeverity
import com.pulseloop.ui.components.ZonePalette
import com.pulseloop.ui.components.toColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The widget-snapshot contract, ported from PulseLoopTests/WidgetSnapshotTests.swift:
 * encode/decode round-trips, color-token bridging, zone reconstruction, the baked-in chart color
 * step function, plus the Android staleness/day-rollover helpers. Everything here is plain-JVM —
 * the payload/logic classes are deliberately bitmap-free.
 */
class WidgetSnapshotTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun sampleMetricPayload(now: Long) = WidgetMetricPayload(
        kind = MetricKind.HEART_RATE.widgetKey, title = "Heart rate",
        valueText = "54-91", unitText = "bpm range", statusText = "Typical",
        statusColorHex = "#FF4D6D", isEmpty = false,
        samples = listOf(WidgetSamplePayload(t = now, v = 72.0)),
        yLower = 40.0, yUpper = 140.0,
        referenceBands = listOf(WidgetBandPayload(60.0, 100.0, "accent:heartRate", 0.08)),
        dashedRules = listOf(92.0),
        thresholds = listOf(50.0, 60.0, 100.0, 120.0),
        intervalColorHexes = listOf("#4DA3FF", "#4DA3FF", "#FF4D6D", "#FFB86B", "#FF1744"),
        zones = listOf(
            WidgetZonePayload(
                id = "normal", label = "Normal", lower = 60.0, upper = 100.0,
                severityRaw = ZoneSeverity.NORMAL.rank, colorToken = "mint",
            ),
        ),
        systolic = 121.0, diastolic = 79.0,
    )

    private fun roundTrip(snapshot: WidgetSnapshot): WidgetSnapshot {
        val decoded = WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(snapshot))
        assertNotNull(decoded)
        return decoded!!
    }

    // ── 1. Round trip ──

    @Test
    fun snapshotRoundTripPreservesPayloads() {
        val now = 1_700_000_000L // epoch seconds
        val snapshot = WidgetSnapshot(
            generatedAt = now,
            dayStart = now - 40_000,
            activity = WidgetActivityPayload(
                steps = 6841.0, stepsGoal = 8000.0, distanceDisplay = 4.9,
                distanceGoalDisplay = 6.0, distanceUnitLabel = "KM",
                calories = 388.0, caloriesGoal = 520.0,
                stepsText = "6,841", distanceText = "4.9", caloriesText = "388",
            ),
            sleep = WidgetSleepPayload(
                durationText = "7h 23m", score = 82,
                segments = listOf(WidgetSleepPayload.Segment(78.0, "#3F2DD8", "DEEP")),
            ),
            metrics = mapOf(MetricKind.HEART_RATE.widgetKey to sampleMetricPayload(now)),
        )

        val decoded = roundTrip(snapshot)

        assertEquals("6,841", decoded.activity?.stepsText)
        assertEquals(82, decoded.sleep?.score)
        val hr = decoded.metrics[MetricKind.HEART_RATE.widgetKey]
        assertNotNull(hr)
        assertEquals("54-91", hr!!.valueText)
        assertEquals(listOf(50.0, 60.0, 100.0, 120.0), hr.thresholds)
        assertEquals(72.0, hr.samples.first().v, 0.0)
        assertEquals("mint", hr.zones.first().colorToken)
        assertEquals(121.0, hr.systolic!!, 0.0)
        assertEquals(92.0, hr.dashedRules.first(), 0.0)
        assertEquals(0.08, hr.referenceBands.first().opacity, 1e-9)
        // Dates survive the epoch-seconds encoding to the second.
        assertEquals(snapshot.generatedAt, decoded.generatedAt)
        assertEquals(now, hr.samples.first().t)
    }

    // ── 2. Color-token bridge ──

    @Test
    fun colorTokenStringBridgeRoundTripsAllTokens() {
        val tokens: MutableList<VitalColorToken> = mutableListOf(
            VitalColorToken.Blue, VitalColorToken.Mint, VitalColorToken.Cyan,
            VitalColorToken.Amber, VitalColorToken.SoftAmber, VitalColorToken.Orange,
            VitalColorToken.Red, VitalColorToken.BrightRed, VitalColorToken.Neutral,
        )
        tokens += MetricKind.entries.map { VitalColorToken.MetricAccent(it) }
        for (token in tokens) {
            assertEquals(token, vitalColorTokenFrom(token.tokenString))
        }
        // Unknown strings degrade to neutral instead of crashing on old/corrupt snapshots.
        assertEquals(VitalColorToken.Neutral, vitalColorTokenFrom("definitely-not-a-token"))
        assertEquals(VitalColorToken.Neutral, vitalColorTokenFrom("accent:not-a-metric"))
    }

    @Test
    fun colorHexBridgeRoundTrips() {
        val original = ZonePalette.ZoneMint
        assertEquals(original, colorFromHex(colorToHex(original)))
        assertEquals("#35E0A1", colorToHex(ZonePalette.ZoneMint))
    }

    // ── 3. Zone payload reconstruction ──

    @Test
    fun zonePayloadReconstructsMetricZone() {
        val zone = MetricZone(
            id = "watch", label = "Elevated", lower = 100.0, upper = 120.0,
            severity = ZoneSeverity.WATCH, colorToken = VitalColorToken.Amber,
            explanation = "detail-only",
        )
        val rebuilt = WidgetZonePayload.from(zone).toMetricZone()
        assertEquals(zone.id, rebuilt.id)
        assertEquals(zone.label, rebuilt.label)
        assertEquals(zone.lower, rebuilt.lower)
        assertEquals(zone.upper, rebuilt.upper)
        assertEquals(zone.severity, rebuilt.severity)
        assertEquals(zone.colorToken, rebuilt.colorToken)
        assertTrue(rebuilt.contains(110.0))
        assertFalse("upper bound stays half-open", rebuilt.contains(120.0))
    }

    @Test
    fun zonePayloadSurvivesJsonRoundTripThroughSnapshot() {
        val zones = VitalsThresholdEngine.zones(MetricKind.STRESS, UserPhysiologyProfile.UNKNOWN)
        val payload = sampleMetricPayload(0).copy(zones = zones.map(WidgetZonePayload::from))
        val decoded = roundTrip(
            WidgetSnapshot(generatedAt = 0, dayStart = 0, metrics = mapOf("stress" to payload)),
        )
        val rebuilt = decoded.metrics["stress"]!!.zones.map { it.toMetricZone() }
        assertEquals(zones.map { it.id }, rebuilt.map { it.id })
        assertEquals(zones.map { it.severity }, rebuilt.map { it.severity })
        assertEquals(zones.map { it.colorToken }, rebuilt.map { it.colorToken })
        assertEquals(zones.map { it.lower }, rebuilt.map { it.lower })
        assertEquals(zones.map { it.upper }, rebuilt.map { it.upper })
    }

    // ── 4. Line-color step function vs the threshold engine ──

    @Test
    fun lineColorStepFunctionMatchesEngineIntervals() {
        val profile = UserPhysiologyProfile.UNKNOWN
        val zones = VitalsThresholdEngine.zones(MetricKind.HEART_RATE, profile)
        val thresholds = WidgetColorSteps.thresholds(zones)
        // Same boundary set as the engine's interior thresholds.
        assertEquals(
            VitalsThresholdEngine.zoneThresholds(MetricKind.HEART_RATE, profile),
            thresholds,
        )
        assertFalse(thresholds.isEmpty())

        // Interval index encoded as a synthetic hex so we can assert pure index math below.
        val hexes = (0..thresholds.size).map { String.format("#%06X", it) }
        val payload = sampleMetricPayload(0).copy(
            thresholds = thresholds, intervalColorHexes = hexes,
        )

        // Below the first boundary → interval 0; above the last → last interval; boundary values
        // belong to the interval above them (half-open zones).
        assertEquals(hexes[0], payload.lineColorHex(thresholds[0] - 5))
        assertEquals(hexes[1], payload.lineColorHex(thresholds[0]))
        assertEquals(hexes.last(), payload.lineColorHex(thresholds.last() + 5))

        // The baked interval colors match the engine's per-value color at each representative.
        val baked = WidgetColorSteps.intervalColorHexes(zones, thresholds, ZonePalette.accent(MetricKind.HEART_RATE), 72.0)
        for (index in 0..thresholds.size) {
            val representative = when (index) {
                0 -> thresholds[0] - 1
                thresholds.size -> thresholds[index - 1] + 1
                else -> (thresholds[index - 1] + thresholds[index]) / 2
            }
            val engineToken = VitalsThresholdEngine.colorToken(representative, MetricKind.HEART_RATE, profile)
            assertEquals(colorToHex(engineToken.toColor()), baked[index])
        }
    }

    @Test
    fun lineColorFallsBackToStatusColorOnMismatch() {
        val payload = sampleMetricPayload(0).copy(
            thresholds = listOf(50.0, 60.0),
            intervalColorHexes = listOf("#000001"), // wrong count
        )
        assertEquals(payload.statusColorHex, payload.lineColorHex(55.0))
    }

    // ── 5. Staleness ──

    @Test
    fun stalenessHiddenAt44MinutesShownAt46() {
        val generatedAt = 1_700_000_000L
        val snapshot = WidgetSnapshot(generatedAt = generatedAt, dayStart = generatedAt)
        val fortyFourMinLater = (generatedAt + 44 * 60) * 1000L
        val fortySixMinLater = (generatedAt + 46 * 60) * 1000L
        assertNull(snapshot.stalenessEpochSeconds(fortyFourMinLater))
        assertEquals(generatedAt, snapshot.stalenessEpochSeconds(fortySixMinLater))
    }

    // ── 6. Day rollover ──

    @Test
    fun rolloverFlipsAtLocalMidnight() {
        // 2023-11-15 00:00:00 America/Los_Angeles.
        val dayStartMs = java.time.ZonedDateTime.of(2023, 11, 15, 0, 0, 0, 0, zone)
            .toInstant().toEpochMilli()
        val snapshot = WidgetSnapshot(generatedAt = dayStartMs / 1000, dayStart = dayStartMs / 1000)

        val samedayEvening = dayStartMs + 23 * 3600_000L
        val pastMidnight = dayStartMs + 24 * 3600_000L + 5_000L
        assertFalse(snapshot.isRolledOver(samedayEvening, zone))
        assertTrue(snapshot.isRolledOver(pastMidnight, zone))
    }

    // ── Downsampler ──

    @Test
    fun bucketAverageCapsSampleCountAndPreservesShortSeries() {
        val samples = (0 until 240).map {
            com.pulseloop.service.VitalSample(timestampMs = it * 60_000L, value = it.toDouble())
        }
        val down = MetricDownsampler.bucketAverage(samples, 48)
        assertTrue(down.size <= 48)
        assertTrue(down.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
        // Short series pass through untouched.
        val short = samples.take(10)
        assertEquals(short, MetricDownsampler.bucketAverage(short, 48))
    }
}
