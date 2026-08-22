package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.ring.MeasurementKind

/**
 * Ported from `DailyCalorieMath` / `DailyCalorieEstimator` in DailyCalorieEstimator.swift (iOS #98).
 * Fills in daily calories for rings that report none (LuckRing/TK18, ring-history-only days).
 *
 * The model attributes **each interval of the day to exactly one estimator** — workout window >
 * HR-above-FLEX segment > step bucket — and every term is net of resting energy, so adding the
 * Mifflin-St Jeor BMR baseline at read time can't double-count. Without that attribution the terms
 * overlap: a logged run's minutes would be paid for by its session calories, again by its elevated
 * HR samples, and a third time by its step bucket.
 */
object DailyCalorieEstimator {

    private const val FLEX_HR_FRACTION = 0.6
    private const val MAX_HR = 220.0
    private const val HR_SAMPLE_MAX_COVERAGE_SECONDS = 600.0
    private const val BUCKET_DURATION_SECONDS = 900.0
    private const val INTERMITTENT_CADENCE_SPM = 100.0
    private const val BRISK_CADENCE_SPM = 100.0
    private const val RUN_CADENCE_SPM = 130.0
    private const val DEFAULT_WEIGHT_KG = 70.0
    private const val DEFAULT_HEIGHT_CM = 170.0
    private const val DEFAULT_AGE = 35
    private const val DAY_MS = 86_400_000L

    /**
     * The `source` value the ring-history ingest writes. iOS drops the ring's own history calorie
     * field as unverified, so a `ring_history` row's `calories` means "no device value", not zero
     * burn (`ActivityDaily.deviceReportedCalories`).
     */
    const val RING_HISTORY_SOURCE = "ring_history"

    data class Profile(val sex: String?, val age: Int?, val weightKg: Double?, val heightCm: Double?)

    /** Mifflin-St Jeor BMR (kcal/day), clamped at 0 like iOS's `mifflinBMR`. */
    fun bmr(
        weightKg: Double = DEFAULT_WEIGHT_KG,
        heightCm: Double = DEFAULT_HEIGHT_CM,
        age: Int = DEFAULT_AGE,
        sex: String? = null,
    ): Double {
        val sexConst = when (sex?.lowercase()) {
            "male" -> 5.0
            "female" -> -161.0
            else -> -78.0   // mean of the two constants when sex is unspecified
        }
        return maxOf(0.0, 10.0 * weightKg + 6.25 * heightCm - 5.0 * age + sexConst)
    }

    fun bmrPerMinute(
        weightKg: Double = DEFAULT_WEIGHT_KG,
        heightCm: Double = DEFAULT_HEIGHT_CM,
        age: Int = DEFAULT_AGE,
        sex: String? = null,
    ): Double = bmr(weightKg, heightCm, age, sex) / 1440.0

    private fun bmrPerMinute(p: Profile): Double = bmrPerMinute(
        p.weightKg ?: DEFAULT_WEIGHT_KG,
        p.heightCm ?: DEFAULT_HEIGHT_CM,
        p.age ?: DEFAULT_AGE,
        p.sex,
    )

    /** Flex-HR threshold: only samples at or above this contribute (Spurr's Flex-HR method). */
    fun flexHR(age: Int = DEFAULT_AGE): Double = FLEX_HR_FRACTION * (MAX_HR - age)

    // ── Read-time selection ─────────────────────────────────────────────────────

    /**
     * The calories the *device itself* reported, or null when it gave none.
     *
     * Mirrors iOS `ActivityDaily.deviceReportedCalories`:
     * `source == ringHistorySource || calories <= 0 ? nil : calories`. Note the direction — a
     * `ring_history` row never counts as a device value however large its `calories` column is.
     * This was inverted on first port (it returned the value *only* for `ring_history`, and ignored
     * genuine device calories from every other source), which made the estimate replace real data
     * and real data replace the estimate.
     */
    fun deviceReportedCalories(day: ActivityDailyEntity): Double? =
        if (day.source == RING_HISTORY_SOURCE || day.calories <= 0.0) null else day.calories

    /**
     * What to display: the device's own figure when it reported one, else the estimated **total** —
     * BMR accrued over the day's elapsed minutes (so today's number grows from midnight, the way
     * Fitbit/Oura/Whoop present it) plus the stored net active estimate.
     */
    fun effectiveCalories(
        day: ActivityDailyEntity,
        profile: Profile,
        now: Long = System.currentTimeMillis(),
    ): Double? {
        deviceReportedCalories(day)?.let { return it }
        val active = day.estimatedActiveCalories ?: return null
        return bmrPerMinute(profile) * elapsedMinutes(day.date, now) + active
    }

    /**
     * The active-energy portion — what the calorie *goal ring* measures, so `UserGoal.calories`
     * stays an active-energy goal even when the displayed total includes basal burn.
     */
    fun effectiveActiveCalories(day: ActivityDailyEntity): Double? =
        deviceReportedCalories(day) ?: day.estimatedActiveCalories

