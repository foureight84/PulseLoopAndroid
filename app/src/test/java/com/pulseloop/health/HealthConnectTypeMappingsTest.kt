package com.pulseloop.health

import androidx.health.connect.client.records.SleepSessionRecord
import com.pulseloop.health.HealthConnectTypeMappings.HrSample
import com.pulseloop.health.HealthConnectTypeMappings.SleepDaySession
import com.pulseloop.health.HealthConnectTypeMappings.SleepStageSpan
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── sleep (Phase 2) ──

    // A fixed waking day and the sessions it can hold: a 14:00–15:00 nap (60 min) and a
    // 23:40–07:10 night (450 min). The day key itself is arbitrary — the id is the raw value.
    private val day0 = 1_755_955_200_000L
    private val napStart = day0 + 14L * 3_600_000L
    private val nightStart = day0 + 23L * 3_600_000L + 40 * 60_000L

    // ── stage-type map (client constants, never raw ints) ──

    @Test
    fun sleepStageTypeMapsAllFiveStages() {
        assertEquals(SleepSessionRecord.STAGE_TYPE_DEEP, HealthConnectTypeMappings.sleepStageType(SleepStage.DEEP.name))
        assertEquals(SleepSessionRecord.STAGE_TYPE_LIGHT, HealthConnectTypeMappings.sleepStageType(SleepStage.LIGHT.name))
        assertEquals(SleepSessionRecord.STAGE_TYPE_REM, HealthConnectTypeMappings.sleepStageType(SleepStage.REM.name))
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE, HealthConnectTypeMappings.sleepStageType(SleepStage.AWAKE.name))
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectTypeMappings.sleepStageType(SleepStage.UNKNOWN.name))
        // Pin the connect-client 1.1.0 constant values (javap + decompiled source) so a silent
        // library change fails here, not in the field.
        assertEquals(0, SleepSessionRecord.STAGE_TYPE_UNKNOWN)
        assertEquals(1, SleepSessionRecord.STAGE_TYPE_AWAKE)
        assertEquals(4, SleepSessionRecord.STAGE_TYPE_LIGHT)
        assertEquals(5, SleepSessionRecord.STAGE_TYPE_DEEP)
        assertEquals(6, SleepSessionRecord.STAGE_TYPE_REM)
    }

    @Test
    fun sleepStageTypeFallsBackToUnknownForUnrecognizedRaw() {
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectTypeMappings.sleepStageType("garbage"))
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectTypeMappings.sleepStageType(""))
        // Case matters: stageRaw persists SleepStage.name, all uppercase.
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectTypeMappings.sleepStageType("deep"))
    }

    // ── pl-sleep id builder (identity trap #1: never the block UUID, never the session UUID) ──

    @Test
    fun sleepRecordIdIsPlainDayEpochForTheMainSession() {
        assertEquals("pl-sleep-$day0", HealthConnectTypeMappings.sleepSessionRecordId(day0))
    }

    @Test
    fun sleepRecordIdIsSuffixedForNonMainSessions() {
        assertEquals("pl-sleep-$day0-1", HealthConnectTypeMappings.sleepSessionRecordId(day0, 1))
        assertEquals("pl-sleep-$day0-2", HealthConnectTypeMappings.sleepSessionRecordId(day0, 2))
    }

    @Test
    fun sleepIdIsStableAcrossResyncsAndBlockUuidChurn() {
        // The id is a pure function of the session's waking-day date: the same date yields the
        // same id on every re-sync, no matter that upsertSleepSessionAtomic replaced the stage
        // blocks with fresh random UUIDs — block ids never enter the input at all.
        val firstPass = HealthConnectTypeMappings.sleepSessionRecordId(day0)
        val resyncPass = HealthConnectTypeMappings.sleepSessionRecordId(day0)
        assertEquals(firstPass, resyncPass)
        assertTrue(firstPass.startsWith("pl-sleep-"))
        assertTrue(firstPass.removePrefix("pl-sleep-").all { it.isDigit() })
        // Recomputed from the day's (re-segmented) session shape after the churn: still equal.
        val day = listOf(SleepDaySession(nightStart, 450L))
        assertEquals(firstPass, HealthConnectTypeMappings.sleepSessionRecordId(day0, HealthConnectTypeMappings.sleepSessionSuffix(day, 0)))
    }

    // ── multi-session waking days: main keeps the plain id, naps get deterministic suffixes ──

    @Test
    fun mainSleepIsTheLongestSession() {
        val day = listOf(
            SleepDaySession(napStart, 60L),
            SleepDaySession(nightStart, 450L),
        )
        assertEquals(1, HealthConnectTypeMappings.mainSleepIndex(day))
    }

    @Test
    fun mainSleepTieGoesToTheEarliestStart() {
        val day = listOf(
            SleepDaySession(nightStart, 300L),
            SleepDaySession(napStart, 300L),
        )
        assertEquals(1, HealthConnectTypeMappings.mainSleepIndex(day))
    }

    @Test
    fun mainSleepIndexOfEmptyDayIsNull() {
        assertNull(HealthConnectTypeMappings.mainSleepIndex(emptyList()))
    }

    @Test
    fun singleSessionDayKeepsThePlainId() {
        val day = listOf(SleepDaySession(nightStart, 450L))
        assertNull(HealthConnectTypeMappings.sleepSessionSuffix(day, 0))
        assertEquals("pl-sleep-$day0", HealthConnectTypeMappings.sleepSessionRecordId(day0, HealthConnectTypeMappings.sleepSessionSuffix(day, 0)))
    }

    @Test
    fun nightKeepsPlainIdNapTakesSuffixedId() {
        val day = listOf(
            SleepDaySession(napStart, 60L),
            SleepDaySession(nightStart, 450L),
        )
        assertNull(HealthConnectTypeMappings.sleepSessionSuffix(day, 1)) // night is main
        assertEquals(1, HealthConnectTypeMappings.sleepSessionSuffix(day, 0)) // nap
        assertEquals("pl-sleep-$day0", HealthConnectTypeMappings.sleepSessionRecordId(day0, HealthConnectTypeMappings.sleepSessionSuffix(day, 1)))
        assertEquals("pl-sleep-$day0-1", HealthConnectTypeMappings.sleepSessionRecordId(day0, HealthConnectTypeMappings.sleepSessionSuffix(day, 0)))
    }

    @Test
    fun twoNapsOnOneDayGetStableStartOrderedSuffixes() {
        val nap2Start = day0 + 16L * 3_600_000L
        val day = listOf(
            SleepDaySession(nightStart, 450L),
            SleepDaySession(nap2Start, 45L),
            SleepDaySession(napStart, 60L),
        )
        assertNull(HealthConnectTypeMappings.sleepSessionSuffix(day, 0)) // night is main
        assertEquals(2, HealthConnectTypeMappings.sleepSessionSuffix(day, 1)) // 16:00 nap starts later
        assertEquals(1, HealthConnectTypeMappings.sleepSessionSuffix(day, 2)) // 14:00 nap starts earlier
    }

    // ── stage normalization: sort, clamp, drop overlaps, drop zero/negative ──

    // Normalization window: 09:00–13:00 (minutes relative to s0).
    private val s0 = 9L * 3_600_000L
    private val s1 = 13L * 3_600_000L

    private fun span(startMin: Long, endMin: Long, type: Int = SleepSessionRecord.STAGE_TYPE_LIGHT): SleepStageSpan =
        SleepStageSpan(s0 + startMin * 60_000L, s0 + endMin * 60_000L, type)

    @Test
    fun normalizeEmptyInputIsEmpty() {
        assertTrue(HealthConnectTypeMappings.normalizeSleepStages(s0, s1, emptyList()).isEmpty())
    }

    @Test
    fun normalizeRejectsNonPositiveSessionSpan() {
        assertTrue(HealthConnectTypeMappings.normalizeSleepStages(s0, s0, listOf(span(0, 60))).isEmpty())
        assertTrue(HealthConnectTypeMappings.normalizeSleepStages(s1, s0, listOf(span(0, 60))).isEmpty())
    }

    @Test
    fun normalizeSortsByStart() {
        val out = HealthConnectTypeMappings.normalizeSleepStages(s0, s1, listOf(span(120, 180), span(0, 60), span(60, 120)))
        assertEquals(listOf(0L, 60 * 60_000L, 120 * 60_000L), out.map { it.startMs - s0 })
        assertEquals(listOf(60 * 60_000L, 120 * 60_000L, 180 * 60_000L), out.map { it.endMs - s0 })
    }

    @Test
    fun normalizeClampsToSessionBounds() {
        val out = HealthConnectTypeMappings.normalizeSleepStages(s0, s1, listOf(span(-30, 30), span(200, 330)))
        assertEquals(2, out.size)
        assertEquals(s0, out[0].startMs) // clamped up to the session start
        assertEquals(s0 + 30 * 60_000L, out[0].endMs)
        assertEquals(s0 + 200 * 60_000L, out[1].startMs)
        assertEquals(s1, out[1].endMs) // clamped down to the session end
    }

    @Test
    fun normalizeDropsOverlapsKeepingEarlierTruncatingLater() {
        val out = HealthConnectTypeMappings.normalizeSleepStages(
            s0, s1,
            listOf(span(0, 120, SleepSessionRecord.STAGE_TYPE_DEEP), span(60, 240)),
        )
        assertEquals(2, out.size)
        assertEquals(SleepStageSpan(s0, s0 + 120 * 60_000L, SleepSessionRecord.STAGE_TYPE_DEEP), out[0])
        assertEquals(SleepStageSpan(s0 + 120 * 60_000L, s0 + 240 * 60_000L, SleepSessionRecord.STAGE_TYPE_LIGHT), out[1])
    }

    @Test
    fun normalizeDropsStageFullyCoveredByAnEarlierOne() {
        val out = HealthConnectTypeMappings.normalizeSleepStages(s0, s1, listOf(span(0, 240), span(60, 120)))
        assertEquals(1, out.size)
        assertEquals(SleepStageSpan(s0, s0 + 240 * 60_000L, SleepSessionRecord.STAGE_TYPE_LIGHT), out[0])
    }

    @Test
    fun normalizeDropsLaterStageWhenEarlierReachesTheSessionEnd() {
        val out = HealthConnectTypeMappings.normalizeSleepStages(
            s0, s1,
            listOf(span(0, 240, SleepSessionRecord.STAGE_TYPE_DEEP), span(230, 300)),
        )
        assertEquals(1, out.size)
        assertEquals(s1, out[0].endMs)
    }

    @Test
    fun normalizeAllowsTouchingStages() {
        // The record's constructor validation rejects a stage ending AFTER the next stage's
        // start; end == next start is legal, so touching blocks must survive.
        val out = HealthConnectTypeMappings.normalizeSleepStages(s0, s1, listOf(span(0, 60), span(60, 120)))
        assertEquals(2, out.size)
        assertEquals(s0 + 60 * 60_000L, out[0].endMs)
        assertEquals(s0 + 60 * 60_000L, out[1].startMs)
    }

    @Test
    fun normalizeDropsZeroAndNegativeLengthStages() {
        val out = HealthConnectTypeMappings.normalizeSleepStages(s0, s1, listOf(span(60, 60), span(120, 90), span(0, 30)))
        assertEquals(1, out.size)
        assertEquals(SleepStageSpan(s0, s0 + 30 * 60_000L, SleepSessionRecord.STAGE_TYPE_LIGHT), out[0])
    }

    @Test
    fun normalizeDropsStagesEntirelyOutsideTheSession() {
        val out = HealthConnectTypeMappings.normalizeSleepStages(s0, s1, listOf(span(-120, -60), span(300, 360)))
        assertTrue(out.isEmpty())
    }
}
