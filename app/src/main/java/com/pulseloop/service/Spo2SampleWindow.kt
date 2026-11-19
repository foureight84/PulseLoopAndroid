package com.pulseloop.service

/**
 * The SpO₂ samples of one spot measurement, and the rule for turning them into a reading
 * (issue #59, RC-1 and RC-2 feedback).
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
 * ## Why the last plausible sample
 *
 * RC-2 shipped a median because one capture could not say whether the peak or the tail of a run
 * was the honest number. Three independent sources then agreed on the answer (issue #59, RC-2
 * feedback):
 *
 *  * **The ring's own log.** These rings write each spot reading into their history. Read back
 *    against the raw streams of five captures, the stored value equalled the **last** streamed
 *    sample five times out of five; the median matched four (it parted company on a run ending
 *    97×3, 99, 99, 98×3, 99 — ring 99, median 98).
 *  * **A collapsing run is a bad measurement, not a rule failure.** The one run whose late burst
 *    fell from 98 to 86–87 is stored by the ring as 87. There was no eleven-point error for a
 *    settle rule to avoid; the ring itself calls that run 87.
 *  * **The vendor app does not settle at all.** `BloodOxygenMeasureActivity.onEvent`
 *    (`com.zhuoting.healthyucheng` 1.27.96) overwrites the on-screen value with every realtime
 *    frame, dropping only zero and anything outside `BLOOD_OXYGEN_VISIBLE_MIN..MAX` (70..100), and
 *    on `04 0e` success re-reads the ring's history rather than deciding a number itself.
 *
 * So the ring decides, and the app's job is to agree with it: the reading is the last sample the
 * ring streamed, filtered by the vendor's plausibility band and nothing else. Anything cleverer
 * disagrees with what the ring will log — which is exactly the doubled, slightly-different
 * readings issue #60's tester saw.
 */
class Spo2SampleWindow {
    private val samples = mutableListOf<Int>()

    /** True once any plausible reading has landed — distinguishes a fresh measurement from a stale
     *  value. */
    val receivedReading: Boolean get() = synchronized(samples) { samples.isNotEmpty() }

    fun begin() {
        synchronized(samples) { samples.clear() }
    }

    /**
     * Collect a sample. Returns false — and keeps nothing — when it is outside the vendor's
     * plausibility band, so a zero or a dropout can neither become the reading nor mark the
     * measurement as having read something.
     */
    fun collect(percent: Int): Boolean {
        if (percent !in PLAUSIBLE) return false
        synchronized(samples) { samples.add(percent) }
        return true
    }

    /** The settled reading: the last plausible sample the ring streamed, or null if there was none. */
    val settled: Int?
        get() = synchronized(samples) { samples.lastOrNull() }

    companion object {
        /** The vendor's `BLOOD_OXYGEN_VISIBLE_MIN..MAX`; also the band every other decoder in this
         *  app already applies to a live SpO₂ value. */
        val PLAUSIBLE: IntRange = 70..100
    }
}