    /** Minutes of [dayStart] that have elapsed: partial for today, full for the past, 0 ahead. */
    private fun elapsedMinutes(dayStart: Long, now: Long): Double = when {
        now < dayStart -> 0.0
        now >= dayStart + DAY_MS -> 1440.0
        else -> ((now - dayStart) / 60_000.0).coerceIn(0.0, 1440.0)
    }

    // ── Recompute ───────────────────────────────────────────────────────────────

    /**
     * Recompute one day from scratch — idempotent, so re-syncs and repeated calls converge.
     *
     * **No `activity_daily` row → no-op.** A day with nothing synced and nothing logged has nothing
     * to estimate against, and inserting a row here would fabricate a zero-step `ring_history` day
     * in the history and charts (iOS: `guard let row = MetricsRepository.activity(...) else return`).
     */
    suspend fun recompute(dayStart: Long, db: PulseLoopDatabase, profile: Profile) {
        val row = db.activityDailyDao().byDay(dayStart) ?: return
        val dayEnd = dayStart + DAY_MS

        // `rangeReal`: the estimate is written back onto the day's REAL `activity_daily` row
        // below, where it drives the calorie goal ring and is eligible for Health Connect export
        // (that export filters on the row's own source, which is not demo). Seeded HR for today
        // would inflate a number that outlives the demo data behind it (`DemoDataPolicy`).
        val hrSamples = db.measurementDao().rangeReal(MeasurementKind.HEART_RATE.name, dayStart, dayEnd)
        val buckets = db.activityBucketDao().byDay(dayStart)
        val workouts = db.activitySessionDao().recent(WORKOUT_SCAN_LIMIT).filter {
            it.statusRaw == "finished" && it.endedAt != null &&
                it.startedAt < dayEnd && it.endedAt!! > dayStart
        }

        val active = estimateNetActive(
            dayStart = dayStart,
            dayTotalSteps = row.steps,
            buckets = buckets,
            workouts = workouts,
            hrSamples = hrSamples,
            profile = profile,
        )
        db.activityDailyDao().upsert(row.copy(
            estimatedActiveCalories = active,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    /** A half-open interval, in epoch millis, already attributed to some estimator. */
    private data class Window(val start: Long, val end: Long)

    /**
     * Net active calories for one day — excludes the BMR baseline, which [effectiveCalories] adds
     * at read time. Pure, so it can be unit-tested without Room.
     */
    fun estimateNetActive(
        dayStart: Long,
        dayTotalSteps: Int,
        buckets: List<ActivityBucketEntity>,
        workouts: List<ActivitySessionEntity>,
        hrSamples: List<MeasurementEntity>,
        profile: Profile,
    ): Double {
        val dayEnd = dayStart + DAY_MS
        val weight = profile.weightKg ?: DEFAULT_WEIGHT_KG
        val bmrPerMin = bmrPerMinute(profile)
        val covered = mutableListOf<Window>()
        var activeKcal = 0.0

        // 1. Workouts — reuse the session's stored calories, prorated across midnight and netted of
        //    the resting energy the BMR baseline already covers for those minutes.
        for (workout in workouts) {
            val end = workout.endedAt ?: continue
            val clippedStart = maxOf(workout.startedAt, dayStart)
            val clippedEnd = minOf(end, dayEnd)
            if (clippedEnd <= clippedStart) continue
            val totalMs = (end - workout.startedAt).toDouble()
            val clippedMs = (clippedEnd - clippedStart).toDouble()
            val fraction = if (totalMs > 0) clippedMs / totalMs else 0.0
            activeKcal += maxOf(0.0, (workout.calories ?: 0.0) * fraction - bmrPerMin * clippedMs / 60_000.0)
            covered.add(Window(clippedStart, clippedEnd))
        }

        // 2. All-day HR above the FLEX threshold, outside workout windows — the Keytel per-minute
        //    rate net of resting, over each sample's coverage interval. Catches unlogged exertion.
        //
        //    Gated on the same profile completeness the workout Keytel path needs: Keytel's
        //    regression has separate male/female forms, so an unspecified sex would silently be
        //    scored as female. iOS applies the identical guard; without it the HR term ran for
        //    every user and quietly used the wrong equation.
        val sex = profile.sex?.lowercase()
        val age = profile.age
        val profileWeight = profile.weightKg
        if ((sex == "male" || sex == "female") && age != null && profileWeight != null) {
            val flex = flexHR(age)
            val samples = hrSamples
                .filter { it.value > 0 && it.timestamp >= dayStart && it.timestamp < dayEnd }
                .sortedBy { it.timestamp }
            for ((index, sample) in samples.withIndex()) {
                if (sample.value < flex) continue
                val nextTs = if (index + 1 < samples.size) samples[index + 1].timestamp else dayEnd
                val coverageSec = ((nextTs - sample.timestamp) / 1000.0)
                    .coerceIn(0.0, HR_SAMPLE_MAX_COVERAGE_SECONDS)
                val segment = Window(sample.timestamp, sample.timestamp + (coverageSec * 1000).toLong())
                val minutes = maxOf(0.0, coverageSec - overlapSeconds(segment, covered)) / 60.0
                if (minutes <= 0.0) continue
                val rate = WorkoutMetricsEngine.keytelCalorieRate(sample.value, sex, age, profileWeight)
                activeKcal += maxOf(0.0, rate - bmrPerMin) * minutes
                covered.add(segment)
            }
        }

        // 3. Step buckets — cadence-tiered walking/running METs net of 1 MET, scaled by the
        //    fraction of the bucket not already attributed above.
        var stepKcal = 0.0
        var bucketSteps = 0
        for (bucket in buckets) {
            bucketSteps += bucket.steps
            if (bucket.steps <= 0) continue
            val bucketEnd = bucket.startEpoch + (BUCKET_DURATION_SECONDS * 1000).toLong()
            val keepFraction = 1.0 -
                overlapSeconds(Window(bucket.startEpoch, bucketEnd), covered) / BUCKET_DURATION_SECONDS
            if (keepFraction <= 0.0) continue
            val durationMinutes = BUCKET_DURATION_SECONDS / 60.0
            val cadence = bucket.steps / durationMinutes
            val met: Double
            val activeMinutes: Double
            when {
                cadence >= RUN_CADENCE_SPM -> { met = 8.3; activeMinutes = durationMinutes }
                cadence >= BRISK_CADENCE_SPM -> { met = 3.5; activeMinutes = durationMinutes }
                else -> {
                    met = intermittentWalkMET(profile.heightCm)
                    activeMinutes = bucket.steps / INTERMITTENT_CADENCE_SPM
                }
            }
            stepKcal += maxOf(0.0, met - 1) * weight * (activeMinutes * keepFraction) / 60.0
        }

        // 4. Residual steps the buckets don't represent — live-only days, or today's cumulative
        //    counter running ahead of the bucket log. With no buckets at all, deduct an allowance
        //    for steps taken inside already-covered windows (a run's steps are in the day total but
        //    its energy is already counted).
        var residual = maxOf(0, dayTotalSteps - bucketSteps).toDouble()
        if (buckets.isEmpty()) {
            val coveredMinutes = mergedDurationSeconds(covered) / 60.0
            residual = maxOf(0.0, residual - coveredMinutes * INTERMITTENT_CADENCE_SPM)
        }
        val residualMET = intermittentWalkMET(profile.heightCm)
        stepKcal += maxOf(0.0, residualMET - 1) * weight * (residual / INTERMITTENT_CADENCE_SPM) / 60.0

        return maxOf(0.0, activeKcal + stepKcal)
    }

    /**
     * Walking MET for intermittent stepping at ~100 steps/min, refined by stride length from height
     * when it's known (stride ≈ 0.414 × height → speed → Compendium walking tier). Height-unknown
     * returns 3.0, iOS's own fallback — deliberately *not* the tier that the 170 cm default would
     * produce, since that would silently assert a stride we don't have.
     */
    fun intermittentWalkMET(heightCm: Double?): Double {
        if (heightCm == null || heightCm <= 0) return 3.0
        val strideM = 0.414 * heightCm / 100.0
        val speedMps = strideM * INTERMITTENT_CADENCE_SPM / 60.0
        return when {
            speedMps < 1.0 -> 2.8    // < 3.6 km/h easy
            speedMps < 1.35 -> 3.5   // ~4.8 km/h moderate
            speedMps < 1.65 -> 4.3   // ~5.6 km/h brisk
            else -> 5.0              // ≥ 6 km/h very brisk
        }
    }

    // ── Interval helpers ────────────────────────────────────────────────────────

    private fun overlapSeconds(interval: Window, windows: List<Window>): Double =
        mergedDurationSeconds(
            windows.mapNotNull { w ->
                val start = maxOf(interval.start, w.start)
                val end = minOf(interval.end, w.end)
                if (end > start) Window(start, end) else null
            }
        )

    private fun mergedDurationSeconds(windows: List<Window>): Double {
        var total = 0L
        var currentEnd = Long.MIN_VALUE
        for (w in windows.sortedBy { it.start }) {
            val start = maxOf(w.start, currentEnd)
            if (w.end > start) {
                total += w.end - start
                currentEnd = w.end
            }
        }
        return total / 1000.0
    }

    /**
     * How many recent sessions to scan for windows overlapping the day. iOS fetches all sessions;
     * Android pages, and a day can only overlap sessions from that day and its neighbours.
     */
    private const val WORKOUT_SCAN_LIMIT = 50
}
