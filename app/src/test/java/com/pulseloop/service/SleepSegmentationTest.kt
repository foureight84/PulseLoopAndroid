package com.pulseloop.service

import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure sleep-splitting + aggregation logic ported from iOS `SleepServiceTests`
 * (PR #83): the 60-minute gap segmenter and the day-collapse used by aggregate math.
 */
class SleepSegmentationTest {
    private val minute = 60_000L
    private var idSeq = 0

    /** A stage block starting [startMin] minutes after [base], running [durMin] minutes. */
    private fun block(base: Long, startMin: Int, durMin: Int, stage: SleepStage = SleepStage.LIGHT) =
        SleepStageBlockEntity(
            id = "b${idSeq++}", sessionId = "s", startAt = base + startMin * minute,
            startMinute = startMin, durationMinutes = durMin, stageRaw = stage.name,
        )

    // ── segment ──────────────────────────────────────────────────────────

    @Test
    fun `empty in empty out`() {
        assertTrue(SleepSegmentation.segment(emptyList()).isEmpty())
    }

    @Test
    fun `contiguous blocks stay in one session`() {
        val base = 0L
        val blocks = listOf(block(base, 0, 30), block(base, 30, 30), block(base, 60, 30))
        val groups = SleepSegmentation.segment(blocks)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test
    fun `short mid-night awakening does not split`() {
        val base = 0L
        // 59-minute gap between block end (min 60) and next start (min 119) — below the threshold.
        val blocks = listOf(block(base, 0, 60), block(base, 119, 60))
        assertEquals(1, SleepSegmentation.segment(blocks).size)
    }

    @Test
    fun `a 60-minute gap splits into two sessions`() {
        val base = 0L
        // Gap from min 60 to min 120 == exactly 60 minutes -> split.
        val blocks = listOf(block(base, 0, 60), block(base, 120, 45))
        val groups = SleepSegmentation.segment(blocks)
        assertEquals(2, groups.size)
        assertEquals(1, groups[0].size)
        assertEquals(1, groups[1].size)
    }

    @Test
    fun `night plus afternoon nap splits into two, time-ordered`() {
        val base = 0L
        val night = (0 until 8).map { block(base, it * 60, 60, SleepStage.DEEP) }   // 8h night
        val nap = block(base, 900, 45)                                              // ~15h later
        val groups = SleepSegmentation.segment(nap.let { night + it }.shuffled())
        assertEquals(2, groups.size)
        assertTrue(groups[0].first().startAt < groups[1].first().startAt)
        assertEquals(8, groups[0].size)
        assertEquals(1, groups[1].size)
    }

    @Test
    fun `overlapping blocks do not spuriously split`() {
        val base = 0L
        // A nested block fully inside the first must not push prevEnd backward and open a gap.
        val blocks = listOf(block(base, 0, 120), block(base, 30, 10), block(base, 130, 30))
        assertEquals(1, SleepSegmentation.segment(blocks).size)
    }

    // ── collapseByDay ────────────────────────────────────────────────────

    private fun session(id: String, day: Long, startMin: Int, durMin: Int, score: Int?) =
        SleepSessionEntity(
            id = id, date = day, startAt = day + startMin * minute,
            endAt = day + (startMin + durMin) * minute, totalMinutes = durMin, score = score,
        )

    @Test
    fun `single-session day passes through unchanged`() {
        val day = 1_000_000L
        val s = session("night", day, 0, 480, 80)
        val collapsed = SleepInsights.collapseByDay(listOf(s)) { emptyList() }
        assertEquals(1, collapsed.size)
        assertEquals("night", collapsed[0].session.id)
    }

    @Test
    fun `multi-session day sums minutes and spans bounds`() {
        val day = 1_000_000L
        val night = session("night", day, 0, 480, 80)     // 8h
        val nap = session("nap", day, 900, 45, 60)        // 45m later in the day
        val collapsed = SleepInsights.collapseByDay(listOf(night, nap)) { emptyList() }
        assertEquals(1, collapsed.size)
        val c = collapsed[0].session
        assertEquals(480 + 45, c.totalMinutes)             // SUM, not wall-clock span
        assertEquals(night.startAt, c.startAt)             // earliest start
        assertEquals(nap.endAt, c.endAt)                   // latest end
    }

    @Test
    fun `combined score is duration-weighted`() {
        val day = 1_000_000L
        val night = session("night", day, 0, 480, 90)     // long, high score
        val nap = session("nap", day, 900, 30, 30)         // short, low score
        val collapsed = SleepInsights.collapseByDay(listOf(night, nap)) { emptyList() }
        // Weighted: (90*480 + 30*30) / (480+30) = 44100/510 ≈ 86.5 -> 86.
        assertEquals(86, collapsed[0].session.score)
    }

    @Test
    fun `null scores are ignored, all-null yields null`() {
        val day = 1_000_000L
        val a = session("a", day, 0, 480, null)
        val b = session("b", day, 900, 30, null)
        val collapsed = SleepInsights.collapseByDay(listOf(a, b)) { emptyList() }
        assertNull(collapsed[0].session.score)
    }

    @Test
    fun `distinct days stay distinct and sorted`() {
        val d1 = 1_000_000L
        val d2 = d1 + 86_400_000L
        val collapsed = SleepInsights.collapseByDay(
            listOf(session("later", d2, 0, 400, 70), session("earlier", d1, 0, 400, 70)),
        ) { emptyList() }
        assertEquals(2, collapsed.size)
        assertTrue(collapsed[0].session.date < collapsed[1].session.date)
    }
}
