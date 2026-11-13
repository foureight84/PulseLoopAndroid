package com.pulseloop.coach.summaries

import com.pulseloop.coach.context.CoachContextBuilder
import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import com.pulseloop.ring.SleepStage
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
        /** The end of the night this summary describes (sleepDay only); null for today.
         *  Drives the sleep-sync gate in [CoachSummaryService] (iOS #65). */
        val sleepSessionEnd: Long? = null,
    )

    // ── Today ────────────────────────────────────────────────────────────

    suspend fun today(db: PulseLoopDatabase, preferPhone: Boolean): Built {
        val packet = CoachContextBuilder.build(db, preferPhone)
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
            steps = com.pulseloop.data.DisplaySteps.count(activity, preferPhone),
            calories = activity?.calories,
            distanceKm = activity?.distanceMeters?.let { it / 1000 },
            activeMinutes = activity?.activeMinutes,
            latestHr = latestHr,
            latestSpo2 = latestSpo2,
            dataQualityWarnings = packet.dataQualityWarnings,
        )
        val contextJson = json.encodeToString(tp)

        // The signature must include the *display* step figure, not the raw column — a phone-mode
        // change that leaves `steps` untouched but moves `phoneSteps` has to trigger a regeneration
        // or the card would keep quoting the old value (review item 5, PR #98).
        val sig = signature(listOf(
            tp.steps?.toString(), activity?.calories?.toInt()?.toString(),
            activity?.activeMinutes?.toString(), tp.distanceKm?.toString(),
            latestHr?.toInt()?.toString(), latestSpo2?.toInt()?.toString(),
        ))

        // Scripted fallback — same display value as the Today tile, so the two cannot disagree.
        val displaySteps = tp.steps ?: 0
        val title = if (displaySteps > 0) "Today's snapshot" else "No activity synced yet"
        val body = if (displaySteps > 0)
            "$displaySteps steps so far today. Your ring is tracking — sync for the latest readings."
        else "I don't have today's activity yet. Wear your ring and sync for a daily recap."

        return Built(
            scopeKey = todayDate,
            contextJson = contextJson,
            signature = sig,
            fallback = CoachSummaryContent(title, body, listOf("How am I doing today?", "Summarize my week")),
        )
    }

    // ── Sleep Day ────────────────────────────────────────────────────────

    suspend fun sleepDay(db: PulseLoopDatabase, preferPhone: Boolean): Built? {
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val session = db.sleepSessionDao().byDay(todayStart) ?: return null
        if (session.totalMinutes <= 0) return null

        val blocks = db.sleepStageBlockDao().forSession(session.id)
        val score = SleepScore.calculate(session, blocks)
        val activitySteps = com.pulseloop.data.DisplaySteps.count(
            db.activityDailyDao().byDay(todayStart), preferPhone,
        )

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
        // stageRaw is persisted as the SleepStage enum NAME, which is uppercase. These three
        // matched lowercase literals and were therefore always zero, so every sleep summary the
        // coach has ever been given said the user had no deep, light or awake sleep at all.
        val deepMin = blocks.filter { it.stageRaw == SleepStage.DEEP.name }.sumOf { it.durationMinutes }
        val lightMin = blocks.filter { it.stageRaw == SleepStage.LIGHT.name }.sumOf { it.durationMinutes }
        val awakeMin = blocks.filter { it.stageRaw == SleepStage.AWAKE.name }.sumOf { it.durationMinutes }

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
            sleepSessionEnd = session.endAt,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun signature(parts: List<String?>): String =
        parts.joinToString("|") { it ?: "·" }
}
