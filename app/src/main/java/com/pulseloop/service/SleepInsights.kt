package com.pulseloop.service

import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.*

/**
 * Ported from SleepInsights.swift.
 * Range-aware sleep scoring + interpretation. Pure logic — zero Android deps.
 * All logic is data-honest: missing nights never treated as zero, averages
 * over valid nights only, no fallback to stale prior nights.
 */

// ── Sleep Score ───────────────────────────────────────────────────────────

enum class SleepQualityLabel { EXCELLENT, GOOD, FAIR, NEEDS_WORK }

data class SleepScoreResult(
    val score: Int,
    val label: SleepQualityLabel,
    val deepPct: Int,
    val lightPct: Int,
    val awakePct: Int?,  // null when no usable awake signal
)

object SleepScore {
    private fun clamp(value: Double, lo: Double, hi: Double): Double =
        min(hi, max(lo, value))

    private fun bandScore(
        value: Double, idealLow: Double, idealHigh: Double,
        softLow: Double, softHigh: Double, hardLow: Double, hardHigh: Double,
        points: Double,
    ): Double {
        if (!value.isFinite()) return 0.0
        if (value in idealLow..idealHigh) return points
        if (value < idealLow && value >= softLow)
            return points * (0.65 + 0.35 * ((value - softLow) / (idealLow - softLow)))
        if (value > idealHigh && value <= softHigh)
            return points * (0.65 + 0.35 * ((softHigh - value) / (softHigh - idealHigh)))
        if (value < softLow)
            return points * 0.65 * clamp((value - hardLow) / (softLow - hardLow), 0.0, 1.0)
        return points * 0.65 * clamp((hardHigh - value) / (hardHigh - softHigh), 0.0, 1.0)
    }

    private fun awakeScore(awakePct: Double?, points: Double): Double {
        if (awakePct == null || !awakePct.isFinite()) return points * 0.55
        if (awakePct <= 10) return points
        if (awakePct <= 20) return points * (1 - 0.65 * ((awakePct - 10) / 10))
        return points * 0.35 * clamp((35 - awakePct) / 15, 0.0, 1.0)
    }

    fun qualityLabel(score: Int): SleepQualityLabel = when {
        score >= 85 -> SleepQualityLabel.EXCELLENT
        score >= 70 -> SleepQualityLabel.GOOD
        score >= 55 -> SleepQualityLabel.FAIR
        else -> SleepQualityLabel.NEEDS_WORK
    }

    /** Calculate sleep score from session + stage blocks. */
    fun calculate(
        session: SleepSessionEntity,
        blocks: List<SleepStageBlockEntity>,
    ): SleepScoreResult {
        val total = if (session.totalMinutes > 0) session.totalMinutes.toDouble() else 0.0
        // stageRaw is persisted as the SleepStage enum name (uppercase) — match it exactly.
        // Some rings report REM in big-data sleep; the score model has no REM band, so fold
        // REM into deep (both are restorative sleep the deep band rewards).
        fun minutesFor(vararg stages: SleepStage) =
            blocks.filter { b -> stages.any { it.name == b.stageRaw } }.sumOf { it.durationMinutes }.toDouble()
        val deep = minutesFor(SleepStage.DEEP, SleepStage.REM)
        val light = minutesFor(SleepStage.LIGHT)
        val awake = minutesFor(SleepStage.AWAKE)
        val coveredStageMin = blocks.sumOf { it.durationMinutes.toDouble() }
        val hasAwakeSignal = blocks.any { it.stageRaw == SleepStage.AWAKE.name } ||
            awake > 0 || (total > 0 && coveredStageMin >= total * 0.95)

        val totalHours = total / 60
        val deepPct = if (total > 0) (deep / total) * 100 else 0.0
        val lightPct = if (total > 0) (light / total) * 100 else 0.0
        val awakePct: Double? = if (total > 0 && hasAwakeSignal) (awake / total) * 100 else null

        val duration = bandScore(totalHours, 7.5, 8.5, 6.0, 9.5, 3.0, 12.0, 35.0)
        val deepScore = bandScore(deepPct, 13.0, 23.0, 5.0, 35.0, 0.0, 45.0, 30.0)
        val lightScore = bandScore(lightPct, 50.0, 60.0, 35.0, 75.0, 20.0, 90.0, 20.0)
        val awakeSub = awakeScore(awakePct, 15.0)
        val score = clamp((duration + deepScore + lightScore + awakeSub).roundToInt().toDouble(), 0.0, 100.0).toInt()

        return SleepScoreResult(
            score = score,
            label = qualityLabel(score),
            deepPct = deepPct.roundToInt(),
            lightPct = lightPct.roundToInt(),
            awakePct = awakePct?.roundToInt(),
        )
    }
}

