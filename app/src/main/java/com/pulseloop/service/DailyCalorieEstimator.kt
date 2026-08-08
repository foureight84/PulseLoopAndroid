package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.ring.MeasurementKind
import kotlin.math.roundToInt

/**
 * Ported from DailyCalorieEstimator.swift (iOS #98).
 * Fills missing calories for rings that don't report them (TK18/LuckRing/ring-history-only days).
 * Mifflin-St Jeor BMR accrued over elapsed minutes + net active energy from existing
 * Keytel/MET engine, all-day HR gating (60% HRmax threshold), and cadence-tiered step energy.
 * Device values always win at read time.
 */
object DailyCalorieEstimator {

    private const val FLEX_HR_FRACTION = 0.6
    private const val MAX_HR = 220.0
    private const val HR_SAMPLE_MAX_COVERAGE_SECONDS = 600
    private const val DEFAULT_WEIGHT_KG = 70.0
    private const val DEFAULT_HEIGHT_CM = 170.0
    private const val DEFAULT_AGE = 35

    /** Mifflin-St Jeor BMR (kcal/day). */
    fun bmr(weightKg: Double = DEFAULT_WEIGHT_KG, heightCm: Double = DEFAULT_HEIGHT_CM, age: Int = DEFAULT_AGE, sex: String? = null): Double {
        val sexConst = when (sex?.lowercase()) {
            "male" -> 5.0
            "female" -> -161.0
            else -> -78.0
        }
        return 10.0 * weightKg + 6.25 * heightCm - 5.0 * age + sexConst
    }

    /** BMR per minute. */
    fun bmrPerMinute(weightKg: Double = DEFAULT_WEIGHT_KG, heightCm: Double = DEFAULT_HEIGHT_CM, age: Int = DEFAULT_AGE, sex: String? = null): Double =
        bmr(weightKg, heightCm, age, sex) / 1440.0

    /** Flex-HR threshold: only HR samples at or above this count toward active calories. */
    fun flexHR(age: Int = DEFAULT_AGE): Double = FLEX_HR_FRACTION * (MAX_HR - age)

    data class Profile(val sex: String?, val age: Int?, val weightKg: Double?, val heightCm: Double?)

    /**
     * Recomputation entry point: estimate net active calories for a day from all-day HR samples
     * and step buckets, then update the entity row.
     */
    suspend fun recompute(dayStart: Long, db: PulseLoopDatabase, profile: Profile) {
        if (profile.age == null) return
        val age = profile.age
        val weight = profile.weightKg ?: DEFAULT_WEIGHT_KG
        val height = profile.heightCm ?: DEFAULT_HEIGHT_CM
        val dayEnd = dayStart + 86_400_000L

        val hrSamples = db.measurementDao().range(MeasurementKind.HEART_RATE.name, dayStart, dayEnd)
        val stepBuckets = db.activityBucketDao().byDay(dayStart)
        val activeKcal = estimateNetActive(hrSamples, stepBuckets, age, weight, height, profile.sex)
        val entity = db.activityDailyDao().byDay(dayStart) ?: ActivityDailyEntity(date = dayStart, source = "ring_history")
        db.activityDailyDao().upsert(entity.copy(
            estimatedActiveCalories = if (activeKcal > 0.0) activeKcal else null,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    /** Device-reported calories always win at read time. */
    fun effectiveCalories(day: ActivityDailyEntity, elapsedMinutes: Double, profile: Profile): Double? {
        if (day.source == "ring_history" && day.calories > 0.0) return day.calories
        val active = day.estimatedActiveCalories ?: return null
        val w = profile.weightKg ?: DEFAULT_WEIGHT_KG
        val h = profile.heightCm ?: DEFAULT_HEIGHT_CM
        val a = profile.age ?: DEFAULT_AGE
        val s = profile.sex
        val basal = bmrPerMinute(w, h, a, s) * elapsedMinutes.coerceAtMost(1440.0)
        return basal + active
    }

    /** Net active calories only — fed into the daily rollup so step/calorie rings work. */
    fun effectiveActiveCalories(day: ActivityDailyEntity): Double? {
        if (day.source == "ring_history" && day.calories > 0.0) return day.calories
        return day.estimatedActiveCalories
    }

    // ── Estimation ──────────────────────────────────────────────────────────────

    private fun estimateNetActive(
        hrSamples: List<MeasurementEntity>,
        stepBuckets: List<com.pulseloop.data.entity.ActivityBucketEntity>,
        age: Int,
        weightKg: Double,
        heightCm: Double,
        sex: String?,
    ): Double {
        val threshold = flexHR(age)
        val bpm = bmrPerMinute(weightKg, heightCm, age, sex)
        var hrKcal = 0.0

        // HR-based active calories (gated: only readings ≥ flex-HR).
        val valid = hrSamples.filter { it.value >= threshold }.sortedBy { it.timestamp }
        if (valid.isNotEmpty()) {
            for (i in valid.indices) {
                val durSec = if (i < valid.size - 1)
                    ((valid[i + 1].timestamp - valid[i].timestamp) / 1000.0).coerceAtMost(HR_SAMPLE_MAX_COVERAGE_SECONDS.toDouble())
                else HR_SAMPLE_MAX_COVERAGE_SECONDS.toDouble()
                val rate = WorkoutMetricsEngine.keytelCalorieRate(valid[i].value, sex, age, weightKg)
                hrKcal += rate * durSec / 60.0
            }
        }

        // Step-based MET calories (cadence-tiered).
        val stepKcal = estimateStepCalories(stepBuckets, heightCm, weightKg)

        return maxOf(0.0, hrKcal + stepKcal - bpm * (1440.0))
    }

    private fun estimateStepCalories(
        buckets: List<com.pulseloop.data.entity.ActivityBucketEntity>,
        heightCm: Double,
        weightKg: Double,
    ): Double {
        var total = 0.0
        for (bucket in buckets) {
            val intervalSeconds = when {
                bucket.distanceMeters > 0 -> (bucket.distanceMeters / 1.0) * 600.0  // placeholder: ~10 min
                else -> 3600.0
            }
            val cadence = if (intervalSeconds > 0) bucket.steps / (intervalSeconds / 60.0) else 0.0
            val met = when {
                cadence >= 130 -> 8.3
                cadence >= 100 -> 3.5
                cadence < 100 -> {
                    val strideM = 0.414 * heightCm / 100.0
                    val speedMps = strideM * 100.0 / 60.0
                    when {
                        speedMps < 1.0 -> 2.8
                        speedMps < 1.35 -> 3.5
                        speedMps < 1.65 -> 4.3
                        else -> 5.0
                    }
                }
                else -> 3.5
            }
            val hours = intervalSeconds / 3600.0
            total += met * weightKg * hours
        }
        return total
    }
}
