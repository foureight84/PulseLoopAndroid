package com.pulseloop.service

/**
 * NON-DIAGNOSTIC screening analysis over a night's SpO2 + HR series, looking for the
 * two consumer-observable proxies for sleep-disordered breathing: repeated oxygen
 * desaturations (an ODI-style count) and cyclic variation of heart rate (CVHR) —
 * a heart-rate spike coincident with a desaturation, the bradycardia-then-arousal
 * signature of an apnea event. This is a screening estimate from a consumer ring's
 * PPG sensor, not a diagnostic tool: real polysomnography measures airflow, effort,
 * and arterial SpO2 at much higher fidelity. Never surface a result without pairing
 * it with that disclaimer.
 */
data class ApneaScreenResult(
    val spo2SampleCount: Int,
    val hrSampleCount: Int,
    val desaturations3pct: Int,
    val desaturations4pct: Int,
    val odi: Double,               // 3%-desaturation events per hour of analyzed span
    val hrSpikesCoincident: Int,
    val dataQuality: String,       // "insufficient" | "sparse" | "good"
    val note: String,
)

/** Rolling baseline lookback: recent plateau SpO2 is assumed stable over ~2 min. */
private const val BASELINE_WINDOW_MS = 2 * 60_000L
/** One dip shouldn't be double-counted while it wobbles near its nadir before recovering. */
private const val MIN_EVENT_SEPARATION_MS = 10_000L
/** A desaturation "recovers" once SpO2 climbs back within 1 point of its event baseline. */
private const val RECOVERY_MARGIN = 1
/** CVHR search window: HR spike within +/-60s of the desaturation nadir. */
private const val CVHR_WINDOW_MS = 60_000L
/** Local HR baseline for spike detection: median over the same +/-2min window used for SpO2. */
private const val HR_MEDIAN_WINDOW_MS = 2 * 60_000L
private const val HR_SPIKE_BPM = 6

/**
 * Detects desaturation events in a SpO2 series and returns each event's nadir timestamp.
 *
 * Baseline rule: for each sample, the baseline is the MAX SpO2 seen in the preceding
 * ~2 minutes (a running max, not a running mean) — SpO2 sits at a stable plateau between
 * events, and the max of a short recent window approximates that plateau without needing
 * future samples (works for a live, real-time stream, not just after-the-fact analysis).
 * A running mean would be dragged down mid-dip and understate the true drop.
 *
 * An event opens when a sample falls >= [dropPct] below its baseline, tracks the lowest
 * value seen (the nadir) while depressed, and closes once SpO2 recovers to within
 * [RECOVERY_MARGIN] of the baseline that opened it. Consecutive nadirs closer than
 * [MIN_EVENT_SEPARATION_MS] apart collapse into one event so a single wobbling dip isn't
 * counted twice.
 */
fun detectDesaturations(spo2: List<Pair<Long, Int>>, dropPct: Int): List<Long> {
    val sorted = spo2.sortedBy { it.first }
    if (sorted.size < 2) return emptyList()

    val nadirs = mutableListOf<Long>()
    var inEvent = false
    var eventBaseline = 0
    var nadirValue = Int.MAX_VALUE
    var nadirTime = 0L
    var lastNadirTime = Long.MIN_VALUE / 2

    for ((t, v) in sorted) {
        val windowStart = t - BASELINE_WINDOW_MS
        val baseline = sorted
            .filter { it.first in windowStart until t }
            .maxOfOrNull { it.second } ?: v

        if (!inEvent) {
            if (baseline - v >= dropPct) {
                inEvent = true
                eventBaseline = baseline
                nadirValue = v
                nadirTime = t
            }
        } else {
            if (v < nadirValue) {
                nadirValue = v
                nadirTime = t
            }
            if (v >= eventBaseline - RECOVERY_MARGIN) {
                if (nadirTime - lastNadirTime >= MIN_EVENT_SEPARATION_MS) {
                    nadirs.add(nadirTime)
                    lastNadirTime = nadirTime
                }
                inEvent = false
            }
        }
    }
    // A dip still underway when the data ends never "recovers" — still count it; it
    // happened, and hiding it would understate the night's true event count.
    if (inEvent && nadirTime - lastNadirTime >= MIN_EVENT_SEPARATION_MS) {
        nadirs.add(nadirTime)
    }
    return nadirs
}

