package com.pulseloop.coach.tools

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import com.pulseloop.ring.MeasurementKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Ported from [CoachDataAccess] in CoachDataAccess.swift.
 * Date-range reads over the Room DAOs, shared by retrieval and analysis tools.
 * The LLM never queries the DB directly — it only reaches data through these
 * deterministic helpers.
 */
object CoachDataAccess {

    /** Parse "YYYY-MM-DD" to epoch-millis start of day in the system default zone. */
    fun parseLocalDate(value: String): Long? {
        return try {
            val trimmed = value.take(10)
            LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) { null }
    }

    /**
     * A tool's `start_time` on [date], in epoch millis. Accepts the documented 24-hour clock
     * (`19:00`), the `7:00 PM` spelling local models favour, and a full ISO timestamp (with or
     * without an offset). Null when [time] is blank or unreadable — the caller then falls back to
     * its own default. [parseLocalDate] alone kept only the date part, so `19:00` fell back to
     * noon and an ISO timestamp landed on midnight (iOS 6d006ec).
     */
    fun resolveStart(date: String, time: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val value = time?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // `2026-10-14 19:00` (SQL-style space instead of `T`) is as common from models as ISO.
        val iso = if (DATE_SPACE_TIME.matches(value)) value.replaceFirst(' ', 'T') else value
        try { return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return java.time.LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) {}
        val day = try { LocalDate.parse(date.trim().take(10), DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) { return null }
        for (formatter in CLOCK_FORMATTERS) {
            val clock = try { java.time.LocalTime.parse(value, formatter) } catch (_: Exception) { continue }
            return day.atTime(clock.hour, clock.minute).atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    private val DATE_SPACE_TIME = Regex("""\d{4}-\d{2}-\d{2} \d.*""")
    private val CLOCK_FORMATTERS = listOf("HH:mm", "H:mm", "HH:mm:ss", "H:mm:ss", "h:mm a", "h:mma").map {
        java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern(it).toFormatter(java.util.Locale.US)
    }

    fun startOfDay(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
            .toInstant().toEpochMilli()

    fun dayBounds(start: String, end: String): Pair<Long, Long>? {
        val s = parseLocalDate(start) ?: return null
        val e = parseLocalDate(end) ?: return null
        val endOfDay = Instant.ofEpochMilli(e).atZone(ZoneId.systemDefault())
            .plusDays(1).truncatedTo(ChronoUnit.DAYS)
            .toInstant().toEpochMilli()
        return s to endOfDay
    }

    fun localDateString(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun isoString(ts: Long): String = Instant.ofEpochMilli(ts).toString()

    // ── Reads ─────────────────────────────────────────────────────────────

    suspend fun measurements(
        db: PulseLoopDatabase, kind: MeasurementKind, start: String, end: String
    ): List<MeasurementEntity> {
        val bounds = dayBounds(start, end) ?: return emptyList()
        return db.measurementDao().range(kind.name, bounds.first, bounds.second)
    }

    suspend fun activityRows(
        db: PulseLoopDatabase, start: String, end: String
    ): List<ActivityDailyEntity> {
        val s = parseLocalDate(start) ?: return emptyList()
        val e = parseLocalDate(end) ?: return emptyList()
        val recent = db.activityDailyDao().recent(90)  // broad window; filter in Kotlin
        return recent.filter { it.date >= s && it.date <= e }.sortedBy { it.date }
    }

    suspend fun activityRow(db: PulseLoopDatabase, date: String): ActivityDailyEntity? {
        val day = parseLocalDate(date) ?: return null
        return db.activityDailyDao().byDay(day)
    }

    suspend fun sleepSessions(
        db: PulseLoopDatabase, start: String, end: String
    ): List<SleepSessionEntity> {
        val s = parseLocalDate(start) ?: return emptyList()
        val e = parseLocalDate(end) ?: return emptyList()
        val recent = db.sleepSessionDao().recent(90)
        return recent.filter { it.date in s..e }.sortedBy { it.date }
    }

    suspend fun activitySessions(
        db: PulseLoopDatabase, start: String, end: String
    ): List<ActivitySessionEntity> {
        val s = parseLocalDate(start) ?: return emptyList()
        val e = parseLocalDate(end) ?: return emptyList()
        val recent = db.activitySessionDao().recent(30)
        return recent.filter { it.startedAt in s..e }.sortedByDescending { it.startedAt }
    }

    // ── Stats ─────────────────────────────────────────────────────────────

    data class Stats(
        val count: Int, val avg: Double?, val min: Double?, val max: Double?
    )

    fun stats(values: List<Double>): Stats {
        if (values.isEmpty()) return Stats(0, null, null, null)
        val avg = (values.sum() / values.size).roundTo(1)
        return Stats(values.size, avg, values.minOrNull(), values.maxOrNull())
    }

    // ── Daily series ──────────────────────────────────────────────────────

    suspend fun dailySeries(
        db: PulseLoopDatabase, metric: String, start: String, end: String,
        preferPhone: Boolean,
    ): List<Pair<Long, Double>> {
        return when (metric) {
            "steps", "calories", "distance", "active_minutes" -> {
                activityRows(db, start, end).map { row ->
                    val v = when (metric) {
                        "steps" -> com.pulseloop.data.DisplaySteps.count(row, preferPhone)?.toDouble() ?: 0.0
                        "calories" -> row.calories
                        "distance" -> (row.distanceMeters / 1000.0).roundTo(2)
                        "active_minutes" -> row.activeMinutes.toDouble()
                        else -> 0.0
                    }
                    row.date to v
                }
            }
            "hr" -> {
                val rows = measurements(db, MeasurementKind.HEART_RATE, start, end)
                rows.groupBy { startOfDay(it.timestamp) }
                    .map { (day, ms) -> day to (ms.map { it.value }.average()).roundTo(1) }
                    .sortedBy { it.first }
            }
            "spo2" -> {
                val rows = measurements(db, MeasurementKind.SPO2, start, end)
                rows.groupBy { startOfDay(it.timestamp) }
                    .map { (day, ms) -> day to (ms.map { it.value }.average()).roundTo(1) }
                    .sortedBy { it.first }
            }
            "sleep" -> {
                sleepSessions(db, start, end).map { it.date to it.totalMinutes.toDouble() }
            }
            else -> emptyList()
        }
    }

    // ── Series points ─────────────────────────────────────────────────────

    suspend fun seriesPoints(
        db: PulseLoopDatabase, metric: String, start: String, end: String, granularity: String,
        preferPhone: Boolean,
    ): List<Pair<String, Double>> {
        val labeler: (Long) -> String = when (granularity) {
            "hour" -> { ts -> hourLabel(ts) }
            "day" -> { ts -> localDateString(ts) }
            else -> { ts -> isoString(ts) }
        }

        val dated: List<Pair<Long, Double>> = when (metric) {
            "hr" -> {
                val rows = measurements(db, MeasurementKind.HEART_RATE, start, end)
                when (granularity) {
                    "hour" -> rows.groupBy {
                        Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault())
                            .truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
                    }.map { (h, ms) -> h to ms.map { it.value }.average().roundTo(1) }.sortedBy { it.first }
                    "day" -> dailySeries(db, metric, start, end, preferPhone)
                    else -> rows.map { it.timestamp to it.value }
                }
            }
            "spo2" -> {
                val rows = measurements(db, MeasurementKind.SPO2, start, end)
                when (granularity) {
                    "hour" -> rows.groupBy {
                        Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault())
                            .truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
                    }.map { (h, ms) -> h to ms.map { it.value }.average().roundTo(1) }.sortedBy { it.first }
                    "day" -> dailySeries(db, metric, start, end, preferPhone)
                    else -> rows.map { it.timestamp to it.value }
                }
            }
            else -> dailySeries(db, metric, start, end, preferPhone)
        }

        return downsample(dated, 300).map { labeler(it.first) to it.second }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun hourLabel(ts: Long): String {
        val t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        return "${t.toLocalDate()} ${t.hour.toString().padStart(2, '0')}:00"
    }

    private fun downsample(points: List<Pair<Long, Double>>, max: Int): List<Pair<Long, Double>> {
        if (points.size <= max) return points
        val step = points.size.toDouble() / max.toDouble()
        return (0 until max).map { points[(it * step).toInt()] }
    }
}

private fun Double.roundTo(places: Int): Double {
    val p = Math.pow(10.0, places.toDouble())
    return Math.round(this * p) / p
}
