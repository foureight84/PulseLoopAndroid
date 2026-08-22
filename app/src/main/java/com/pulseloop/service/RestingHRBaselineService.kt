package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.util.TimeUtil
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Ported from RestingHRBaselineService.swift (iOS #95).
 * Learns a personalized resting-HR baseline from the 10th percentile of the trailing 30 days
 * of HR samples. Needs ≥20 samples spanning ≥7 days to establish.
 *
 * Called from [EventPersistenceSubscriber] on sync completion. That wiring is load-bearing: with no
 * caller, `hrRestingBaseline` stays null forever and [VitalsThresholdEngine]'s default `"auto"` HR
 * zone mode silently falls back to fixed 50/90 boundaries — which is how it shipped on first port,
 * so the feature moved everyone's "normal" band without giving anyone the personalisation that
 * justified moving it.
 */
object RestingHRBaselineService {

    private const val BASELINE_DAYS = 30
    private const val MIN_SAMPLES = 20
    private const val MIN_CALENDAR_DAYS = 7
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
        // Room suspend queries already run off the main thread on their own dispatcher.
        // `rangeReal`, not `range`: the seeder plants ~30 days of HR — overnight ~56 bpm, workout
        // spikes at 142/152 — squarely inside this window, and since PR #52 a connect no longer
        // clears it. A demo-derived p10 is then PERSISTED to `hrRestingBaseline` and drives the
        // `"auto"` HR zones applied to real readings, surviving a Clear Demo Data by up to the
        // 6-hour refresh interval (`DemoDataPolicy`).
        val samples = db.measurementDao().rangeReal(MeasurementKind.HEART_RATE.name, start, now)
        if (samples.size < MIN_SAMPLES) return

        val values = samples.map { it.value }.sorted()
        // Distinct *local* days: `timestamp / 86_400_000` buckets by UTC, which silently splits or
        // merges a day for anyone far enough from GMT and skews the ≥7-day spread requirement.
        val calendarDays = samples.map { TimeUtil.startOfDayLocal(it.timestamp) }.distinct().size
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
