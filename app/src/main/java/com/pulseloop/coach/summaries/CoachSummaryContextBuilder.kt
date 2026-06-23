package com.pulseloop.coach.summaries

import com.pulseloop.coach.context.CoachContextBuilder
import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import com.pulseloop.service.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ported from CoachSummaryContextBuilder.swift.
 * Builds the per-kind context packet + data signature + scripted fallback.
 */
object CoachSummaryContextBuilder {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    data class Built(
        val scopeKey: String,
        val contextJson: String,
        val signature: String,
        val fallback: CoachSummaryContent,
    )

    // ── Today ────────────────────────────────────────────────────────────

    suspend fun today(db: PulseLoopDatabase): Built {
        val packet = CoachContextBuilder.build(db)
        val todayDate = packet.today

        @Serializable
        data class TodayPacket(
            val today: String,
            val steps: Int?,
            val calories: Double?,
            val distanceKm: Double?,
            val activeMinutes: Int?,
            val latestHr: Double?,
            val latestSpo2: Double?,
            val dataQualityWarnings: List<String>,
        )
        val activity = db.activityDailyDao().byDay(
            java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        )
        val latestHr = db.measurementDao().latest(com.pulseloop.ring.MeasurementKind.HEART_RATE.name)
        val latestSpo2 = db.measurementDao().latest(com.pulseloop.ring.MeasurementKind.SPO2.name)

        val tp = TodayPacket(
            today = todayDate,
            steps = activity?.steps,
            calories = activity?.calories,
            distanceKm = activity?.distanceMeters?.let { it / 1000 },
            activeMinutes = activity?.activeMinutes,
            latestHr = latestHr,
            latestSpo2 = latestSpo2,
            dataQualityWarnings = packet.dataQualityWarnings,
        )
        val contextJson = json.encodeToString(tp)

        val sig = signature(listOf(
            activity?.steps?.toString(), activity?.calories?.toInt()?.toString(),
            activity?.activeMinutes?.toString(), tp.distanceKm?.toString(),
            latestHr?.toInt()?.toString(), latestSpo2?.toInt()?.toString(),
        ))

        // Scripted fallback
        val title = if (activity != null && activity.steps > 0) "Today's snapshot" else "No activity synced yet"
        val body = if (activity != null && activity.steps > 0)
            "${activity.steps} steps so far today. Your ring is tracking — sync for the latest readings."
        else "I don't have today's activity from the ring yet. Wear your ring and sync for a daily recap."

        return Built(
            scopeKey = todayDate,
            contextJson = contextJson,
            signature = sig,
            fallback = CoachSummaryContent(title, body, listOf("How am I doing today?", "Summarize my week")),
        )
    }

    // ── Sleep Day ────────────────────────────────────────────────────────

    suspend fun sleepDay(db: PulseLoopDatabase): Built? {
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val session = db.sleepSessionDao().byDay(todayStart) ?: return null
        if (session.totalMinutes <= 0) return null

        val blocks = db.sleepStageBlockDao().forSession(session.id)
        val score = SleepScore.calculate(session, blocks)
        val activitySteps = db.activityDailyDao().byDay(todayStart)?.steps

        @Serializable
        data class SleepDayPacket(
            val date: String,
            val totalMin: Int,
            val deepMin: Int,
            val lightMin: Int,
            val awakeMin: Int,
            val score: Int,
            val scoreLabel: String,
            val awakePct: Int?,
            val deepPct: Int,
            val activitySteps: Int?,
        )
        val deepMin = blocks.filter { it.stageRaw == "deep" }.sumOf { it.durationMinutes }
        val lightMin = blocks.filter { it.stageRaw == "light" }.sumOf { it.durationMinutes }
        val awakeMin = blocks.filter { it.stageRaw == "awake" }.sumOf { it.durationMinutes }

        val p = SleepDayPacket(
            date = session.date.toString(),
            totalMin = session.totalMinutes,
            deepMin = deepMin, lightMin = lightMin, awakeMin = awakeMin,
            score = score.score, scoreLabel = score.label.name.lowercase(),
            awakePct = score.awakePct, deepPct = score.deepPct,
            activitySteps = activitySteps,
        )
        val contextJson = json.encodeToString(p)

        val sig = signature(listOf(
            session.totalMinutes.toString(), deepMin.toString(),
            lightMin.toString(), awakeMin.toString(), score.score.toString(),
        ))

        val coach = SleepInsights.dayCoach(session, blocks, activitySteps)
        val scopeKey = java.time.Instant.ofEpochMilli(session.date)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

        return Built(
            scopeKey = scopeKey,
            contextJson = contextJson,
            signature = sig,
            fallback = CoachSummaryContent(coach.headline, coach.body, coach.chips),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun signature(parts: List<String?>): String =
        parts.joinToString("|") { it ?: "·" }
}
