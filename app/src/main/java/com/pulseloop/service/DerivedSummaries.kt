package com.pulseloop.service

/**
 * Ported from DerivedSummaries.swift.
 * Core data types for metrics, ranges, freshness, and summary structures.
 * Pure data classes/enums — zero Android dependencies.
 */

enum class MetricKey(val key: String) {
    STEPS("steps"),
    HEART_RATE("hr"),
    SPO2("spo2"),
    SLEEP("sleep"),
    CALORIES("calories"),
    DISTANCE("distance"),
    ACTIVE_MINUTES("active_minutes"),
    BATTERY("battery"),
    STRESS("stress"),
    HRV("hrv"),
    TEMPERATURE("temp");

    /** The wearable capability that must be present for this metric to show. */
    val requiredCapability: com.pulseloop.ring.WearableCapability?
        get() = when (this) {
            HEART_RATE -> com.pulseloop.ring.WearableCapability.HEART_RATE
            SPO2 -> com.pulseloop.ring.WearableCapability.SPO2
            STRESS -> com.pulseloop.ring.WearableCapability.STRESS
            HRV -> com.pulseloop.ring.WearableCapability.HRV
            TEMPERATURE -> com.pulseloop.ring.WearableCapability.TEMPERATURE
            STEPS, CALORIES, DISTANCE, ACTIVE_MINUTES -> com.pulseloop.ring.WearableCapability.STEPS
            SLEEP -> com.pulseloop.ring.WearableCapability.SLEEP
            BATTERY -> com.pulseloop.ring.WearableCapability.BATTERY
        }

    companion object {
        fun fromKey(key: String): MetricKey? = entries.firstOrNull { it.key == key }
    }
}

enum class MetricRange(val key: String) {
    TWENTY_FOUR_HOURS("24h"),
    SEVEN_DAYS("7d"),
    THIRTY_DAYS("30d"),
    TWELVE_MONTHS("12mo");

    companion object {
        fun fromKey(key: String): MetricRange? = entries.firstOrNull { it.key == key }
    }
}

enum class SleepRangeKey(val key: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year");

    companion object {
        fun fromKey(key: String): SleepRangeKey? = entries.firstOrNull { it.key == key }
    }
}

enum class DataFreshness(val key: String) {
    LIVE("live"),
    SYNCED_TODAY("synced_today"),
    STALE("stale"),
    MISSING("missing"),
    DEMO("demo"),
    CALIBRATING("calibrating"),
    EXPERIMENTAL("experimental");
}

enum class MetricConfidence(val key: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    PARTIAL("partial"),
    EXPERIMENTAL("experimental");
}

data class MetricSample(
    val timestamp: Long,  // epoch millis
    val value: Double,
)

data class DailyMetricPoint(
    val date: Long,       // epoch millis, start of day
    val value: Double,
)

data class MetricState(
    val freshness: DataFreshness,
    val confidence: MetricConfidence,
    val source: String?,
    val sampleCount: Int,
    val requiredSamples: Int,
    val lastUpdatedAt: Long?,
    val status: String,
    val zeroIsReal: Boolean = false,
)

data class CalibrationState(
    val isCalibrating: Boolean,
    val day: Int,
    val totalDays: Int,
    val startedAt: Long?,
    val reason: String,
)

data class GoalsSummary(
    val stepsDaily: Int = 10000,
    val activeMinutesDaily: Int = 45,
    val sleepHours: Double = 8.0,
    val exerciseDaysWeekly: Int = 4,
)

data class TrendsSummary(
    val steps7d: List<DailyMetricPoint> = emptyList(),
    val calories7d: List<DailyMetricPoint> = emptyList(),
    val distance7d: List<DailyMetricPoint> = emptyList(),
    val hrSamples24h: List<MetricSample> = emptyList(),
    val spo2Samples24h: List<MetricSample> = emptyList(),
)

data class ActivityDailyUpdate(
    val date: Long,        // epoch millis, start of day
    val steps: Int? = null,
    val calories: Double? = null,
    val distanceMeters: Double? = null,
    val activeMinutes: Int? = null,
    val source: String = "sync",
    val syncedAt: Long? = null,
)
