package com.pulseloop.service

/**
 * A stress figure derived from HRV, for rings whose hardware never reports one (issue #67).
 *
 * The R100 answers neither the stress history query nor its monitor-state read-back — 22 sends, 0
 * replies, while every other state query on that ring answered — so on that ring stress is absent,
 * not switched off. The app still advertises it, because the capability list is a static per-family
 * constant rather than something an individual ring confirmed, and the user gets a card that can
 * never fill. That ring does return plenty of HRV, and stress is conventionally read from HRV, so
 * the number is derivable.
 *
 * **It is an inference, and it is labelled as one wherever it is shown.** Presenting a derived
 * figure as a measurement would be worse than the empty card this replaces: the ring did not
 * measure this, and a user comparing it against a friend's ring — or against the same number from
 * the vendor app — is entitled to know which of the two they are looking at.
 *
 * ## The rule
 *
 * Relative to the person, not to a population. HRV varies several-fold between individuals, so an
 * absolute cutoff would label whole people permanently stressed or permanently calm. The reading is
 * scored against that user's own recent HRV: at their own median the score is 50, and it moves
 * inversely with HRV — lower HRV than usual reads as more stress. The spread is the user's own
 * too, so someone with naturally steady HRV isn't pinned to the middle of the scale.
 */
object DerivedStress {

    /** Fewer readings than this and the baseline is not that person's normal, it is a coincidence. */
    const val MIN_BASELINE_SAMPLES = 12

    /**
     * How far from the median, in units of the baseline's own spread, maps to the ends of the
     * scale. Two is the conventional "unusual for you" distance and keeps ordinary days off 0/100 —
     * a scale that saturates says nothing on the days it matters.
     */
    private const val SPREAD_AT_FULL_SCALE = 2.0

    data class Reading(
        /** 0–100, higher meaning more stress. */
        val score: Int,
        /** Always true. Present so no caller can render this without having seen the word. */
        val derived: Boolean = true,
    )

    /**
     * A stress score for [hrv] against [baseline] (that user's recent HRV readings), or null when
     * the baseline is too thin to mean anything.
     *
     * Null rather than a default: an unearned number on a health screen is read as a measurement.
     */
    fun score(hrv: Double, baseline: List<Double>): Reading? {
        if (hrv <= 0) return null
        val usable = baseline.filter { it > 0 }
        if (usable.size < MIN_BASELINE_SAMPLES) return null

        val median = median(usable) ?: return null
        if (median <= 0) return null

        // Median absolute deviation: the spread measure that a few wild readings can't drag around,
        // which matters because ring HRV history contains obvious outliers.
        val mad = median(usable.map { kotlin.math.abs(it - median) }) ?: return null
        // A perfectly flat baseline has no spread to scale against; fall back to a proportion of
        // the median so the score still responds rather than snapping between 0 and 100.
        val spread = if (mad > 0.0) mad else median * 0.1
        if (spread <= 0.0) return null

        val deviations = (median - hrv) / spread          // positive = HRV below normal = more stress
        val scaled = 50.0 + 50.0 * (deviations / SPREAD_AT_FULL_SCALE)
        return Reading(score = scaled.coerceIn(0.0, 100.0).toInt())
    }

    /**
     * The series a chart can show: each HRV reading scored against the baseline of the readings
     * before it, so the line is what the app could have said at the time rather than hindsight.
     */
    fun series(hrv: List<Double>): List<Int> {
        val out = mutableListOf<Int>()
        for (i in hrv.indices) {
            val baseline = hrv.subList(0, i)
            score(hrv[i], baseline)?.let { out += it.score }
        }
        return out
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
