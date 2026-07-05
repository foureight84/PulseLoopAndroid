package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.ring.MeasurementKind
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * Computes app-side estimates the ring's firmware doesn't honestly provide:
 *
 *  - HRV proxy: SDNN over pseudo-RR intervals inverted from the 1 Hz HR
 *    stream. The firmware smooths HR and quantizes to integer bpm, so this
 *    tracks *relative* change (your Tuesday vs your Friday), not clinical
 *    SDNN. The ring's own HRV byte has read 80-81 ms in every packet ever
 *    captured, so even a rough estimate beats it.
 *  - Stress 0-100: resting-HR elevation vs a personal 7-day baseline,
 *    blended with HRV-proxy depression. Transparent, personal, documented —
 *    unlike the firmware's black box.
 *  - Derived sleep: the firmware has never emitted a 0x11 sleep packet, but
 *    the auto-HR stream samples all night. A sustained overnight run of
 *    below-daytime-baseline HR is a serviceable sleep window estimate.
 *  - Fatigue 0-100: morning composite of sleep deficit + resting-HR
 *    elevation + HRV drop.
 *
 * All rows are written with sourceRaw = SOURCE_DERIVED so they are
 * distinguishable from ring-reported values in the DB and diagnostics.
 */
class DerivedMetricsEngine(
    private val db: PulseLoopDatabase,
    private val coordinator: RingSyncCoordinator? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) {
                    android.util.Log.e("DerivedMetrics", "tick failed", e)
                }
                delay(TICK_MS)
            }
        }
    }

    fun destroy() { scope.cancel() }

    private suspend fun tick() {
        val now = System.currentTimeMillis()

        // ── HRV proxy + stress, at most one row per tick interval ──
        val recentHrv = db.measurementDao()
            .range(MeasurementKind.HRV.name, now - TICK_MS + 60_000, now)
        if (recentHrv.none { it.sourceRaw == SOURCE_DERIVED }) {
            val window = db.measurementDao()
                .range(MeasurementKind.HEART_RATE.name, now - HRV_WINDOW_MS, now)
                .map { it.value.toInt() }
            sdnnProxy(window)?.let { sdnn ->
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.HRV.name, value = sdnn, unit = "ms",
                    timestamp = now, sourceRaw = SOURCE_DERIVED,
                ))
            }

            val current = db.measurementDao()
                .range(MeasurementKind.HEART_RATE.name, now - 30 * 60_000, now)
                .map { it.value }
            val week = db.measurementDao()
                .range(MeasurementKind.HEART_RATE.name, now - 7 * 86_400_000L, now)
                .map { it.value }
            val hrvWeek = db.measurementDao()
                .range(MeasurementKind.HRV.name, now - 7 * 86_400_000L, now)
                .filter { it.sourceRaw == SOURCE_DERIVED }.map { it.value }
            val stress = stressScore(
                currentHr = current.medianOrNull(),
                baselineRestingHr = week.percentileOrNull(0.10),
                hrvNow = sdnnProxy(current.map { it.toInt() }),
                hrvBaseline = hrvWeek.medianOrNull(),
            )
            if (stress != null) db.measurementDao().insert(MeasurementEntity(
                kindRaw = MeasurementKind.STRESS.name, value = stress.toDouble(), unit = "",
                timestamp = now, sourceRaw = SOURCE_DERIVED,
            ))
        }

        // ── Night SpO2 now lives in SleepStreamController, at a denser ~3-min
        // cadence while it holds the overnight HR stream open (charging + in
        // night window), instead of this engine's 30-min tick — avoids double
        // measurement and gives the apnea screen below a richer series. ──
        val hour = java.time.LocalTime.now().hour
        // ── Morning jobs: derived sleep, then fatigue (needs the sleep row) ──
        if (hour in 5..15) {
            val lastNightDay = com.pulseloop.util.TimeUtil.startOfTodayLocal()
            deriveSleepIfMissing(lastNightDay)
            deriveFatigueOncePerDay(lastNightDay, now)
        }
        // ── Screening-only apnea/ODI check: narrower morning window since it
        // wants a completed night (22:00 prev → 10:00 today) already in Room. ──
        if (hour in 7..11) {
            runApneaScreenOncePerDay(com.pulseloop.util.TimeUtil.startOfTodayLocal(), now)
        }
    }

    /** Estimate last night's sleep from sustained low overnight HR. */
    private suspend fun deriveSleepIfMissing(dayStart: Long) {
        if (db.sleepSessionDao().byDay(dayStart) != null) return  // ring or prior estimate won

        val windowStart = dayStart - 4 * 3_600_000L               // 20:00 yesterday
        val windowEnd = dayStart + 12 * 3_600_000L                // noon today
        val hr = db.measurementDao().range(MeasurementKind.HEART_RATE.name, windowStart, windowEnd)
        if (hr.size < MIN_NIGHT_SAMPLES) return

        // Daytime baseline: yesterday 10:00-20:00 median.
        val daytime = db.measurementDao().range(
            MeasurementKind.HEART_RATE.name,
            dayStart - 14 * 3_600_000L, dayStart - 4 * 3_600_000L,
        ).map { it.value }.medianOrNull() ?: return

        // 10-min buckets. Thresholds self-anchor to the night's own low HR:
        // a daytime-median anchor alone proved unstable (a workout day inflated
        // it and scored 11h "asleep"). Asleep = below BOTH the daytime-derived
        // ceiling and nightLow*1.12; deep = within 5% of the night's floor.
        val buckets = hr.groupBy { (it.timestamp - windowStart) / BUCKET_MS }
            .mapValues { (_, rows) -> rows.map { it.value }.medianOrNull() }
        val nightLow = buckets.values.filterNotNull().percentileOrNull(0.05) ?: return
        val asleepThresh = minOf(daytime * SLEEP_HR_FACTOR, nightLow * 1.12)
        val asleep = { i: Long -> (buckets[i] ?: Double.MAX_VALUE) <= asleepThresh }

        // Longest run, tolerating up to 2 consecutive non-sleep buckets (brief wakes).
        var bestStart = -1L; var bestLen = 0L
        var runStart = -1L; var gap = 0
        val maxBucket = (windowEnd - windowStart) / BUCKET_MS
        for (i in 0..maxBucket) {
            if (asleep(i)) {
                if (runStart < 0) runStart = i
                gap = 0
            } else if (runStart >= 0 && ++gap > 2) {
                val len = i - gap - runStart + 1
                if (len > bestLen) { bestLen = len; bestStart = runStart }
                runStart = -1; gap = 0
            }
        }
        if (runStart >= 0) {
            val len = maxBucket - runStart + 1
            if (len > bestLen) { bestLen = len; bestStart = runStart }
        }

        val minutes = bestLen * BUCKET_MS / 60_000
        if (minutes < MIN_SLEEP_MINUTES) return
        val startAt = windowStart + bestStart * BUCKET_MS
        val sessionId = "derived-$dayStart"
        db.sleepSessionDao().upsert(SleepSessionEntity(
            id = sessionId,
            date = dayStart,
            startAt = startAt,
            endAt = startAt + bestLen * BUCKET_MS,
            totalMinutes = minutes.toInt(),
            score = null,                      // estimates don't get a score
            syncedAt = System.currentTimeMillis(),  // non-null: survives demo clears
        ))
        // Coarse derived stages: deep = HR well below daytime baseline, else light.
        // Not polysomnography — but it gives the Sleep screen structure and is
        // honest about what an HR-only estimate can see.
        db.sleepStageBlockDao().deleteBySession(sessionId)
        var blockStart = bestStart; var blockStage: String? = null
        fun stageOf(i: Long): String {
            val v = buckets[i] ?: return "LIGHT"
            return if (v <= nightLow * 1.05) "DEEP" else "LIGHT"
        }
        for (i in bestStart..(bestStart + bestLen)) {
            val stg = if (i < bestStart + bestLen) stageOf(i) else null
            if (stg != blockStage) {
                if (blockStage != null) {
                    val bs = windowStart + blockStart * BUCKET_MS
                    db.sleepStageBlockDao().insert(com.pulseloop.data.entity.SleepStageBlockEntity(
                        sessionId = sessionId,
                        startAt = bs,
                        startMinute = ((bs - startAt) / 60_000).toInt(),
                        durationMinutes = ((i - blockStart) * BUCKET_MS / 60_000).toInt(),
                        stageRaw = blockStage!!,
                    ))
                }
                blockStart = i; blockStage = stg
            }
        }
    }

    /**
     * Screening-only ODI/CVHR check over last night's window (22:00 prev day → 10:00
     * today). Guarded so it runs at most once per calendar day: the coach-memory row
     * it writes is itself the guard, keyed by date — but since a row is only written
     * when [ApneaScreenResult.dataQuality] isn't "insufficient", an insufficient night
     * (nothing meaningful to say yet) is retried on every tick through the 7-11 window
     * in case more of last night's data lands late.
     */
    private suspend fun runApneaScreenOncePerDay(dayStart: Long, now: Long) {
        val dateKey = java.time.Instant.ofEpochMilli(dayStart)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val memoryKey = "sleep_apnea_screen_$dateKey"
        if (db.coachMemoryDao().byKey(memoryKey) != null) return

        val windowStart = dayStart - 2 * 3_600_000L   // 22:00 the previous day
        val windowEnd = dayStart + 10 * 3_600_000L    // 10:00 today
        val spo2 = db.measurementDao().range(MeasurementKind.SPO2.name, windowStart, windowEnd)
            .map { it.timestamp to it.value.toInt() }
        val hr = db.measurementDao().range(MeasurementKind.HEART_RATE.name, windowStart, windowEnd)
            .map { it.timestamp to it.value.toInt() }
        val result = buildApneaScreenResult(spo2, hr, windowStart, windowEnd)
        if (result.dataQuality == "insufficient") return

        db.coachMemoryDao().upsert(com.pulseloop.data.entity.CoachMemoryEntity(
            key = memoryKey,
            value = "Overnight screening for $dateKey: ODI ${"%.1f".format(result.odi)} events/hr " +
                "(${result.desaturations3pct} desaturations >=3%, ${result.desaturations4pct} >=4%), " +
                "${result.hrSpikesCoincident} coincident with an HR spike, data quality ${result.dataQuality}. " +
                "Screening estimate only, not a medical assessment.",
            memoryType = "note",
            importance = 3,
        ))
    }

    private suspend fun deriveFatigueOncePerDay(dayStart: Long, now: Long) {
        val today = db.measurementDao().range(MeasurementKind.FATIGUE.name, dayStart, now)
        if (today.any { it.sourceRaw == SOURCE_DERIVED }) return

        val sleepMin = db.sleepSessionDao().byDay(dayStart)?.totalMinutes
        val morningHr = db.measurementDao()
            .range(MeasurementKind.HEART_RATE.name, dayStart + 5 * 3_600_000L, now)
            .map { it.value }.percentileOrNull(0.10)
        val weekHr = db.measurementDao()
            .range(MeasurementKind.HEART_RATE.name, now - 7 * 86_400_000L, now)
            .map { it.value }.percentileOrNull(0.10)
        val hrvToday = db.measurementDao().range(MeasurementKind.HRV.name, dayStart, now)
            .filter { it.sourceRaw == SOURCE_DERIVED }.map { it.value }.medianOrNull()
        val hrvWeek = db.measurementDao().range(MeasurementKind.HRV.name, now - 7 * 86_400_000L, now)
            .filter { it.sourceRaw == SOURCE_DERIVED }.map { it.value }.medianOrNull()

        val fatigue = fatigueScore(sleepMin, morningHr, weekHr, hrvToday, hrvWeek) ?: return
        db.measurementDao().insert(MeasurementEntity(
            kindRaw = MeasurementKind.FATIGUE.name, value = fatigue.toDouble(), unit = "",
            timestamp = now, sourceRaw = SOURCE_DERIVED,
        ))
    }

    companion object {
        const val SOURCE_DERIVED = "derived"
        private const val TICK_MS = 30 * 60_000L
        private const val HRV_WINDOW_MS = 10 * 60_000L
        private const val BUCKET_MS = 10 * 60_000L
        private const val SLEEP_HR_FACTOR = 0.88  // 0.92 flagged quiet-awake time as sleep
        private const val MIN_SLEEP_MINUTES = 180
        private const val MIN_NIGHT_SAMPLES = 500
    }
}

