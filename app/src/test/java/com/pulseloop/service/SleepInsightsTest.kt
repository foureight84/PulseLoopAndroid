package com.pulseloop.service

import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Ported from SleepServiceTests.swift + sleep score validations.
 * Tests sleep scoring, formatting, insights, and coach interpretation.
 * Pure logic — no Room/SwiftData dependency.
 */
class SleepInsightsTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun session(totalMin: Int, dateOffset: Int = 0): SleepSessionEntity {
        val day = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(dateOffset.toLong(), ChronoUnit.DAYS)
        return SleepSessionEntity(
            date = day.toEpochMilli(),
            startAt = day.plusSeconds(23 * 3600L).toEpochMilli(),
            endAt = day.plusSeconds((23 + totalMin / 60).toLong() * 3600L).toEpochMilli(),
            totalMinutes = totalMin,
        )
    }

    private fun blocks(vararg stages: String): List<SleepStageBlockEntity> =
        stages.map { stage ->
            SleepStageBlockEntity(
                sessionId = "test",
                startAt = 0,
                startMinute = 0,
                durationMinutes = 5,
                stageRaw = stage,
            )
        }

    private fun sessionWithBlocks(totalMin: Int, vararg stageCounts: Pair<String, Int>): Pair<SleepSessionEntity, List<SleepStageBlockEntity>> {
        val s = session(totalMin)
        val blks = stageCounts.flatMap { (stage, count) -> (1..count).map { SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = stage) } }
        return s to blks
    }

    // ── Sleep Score Tests ────────────────────────────────────────────────

    @Test
    fun testIdealNightScoresHigh() {
        // 8h with balanced stages → should be 85+. Stage percentages are computed over the
        // session's totalMinutes (480), and REM folds into deep (see SleepScore.calculate).
        val s = session(480)  // 8 hours
        val blocks = buildList {
            repeat(288) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "LIGHT")) }
            repeat(66) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "DEEP")) }
            repeat(30) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "REM")) }
            repeat(24) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "AWAKE")) }
        }
        val result = SleepScore.calculate(s, blocks)
        assertTrue("Ideal 8h night should score >= 85, got ${result.score}", result.score >= 85)
        assertEquals(SleepQualityLabel.EXCELLENT, result.label)
        assertEquals(20, result.deepPct)   // (66 deep + 30 REM) / 480 = 20%
        assertEquals(60, result.lightPct)  // 288/480 = 60%
        assertNotNull(result.awakePct)
        assertEquals(5, result.awakePct)   // 24/480 = 5%
    }

    @Test
    fun testShortNightScoresLow() {
        // 4h sleep → low score
        val s = session(240)
        val blocks = buildList {
            repeat(80) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "LIGHT")) }
            repeat(20) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "DEEP")) }
        }
        val result = SleepScore.calculate(s, blocks)
        assertTrue("Short 4h night should score < 70, got ${result.score}", result.score < 70)
    }

    @Test
    fun testNoDeepScoresLow() {
        val s = session(480)
        val blocks = buildList {
            repeat(130) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "LIGHT")) }
            repeat(10) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "AWAKE")) }
        }
        val result = SleepScore.calculate(s, blocks)
        assertTrue("No deep sleep should score < 80, got ${result.score}", result.score < 80)
        assertEquals(0, result.deepPct)
    }

    @Test
    fun testScoreRangeIsZeroToOneHundred() {
        // Test various inputs always return 0-100
        val s = session(300)
        val blocks = buildList {
            repeat(50) { add(SleepStageBlockEntity(sessionId = s.id, startAt = 0, startMinute = 0, durationMinutes = 1, stageRaw = "LIGHT")) }
        }
        val result = SleepScore.calculate(s, blocks)
        assertTrue("Score ${result.score} should be >= 0", result.score >= 0)
        assertTrue("Score ${result.score} should be <= 100", result.score <= 100)
    }

    @Test
    fun testQualityLabelThresholds() {
        assertEquals(SleepQualityLabel.EXCELLENT, SleepScore.qualityLabel(85))
        assertEquals(SleepQualityLabel.GOOD, SleepScore.qualityLabel(72))
        assertEquals(SleepQualityLabel.FAIR, SleepScore.qualityLabel(58))
        assertEquals(SleepQualityLabel.NEEDS_WORK, SleepScore.qualityLabel(40))
    }

    @Test
    fun testNoBlocksReturnsValidScore() {
        val s = session(420)
        val result = SleepScore.calculate(s, emptyList())
        assertTrue(result.score in 0..100)
        assertNull(result.awakePct)
        assertEquals(0, result.deepPct)
        assertEquals(0, result.lightPct)
    }

    // ── Format Tests ─────────────────────────────────────────────────────

    @Test
    fun testDurationFormat() {
        assertEquals("7h 30m", SleepFormat.duration(450))
        assertEquals("5m", SleepFormat.duration(5))
        assertEquals("1h 00m", SleepFormat.duration(60))
        assertEquals("—", SleepFormat.duration(null))
        assertEquals("—", SleepFormat.duration(-1))
    }

    // ── Valid Sessions ───────────────────────────────────────────────────

    @Test
    fun testValidSessionsFiltersZeroMinutes() {
        val sessions = listOf(session(0), session(300), session(0), session(420))
        val valid = SleepInsights.validSessions(sessions)
        assertEquals(2, valid.size)
        assertEquals(300, valid[0].totalMinutes)
        assertEquals(420, valid[1].totalMinutes)
    }

    // ── Average Duration ─────────────────────────────────────────────────

    @Test
    fun testAverageDuration() {
        val sessions = listOf(session(300), session(420), session(360))
        val avg = SleepInsights.averageDuration(sessions)
        assertEquals(360, avg)
    }

    @Test
    fun testAverageDurationEmpty() {
        assertNull(SleepInsights.averageDuration(emptyList()))
    }

    // ── Day Coach ────────────────────────────────────────────────────────

    @Test
    fun testDayCoachHighScore() {
        val (s, blocks) = sessionWithBlocks(480,
            "LIGHT" to 80, "DEEP" to 30, "REM" to 20, "AWAKE" to 10)
        val coach = SleepInsights.dayCoach(s, blocks, 6000)
        assertTrue(coach.headline.isNotEmpty())
        assertTrue(coach.chips.isNotEmpty())
    }

    @Test
    fun testDayCoachShortDuration() {
        val (s, blocks) = sessionWithBlocks(240,
            "LIGHT" to 40, "DEEP" to 10)
        val coach = SleepInsights.dayCoach(s, blocks, 2000)
        assertTrue(coach.headline.contains("Duration", ignoreCase = true) ||
            coach.body.contains("short", ignoreCase = true))
    }

    @Test
    fun testDayNoDataCoach() {
        assertTrue(SleepInsights.dayNoDataCoach.headline.isNotEmpty())
        assertTrue(SleepInsights.dayNoDataCoach.chips.isEmpty())
    }

    // ── Aggregate Coach ─────────────────────────────────────────────────

    @Test
    fun testAggregateCoachInsufficientData() {
        val sessions = listOf(session(300))
        val coach = SleepInsights.aggregateCoach(
            SleepRangeKey.WEEK, sessions, 7, 480,
        ) { emptyList() }
        assertTrue(coach.headline.contains("Not enough", ignoreCase = true))
        assertEquals(1, coach.chips.size)
        assertTrue(coach.chips[0].contains("1 of 7"))
    }

    @Test
    fun testAggregateCoachWeekWithFullData() {
        val sessions = (1..7).map { session(420 + it * 5) }
        val coach = SleepInsights.aggregateCoach(
            SleepRangeKey.WEEK, sessions, 7, 480,
        ) { emptyList() }
        assertTrue(coach.headline.contains("week", ignoreCase = true))
        assertTrue(coach.chips.isNotEmpty())
    }

    // ── No-Data States ──────────────────────────────────────────────────

    @Test
    fun testNoDataStates() {
        for (range in SleepRangeKey.entries) {
            val state = SleepInsights.noDataState(range)
            assertTrue(state.label.isNotEmpty())
            assertTrue(state.value.isNotEmpty())
            assertTrue(state.support.isNotEmpty())
        }
    }

    // ── Night Axis ──────────────────────────────────────────────────────

    @Test
    fun testNightAxisBuildsCorrectLength() {
        val now = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val start = now.minus(6, ChronoUnit.DAYS).toEpochMilli()
        val end = now.toEpochMilli()
        val sessions = listOf(
            session(400, -1),
            session(380, -3),
        )
        val bars = SleepInsights.buildNightAxis(start, end, sessions, { emptyList() }, SleepRangeKey.WEEK)
        assertEquals(7, bars.size)
        val present = bars.count { it.present }
        assertEquals(2, present)
    }

    // ── Month Buckets ───────────────────────────────────────────────────

    @Test
    fun testMonthBucketsReturns12Bars() {
        val now = Instant.now()
        val sessions = listOf(session(420))
        val bars = SleepInsights.buildMonthBuckets(now.toEpochMilli(), sessions) { emptyList() }
        assertEquals(12, bars.size)
        val present = bars.count { it.present }
        assertEquals(1, present)
    }
}