// ── Formatting ────────────────────────────────────────────────────────────

object SleepFormat {
    fun duration(minutes: Int?): String {
        if (minutes == null || minutes < 0) return "—"
        val h = minutes / 60; val m = minutes % 60
        return if (h <= 0) "${m}m" else "${h}h ${m.toString().padStart(2, '0')}m"
    }

    fun clockTime(ts: Long): String {
        val t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        val h = t.hour % 12; val displayH = if (h == 0) 12 else h
        val ampm = if (t.hour < 12) "AM" else "PM"
        return "$displayH:${t.minute.toString().padStart(2, '0')} $ampm"
    }
}

// ── Coach Interpretation ──────────────────────────────────────────────────

data class SleepCoach(
    val headline: String,
    val body: String,
    val chips: List<String>,
)

data class SleepNoDataState(
    val label: String,
    val value: String,
    val support: String,
)

data class SleepBar(
    val label: String,
    val durationMin: Int?,
    val score: Int?,
    val present: Boolean,
)

object SleepInsights {
    val rangeHeroLabel = mapOf(
        SleepRangeKey.DAY to "Last Sleep",
        SleepRangeKey.WEEK to "Weekly Sleep",
        SleepRangeKey.MONTH to "Monthly Sleep",
        SleepRangeKey.YEAR to "Yearly Sleep",
    )

    private const val minAggNights = 2

    // ── Aggregation ──────────────────────────────────────────────────────

    fun validSessions(sessions: List<SleepSessionEntity>) =
        sessions.filter { it.totalMinutes > 0 }

    fun averageDuration(valid: List<SleepSessionEntity>): Int? {
        if (valid.isEmpty()) return null
        return valid.sumOf { it.totalMinutes } / valid.size
    }

    fun averageScore(valid: List<SleepSessionEntity>, blocksBySession: (String) -> List<SleepStageBlockEntity>): Int? {
        if (valid.isEmpty()) return null
        val scores = valid.map { SleepScore.calculate(it, blocksBySession(it.id)).score }
        return scores.average().roundToInt()
    }

    private fun durationConsistency(valid: List<SleepSessionEntity>): Double? {
        if (valid.size < 2) return null
        val avg = averageDuration(valid)?.toDouble() ?: return null
        val variance = valid.sumOf { (it.totalMinutes - avg).pow(2) } / valid.size
        return sqrt(variance)
    }

    // ── Coach Text ───────────────────────────────────────────────────────