// ── Pure, testable math ──────────────────────────────────────────────────

/**
 * SDNN over pseudo-RR intervals from 1 Hz integer bpm samples.
 * ponytail: quantization (~13 ms at rest) + firmware smoothing make this a
 * relative index, not clinical SDNN; upgrade path is raw RR/PPG, which this
 * firmware doesn't expose.
 */
fun sdnnProxy(bpm: List<Int>): Double? {
    val rr = bpm.filter { it in 30..220 }.map { 60_000.0 / it }
    if (rr.size < 60) return null
    val mean = rr.average()
    return sqrt(rr.sumOf { (it - mean) * (it - mean) } / (rr.size - 1))
}

/** Stress 0-100 from HR elevation vs baseline + HRV depression vs baseline. */
fun stressScore(
    currentHr: Double?, baselineRestingHr: Double?,
    hrvNow: Double?, hrvBaseline: Double?,
): Int? {
    if (currentHr == null || baselineRestingHr == null || baselineRestingHr <= 0) return null
    val hrElev = ((currentHr / baselineRestingHr - 1.0) * 2.5).coerceIn(0.0, 1.0)
    val hrvDep = if (hrvNow != null && hrvBaseline != null && hrvBaseline > 0)
        (1.0 - hrvNow / hrvBaseline).coerceIn(0.0, 1.0) else 0.0
    val weightHrv = if (hrvNow != null && hrvBaseline != null) 0.35 else 0.0
    val score = 100 * ((1 - weightHrv) * hrElev + weightHrv * hrvDep)
    return score.toInt().coerceIn(0, 100)
}

