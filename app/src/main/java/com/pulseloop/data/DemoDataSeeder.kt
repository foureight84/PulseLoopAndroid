package com.pulseloop.data

import com.pulseloop.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Ported from SeedData.swift.
 * Seeds demo data into Room so the UI is explorable without a ring.
 */
object DemoDataSeeder {
    suspend fun seed(db: PulseLoopDatabase) = withContext(Dispatchers.IO) {
        // Local midnight so seeded "today" rows match how the app keys per-day data.
        val today = Instant.ofEpochMilli(com.pulseloop.util.TimeUtil.startOfTodayLocal())

        // Device — do NOT create a fake connected device; the real ring creates its own
        // when it connects. We seed a disconnected stub so the UI shows "no ring" state.

        // User profile
        db.userProfileDao().upsert(UserProfileEntity(
            name = "Demo User", age = 30, sex = "male",
            heightCm = 175.0, weightKg = 70.0,
            onboardingCompleted = true,
        ))

        // Goals
        db.userGoalDao().upsert(UserGoalEntity(steps = 10000, sleepMinutes = 480))

        // 7 days of activity
        val stepValues = listOf(7560, 9100, 8230, 10200, 6800, 9400, 8432)
        for (i in 6 downTo 0) {
            val day = today.minus(i.toLong(), ChronoUnit.DAYS).toEpochMilli()
            db.activityDailyDao().upsert(ActivityDailyEntity(
                date = day, steps = stepValues[6 - i],
                calories = (stepValues[6 - i] * 0.04).toDouble(),
                distanceMeters = (stepValues[6 - i] * 0.7).toDouble(),
                activeMinutes = 30 + (i * 5), source = "demo",
            ))
        }

        // HR samples for today
        val hrSamples = listOf(68, 72, 75, 142, 98, 72, 65, 70, 78, 85, 92, 74)
        for ((i, bpm) in hrSamples.withIndex()) {
            val ts = today.plus((6 + i * 2).toLong(), ChronoUnit.HOURS).toEpochMilli()
            db.measurementDao().insert(MeasurementEntity(
                kindRaw = "heartRate", value = bpm.toDouble(), unit = "bpm",
                timestamp = ts, sourceRaw = "demo",
            ))
        }

        // SpO2 samples
        for (h in listOf(8, 14, 20)) {
            val ts = today.plus(h.toLong(), ChronoUnit.HOURS).toEpochMilli()
            db.measurementDao().insert(MeasurementEntity(
                kindRaw = "spo2", value = (96 + (h % 3)).toDouble(), unit = "%",
                timestamp = ts, sourceRaw = "demo",
            ))
        }

        // Sleep session (last night)
        val sleepDate = today.minus(1, ChronoUnit.DAYS).toEpochMilli()
        val sleepStart = today.minus(14, ChronoUnit.HOURS).toEpochMilli()
        val sleepEnd = today.minus(7, ChronoUnit.HOURS).toEpochMilli()
        db.sleepSessionDao().upsert(SleepSessionEntity(
            date = sleepDate, startAt = sleepStart, endAt = sleepEnd,
            totalMinutes = 443, score = 85,
        ))

        // Coach conversation
        db.coachConversationDao().upsert(CoachConversationEntity(
            title = "Welcome to PulseLoop",
        ))
    }
}
