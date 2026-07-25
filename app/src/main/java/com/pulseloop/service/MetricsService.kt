package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.WearableCapability
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Ported from MetricsService in PulseServices.swift.
 * Core metrics aggregation: buildTodaySummary, metricRange, deviceCapabilities,
 * supports, and mock measurement insertion.
 */
object MetricsService {

    // ── Today Summary ────────────────────────────────────────────────────

    data class TodaySummary(
        val steps: Int? = null,
        val calories: Double? = null,
        val distanceMeters: Double? = null,
        val activeMinutes: Int? = null,
        val latestHeartRate: Int? = null,
        val latestSpO2: Int? = null,
        val restingHeartRate: Double? = null,
        val peakHeartRate: Int? = null,
        val batteryPercent: Int? = null,
        val isDemo: Boolean = false,
        val stepsTrend: List<Double> = emptyList(),
        val hrTrend24h: List<Double> = emptyList(),
        val spo2Trend24h: List<Double> = emptyList(),
    )

    suspend fun buildTodaySummary(db: PulseLoopDatabase): TodaySummary {
        val now = System.currentTimeMillis()
        val todayStart = Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()

        val todayActivity = db.activityDailyDao().byDay(todayStart)
        val device = db.deviceDao().current()
        val hr24h = db.measurementDao().range(MeasurementKind.HEART_RATE.name, now - 24 * 3600_000L, now)
        val spo24h = db.measurementDao().range(MeasurementKind.SPO2.name, now - 24 * 3600_000L, now)
        val recent7 = db.activityDailyDao().recent(7)

        val latestHr = db.measurementDao().latest(MeasurementKind.HEART_RATE.name, now)?.toInt()
        val latestSpo2 = db.measurementDao().latest(MeasurementKind.SPO2.name, now)?.toInt()

        val hrValues = hr24h.map { it.value }
        val spoValues = spo24h.map { it.value }

        val restingHr = hrValues.filter { it <= 72.0 }.minOrNull()

        return TodaySummary(
            steps = todayActivity?.steps,
            calories = todayActivity?.calories,
            distanceMeters = todayActivity?.distanceMeters,
            activeMinutes = todayActivity?.activeMinutes,
            latestHeartRate = latestHr,
            latestSpO2 = latestSpo2,
            restingHeartRate = restingHr,
            peakHeartRate = hrValues.maxOrNull()?.toInt(),
            batteryPercent = device?.batteryPercent,
            isDemo = todayActivity?.source == "mock",
            stepsTrend = recent7.map { it.steps.toDouble() },
            hrTrend24h = hrValues,
            spo2Trend24h = spoValues,
        )
    }

    // ── Metric Range ─────────────────────────────────────────────────────

    data class MetricSample(
        val timestamp: Long,
        val value: Double,
    )

    suspend fun metricRange(
        kind: MeasurementKind,
        db: PulseLoopDatabase,
        hours: Long = 24,
    ): List<MetricSample> {
        val now = System.currentTimeMillis()
        val cutoff = now - hours * 3600_000L
        return db.measurementDao().range(kind.name, cutoff, now)
            .map { MetricSample(it.timestamp, it.value) }
    }

    // ── Device Capabilities ──────────────────────────────────────────────

    suspend fun deviceCapabilities(db: PulseLoopDatabase): Set<WearableCapability> {
        val device = db.deviceDao().current()
        val caps = device?.capabilities ?: emptySet()
        if (caps.isEmpty()) {
            // Fall back to Jring base set
            return setOf(WearableCapability.HEART_RATE, WearableCapability.SPO2, WearableCapability.STEPS, WearableCapability.SLEEP, WearableCapability.BATTERY)
        }
        return caps
    }

    suspend fun supports(kind: MeasurementKind, db: PulseLoopDatabase): Boolean {
        val caps = deviceCapabilities(db)
        return when (kind) {
            MeasurementKind.HEART_RATE -> caps.contains(WearableCapability.HEART_RATE)
            MeasurementKind.SPO2 -> caps.contains(WearableCapability.SPO2)
            MeasurementKind.STRESS -> caps.contains(WearableCapability.STRESS)
            MeasurementKind.FATIGUE -> caps.contains(WearableCapability.FATIGUE)
            MeasurementKind.HRV -> caps.contains(WearableCapability.HRV)
            MeasurementKind.TEMPERATURE -> caps.contains(WearableCapability.TEMPERATURE)
            MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> caps.contains(WearableCapability.BLOOD_PRESSURE)
            MeasurementKind.BLOOD_SUGAR -> caps.contains(WearableCapability.BLOOD_SUGAR)
            MeasurementKind.RESPIRATORY_RATE -> db.measurementDao().latest(kind.name) != null
            MeasurementKind.VO2MAX -> WearableCapability.VO2MAX in caps ||
                db.measurementDao().latest(kind.name) != null
        }
    }

    // ── Mock Measurement ─────────────────────────────────────────────────

    suspend fun insertMockMeasurement(kind: MeasurementKind, db: PulseLoopDatabase) {
        val value = when (kind) {
            MeasurementKind.HEART_RATE -> (62..86).random().toDouble()
            MeasurementKind.SPO2 -> (96..99).random().toDouble()
            MeasurementKind.STRESS -> (20..70).random().toDouble()
            MeasurementKind.FATIGUE -> (20..70).random().toDouble()
            MeasurementKind.HRV -> (30..90).random().toDouble()
            MeasurementKind.TEMPERATURE -> (330..360).random() / 10.0
            MeasurementKind.BLOOD_PRESSURE_SYSTOLIC -> (110..130).random().toDouble()
            MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> (70..85).random().toDouble()
            MeasurementKind.BLOOD_SUGAR -> (80..140).random().toDouble()
            MeasurementKind.RESPIRATORY_RATE -> (12..20).random().toDouble()
            MeasurementKind.VO2MAX -> (30..55).random().toDouble()
        }
        val unit = when (kind) {
            MeasurementKind.HEART_RATE -> "bpm"
            MeasurementKind.SPO2 -> "%"
            MeasurementKind.HRV -> "ms"
            MeasurementKind.TEMPERATURE -> "°C"
            MeasurementKind.BLOOD_PRESSURE_SYSTOLIC -> "mmHg"
            MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> "mmHg"
            MeasurementKind.BLOOD_SUGAR -> "mg/dL"
            else -> ""
        }
        val m = MeasurementEntity(
            kindRaw = kind.name,
            value = value,
            unit = unit,
            timestamp = System.currentTimeMillis(),
            sourceRaw = "mock",
            confidenceRaw = "known",
        )
        db.measurementDao().insert(m)
    }

    // ── Trend Analysis ───────────────────────────────────────────────────

    /**
     * Compute the 7-day trend direction for a list of daily values.
     */
    fun trendDirection(values: List<Double>): String {
        if (values.size < 2) return "flat"
        val first = values.take(3).average()
        val last = values.takeLast(3).average()
        val change = last - first
        val threshold = first * 0.05
        return when {
            change > threshold -> "rising"
            change < -threshold -> "falling"
            else -> "flat"
        }
    }
}