/** Fatigue 0-100: sleep deficit + morning resting-HR elevation + HRV drop. */
fun fatigueScore(
    sleepMinutes: Int?, morningRestingHr: Double?, weekRestingHr: Double?,
    hrvToday: Double?, hrvWeek: Double?,
): Int? {
    if (morningRestingHr == null || weekRestingHr == null || weekRestingHr <= 0) return null
    val sleepDef = if (sleepMinutes != null) ((480.0 - sleepMinutes) / 480.0).coerceIn(0.0, 1.0)
        else return null  // no sleep signal, no fatigue estimate — don't guess
    val rhrElev = ((morningRestingHr / weekRestingHr - 1.0) * 2.5).coerceIn(0.0, 1.0)
    val hrvDrop = if (hrvToday != null && hrvWeek != null && hrvWeek > 0)
        (1.0 - hrvToday / hrvWeek).coerceIn(0.0, 1.0) else 0.0
    return (100 * (0.45 * sleepDef + 0.35 * rhrElev + 0.20 * hrvDrop)).toInt().coerceIn(0, 100)
}

fun List<Double>.medianOrNull(): Double? =
    if (isEmpty()) null else sorted()[size / 2]

fun List<Double>.percentileOrNull(p: Double): Double? =
    if (isEmpty()) null else sorted()[((size - 1) * p).toInt()]
