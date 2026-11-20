package com.pulseloop.service

import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.SleepStage
import com.pulseloop.settings.ZenWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for Bedtime-bounded sleep tail suggestion detection and dismissal pruning (issue #148).
 */
class SleepTailSuggestionTest {

    private val zone = ZoneId.of("UTC")
    private val wakingDay = ZonedDateTime.of(2025, 11, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(h: Int, m: Int, s: Int = 0): Long = wakingDay + ((h * 60L + m) * 60 + s) * 1000L

    private fun ringBlocks(
        sessionId: String = "s1",
        startAt: Long,
        durationMinutes: Int,
        addedTailMinutes: Int = 0,
    ): List<SleepStageBlockEntity> {
        val measured = buildStageBlocks(
            sessionId = sessionId,
            startTs = startAt,
            stages = List(durationMinutes) { SleepStage.LIGHT },
            recordStartAt = startAt,
        )
        if (addedTailMinutes <= 0) return measured
        val measuredEnd = measured.maxOf { it.startAt + it.durationMinutes * 60_000L }
        return measured + SleepStageBlockEntity(
            sessionId = sessionId,
            startAt = measuredEnd,
            startMinute = 0,
            durationMinutes = addedTailMinutes,
            stageRaw = SleepStage.ADDED.name,
            recordStartAt = startAt,
        )
    }

    private fun session(
        id: String = "s1",
        blocks: List<SleepStageBlockEntity>,
        sourceRaw: String = "ring",
    ) = SleepSessionEntity(
        id = id,
        date = wakingDay,
        startAt = blocks.minOf { it.startAt },
        endAt = blocks.maxOf { it.startAt + it.durationMinutes * 60_000L },
        totalMinutes = asleepMinutes(blocks),
        sourceRaw = sourceRaw,
    )

    private fun detect(
        sessions: List<SleepSessionEntity>,
        blocksBySession: Map<String, List<SleepStageBlockEntity>>,
        windows: List<ZenWindow>? = listOf(ZenWindow(start = at(2, 0), end = at(9, 27))),
        buckets: List<ActivityBucketEntity> = emptyList(),
        lastFullSyncAt: Long? = at(9, 30),
        isDismissed: (Long, Long) -> Boolean = { _, _ -> false },
        isEdited: (Long, Long) -> Boolean = { _, _ -> false },
    ): SleepTailSuggestion? = SleepTailSuggestionDetector.detect(
        daySessions = sessions,
        dayBlocks = blocksBySession,
        bedtimeWindows = windows,
        stepBuckets = buckets,
        lastFullSyncAt = lastFullSyncAt,
        isDismissed = isDismissed,
        isEdited = isEdited,
        zone = zone,
    )

    @Test
    fun `reporter Oct 12 example — 28m zero-step gap triggers prompt`() {
        val blocks = ringBlocks(startAt = at(0, 10), durationMinutes = 408) // 00:10 -> 06:58
        val s = session(blocks = blocks)

        val suggestion = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = at(7, 26))),
            lastFullSyncAt = at(7, 30),
        )

        assertNotNull(suggestion)
        assertEquals(at(6, 58), suggestion!!.ringEndedAt)
        assertEquals(at(7, 26), suggestion.bedtimeEndedAt)
        assertEquals(at(7, 26), suggestion.suggestedEndAt)
        assertEquals(28, suggestion.gapMinutes)
    }

    @Test
    fun `reporter Nov 3 example — 75m gap ignores pre-end get-up and post-bedtime buckets`() {
        val blocks = ringBlocks(startAt = at(0, 4), durationMinutes = 450) // 00:04 -> 07:34
        val s = session(blocks = blocks)
        val buckets = listOf(
            // Get-up at 07:34 sits in the 07:30 bucket (< 07:34 startEpoch).
            ActivityBucketEntity(startEpoch = at(7, 30), date = wakingDay, steps = 38),
            // Turning off Bedtime / getting out of bed at 08:49 sits in 08:45 bucket (straddles 08:49).
            ActivityBucketEntity(startEpoch = at(8, 45), date = wakingDay, steps = 54),
        )

        val suggestion = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 49))),
            buckets = buckets,
            lastFullSyncAt = at(9, 0),
        )

        assertNotNull(suggestion)
        assertEquals(at(7, 34), suggestion!!.ringEndedAt)
        assertEquals(at(8, 49), suggestion.suggestedEndAt)
        assertEquals(75, suggestion.gapMinutes)
    }

    @Test
    fun `reporter Nov 15 example — 99m zero-step gap triggers prompt`() {
        val blocks = ringBlocks(startAt = at(2, 59), durationMinutes = 289) // 02:59 -> 07:48
        val s = session(blocks = blocks)

        val suggestion = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(1, 0), end = at(9, 27))),
            lastFullSyncAt = at(9, 30),
        )

        assertNotNull(suggestion)
        assertEquals(at(7, 48), suggestion!!.ringEndedAt)
        assertEquals(at(9, 27), suggestion.suggestedEndAt)
        assertEquals(99, suggestion.gapMinutes)
    }

    @Test
    fun `interior steps inside tail suppress prompt`() {
        val blocks = ringBlocks(startAt = at(0, 4), durationMinutes = 450) // -> 07:34
        val s = session(blocks = blocks)
        val buckets = listOf(
            ActivityBucketEntity(startEpoch = at(7, 45), date = wakingDay, steps = 12),
        )

        val suggestion = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 49))),
            buckets = buckets,
            lastFullSyncAt = at(9, 0),
        )

        assertNull(suggestion)
    }

    @Test
    fun `bucket ending right at Bedtime end suppresses while straddling tail bucket does not`() {
        val blocks = ringBlocks(startAt = at(0, 4), durationMinutes = 450) // -> 07:34
        val s = session(blocks = blocks)
        val bucketAt0830 = listOf(
            ActivityBucketEntity(startEpoch = at(8, 30), date = wakingDay, steps = 20),
        )

        // 08:30..08:45 lies wholly inside 07:34..08:45 -> suppressed.
        assertNull(
            detect(
                sessions = listOf(s),
                blocksBySession = mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 45))),
                buckets = bucketAt0830,
                lastFullSyncAt = at(9, 0),
            ),
        )

        // With Bedtime ending at 08:44, 08:30..08:45 straddles wake-up -> ignored, prompt fires.
        assertNotNull(
            detect(
                sessions = listOf(s),
                blocksBySession = mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 44))),
                buckets = bucketAt0830,
                lastFullSyncAt = at(9, 0),
            ),
        )
    }

    @Test
    fun `null empty or open Bedtime windows produce no prompt`() {
        val blocks = ringBlocks(startAt = at(2, 59), durationMinutes = 289)
        val s = session(blocks = blocks)

        assertNull(detect(listOf(s), mapOf(s.id to blocks), windows = null))
        assertNull(detect(listOf(s), mapOf(s.id to blocks), windows = emptyList()))
        assertNull(detect(listOf(s), mapOf(s.id to blocks), windows = listOf(ZenWindow(start = at(2, 0), end = null))))
    }

    @Test
    fun `full sync must be present and at or after Bedtime end`() {
        val blocks = ringBlocks(startAt = at(2, 59), durationMinutes = 289) // -> 07:48
        val s = session(blocks = blocks)
        val windows = listOf(ZenWindow(start = at(1, 0), end = at(9, 27)))

        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = windows,
                lastFullSyncAt = at(9, 26),
            ),
        )
        assertNotNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = windows,
                lastFullSyncAt = at(9, 27),
            ),
        )
        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = windows,
                lastFullSyncAt = null,
            ),
        )
    }

    @Test
    fun `gap must fall inside 15 to 120 minutes inclusive`() {
        val blocks = ringBlocks(startAt = at(1, 0), durationMinutes = 360) // -> 07:00
        val s = session(blocks = blocks)

        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(0, 30), end = at(7, 14))),
                lastFullSyncAt = at(10, 0),
            ),
        )
        assertNotNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(0, 30), end = at(7, 15))),
                lastFullSyncAt = at(10, 0),
            ),
        )
        assertNotNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(0, 30), end = at(9, 0))),
                lastFullSyncAt = at(10, 0),
            ),
        )
        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(0, 30), end = at(9, 1))),
                lastFullSyncAt = at(10, 0),
            ),
        )
    }

    @Test
    fun `already extended record with ADDED block suppresses prompt`() {
        val blocks = ringBlocks(startAt = at(2, 59), durationMinutes = 289, addedTailMinutes = 20)
        val s = session(blocks = blocks)

        assertNull(detect(listOf(s), mapOf(s.id to blocks)))
    }

    @Test
    fun `manually edited or dismissed record suppresses prompt`() {
        val blocks = ringBlocks(startAt = at(2, 59), durationMinutes = 289)
        val s = session(blocks = blocks)

        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                isEdited = { date, start -> date == wakingDay && start == at(2, 59) },
            ),
        )
        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                isDismissed = { date, start -> date == wakingDay && start == at(2, 59) },
            ),
        )
    }

    @Test
    fun `demo session never triggers prompt`() {
        val blocks = ringBlocks(startAt = at(2, 59), durationMinutes = 289)
        val s = session(blocks = blocks, sourceRaw = "demo")

        assertNull(detect(listOf(s), mapOf(s.id to blocks)))
    }

    @Test
    fun `multi-session day blocks overlap across sessions while keeping unrelated afternoon nap`() {
        val nightBlocks = ringBlocks("night", startAt = at(2, 0), durationMinutes = 300) // 02:00..07:00
        val nightSession = session("night", nightBlocks)

        // Case A: a second morning session overlaps (07:00, 08:30) -> must suppress.
        val lateMorningBlocks = ringBlocks("late", startAt = at(7, 20), durationMinutes = 80) // 07:20..08:40
        val lateMorningSession = session("late", lateMorningBlocks)
        assertNull(
            detect(
                sessions = listOf(nightSession, lateMorningSession),
                blocksBySession = mapOf("night" to nightBlocks, "late" to lateMorningBlocks),
                windows = listOf(ZenWindow(start = at(1, 0), end = at(8, 30))),
                lastFullSyncAt = at(9, 0),
            ),
        )

        // Case B: an unrelated afternoon nap 15:00..16:00 does not block the 07:00 -> 08:30 morning prompt.
        val napBlocks = ringBlocks("nap", startAt = at(15, 0), durationMinutes = 60)
        val napSession = session("nap", napBlocks)
        val suggestion = detect(
            sessions = listOf(nightSession, napSession),
            blocksBySession = mapOf("night" to nightBlocks, "nap" to napBlocks),
            windows = listOf(ZenWindow(start = at(1, 0), end = at(8, 30))),
            lastFullSyncAt = at(16, 30),
        )
        assertNotNull(suggestion)
        assertEquals("night", suggestion!!.sessionId)
    }

    @Test
    fun `multi-record session carries previousRun and snaps end onto record minute grid`() {
        val rec1 = ringBlocks("s1", startAt = at(0, 51, 23), durationMinutes = 293) // 00:51:23..05:44:23
        val rec2 = ringBlocks("s1", startAt = at(6, 56, 23), durationMinutes = 71)  // 06:56:23..08:07:23
        val allBlocks = rec1 + rec2
        val s = session("s1", allBlocks)

        val suggestion = detect(
            sessions = listOf(s),
            blocksBySession = mapOf("s1" to allBlocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 50, 10))),
            lastFullSyncAt = at(9, 0),
        )

        assertNotNull(suggestion)
        assertEquals(at(6, 56, 23), suggestion!!.run.recordStartAt)
        assertNotNull(suggestion.previousRun)
        assertEquals(at(0, 51, 23), suggestion.previousRun!!.recordStartAt)
        // Wall-clock 08:50:10 -> minute floor 08:50:00 -> ceiled onto :23s grid -> 08:50:23.
        assertEquals(at(8, 50, 23), suggestion.suggestedEndAt)
        assertEquals(43, suggestion.gapMinutes)
    }

    @Test
    fun `extension is clamped at 7 PM night boundary and suppressed if clamped gap drops below 15m`() {
        val blocks1 = ringBlocks("s1", startAt = at(16, 0), durationMinutes = 130) // 16:00..18:10
        val s1 = session("s1", blocks1)
        val clamped = detect(
            sessions = listOf(s1),
            blocksBySession = mapOf("s1" to blocks1),
            windows = listOf(ZenWindow(start = at(15, 0), end = at(19, 30))),
            lastFullSyncAt = at(20, 0),
        )
        assertNotNull(clamped)
        assertEquals(at(19, 0), clamped!!.suggestedEndAt)
        assertEquals(50, clamped.gapMinutes)

        // Ending at 18:55 leaves only 5m before 19:00 night boundary (< 15m) -> no prompt.
        val blocks2 = ringBlocks("s2", startAt = at(17, 0), durationMinutes = 115) // 17:00..18:55
        val s2 = session("s2", blocks2)
        assertNull(
            detect(
                sessions = listOf(s2),
                blocksBySession = mapOf("s2" to blocks2),
                windows = listOf(ZenWindow(start = at(15, 0), end = at(19, 30))),
                lastFullSyncAt = at(20, 0),
            ),
        )
    }

    @Test
    fun `store pruneKeys drops expired and malformed keys while keeping 45 day entries`() {
        val now = wakingDay + 45L * 86_400_000L
        val justInside = "${wakingDay}_12345"
        val expired = "${wakingDay - 86_400_000L}_99999"
        val recent = "${wakingDay + 10L * 86_400_000L}_54321"
        val malformed = "not-a-date"

        val pruned = SleepTailSuggestionStore.pruneKeys(
            setOf(justInside, expired, recent, malformed),
            nowMillis = now,
        )

        assertEquals(setOf(justInside, recent), pruned)
        assertTrue(expired !in pruned)
        assertTrue(malformed !in pruned)
    }

    private fun hr(ts: Long, bpm: Double, source: String = "ring_history") = MeasurementEntity(
        kindRaw = "HEART_RATE",
        value = bpm,
        unit = "bpm",
        timestamp = ts,
        sourceRaw = source,
    )

    @Test
    fun `withHeartRateContext attaches single hourly reading inside gap and night average`() {
        // Nov 16 example: ring stopped 07:54, Bedtime ended 08:52, hourly HR at 08:00 = 58, 09:00 = 104 (outside).
        val blocks = ringBlocks(startAt = at(0, 14), durationMinutes = 460) // 00:14..07:54
        val s = session(blocks = blocks)
        val base = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 52))),
            lastFullSyncAt = at(9, 10),
        )!!

        val enriched = SleepTailSuggestionDetector.withHeartRateContext(
            suggestion = base,
            sessionStartAt = s.startAt,
            hrSamples = listOf(
                hr(at(1, 0), 56.0),
                hr(at(4, 0), 60.0),
                hr(at(7, 0), 61.0),
                hr(at(8, 0), 58.0),
                hr(at(9, 0), 104.0),
            ),
        )

        assertEquals(listOf(TailHeartRateReading(at(8, 0), 58)), enriched.gapHeartRates)
        assertEquals(59, enriched.nightAvgHeartRate)
    }

    @Test
    fun `withHeartRateContext respects boundaries filters demo and invalid values and deduplicates per minute`() {
        val blocks = ringBlocks(startAt = at(0, 14), durationMinutes = 460) // 00:14..07:54
        val s = session(blocks = blocks)
        val base = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 52))),
            lastFullSyncAt = at(9, 10),
        )!!

        val enriched = SleepTailSuggestionDetector.withHeartRateContext(
            suggestion = base,
            sessionStartAt = s.startAt,
            hrSamples = listOf(
                // Exact ringEndedAt counts toward night avg, not gap.
                hr(at(7, 54), 60.0),
                // Exact suggestedEndAt is excluded from gap.
                hr(at(8, 52), 95.0),
                // Demo / mock and implausible values ignored.
                hr(at(8, 10), 62.0, source = "demo"),
                hr(at(8, 12), 63.0, source = "mock"),
                hr(at(8, 15), 0.0),
                hr(at(8, 20), 25.0),
                hr(at(8, 25), 250.0),
                // Same-minute duplicate keeps latest (08:30:40 -> 57 bpm).
                hr(at(8, 30, 10), 61.0),
                hr(at(8, 30, 40), 57.0),
            ),
        )

        assertEquals(listOf(TailHeartRateReading(at(8, 30, 40), 57)), enriched.gapHeartRates)
        assertEquals(60, enriched.nightAvgHeartRate)
    }

    @Test
    fun `withHeartRateContext sorts multiple gap readings ascending and handles empty night HR`() {
        val blocks = ringBlocks(startAt = at(0, 14), durationMinutes = 460) // 00:14..07:54
        val s = session(blocks = blocks)
        val base = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = at(8, 52))),
            lastFullSyncAt = at(9, 10),
        )!!

        val enriched = SleepTailSuggestionDetector.withHeartRateContext(
            suggestion = base,
            sessionStartAt = s.startAt,
            hrSamples = listOf(
                hr(at(8, 40), 64.0),
                hr(at(8, 0), 58.0),
                hr(at(8, 20), 59.0),
                hr(at(8, 10), 57.0),
            ),
        )

        assertEquals(
            listOf(
                TailHeartRateReading(at(8, 0), 58),
                TailHeartRateReading(at(8, 10), 57),
                TailHeartRateReading(at(8, 20), 59),
                TailHeartRateReading(at(8, 40), 64),
            ),
            enriched.gapHeartRates,
        )
        assertNull(enriched.nightAvgHeartRate)
    }

    @Test
    fun `intraday step bucket support and bucket duration follow ring family`() {
        assertFalse(SleepTailSuggestionDetector.supportsIntradayStepBuckets(RingDeviceType.CRP))
        assertFalse(SleepTailSuggestionDetector.supportsIntradayStepBuckets(RingDeviceType.LUCK_RING))
        assertTrue(SleepTailSuggestionDetector.supportsIntradayStepBuckets(RingDeviceType.YCBT))
        assertTrue(SleepTailSuggestionDetector.supportsIntradayStepBuckets(RingDeviceType.COLMI_R02))
        assertTrue(SleepTailSuggestionDetector.supportsIntradayStepBuckets(RingDeviceType.JRING))

        assertEquals(60_000L, SleepTailSuggestionDetector.stepBucketDurationMsFor(RingDeviceType.JRING))
        assertEquals(15 * 60_000L, SleepTailSuggestionDetector.stepBucketDurationMsFor(RingDeviceType.YCBT))

        val blocks = ringBlocks(startAt = at(2, 59), durationMinutes = 289)
        val s = session(blocks = blocks)
        assertNull(
            SleepTailSuggestionDetector.detect(
                daySessions = listOf(s),
                dayBlocks = mapOf(s.id to blocks),
                bedtimeWindows = listOf(ZenWindow(start = at(1, 0), end = at(9, 27))),
                stepBuckets = emptyList(),
                lastFullSyncAt = at(9, 30),
                isDismissed = { _, _ -> false },
                isEdited = { _, _ -> false },
                zone = zone,
                hasIntradayStepBuckets = false,
            ),
        )
    }

    @Test
    fun `unaligned 28m gap suppresses on 15m buckets but fires and checks steps on 1m JRing buckets`() {
        val blocks = ringBlocks(startAt = at(0, 1), durationMinutes = 420) // 00:01..07:01
        val s = session(blocks = blocks)
        val windows = listOf(ZenWindow(start = at(0, 0), end = at(7, 29))) // first aligned 15m slot 07:15..07:30 > 07:29

        assertNull(
            SleepTailSuggestionDetector.detect(
                daySessions = listOf(s),
                dayBlocks = mapOf(s.id to blocks),
                bedtimeWindows = windows,
                stepBuckets = emptyList(),
                lastFullSyncAt = at(8, 0),
                isDismissed = { _, _ -> false },
                isEdited = { _, _ -> false },
                zone = zone,
                stepBucketDurationMs = 15 * 60_000L,
            ),
        )

        val jringPrompt = SleepTailSuggestionDetector.detect(
            daySessions = listOf(s),
            dayBlocks = mapOf(s.id to blocks),
            bedtimeWindows = windows,
            stepBuckets = emptyList(),
            lastFullSyncAt = at(8, 0),
            isDismissed = { _, _ -> false },
            isEdited = { _, _ -> false },
            zone = zone,
            stepBucketDurationMs = 60_000L,
        )
        assertNotNull(jringPrompt)
        assertEquals(28, jringPrompt!!.gapMinutes)

        assertNull(
            SleepTailSuggestionDetector.detect(
                daySessions = listOf(s),
                dayBlocks = mapOf(s.id to blocks),
                bedtimeWindows = windows,
                stepBuckets = listOf(ActivityBucketEntity(startEpoch = at(7, 25), date = wakingDay, steps = 8)),
                lastFullSyncAt = at(8, 0),
                isDismissed = { _, _ -> false },
                isEdited = { _, _ -> false },
                zone = zone,
                stepBucketDurationMs = 60_000L,
            ),
        )
    }

    @Test
    fun `midnight crossing sleep in non-UTC timezone and spring-forward DST fires cleanly`() {
        val la = ZoneId.of("America/Los_Angeles")
        val dstWakingDate = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, la)
        val dstDayKey = dstWakingDate.toInstant().toEpochMilli()
        val sleepStart = ZonedDateTime.of(2026, 3, 7, 23, 15, 0, 0, la).toInstant().toEpochMilli()
        val ringStop = ZonedDateTime.of(2026, 3, 8, 6, 58, 0, 0, la).toInstant().toEpochMilli()
        val bedtimeStart = ZonedDateTime.of(2026, 3, 7, 22, 30, 0, 0, la).toInstant().toEpochMilli()
        val bedtimeEnd = ZonedDateTime.of(2026, 3, 8, 7, 35, 0, 0, la).toInstant().toEpochMilli()
        val durationMin = ((ringStop - sleepStart) / 60_000L).toInt() // 403 elapsed min across spring-forward

        val blocks = ringBlocks(startAt = sleepStart, durationMinutes = durationMin)
        val s = SleepSessionEntity(
            id = "dst",
            date = dstDayKey,
            startAt = sleepStart,
            endAt = ringStop,
            totalMinutes = durationMin,
            sourceRaw = "ring",
        )

        val suggestion = SleepTailSuggestionDetector.detect(
            daySessions = listOf(s),
            dayBlocks = mapOf(s.id to blocks),
            bedtimeWindows = listOf(ZenWindow(start = bedtimeStart, end = bedtimeEnd)),
            stepBuckets = emptyList(),
            lastFullSyncAt = bedtimeEnd + 60_000L,
            isDismissed = { _, _ -> false },
            isEdited = { _, _ -> false },
            zone = la,
        )

        assertNotNull(suggestion)
        assertEquals(ringStop, suggestion!!.ringEndedAt)
        assertEquals(bedtimeEnd, suggestion.suggestedEndAt)
        assertEquals(37, suggestion.gapMinutes)
    }

    @Test
    fun `zero-step bucket does not suppress while bucket starting at ringEndedAt suppresses and non-spanning window is ignored`() {
        val blocks = ringBlocks(startAt = at(0, 0), durationMinutes = 420) // 00:00..07:00
        val s = session(blocks = blocks)
        val windows = listOf(ZenWindow(start = at(0, 0), end = at(7, 30)))

        // Zero-step row does not count as activity.
        assertNotNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = windows,
                buckets = listOf(ActivityBucketEntity(startEpoch = at(7, 15), date = wakingDay, steps = 0)),
                lastFullSyncAt = at(8, 0),
            ),
        )

        // Exact boundary b.startEpoch == lastRun.endAt (07:00..07:15) inside [07:00, 07:30] suppresses.
        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = windows,
                buckets = listOf(ActivityBucketEntity(startEpoch = at(7, 0), date = wakingDay, steps = 10)),
                lastFullSyncAt = at(8, 0),
            ),
        )

        // Window starting after lastRun.endAt or ending right at lastRun.endAt never triggers.
        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(7, 1), end = at(7, 30))),
                lastFullSyncAt = at(8, 0),
            ),
        )
        assertNull(
            detect(
                listOf(s),
                mapOf(s.id to blocks),
                windows = listOf(ZenWindow(start = at(0, 0), end = at(7, 0))),
                lastFullSyncAt = at(8, 0),
            ),
        )
    }

    @Test
    fun `overnight Bedtime prompt beats afternoon nap inside daytime DND`() {
        val nightBlocks = ringBlocks("night", startAt = at(0, 14), durationMinutes = 460) // 00:14..07:54
        val napBlocks = ringBlocks("nap", startAt = at(14, 0), durationMinutes = 60)      // 14:00..15:00
        val night = session("night", nightBlocks)
        val nap = session("nap", napBlocks)

        val suggestion = detect(
            sessions = listOf(night, nap),
            blocksBySession = mapOf("night" to nightBlocks, "nap" to napBlocks),
            windows = listOf(
                ZenWindow(start = at(0, 0), end = at(8, 52)),
                ZenWindow(start = at(13, 50), end = at(15, 30)),
            ),
            lastFullSyncAt = at(16, 0),
        )

        assertNotNull(suggestion)
        assertEquals("night", suggestion!!.sessionId)
        assertEquals(at(8, 52), suggestion.suggestedEndAt)
    }

    @Test
    fun `deleted trailing phantom record inside Bedtime suppresses extension prompt`() {
        val rec1 = ringBlocks("s1", startAt = at(0, 30), durationMinutes = 360) // 00:30..06:30
        val s = session("s1", rec1)

        assertNull(
            SleepTailSuggestionDetector.detect(
                daySessions = listOf(s),
                dayBlocks = mapOf("s1" to rec1),
                bedtimeWindows = listOf(ZenWindow(start = at(0, 0), end = at(8, 15))),
                stepBuckets = emptyList(),
                lastFullSyncAt = at(8, 30),
                isDismissed = { _, _ -> false },
                isEdited = { _, _ -> false },
                zone = zone,
                hasDeletedRecordInGap = { _, gapStart, gapEnd -> at(7, 0) in (gapStart + 1)..(gapEnd - 1) },
            ),
        )
    }

    @Test
    fun `120m gap with sub-minute remainder keeps raw bedtimeEndedAt while clamping suggestedEndAt`() {
        val blocks = ringBlocks(startAt = at(0, 14, 50), durationMinutes = 460) // 00:14:50..07:54:50
        val s = session(blocks = blocks)
        val windowEnd = at(9, 55, 10) // rawDiff = 120m 20s -> rawGapMinutes = 120

        val suggestion = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = windowEnd)),
            lastFullSyncAt = windowEnd + 60_000L,
        )

        assertNotNull(suggestion)
        assertEquals(windowEnd, suggestion!!.bedtimeEndedAt)
        assertEquals(at(9, 54, 50), suggestion.suggestedEndAt)
        assertEquals(120, suggestion.gapMinutes)
    }

    @Test
    fun `withHeartRateContext filters non-finite enforces 30-220 deduplicates 1Hz night burst and excludes post-bedtime`() {
        val blocks = ringBlocks(startAt = at(0, 14, 23), durationMinutes = 460) // 00:14:23..07:54:23
        val s = session(blocks = blocks)
        val windowEnd = at(8, 50, 10)
        // grid-snapped suggestedEndAt = 08:50:23 > bedtimeEndedAt (08:50:10).
        val base = detect(
            sessions = listOf(s),
            blocksBySession = mapOf(s.id to blocks),
            windows = listOf(ZenWindow(start = at(0, 0), end = windowEnd)),
            lastFullSyncAt = at(9, 10),
        )!!

        val burstMinute = (0 until 60).map { sec -> hr(at(4, 0, sec), 80.0, source = "live") }
        val enriched = SleepTailSuggestionDetector.withHeartRateContext(
            suggestion = base,
            sessionStartAt = s.startAt,
            hrSamples = burstMinute + listOf(
                hr(at(1, 0), 56.0),
                hr(at(2, 0), 56.0),
                hr(at(3, 0), 56.0),
                hr(at(5, 0), Double.NaN),
                hr(at(5, 5), Double.POSITIVE_INFINITY),
                hr(at(5, 10), Double.NEGATIVE_INFINITY),
                hr(at(8, 0), 29.4),  // rounds to 29 -> out
                hr(at(8, 5), 30.0),  // exact min -> in
                hr(at(8, 15), 220.0), // exact max -> in
                hr(at(8, 25), 220.6), // rounds to 221 -> out
                hr(at(8, 50, 15), 99.0), // between bedtimeEndedAt (:10) and suggestedEndAt (:23) -> out!
            ),
        )

        assertEquals(
            listOf(
                TailHeartRateReading(at(8, 5), 30),
                TailHeartRateReading(at(8, 15), 220),
            ),
            enriched.gapHeartRates,
        )
        // 60 burst samples at 04:00 collapse to 1 sample (80), averaged equally with 3x 56 -> (80+56*3)/4 = 62.
        assertEquals(62, enriched.nightAvgHeartRate)
    }
}
