package com.pulseloop.service

import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import com.pulseloop.settings.ZenWindow
import org.junit.Assert.assertEquals
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
    fun `full sync must be at or after Bedtime end unless null`() {
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
        assertNotNull(
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
}