    fun dayCoach(
        session: SleepSessionEntity,
        blocks: List<SleepStageBlockEntity>,
        activitySteps: Int?,
    ): SleepCoach {
        val score = SleepScore.calculate(session, blocks)
        val deepPct = score.deepPct
        val awakePct = score.awakePct
        val chips = mutableListOf<String>()
        if (session.totalMinutes in 420..540) chips.add("Good duration")
        if (deepPct in 13..23) chips.add("Deep sleep balanced")
        else if (deepPct > 23) chips.add("Deep sleep strong")
        if (awakePct != null && awakePct <= 10) chips.add("Awake time low")

        if (score.score >= 85) {
            val body = if ((activitySteps ?: 0) > 5000)
                "Looks like a strong night after a more active day. Your duration was solid and deep sleep made up a healthy part of the night, which kept the score high."
            else "Your sleep duration and stage balance were strong. Deep sleep looked supportive, which helped keep the overall score high."
            return SleepCoach("Strong recovery signal", body, chips.ifEmpty { listOf("Excellent") }.take(3))
        }
        if (awakePct != null && awakePct > 15) {
            return SleepCoach(
                "Good sleep, with some restlessness",
                "You slept long enough, but awake time was a bit elevated. If this repeats, look at late caffeine, alcohol, temperature, or stress near bedtime.",
                (listOf("Awake time elevated") + chips).take(3),
            )
        }
        if (session.totalMinutes < 390) {
            return SleepCoach(
                "Duration held the score back",
                "The stage mix was useful, but total sleep time was short for a full recovery window. A slightly earlier wind-down would likely improve tomorrow's score.",
                (listOf("Short duration") + chips).take(3),
            )
        }
        return SleepCoach(
            "Solid night overall",
            "Your sleep was in a workable range, with the score shaped mostly by duration and stage balance. Deep and light sleep were readable enough to give a useful recovery snapshot.",
            chips.ifEmpty { listOf("Good") }.take(3),
        )
    }

    val dayNoDataCoach = SleepCoach(
        "No sleep tracked last night",
        "I don't see sleep data for last night. Wear your ring overnight and sync in the morning so I can compare your sleep against your baseline.",
        emptyList(),
    )

    fun aggregateCoach(
        range: SleepRangeKey,
        sessions: List<SleepSessionEntity>,
        expectedNights: Int,
        goalMin: Int?,
        blocksBySession: (String) -> List<SleepStageBlockEntity>,
    ): SleepCoach {
        val valid = validSessions(sessions)
        val avgMin = averageDuration(valid)
        val periodWord = when (range) { SleepRangeKey.WEEK -> "week"; SleepRangeKey.MONTH -> "month"; else -> "year" }

        if (valid.size < minAggNights) {
            val nightWord = if (valid.size == 1) "night" else "nights"
            return SleepCoach(
                "Not enough $periodWord data yet",
                "I only have ${valid.size} tracked $nightWord for this $periodWord. Wear the ring overnight for a few more nights and I'll build a reliable ${periodWord}ly picture.",
                listOf(nightsTrackedChip(valid.size, expectedNights)),
            )
        }

        val chips = mutableListOf(nightsTrackedChip(valid.size, expectedNights))
        avgMin?.let { goalDeltaChip(it, goalMin) }?.let { chips.add(it) }
        durationConsistency(valid)?.let { consistencyChip(it) }?.let { chips.add(it) }

        val avgText = SleepFormat.duration(avgMin)
        val coveragePhrase = "${valid.size} of $expectedNights nights tracked"

        return when (range) {
            SleepRangeKey.WEEK -> {
                val incomplete = valid.size < expectedNights
                val missing = expectedNights - valid.size
                val missingWord = if (missing == 1) "night" else "nights"
                val body = if (incomplete)
                    "You averaged $avgText across ${valid.size} tracked nights this week. That's a useful read, but $missing missing $missingWord mean the trend is still incomplete."
                else "You averaged $avgText across the full week. Your nights were tracked consistently, so this is a dependable picture of where your sleep sits right now."
                SleepCoach("Your week at a glance", body, chips.take(3))
            }
            SleepRangeKey.MONTH -> {
                val sparse = valid.size.toDouble() < expectedNights.toDouble() * 0.5
                val body = if (sparse)
                    "Your monthly average is $avgText, but coverage is low ($coveragePhrase), so I'd treat that number cautiously. More nights tracked will sharpen the trend."
                else "Your monthly average is $avgText across $coveragePhrase. The biggest lever is consistency — a few short nights move this number more than any single great one."
                SleepCoach("Your month in sleep", body, chips.take(3))
            }
            else -> SleepCoach(
                "Your long-term sleep trend",
                "Across the year your tracked average is $avgText over ${valid.size} nights. The long-term trend is still forming — as more months fill in, I'll be able to compare seasonal changes and consistency.",
                chips.take(3),
            )
        }
    }

