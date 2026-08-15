package com.pulseloop.health

import com.pulseloop.health.HealthConnectTypeMappings.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Phase 1 (docs/health-connect-integration.md): the clientRecordId scheme and the heart-rate
 * series segmentation are the parts that must stay stable or the upsert silently duplicates —
 * pinned here without a database or a HealthConnectClient (HealthConnectTypeMappings is pure on
 * purpose).
 */
class HealthConnectTypeMappingsTest {

    private val utc = ZoneOffset.UTC

    // ── id builders ──

    @Test
    fun vitalsRecordIdIsKindPlusEpochMillis() {
        assertEquals("pl-m-spo2-1723700000123", HealthConnectTypeMappings.vitalsRecordId("spo2", 1_723_700_000_123L))
        assertEquals("pl-m-hr-0", HealthConnectTypeMappings.vitalsRecordId("hr", 0L))
    }

    @Test
    fun hrBucketIdPlainForSingleSegmentSuffixedOtherwise() {
        assertEquals("pl-hr-1723700000000", HealthConnectTypeMappings.hrRecordId(1_723_700_000_000L))
        assertEquals("pl-hr-1723700000000-0", HealthConnectTypeMappings.hrRecordId(1_723_700_000_000L, 0))
        assertEquals("pl-hr-1723700000000-3", HealthConnectTypeMappings.hrRecordId(1_723_700_000_000L, 3))
    }

    // ── hour bucketing ──

    @Test
    fun hourStartOfTruncatesToLocalHour() {
        // 2024-08-15T05:33:20.123Z
        val base = 1_723_700_000_123L
        assertEquals(1_723_698_000_000L, HealthConnectTypeMappings.hourStartOf(base, utc)) // 05:00:00Z
        // +05:30 zone: local 11:03:20 → local hour 11:00 = 05:30:00Z
        val zone = ZoneOffset.ofHoursMinutes(5, 30)
        assertEquals(1_723_699_800_000L, HealthConnectTypeMappings.hourStartOf(base, zone))
    }

    @Test
    fun zoneOffsetAtReflectsZone() {
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30),
            HealthConnectTypeMappings.zoneOffsetAt(java.time.Instant.ofEpochMilli(1_723_700_000_123L), ZoneOffset.ofHoursMinutes(5, 30)))
    }

    // ── plausibility guards (platform validation rejects out-of-range inserts) ──

    @Test
    fun hrPlausibilityIsOneToThreeHundred() {
        assertTrue(HealthConnectTypeMappings.isPlausibleHr(1.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleHr(300.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleHr(0.99))
        assertFalse(HealthConnectTypeMappings.isPlausibleHr(300.01))
        assertFalse(HealthConnectTypeMappings.isPlausibleHr(0.0)) // "not measured"
    }

    @Test
    fun spo2PlausibilityExcludesArtifacts() {
        assertTrue(HealthConnectTypeMappings.isPlausibleSpO2(20.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleSpO2(100.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleSpO2(19.9))
        assertFalse(HealthConnectTypeMappings.isPlausibleSpO2(0.0)) // "not measured"
    }

    @Test
    fun hrvPlausibilityMatchesPlatformBounds() {
        assertTrue(HealthConnectTypeMappings.isPlausibleHrvRmssd(1.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleHrvRmssd(200.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleHrvRmssd(0.5))
        assertFalse(HealthConnectTypeMappings.isPlausibleHrvRmssd(200.5))
    }

    @Test
    fun temperaturePlausibilityIsCoreRange() {
        assertTrue(HealthConnectTypeMappings.isPlausibleBodyTemperature(30.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleBodyTemperature(42.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleBodyTemperature(29.9))
        assertFalse(HealthConnectTypeMappings.isPlausibleBodyTemperature(42.1))
    }

    // ── heart-rate series segmentation (Gadgetbridge rules) ──

    @Test
    fun emptyInputGivesNoSegments() {
        assertTrue(HealthConnectTypeMappings.splitHrSegments(emptyList(), utc).isEmpty())
    }

    @Test
    fun singleSampleIsOneSegment() {
        val segments = HealthConnectTypeMappings.splitHrSegments(listOf(HrSample(1000L, 70L)), utc)
        assertEquals(1, segments.size)
        assertEquals(1, segments[0].size)
    }

    @Test
    fun gapUpToFifteenMinutesStaysOneSegment() {
        // exactly 15:00 apart — the rule is STRICTLY longer
        val segments = HealthConnectTypeMappings.splitHrSegments(
            listOf(HrSample(0L, 70L), HrSample(15 * 60_000L, 75L)), utc)
        assertEquals(1, segments.size)
    }

    @Test
    fun gapOverFifteenMinutesSplits() {
        val segments = HealthConnectTypeMappings.splitHrSegments(
            listOf(HrSample(0L, 70L), HrSample(15 * 60_000L + 1000L, 75L)), utc)
        assertEquals(2, segments.size)
        assertEquals(1, segments[0].size)
        assertEquals(1, segments[1].size)
    }

    @Test
    fun localDateChangeSplitsEvenWithoutAGap() {
        // 23:59:59.999 → 00:00:00.001: a 2 ms gap, but a new local day
        val segments = HealthConnectTypeMappings.splitHrSegments(
            listOf(HrSample(86_399_999L, 70L), HrSample(86_400_001L, 71L)), utc)
        assertEquals(2, segments.size)
    }

    @Test
    fun thousandSampleCapSplits() {
        // 1001 samples at 30 s spacing = 8.33 h — one local day, no >15 min gaps, so only the
        // sample cap can split: [1000, 1]
        val samples = (0 until 1001).map { HrSample(it * 30_000L + 30_000L, 70L + (it % 30)) }
        val segments = HealthConnectTypeMappings.splitHrSegments(samples, utc)
        assertEquals(2, segments.size)
        assertEquals(1000, segments[0].size)
        assertEquals(1, segments[1].size)
    }

    @Test
    fun unsortedInputIsSortedBeforeSegmenting() {
        val segments = HealthConnectTypeMappings.splitHrSegments(
            listOf(HrSample(20_000L, 70L), HrSample(10_000L, 65L), HrSample(30_000L, 72L)), utc)
        assertEquals(1, segments.size)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), segments[0].map { it.timeMs })
    }

    // ── positive-duration rule ──

    @Test
    fun singleSampleSegmentEndBumpedByOneSecond() {
        assertEquals(1001L, HealthConnectTypeMappings.seriesEndMs(1000L, 1000L))
    }

    @Test
    fun normalSpanUnchanged() {
        assertEquals(200_000L, HealthConnectTypeMappings.seriesEndMs(100_000L, 200_000L))
    }
}