/**
 * Counts desaturation nadirs that coincide with a heart-rate spike (>= +[HR_SPIKE_BPM]
 * bpm above the local ~2-min median) within +/-[CVHR_WINDOW_MS] of the nadir — the
 * CVHR (cyclic variation of heart rate) signature of an arousal following an apnea.
 */
fun countCoincidentHrSpikes(desatNadirs: List<Long>, hr: List<Pair<Long, Int>>): Int {
    if (hr.isEmpty() || desatNadirs.isEmpty()) return 0
    val sorted = hr.sortedBy { it.first }

    var count = 0
    for (nadir in desatNadirs) {
        val medianWindow = sorted
            .filter { it.first in (nadir - HR_MEDIAN_WINDOW_MS)..(nadir + HR_MEDIAN_WINDOW_MS) }
            .map { it.second.toDouble() }
        val median = medianWindow.medianOrNull() ?: continue

        val spiked = sorted.any {
            it.first in (nadir - CVHR_WINDOW_MS)..(nadir + CVHR_WINDOW_MS) && it.second - median >= HR_SPIKE_BPM
        }
        if (spiked) count++
    }
    return count
}

/**
 * Assembles the full screening result over an analyzed span [spanStartMs, spanEndMs).
 * Quality gate: "insufficient" when there simply isn't enough data to mean anything
 * (fewer than 10 SpO2 samples, or under 2h of span) — counts still compute (so callers
 * can inspect them) but the note is explicit that they shouldn't be trusted. "sparse"
 * covers a night of occasional spot SpO2 readings (median gap > 5 min — not a live
 * stream); "good" is a denser night (median gap <= ~4 min) spanning at least 2h.
 */
fun buildApneaScreenResult(
    spo2: List<Pair<Long, Int>>,
    hr: List<Pair<Long, Int>>,
    spanStartMs: Long,
    spanEndMs: Long,
): ApneaScreenResult {
    val spo2Sorted = spo2.sortedBy { it.first }
    val hrSorted = hr.sortedBy { it.first }

    val nadirs3 = detectDesaturations(spo2Sorted, 3)
    val nadirs4 = detectDesaturations(spo2Sorted, 4)
    val coincident = countCoincidentHrSpikes(nadirs3, hrSorted)

    val spanHours = (spanEndMs - spanStartMs).coerceAtLeast(0).toDouble() / 3_600_000.0
    val odi = if (spanHours > 0) nadirs3.size / spanHours else 0.0

    val medianGapMs = spo2Sorted.zipWithNext { a, b -> (b.first - a.first).toDouble() }.medianOrNull()

    val quality = when {
        spo2Sorted.size < 10 || spanHours < 2.0 -> "insufficient"
        medianGapMs != null && medianGapMs <= 4 * 60_000.0 -> "good"
        else -> "sparse"
    }

    val note = when (quality) {
        "insufficient" ->
            "Not enough overnight data to estimate an oxygen desaturation index tonight. " +
                "Screening estimate only, not a medical assessment."
        "sparse" ->
            "Overnight readings were infrequent, so this ODI is a rough screening estimate, " +
                "not a medical assessment."
        else ->
            "Screening estimate from consumer sensors, not a medical assessment. " +
                "Discuss persistent patterns with a clinician."
    }

    return ApneaScreenResult(
        spo2SampleCount = spo2Sorted.size,
        hrSampleCount = hrSorted.size,
        desaturations3pct = nadirs3.size,
        desaturations4pct = nadirs4.size,
        odi = odi,
        hrSpikesCoincident = coincident,
        dataQuality = quality,
        note = note,
    )
}
