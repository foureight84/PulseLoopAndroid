package com.pulseloop.coach.context

import com.pulseloop.ring.MeasurementKind
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Ported from [CoachContextBuilder] in CoachContextBuilder.swift.
 * Composes the CoachContextPacket from real Room data.
 */
object CoachContextBuilder {

    suspend fun build(
        db: PulseLoopDatabase,
        conversationSummary: String? = null,
    ): CoachContextPacket {
        val now = System.currentTimeMillis()
        val profile = db.userProfileDao().get()
        val device = db.deviceDao().current()
        val goal = db.userGoalDao().get()

        val todayStart = localStartOfDay(now)
        val todayActivity = db.activityDailyDao().byDay(todayStart)

        // Latest HR / SpO2
        val latestHr = db.measurementDao().latest(MeasurementKind.HEART_RATE.name, now)
        val latestSpo2 = db.measurementDao().latest(MeasurementKind.SPO2.name, now)

        // 7-day trends
        val sevenDaysAgo = todayStart - 7 * 24 * 3600_000L
        val activityWeek = db.activityDailyDao().recent(7).filter { it.date >= sevenDaysAgo }
        val stepsWeek = activityWeek.map { it.steps.toDouble() }
        val daysAvailable = activityWeek.count { it.steps > 0 }

        // Resting HR: average of HR values in the last 24h while at rest (approximated as lowest 25th percentile)
        val hr24h = db.measurementDao().range(MeasurementKind.HEART_RATE.name, now - 24 * 3600_000L, now)
        val restingHr = if (hr24h.isNotEmpty()) {
            val sorted = hr24h.map { it.value }.sorted()
            sorted[(sorted.size * 0.25).toInt().coerceAtMost(sorted.size - 1)]
        } else null

        // Sleep
        val latestSleep = db.sleepSessionDao().recent(1).firstOrNull()

        val completeness = profileCompleteness(profile)
        val warnings = DataQualityAnalyzer.warnings(
            DataQualityAnalyzer.Inputs(
                profileCompleteness = completeness,
                daysAvailable = daysAvailable,
                hasSleep = latestSleep != null,
                lastSyncAt = device?.lastSyncAt,
                isDemo = false,
            ),
            now = now,
        )

        return CoachContextPacket(
            today = LocalDate.now().toString(),
            timezone = ZoneId.systemDefault().id,
            profile = CoachContextPacket.ProfileContext(
                name = profile?.name,
                age = profile?.age,
                sex = profile?.sex,
                heightCm = profile?.heightCm,
                weightKg = profile?.weightKg,
                completeness = if (completeness == "complete") 1.0 else if (completeness == "partial") 0.5 else 0.0,
            ),
            device = CoachContextPacket.DeviceContext(
                name = device?.name ?: "Unknown Ring",
                batteryPercent = device?.batteryPercent,
                state = device?.stateRaw ?: "idle",
                lastConnectedAt = device?.lastConnectedAt?.let { iso(it) },
                lastSyncAt = device?.lastSyncAt?.let { iso(it) },
            ),
            goals = CoachContextPacket.GoalContext(
                stepsDaily = goal?.steps ?: 10000,
                activeMinutesDaily = goal?.activeMinutes ?: 45,
                sleepHours = (goal?.sleepMinutes ?: 480) / 60,
                exerciseDaysWeekly = goal?.workoutsPerWeek ?: 4,
            ),
            trends = CoachContextPacket.TrendsContext(
                steps7d = stepsWeek,
                hrResting = restingHr,
                sleepAvgMin = null,  // Not fetching sleep per day in quick context build
            ),
            conversationSummary = conversationSummary,
            dataQualityWarnings = warnings,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun localStartOfDay(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()

    private fun profileCompleteness(profile: UserProfileEntity?): String {
        if (profile == null) return "empty"
        val filled = listOf(profile.name, profile.age, profile.heightCm, profile.weightKg).count { it != null }
        return when {
            filled == 0 -> "empty"
            filled == 4 -> "complete"
            else -> "partial"
        }
    }

    private fun iso(ts: Long): String = Instant.ofEpochMilli(ts).toString()

    /**
     * Inline data quality analyzer (ported from DataQualityAnalyzer.swift).
     */
    object DataQualityAnalyzer {
        data class Inputs(
            val profileCompleteness: String,
            val daysAvailable: Int,
            val hasSleep: Boolean,
            val lastSyncAt: Long?,
            val isDemo: Boolean,
        )

        val sleepDecoderNote =
            "Sleep stage decoding is experimental — light/deep/awake only, no REM; awake time may read as zero."

        fun warnings(input: Inputs, now: Long = System.currentTimeMillis()): List<String> {
            val out = mutableListOf<String>()

            if (input.isDemo) {
                out.add("This is demo/sample data, not live readings from the ring.")
            }

            if (input.profileCompleteness != "complete") {
                out.add("User profile is incomplete (missing age/height/weight). Don't compute personalized HR zones, BMI, or weight targets.")
            }

            if (input.daysAvailable <= 3) {
                out.add("Only ${input.daysAvailable} day(s) of activity data available — trends are limited; avoid strong week-over-week claims.")
            }

            if (input.hasSleep) {
                out.add(sleepDecoderNote)
            }

            if (!input.isDemo) {
                val last = input.lastSyncAt
                if (last != null) {
                    val hours = (now - last) / 3600_000L
                    if (hours >= 12) {
                        out.add("Ring hasn't synced in ~${hours}h — today's data may be stale.")
                    }
                } else {
                    out.add("No recent ring sync recorded — data may be incomplete.")
                }
            }

            out.add("Ring HR and SpO₂ are wellness signals, not medical-grade measurements; do not diagnose.")
            return out
        }
    }
}
