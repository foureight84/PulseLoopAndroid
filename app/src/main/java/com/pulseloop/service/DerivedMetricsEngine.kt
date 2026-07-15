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

    /** Delete isolated implausible HR spikes (off-wrist artifacts). A sample is a
     *  spike if it's >=140 bpm while BOTH temporal neighbours (within 3 min) are
     *  <100 — genuine exertion holds elevated HR across consecutive samples, so
     *  only lone spikes match. Conservative: leaves any spike with a hot neighbour. */
    private suspend fun scrubHrArtifacts(from: Long, to: Long) {
        val rows = db.measurementDao().range(MeasurementKind.HEART_RATE.name, from, to)
        for (i in 1 until rows.size - 1) {
            val v = rows[i].value
            if (v < 140) continue
            val prev = rows[i - 1]; val next = rows[i + 1]
            val near = { r: MeasurementEntity -> kotlin.math.abs(r.timestamp - rows[i].timestamp) <= 3 * 60_000L }
            if (near(prev) && near(next) && prev.value < 100 && next.value < 100) {
                db.measurementDao().deleteAt(MeasurementKind.HEART_RATE.name, rows[i].timestamp)
            }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()

        // ── Off-wrist artifact scrub: the ring computes garbage PPG when it loses
        // skin contact (shower, re-seating, hand motion), logging isolated HR
        // spikes into the 150-200 range flanked by resting values. A real HR can't
        // jump 55→183→55 in two minutes; these are non-physiological and both alarm
        // the user and inflate the HRV SD. Delete lone spikes before computing HRV. ──
        scrubHrArtifacts(now - 24 * 3_600_000L, now)

        // ── HRV proxy + stress, at most one row per tick interval ──
        val recentHrv = db.measurementDao()
            .range(MeasurementKind.HRV.name, now - TICK_MS + 60_000, now)
        if (recentHrv.none { it.sourceRaw == SOURCE_DERIVED }) {
            val window = db.measurementDao()
                .range(MeasurementKind.HEART_RATE.name, now - HRV_WINDOW_MS, now)
                .map { it.value.toInt() }
            // Only write physiologically-plausible HRV. A window of near-constant
            // (heavily-smoothed) HR yields SDNN≈0, and a single stray interval yields
            // an absurd value — both are computation artifacts, not real HRV. Real
            // sleeping SDNN sits ~20-200ms. (2026-07-06: overnight HRV was pocked with
            // 0.0 and 10.5 dropouts from flat-HR windows.)
            sdnnProxy(window)?.takeIf { it in 20.0..200.0 }?.let { sdnn ->
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
        if (hour in 5..20) {
            val lastNightDay = com.pulseloop.util.TimeUtil.startOfTodayLocal()
            deriveSleepIfMissing(lastNightDay)
            deriveFatigueOncePerDay(lastNightDay, now)
        }
        // ── Screening-only apnea/ODI check: narrower morning window since it
        // wants a completed night (22:00 prev → 10:00 today) already in Room. ──
        if (hour in 10..14) {
            runApneaScreenOncePerDay(com.pulseloop.util.TimeUtil.startOfTodayLocal(), now)
        }
    }

    /**
     * Button-bounded sleep: the user tapped Start when getting in bed and Stop on
     * waking, so [startMs, endMs] IS the sleep window — no HR-guessing of the bounds
     * (which counted lying-quietly-awake before button press). Total sleep and
     * efficiency are still computed from HR within it. Attributed to the wake day.
     */
    suspend fun deriveSleepForWindow(startMs: Long, endMs: Long) {
        if (endMs - startMs < MIN_SLEEP_MINUTES * 60_000L) return
        val dayStart = com.pulseloop.util.TimeUtil.startOfDayLocal(endMs)
        val hr = db.measurementDao().range(MeasurementKind.HEART_RATE.name, startMs, endMs)
        if (hr.size < MIN_NIGHT_SAMPLES) return
        val buckets = hr.groupBy { (it.timestamp - startMs) / BUCKET_MS }
            .mapValues { (_, rows) -> rows.map { it.value }.medianOrNull() }
        val nightLow = buckets.values.filterNotNull().percentileOrNull(0.05) ?: return
        val thresh = nightLow * SLEEP_HR_MARGIN
        val maxB = (endMs - startMs) / BUCKET_MS
        val motion = stepMotionSet(startMs, endMs, startMs)  // buckets you were up and walking
        val asleep = { i: Long -> buckets[i]?.let { it <= thresh } == true && i !in motion }
        val has = { i: Long -> buckets[i] != null }
        val total = (0..maxB).count { asleep(it) } * 10
        if (total < MIN_SLEEP_MINUTES) return
        val awake = (0..maxB).count { !asleep(it) && has(it) } * 10
        val eff = (100 * total / (total + awake).coerceAtLeast(1)).coerceIn(0, 100)
        writeSleep("derived-$dayStart", dayStart, startMs, endMs, total, eff, startMs, nightLow, buckets, 0..maxB, asleep, has)
    }

    /** 10-min bucket indices (relative to baseMs) where the step count shows the user
     *  was up and walking — a bathroom trip is real out-of-bed wake, distinct from an
     *  in-bed HR spike (restless but still lying down). Overrides HR: a step bucket is
     *  never scored asleep. */
    private suspend fun stepMotionSet(startMs: Long, endMs: Long, baseMs: Long): Set<Long> {
        val steps = db.measurementDao().range(MeasurementKind.STEPS.name, startMs, endMs)
        if (steps.isEmpty()) return emptySet()
        val perBucket = HashMap<Long, Int>()
        for (s in steps) {
            val b = (s.timestamp - baseMs) / BUCKET_MS
            perBucket[b] = (perBucket[b] ?: 0) + s.value.toInt()
        }
        return perBucket.filterValues { it >= STEP_WAKE_THRESHOLD }.keys
    }

    /** Estimate last night's sleep from sustained low overnight HR (automatic path). */
    private suspend fun deriveSleepIfMissing(dayStart: Long) {
        if (db.sleepSessionDao().byDay(dayStart) != null) return  // ring or prior estimate won

        val windowStart = dayStart - 4 * 3_600_000L               // 20:00 yesterday
        val windowEnd = dayStart + 12 * 3_600_000L                // noon today
        val hr = db.measurementDao().range(MeasurementKind.HEART_RATE.name, windowStart, windowEnd)
        if (hr.size < MIN_NIGHT_SAMPLES) return

        // 10-min buckets.
        val buckets = hr.groupBy { (it.timestamp - windowStart) / BUCKET_MS }
            .mapValues { (_, rows) -> rows.map { it.value }.medianOrNull() }
        val bucketVals = buckets.values.filterNotNull()
        val nightLow = bucketVals.percentileOrNull(0.05) ?: return

        // Asleep = HR within 15% of the night's own floor. This single self-anchored
        // rule is robust to both failure modes the daytime-baseline approach hit:
        // (a) no daytime HR when the ring wasn't worn during the day made the estimate
        // bail entirely, and (b) an all-sleep tracked window (went to bed before
        // tracking start) collapsed the derived ceiling BELOW the sleeping HR, scoring
        // zero minutes. Anchoring only to nightLow avoids both — awake HR sits well
        // above nightLow*1.15, so it's excluded without needing an explicit ceiling.
        // (2026-07-14: a perfectly-tracked 8h night was producing no session at all.)
        val asleepThresh = nightLow * SLEEP_HR_MARGIN
        val maxBucket = (windowEnd - windowStart) / BUCKET_MS
        val motion = stepMotionSet(windowStart, windowEnd, windowStart)  // out-of-bed buckets
        val asleep = { i: Long -> buckets[i]?.let { it <= asleepThresh } == true && i !in motion }
        val hasData = { i: Long -> buckets[i] != null }

        // Find the SLEEP WINDOW as the longest run bounded by sleep, tolerating up to
        // WAKE_TOLERANCE_BUCKETS consecutive awake buckets (mid-sleep wakes) — so a
        // fragmented night (e.g. restless 1-3am between two blocks) merges into ONE
        // window instead of reporting only the longest unbroken stretch. A wake longer
        // than that ends the window (you got up). Total sleep is then the actual
        // asleep buckets INSIDE the window, not the window length — the standard
        // "time in bed" vs "total sleep time" distinction. (2026-07-15: a 6-7h
        // fragmented night was reported as 3h10m — its single longest run.)
        var winStart = -1L; var winEnd = -1L; var bestSleep = 0L
        var runStart = -1L; var runLastSleep = -1L; var gap = 0; var runSleep = 0L
        for (i in 0..maxBucket) {
            when {
                asleep(i) -> {
                    if (runStart < 0) runStart = i
                    runLastSleep = i; runSleep++; gap = 0
                }
                hasData(i) && runStart >= 0 -> {
                    if (++gap > WAKE_TOLERANCE_BUCKETS) {
                        if (runSleep > bestSleep) { bestSleep = runSleep; winStart = runStart; winEnd = runLastSleep }
                        runStart = -1; runSleep = 0; gap = 0
                    }
                }
                // data gap (ring off): don't count as wake, just don't extend sleep
            }
        }
        if (runStart >= 0 && runSleep > bestSleep) { bestSleep = runSleep; winStart = runStart; winEnd = runLastSleep }
        if (winStart < 0) return

        val startAt = windowStart + winStart * BUCKET_MS
        val endAt = windowStart + (winEnd + 1) * BUCKET_MS
        val totalSleepMin = (bestSleep * BUCKET_MS / 60_000).toInt()
        if (totalSleepMin < MIN_SLEEP_MINUTES) return
        val awakeMin = (winStart..winEnd).count { !asleep(it) && hasData(it) } * (BUCKET_MS / 60_000).toInt()
        val efficiency = (100 * totalSleepMin / (totalSleepMin + awakeMin).coerceAtLeast(1)).coerceIn(0, 100)
        writeSleep("derived-$dayStart", dayStart, startAt, endAt, totalSleepMin, efficiency,
            windowStart, nightLow, buckets, winStart..winEnd,
            { asleep(it) }, { hasData(it) })
    }

    /** Shared session + stage-block writer for both the auto and button-bounded paths.
     *  Efficiency is sleep/(sleep+confirmed-awake); gap buckets emit no block so the
     *  hypnogram shows real gaps rather than fake LIGHT sleep. */
    private suspend fun writeSleep(
        sessionId: String, dayStart: Long, startAt: Long, endAt: Long,
        totalMin: Int, efficiency: Int, bucketBaseMs: Long, nightLow: Double,
        buckets: Map<Long, Double?>, range: LongRange,
        asleep: (Long) -> Boolean, has: (Long) -> Boolean,
    ) {
        db.sleepSessionDao().upsert(SleepSessionEntity(
            id = sessionId, date = dayStart, startAt = startAt, endAt = endAt,
            totalMinutes = totalMin, score = efficiency,
            syncedAt = System.currentTimeMillis(),
        ))
        db.sleepStageBlockDao().deleteBySession(sessionId)
        fun stageOf(i: Long): String? = when {
            !has(i) -> null
            !asleep(i) -> "AWAKE"
            buckets[i]?.let { it <= nightLow * 1.05 } == true -> "DEEP"
            else -> "LIGHT"
        }
        var blockStart = range.first; var blockStage: String? = null
        for (i in range.first..(range.last + 1)) {
            val stg = if (i <= range.last) stageOf(i) else null
            if (stg != blockStage) {
                if (blockStage != null) {
                    val bs = bucketBaseMs + blockStart * BUCKET_MS
                    db.sleepStageBlockDao().insert(com.pulseloop.data.entity.SleepStageBlockEntity(
                        sessionId = sessionId, startAt = bs,
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
        runApneaScreen(dayStart, dayStart + 11 * 3_600_000L)  // fixed-window fallback
    }

    /**
     * Analyze an explicit window and write the screening memory. Called by the
     * morning timer (fixed window) AND — the primary path — by SleepStreamController
     * on unplug, with the exact plug-in→wake span it just streamed. The per-date
     * memory key makes both idempotent; the unplug call, arriving first with the
     * denser and better-bounded data, wins.
     */
    suspend fun runApneaScreen(windowStart: Long, windowEnd: Long) {
        val dateKey = java.time.Instant.ofEpochMilli(windowStart)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val memoryKey = "sleep_apnea_screen_$dateKey"
        if (db.coachMemoryDao().byKey(memoryKey) != null) return

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
        /** Asleep = bucket HR within this factor of the night's 5th-pct floor. */
        private const val SLEEP_HR_MARGIN = 1.15
        /** Mid-sleep wake tolerated before the window ends (120min: bridges a long
         *  restless middle / biphasic gap; a real morning wake+activity exceeds it). */
        private const val WAKE_TOLERANCE_BUCKETS = 12
        /** Steps in a 10-min bucket that mean out-of-bed walking (vs a hand twitch). */
        private const val STEP_WAKE_THRESHOLD = 15
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