    private fun nightsTrackedChip(valid: Int, expected: Int) = "$valid of $expected tracked"

    private fun goalDeltaChip(avgMin: Int, goalMin: Int?): String? {
        if (goalMin == null) return null
        val delta = avgMin - goalMin
        if (abs(delta) <= 20) return "On target"
        return if (delta < 0) "Below goal" else "Above goal"
    }

    private fun consistencyChip(sd: Double): String? = when {
        sd <= 40 -> "Consistent"
        sd >= 80 -> "Variable nights"
        else -> null
    }

    // ── No-data states ───────────────────────────────────────────────────

    fun noDataState(range: SleepRangeKey): SleepNoDataState = when (range) {
        SleepRangeKey.DAY -> SleepNoDataState("Last Sleep", "No sleep captured last night",
            "Wear your ring overnight so PulseLoop can track your next night.")
        SleepRangeKey.WEEK -> SleepNoDataState("Weekly Sleep", "Not enough weekly data",
            "Wear your ring overnight for a few nights to build a weekly view.")
        SleepRangeKey.MONTH -> SleepNoDataState("Monthly Sleep", "Not enough monthly data",
            "Track more nights this month to see a monthly average.")
        SleepRangeKey.YEAR -> SleepNoDataState("Yearly Sleep", "Not enough yearly data",
            "Long-term insights appear as more nights are tracked.")
    }

    // ── Histogram builders ───────────────────────────────────────────────

    fun buildNightAxis(
        start: Long, end: Long,
        sessions: List<SleepSessionEntity>,
        blocksBySession: (String) -> List<SleepStageBlockEntity>,
        range: SleepRangeKey,
    ): List<SleepBar> {
        val byDate = sessions.associateBy {
            Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
                .toInstant().toEpochMilli()
        }
        val bars = mutableListOf<SleepBar>()
        var cursor = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
        val last = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
        val weekdayFmt = DateTimeFormatter.ofPattern("EEEEE")

        while (!cursor.isAfter(last)) {
            val cursorMs = cursor.toInstant().toEpochMilli()
            val session = byDate[cursorMs]
            val present = (session?.totalMinutes ?: 0) > 0
            val label = if (range == SleepRangeKey.WEEK) cursor.format(weekdayFmt)
                else cursor.dayOfMonth.toString()
            val score = if (present) session?.let { s -> SleepScore.calculate(s, blocksBySession(s.id)).score } else null
            bars.add(SleepBar(label, session?.totalMinutes, score, present))
            cursor = cursor.plusDays(1)
        }
        return bars
    }

    fun buildMonthBuckets(
        end: Long,
        sessions: List<SleepSessionEntity>,
        blocksBySession: (String) -> List<SleepStageBlockEntity>,
    ): List<SleepBar> {
        val valid = validSessions(sessions)
        val byMonth = valid.groupBy {
            val t = Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault())
            "${t.year}-${t.monthValue}"
        }
        val monthFmt = DateTimeFormatter.ofPattern("MMM")
        val endZ = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault())
        return (11 downTo 0).map { i ->
            val monthDate = endZ.minusMonths(i.toLong())
            val key = "${monthDate.year}-${monthDate.monthValue}"
            val monthSessions = byMonth[key] ?: emptyList()
            SleepBar(
                label = monthDate.format(monthFmt),
                durationMin = averageDuration(monthSessions),
                score = averageScore(monthSessions, blocksBySession),
                present = monthSessions.isNotEmpty(),
            )
        }
    }
}

// ── Extensions ────────────────────────────────────────────────────────────

private fun Double.roundToInt(): Int = round(this).toInt()
