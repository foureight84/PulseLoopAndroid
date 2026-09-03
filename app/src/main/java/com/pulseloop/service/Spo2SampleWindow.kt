package com.pulseloop.service

/**
 * The SpO₂ samples of one spot measurement, and the rule for turning them into a reading
 * (issue #59, RC-1 feedback).
 *
 * ## Why this exists at all
 *
 * The SpO₂ leg used to return the **first** plausible sample and stop. On the instrumented
 * `Ale-Hop2211` capture that is 96 % at t+13 s — thirty-seven seconds before the ring finished,
 * and before nine further samples arrived (99, 98, 98, 98, 96, 96, 95, 94, 94). Whatever the right
 * answer is, "whichever sample happened to arrive first" is not it, and returning early also made
 * the ring's own `04 0e` completion unreachable for this leg.
 *
 * Note what is *not* the problem here, because it differs from heart rate: there is no cached echo
 * to discard. That ring sends nothing at all for the first 13 s, so a time-based warm-up window
 * would drop nothing and inventing one would be guessing.
 *
 * ## Why the median, and why that is provisional
 *
 * The capture rises to a peak of 99 and then *declines* to 94 — so unlike heart rate, later samples
 * are not obviously better evidence, and the tail rule that fixed HR would land on 94-95 while the
 * sensor's strongest signal was several points higher. Which of those is the honest number is an
 * open question that one capture cannot answer.
 *
 * The median is chosen precisely because it refuses to answer it: it privileges neither the peak
 * nor the tail, and it is robust to the scatter either end contributes. **Revisit this once
 * repeated captures show whether the peak-then-decline shape is consistent or was one attempt.**
 * That is the whole reason this is a separate, tested class rather than three lines inline.
 */
class Spo2SampleWindow {
    private val samples = mutableListOf<Int>()

    /** True once any reading has landed — distinguishes a fresh measurement from a stale value. */
    val receivedReading: Boolean get() = samples.isNotEmpty()

    fun begin() {
        samples.clear()
    }

    fun collect(percent: Int) {
        samples.add(percent)
    }

    /**
     * The settled reading: the median of everything collected, or null if nothing was. Even
     * ties break low (`size / 2` on a sorted even-length list), which is the conservative
     * direction for a saturation reading.
     */
    val settled: Int?
        get() {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            return sorted[(sorted.size - 1) / 2]
        }
}
