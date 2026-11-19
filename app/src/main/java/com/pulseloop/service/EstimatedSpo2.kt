package com.pulseloop.service

import com.pulseloop.data.dao.MeasurementDao
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.ring.MeasurementKind

/**
 * Hourly SpO₂ that a ring computes from heart rate rather than measures (issue #103).
 *
 * On a YCBT ring (`Ale-Hop2211`, COLMI_SMART_HEALTH) the reporter read all 304 hourly `05 18`
 * composite records out of the ring's memory and the same heart rate always came with the same SpO₂,
 * without one exception: HR 48 → 88, 57 → 94, 67–92 → 98, 105 → 96, never 99. That is a fixed
 * function of heart rate, not a second sensor, and the night "dips" were just a low sleeping heart
 * rate. A spot SpO₂ on the same ring is a real measurement (red LED, values up to 99), and the ring
 * logs it in the same `05 18` block with heart rate and respiration both 0.
 *
 * **Detected from the data, not from the family.** A capability list is a static per-family
 * constant and says nothing about an individual ring (issue #67); another ring in the family may well
 * measure hourly SpO₂ for real, and calling that "estimated" would be a false statement about the
 * user's hardware. So the verdict is earned per ring, from what it actually logged.
 *
 * ## What is stored, and why the rule has this shape
 *
 * `YCBTHealthRecords.combinedVitals` writes a `05 18` record's SpO₂ (offset 9) and respiratory rate
 * (offset 10) as two history rows with the record's own timestamp. It does **not** store the record's
 * heart rate (offset 6) — HR history comes from `05 15`, at that log's own timestamps. Hence:
 *
 * - **Hourly vs spot** is decided by the respiratory row: an SpO₂ history row with a respiratory
 *   history row at the *identical* timestamp came from a composite record that carried more than
 *   SpO₂. A spot record logs respiration 0, which the decoder never stores, so a spot reading can
 *   never qualify — not by threshold, by construction.
 * - **"A function of heart rate"** is tested by pairing each hourly row with the nearest HR history
 *   row within [PAIR_WINDOW_MS], and asking whether any heart rate ever came with two different SpO₂
 *   values. A real oximeter, sampled at the same heart rate twice, disagrees with itself sooner or
 *   later; this ring never did across 304 records, so the test allows no exceptions.
 *
 * Nothing is labelled until the evidence is there, for the same reason derived stress refuses to
 * score below its baseline: an unearned label on a health screen is a claim about the hardware.
 */
object EstimatedSpo2 {

    /**
     * How far an HR history row may sit from an hourly SpO₂ row and still be the same measurement.
     * The `05 18` records land ~35 s after the hour (the end of the ring's ~35 s HR measurement), and
     * the ring runs one measurement at a time, so a neighbour inside two minutes is that measurement.
     */
    const val PAIR_WINDOW_MS = 2 * 60_000L

    /** A day of hourly records — fewer, and a constant-looking stretch is a coincidence. */
    const val MIN_PAIRS = 24

    /**
     * Pairs whose heart rate had already been seen. These are the only pairs that *test* the
     * mapping; a heart rate seen once is trivially consistent with itself.
     */
    const val MIN_REPEATS = 12

    /** How much history the verdict is drawn from: wider than a chart's 24 h, so one quiet day
     *  doesn't decide it, and bounded so a previously paired ring ages out of it. */
    const val LOOKBACK_MS = 7 * 86_400_000L

    /** One row reduced to what the rule reads. */
    data class Sample(val timestampMs: Long, val value: Double)

    /**
     * The SpO₂ rows that came from an hourly composite record: those with a respiratory-rate row at
     * the identical timestamp. Spot records (respiration 0) are excluded by construction.
     */
    fun hourly(spo2: List<Sample>, respiratoryTimestamps: Set<Long>): List<Sample> =
        spo2.filter { it.timestampMs in respiratoryTimestamps }

