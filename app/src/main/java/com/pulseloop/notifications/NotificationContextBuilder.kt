package com.pulseloop.notifications

import com.pulseloop.ring.MeasurementKind
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Ported from NotificationContextBuilder.swift.
 * Compact ~12-hour context the notification generator sees.
 * Reuses CoachContextBuilder for shared profile/goals/today/sleep/memory
 * and adds a rolling 12h HR/SpO₂ window plus the slot.
 */
object NotificationContextBuilder {

    data class Stats(val min: Double, val max: Double, val avg: Double, val count: Int)

    suspend fun build(
        slot: CoachNotificationSlot,
        db: PulseLoopDatabase,
        now: Long = System.currentTimeMillis(),
    ): NotificationContextPacket {
        val cutoff = now - 12 * 3600_000L

        val profile = db.userProfileDao().get()
        val goal = db.userGoalDao().get()
        val device = db.deviceDao().current()

        // Today's activity
        val todayStart = localStartOfDay(now)
        val todayActivity = db.activityDailyDao().byDay(todayStart)

        // Latest sleep
        val latestSleep = db.sleepSessionDao().recent(1).firstOrNull()

        // 12h HR & SpO2 stats
        val hrRows = db.measurementDao().range(MeasurementKind.HEART_RATE.name, cutoff, now)
        val spo2Rows = db.measurementDao().range(MeasurementKind.SPO2.name, cutoff, now)

        val hrStats = if (hrRows.isNotEmpty()) {
            Stats(
                min = hrRows.minOf { it.value },
                max = hrRows.maxOf { it.value },
                avg = hrRows.map { it.value }.average(),
                count = hrRows.size,
            )
        } else Stats(0.0, 0.0, 0.0, 0)

        val spo2Stats = if (spo2Rows.isNotEmpty()) {
            Stats(
                min = spo2Rows.minOf { it.value },
                max = spo2Rows.maxOf { it.value },
                avg = spo2Rows.map { it.value }.average(),
                count = spo2Rows.size,
            )
        } else Stats(0.0, 0.0, 0.0, 0)

        // Recent workouts
        val recentWorkouts = db.activitySessionDao().recent(3)

        // Coach memories
        val memories = db.coachMemoryDao().allRanked()

        // Data quality warnings (simplified from CoachContextBuilder.DataQualityAnalyzer)
        val warnings = mutableListOf<String>()
        val profileComplete = profile != null && profile.name != null && profile.age != null && profile.heightCm != null && profile.weightKg != null
        if (!profileComplete) {
            warnings.add("User profile is incomplete — insights may be less personalized.")
        }
        val lastSync = device?.lastSyncAt
        if (lastSync != null && (now - lastSync) > 12 * 3600_000L) {
            warnings.add("Ring hasn't synced in >12h — today's data may be stale.")
        }
        if (hrRows.size < 10) {
            warnings.add("Limited HR data available for today.")
        }

        return NotificationContextPacket(
            slot = slot.name.lowercase(),
            generatedAt = Instant.ofEpochMilli(now).toString(),
            timezone = ZoneId.systemDefault().id,
            profileName = profile?.name,
            goals = NotificationContextPacket.GoalContext(
                stepsDaily = goal?.steps ?: 10000,
                activeMinutesDaily = goal?.activeMinutes ?: 45,
                sleepHours = (goal?.sleepMinutes ?: 480) / 60,
                exerciseDaysWeekly = goal?.workoutsPerWeek ?: 4,
            ),
            today = NotificationContextPacket.DayContext(
                steps = todayActivity?.steps,
                calories = todayActivity?.calories,
                distanceMeters = todayActivity?.distanceMeters,
                activeMinutes = todayActivity?.activeMinutes,
            ),
            latestSleep = if (latestSleep != null) {
                NotificationContextPacket.SleepContext(
                    totalMin = latestSleep.totalMinutes,
                    startAt = latestSleep.startAt,
                    endAt = latestSleep.endAt,
                )
            } else null,
            latestVitals = NotificationContextPacket.VitalsContext(
                latestHr = hrRows.lastOrNull()?.value,
                latestSpo2 = spo2Rows.lastOrNull()?.value,
            ),
            hrLast12h = NotificationContextPacket.MetricStats(
                min = hrStats.min,
                max = hrStats.max,
                avg = hrStats.avg,
                count = hrStats.count,
            ),
            spo2Last12h = NotificationContextPacket.MetricStats(
                min = spo2Stats.min,
                max = spo2Stats.max,
                avg = spo2Stats.avg,
                count = spo2Stats.count,
            ),
            recentWorkouts = recentWorkouts.map { wo ->
                NotificationContextPacket.WorkoutContext(
                    type = wo.type,
                    calories = wo.calories,
                    distanceMeters = wo.distanceMeters,
                    avgHeartRate = wo.avgHeartRate,
                )
            },
            memories = memories.map { m ->
                NotificationContextPacket.MemoryContext(
                    key = m.key,
                    value = m.value,
                    memoryType = m.key, // simplified
                )
            },
            dataQualityWarnings = warnings,
        )
    }

    private fun localStartOfDay(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
}
