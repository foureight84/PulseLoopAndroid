package com.pulseloop.notifications

import com.pulseloop.coach.context.EnvironmentContext

/**
 * Ported from NotificationContextPacket, CoachNotificationSlot, CoachNotification
 * and CoachNotificationSchema in the iOS notification system.
 */

/**
 * Notification slot types — ported from CoachNotificationSlot.swift.
 */
enum class CoachNotificationSlot(val label: String) {
    MORNING("Morning"),
    EVENING("Evening");

    companion object {
        fun current(
            hour: Int,
            morningHour: Int = 8,
            eveningHour: Int = 20,
        ): CoachNotificationSlot? = when {
            hour >= morningHour && hour <= minOf(morningHour + 4, eveningHour - 1) -> MORNING
            hour >= eveningHour && hour <= eveningHour + 4 -> EVENING
            else -> null
        }

        fun forcedSlot(hour: Int): CoachNotificationSlot =
            if (hour < 14) MORNING else EVENING
    }
}

/**
 * Context packet for notification generation.
 * Ported from NotificationContextPacket in NotificationContextBuilder.swift.
 */
data class NotificationContextPacket(
    val slot: String,
    val generatedAt: String,
    val timezone: String,
    val profileName: String?,
    val goals: GoalContext,
    val today: DayContext,
    val latestSleep: SleepContext?,
    val latestVitals: VitalsContext,
    val hrLast12h: MetricStats,
    val spo2Last12h: MetricStats,
    val recentWorkouts: List<WorkoutContext>,
    val memories: List<MemoryContext>,
    val dataQualityWarnings: List<String>,
    /** Opt-in city + weather (iOS #65d), null when the toggle is off/denied/failed. */
    val environment: EnvironmentContext? = null,
) {
    data class GoalContext(
        val stepsDaily: Int,
        val activeMinutesDaily: Int,
        val sleepHours: Int,
        val exerciseDaysWeekly: Int,
    )
    data class DayContext(
        val steps: Int?,
        val calories: Double?,
        val distanceMeters: Double?,
        val activeMinutes: Int?,
    )
    data class SleepContext(
        val totalMin: Int,
        val startAt: Long,
        val endAt: Long,
    )
    data class VitalsContext(
        val latestHr: Double?,
        val latestSpo2: Double?,
    )
    data class MetricStats(
        val min: Double,
        val max: Double,
        val avg: Double,
        val count: Int,
    )
    data class WorkoutContext(
        val type: String,
        val calories: Double?,
        val distanceMeters: Double?,
        val avgHeartRate: Double?,
    )
    data class MemoryContext(
        val key: String,
        val value: String,
        val memoryType: String,
    )

    fun toSerializable(): SerializablePacket = SerializablePacket(
        slot = slot,
        generatedAt = generatedAt,
        timezone = timezone,
        profileName = profileName,
        goals = SerializablePacket.SerializableGoalContext(
            stepsDaily = goals.stepsDaily,
            activeMinutesDaily = goals.activeMinutesDaily,
            sleepHours = goals.sleepHours,
            exerciseDaysWeekly = goals.exerciseDaysWeekly,
        ),
        today = SerializablePacket.SerializableDayContext(
            steps = today.steps,
            calories = today.calories,
            distanceMeters = today.distanceMeters,
            activeMinutes = today.activeMinutes,
        ),
        latestSleep = latestSleep?.let {
            SerializablePacket.SerializableSleepContext(it.totalMin, it.startAt, it.endAt)
        },
        latestVitals = SerializablePacket.SerializableVitalsContext(
            latestHr = latestVitals.latestHr,
            latestSpo2 = latestVitals.latestSpo2,
        ),
        hrLast12h = SerializablePacket.SerializableMetricStats(
            hrLast12h.min, hrLast12h.max, hrLast12h.avg, hrLast12h.count
        ),
        spo2Last12h = SerializablePacket.SerializableMetricStats(
            spo2Last12h.min, spo2Last12h.max, spo2Last12h.avg, spo2Last12h.count
        ),
        recentWorkouts = recentWorkouts.map {
            SerializablePacket.SerializableWorkoutContext(it.type, it.calories, it.distanceMeters, it.avgHeartRate)
        },
        memories = memories.map {
            SerializablePacket.SerializableMemoryContext(it.key, it.value, it.memoryType)
        },
        dataQualityWarnings = dataQualityWarnings,
        environment = environment,
    )

    fun toJsonString(): String {
        return kotlinx.serialization.json.Json {
            prettyPrint = false; encodeDefaults = true
        }.encodeToString(SerializablePacket.serializer(), toSerializable())
    }

    companion object {
        @kotlinx.serialization.Serializable
        data class SerializablePacket(
            val slot: String,
            val generatedAt: String,
            val timezone: String,
            val profileName: String? = null,
            val goals: SerializableGoalContext,
            val today: SerializableDayContext,
            val latestSleep: SerializableSleepContext? = null,
            val latestVitals: SerializableVitalsContext,
            val hrLast12h: SerializableMetricStats,
            val spo2Last12h: SerializableMetricStats,
            val recentWorkouts: List<SerializableWorkoutContext> = emptyList(),
            val memories: List<SerializableMemoryContext> = emptyList(),
            val dataQualityWarnings: List<String> = emptyList(),
            val environment: EnvironmentContext? = null,
        ) {
            @kotlinx.serialization.Serializable
            data class SerializableGoalContext(val stepsDaily: Int, val activeMinutesDaily: Int, val sleepHours: Int, val exerciseDaysWeekly: Int)
            @kotlinx.serialization.Serializable
            data class SerializableDayContext(val steps: Int? = null, val calories: Double? = null, val distanceMeters: Double? = null, val activeMinutes: Int? = null)
            @kotlinx.serialization.Serializable
            data class SerializableSleepContext(val totalMin: Int, val startAt: Long, val endAt: Long)
            @kotlinx.serialization.Serializable
            data class SerializableVitalsContext(val latestHr: Double? = null, val latestSpo2: Double? = null)
            @kotlinx.serialization.Serializable
            data class SerializableMetricStats(val min: Double, val max: Double, val avg: Double, val count: Int)
            @kotlinx.serialization.Serializable
            data class SerializableWorkoutContext(val type: String, val calories: Double? = null, val distanceMeters: Double? = null, val avgHeartRate: Double? = null)
            @kotlinx.serialization.Serializable
            data class SerializableMemoryContext(val key: String, val value: String, val memoryType: String)
        }
    }
}

/**
 * A generated daily check-in notification — ported from CoachNotification.swift.
 */
@kotlinx.serialization.Serializable
data class CoachNotificationContent(
    val title: String,
    val body: String,
) {
    companion object {
        fun decodeFromJson(json: String?): CoachNotificationContent? {
            if (json == null) return null
            val trimmed = json.trim()
            val json2 = when {
                trimmed.startsWith("{") -> trimmed
                trimmed.contains("{") && trimmed.contains("}") -> {
                    val start = trimmed.indexOf('{')
                    val end = trimmed.lastIndexOf('}') + 1
                    if (start < end) trimmed.substring(start, end) else return null
                }
                else -> return null
            }
            return try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<CoachNotificationContent>(json2)
            } catch (_: Exception) { null }
        }
    }
}
