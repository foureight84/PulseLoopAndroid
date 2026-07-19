package com.pulseloop.service

/** Plain profile values the calorie model needs. */
data class MetricsProfileValues(
    val sex: String? = null,
    val age: Int? = null,
    val weightKg: Double? = null,
)

/**
 * Ported from [WorkoutMetricsEngine] in WorkoutMetricsEngine.swift (iOS #57a).
 * Calories use the Keytel et al. (2005) HR model when the ring gave us dense-enough HR and the
 * profile is complete, otherwise a per-type MET estimate (speed-tiered for run/cycle) replaces
 * the old flat 8 kcal/min for everything.
 */
object WorkoutMetricsEngine {
    /** Fallback body weight when no profile exists. */
    const val DEFAULT_WEIGHT_KG = 70.0
    /** Minimum fraction of workout minutes with an HR sample before the HR model is trusted. */
    const val KEYTEL_COVERAGE_THRESHOLD = 0.6

    fun calories(
        type: String,
        durationSeconds: Int,
        distanceMeters: Double?,
        hrSamples: List<Pair<Long, Double>>,  // (epochMillis, bpm)
        profile: MetricsProfileValues,
    ): Double {
        val minutes = durationSeconds / 60.0
        if (minutes <= 0) return 0.0
        keytelCalories(minutes, hrSamples, profile)?.let { return it }
        val speed = distanceMeters?.let { if (durationSeconds > 0) it / durationSeconds else null }
        val met = metValue(type, speed)
        return met * (profile.weightKg ?: DEFAULT_WEIGHT_KG) * (minutes / 60.0)
    }

    /** MET by canonical activity type (2011 Compendium ballparks), speed-tiered where average
     *  speed meaningfully changes intensity. */
    fun metValue(type: String, averageSpeedMps: Double?): Double = when (type) {
        "walk" -> 3.5
        "run" -> when {
            averageSpeedMps == null || averageSpeedMps <= 0 -> 9.8
            averageSpeedMps < 2.2 -> 8.3    // ≲ 8 km/h jog
            averageSpeedMps < 2.7 -> 9.8    // ~9.7 km/h
            averageSpeedMps < 3.2 -> 11.0   // ~11.3 km/h
            else -> 12.3
        }
        "cycle" -> when {
            averageSpeedMps == null || averageSpeedMps <= 0 -> 6.8
            averageSpeedMps < 4.2 -> 5.8    // < 15 km/h
            averageSpeedMps < 5.5 -> 6.8    // 15-20 km/h
            averageSpeedMps < 6.9 -> 8.0    // 20-25 km/h
            else -> 10.0
        }
        "gym" -> 5.0
        "squash" -> 12.0
        "sport" -> 7.0
        "yoga" -> 2.5
        "dance" -> 5.5
        "hike" -> 6.0
        else -> 4.0
    }

    /** Sum of per-minute Keytel rates, with uncovered minutes credited at the covered minutes'
     *  mean rate. Returns null when the profile is incomplete or HR coverage is below threshold. */
    private fun keytelCalories(
        minutes: Double,
        hrSamples: List<Pair<Long, Double>>,
        profile: MetricsProfileValues,
    ): Double? {
        val sex = profile.sex?.lowercase()?.takeIf { it == "male" || it == "female" } ?: return null
        val age = profile.age ?: return null
        val weight = profile.weightKg ?: return null
        if (hrSamples.isEmpty()) return null
        val byMinute = hrSamples.filter { it.second > 0 }.groupBy { it.first / 60_000L }
        if (byMinute.isEmpty() || byMinute.size / minutes < KEYTEL_COVERAGE_THRESHOLD) return null
        val rates = byMinute.values.map { bucket ->
            val meanHR = bucket.sumOf { it.second } / bucket.size
            keytelRate(meanHR, male = sex == "male", age = age.toDouble(), weightKg = weight)
        }
        val meanRate = rates.sum() / rates.size
        return maxOf(0.0, meanRate * minutes)
    }

    /** kcal/min for a given heart rate (Keytel et al. 2005, without VO2max), clamped >= 0. */
    private fun keytelRate(hr: Double, male: Boolean, age: Double, weightKg: Double): Double {
        val kj = if (male)
            -55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age
        else
            -20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.0740 * age
        return maxOf(0.0, kj / 4.184)
    }
}
