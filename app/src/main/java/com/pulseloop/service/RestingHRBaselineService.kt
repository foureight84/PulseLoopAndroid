package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.ring.MeasurementKind
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ported from RestingHRBaselineService.swift (iOS #95).
 * Learns a personalized resting-HR baseline from the 10th percentile of the trailing 30 days
 * of HR samples. Needs ≥20 samples spanning ≥7 days to establish.
 */
object RestingHRBaselineService {

    private const val BASELINE_DAYS = 30
    private const val MIN_SAMPLES = 20
    private const val MIN_CALENDAR_DAYS = 7
    private const val MAX_SAMPLES = 5000
    private const val REFRESH_INTERVAL_MS = 6 * 3600_000L

    suspend fun refreshIfStale(db: PulseLoopDatabase) {
        val profile = db.userProfileDao().get() ?: return
        val lastUpdate = profile.hrRestingBaselineUpdatedAt ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastUpdate < REFRESH_INTERVAL_MS) return
        refresh(db, profile)
    }

    private suspend fun refresh(db: PulseLoopDatabase, profile: UserProfileEntity) {
        val now = System.currentTimeMillis()
        val start = now - BASELINE_DAYS * 86_400_000L
        val samples = withContext(Dispatchers.Default) {
            db.measurementDao().range(MeasurementKind.HEART_RATE.name, start, now)
        }
        if (samples.size < MIN_SAMPLES) return

        val values = samples.map { it.value }.sorted()
        val calendarDays = samples.map { it.timestamp / 86_400_000L }.distinct().size
        if (calendarDays < MIN_CALENDAR_DAYS) return

        val baselineP10 = percentile(values, 0.10)
        val rounded = (baselineP10 * 2).roundToInt() / 2.0 // round to nearest 0.5

        if (profile.hrRestingBaseline != rounded) {
            db.userProfileDao().upsert(profile.copy(
                hrRestingBaseline = rounded,
                hrRestingBaselineUpdatedAt = now,
                updatedAt = now,
            ))
        }
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted.first()
        val rank = fraction * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = kotlin.math.ceil(rank).toInt().coerceAtMost(sorted.size - 1)
        if (lower == upper) return sorted[lower]
        val weight = rank - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }
}