    /**
     * Each hourly row paired with the nearest heart-rate sample within [PAIR_WINDOW_MS], as
     * (heart rate, SpO₂) whole numbers. Rows with no heart rate close enough are left out.
     */
    fun pairs(hourly: List<Sample>, heartRate: List<Sample>): List<Pair<Int, Int>> {
        if (hourly.isEmpty() || heartRate.isEmpty()) return emptyList()
        val hr = heartRate.sortedBy { it.timestampMs }
        val times = hr.map { it.timestampMs }
        return hourly.mapNotNull { s ->
            val at = times.binarySearch(s.timestampMs).let { if (it >= 0) it else -it - 1 }
            val nearest = listOfNotNull(hr.getOrNull(at - 1), hr.getOrNull(at))
                .minByOrNull { kotlin.math.abs(it.timestampMs - s.timestampMs) }
                ?.takeIf { kotlin.math.abs(it.timestampMs - s.timestampMs) <= PAIR_WINDOW_MS }
                ?: return@mapNotNull null
            nearest.value.toInt() to s.value.toInt()
        }
    }

    /**
     * Whether [pairs] (heart rate, SpO₂) show SpO₂ as a fixed function of heart rate.
     *
     * Requires enough pairs, enough repeated heart rates to actually test the mapping, more than one
     * SpO₂ value (a constant reading is a function of anything, and says nothing about where it came
     * from), and no heart rate ever paired with two different SpO₂ values.
     */
    fun derivesFromHeartRate(pairs: List<Pair<Int, Int>>): Boolean {
        if (pairs.size < MIN_PAIRS) return false
        val byHr = pairs.groupBy({ it.first }, { it.second })
        if (pairs.size - byHr.size < MIN_REPEATS) return false
        if (pairs.map { it.second }.distinct().size < 2) return false
        return byHr.values.all { it.distinct().size == 1 }
    }

    /**
     * The timestamps of the [spo2] rows to label as estimated: the hourly ones, and only when the
     * hourly rows in [evidence] (with [evidenceHeartRate]) show the ring deriving them from heart rate.
     */
    fun estimated(
        spo2: List<Sample>,
        respiratoryTimestamps: Set<Long>,
        evidence: List<Sample>,
        evidenceRespiratoryTimestamps: Set<Long>,
        evidenceHeartRate: List<Sample>,
    ): Set<Long> {
        val evidencePairs = pairs(hourly(evidence, evidenceRespiratoryTimestamps), evidenceHeartRate)
        if (!derivesFromHeartRate(evidencePairs)) return emptySet()
        return hourly(spo2, respiratoryTimestamps).mapTo(mutableSetOf()) { it.timestampMs }
    }

    /**
     * [estimated] over Room: the SpO₂ history rows in [from]..[to] that this ring computed from heart
     * rate, judged on the last [LOOKBACK_MS] of its history. Reads only — nothing stored is changed.
     */
    suspend fun estimatedTimestamps(
        dao: MeasurementDao,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis(),
    ): Set<Long> {
        val spo2 = dao.range(MeasurementKind.SPO2.name, from, to).historySamples()
        if (spo2.isEmpty()) return emptySet()
        val since = now - LOOKBACK_MS
        val evidence = dao.range(MeasurementKind.SPO2.name, since, now).historySamples()
        val respiratory = dao.timestampsBySource(MeasurementKind.RESPIRATORY_RATE.name, HISTORY_SOURCE, minOf(from, since))
            .toSet()
        val heartRate = dao.range(MeasurementKind.HEART_RATE.name, since - PAIR_WINDOW_MS, now + PAIR_WINDOW_MS)
            .historySamples()
        return estimated(spo2, respiratory, evidence, respiratory, heartRate)
    }

    /** The ring's own log only: a live or spot row is the app's reading, never a composite record. */
    private const val HISTORY_SOURCE = "history"

    private fun List<MeasurementEntity>.historySamples(): List<Sample> =
        filter { it.sourceRaw == HISTORY_SOURCE }.map { Sample(it.timestamp, it.value) }
}
