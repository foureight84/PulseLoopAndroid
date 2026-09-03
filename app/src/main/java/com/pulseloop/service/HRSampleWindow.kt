package com.pulseloop.service

import kotlin.math.abs

/**
 * The bpm samples of one spot HR measurement, and the rule for whether they add up to a reading.
 * Ported from iOS #66 (`HRSampleWindow`). Pure and unit-testable — the clock is injectable.
 *
 * Two things make a raw bpm untrustworthy, and this owns both:
 *
 *  * **The cached echo.** The ring replies with its last stored bpm the instant the manual-HR command
 *    is sent — before the sensor has read anything. Everything inside [warmupMs] is therefore dropped;
 *    without that, a measurement "succeeds" in two seconds on a number from hours ago.
 *  * **Scatter.** Finger motion and poor contact make the PPG estimate jump around instead of holding
 *    within a few beats. A majority of the considered samples must agree ([band], [majority]) or we
 *    report nothing: a heart rate the user has no reason to doubt, but shouldn't trust, is worse than
 *    an honest retry.
 *
 * ## Why the settle looks at the tail, not the whole window (issue #59)
 *
 * A dropped warm-up echo is not the same thing as a converged sensor. On the YCBT ring in #59 the
 * PPG takes ~26 s to converge, and everything before that sits on a *flat* pre-converged plateau —
 * 47 47 47, then 46 46 46, against a real rate of 81. Judged over the whole window that plateau is
 * both the majority and the most consistent thing in it, so a whole-window median returns it and
 * the user is shown a confident number that was never their heart rate.
 *
 * So the settle considers only the tail of the window: samples within [settleTailMs] of the last
 * one, and never fewer than [minSamples] of them. Later samples are strictly better evidence than
 * earlier ones on an optical sensor that is still converging, and this is the cheapest rule that
 * says so without guessing where convergence happened. It costs nothing on a ring that streams a
 * steady rate for the whole window — its tail agrees with its head.
 */
class HRSampleWindow(private val clock: () -> Long = System::currentTimeMillis) {
    /** Discard window for the cached echo described above. */
    private val warmupMs = 5_000L
    /**
     * A gap this long between collected samples means we've stopped getting real data (ring slipped).
     *
     * Sized for the burstiest cadence we've measured, not the average one: the #59 ring emits
     * samples in bursts of three about a second apart and then goes quiet for **4–6 s** before the
     * next burst. At the old 3 s this fired mid-measurement on a ring that was working perfectly,
     * aborting the leg before its sensor had even converged — which is most of why that ring could
     * never produce a reading. Raising it costs only how quickly a genuinely slipped ring is
     * noticed, and the measurement window still bounds that.
     */
    private val contactGapMs = 8_000L
    /** How far back from the newest sample the settle looks. See the class note. */
    private val settleTailMs = 12_000L
    private val minSamples = 6
    private val band = 8            // bpm neighbourhood around the median
    private val majority = 0.6      // this much of the considered samples must sit inside that band

    private data class Sample(val bpm: Int, val at: Long)

    private var startedAt: Long? = null
    private val samples = mutableListOf<Sample>()

    /**
     * True once a *real* (post-warm-up) reading has landed — which is what distinguishes a fresh
     * measurement from the stale live value still on screen from the last one.
     */
    val receivedReading: Boolean get() = samples.isNotEmpty()

    fun begin(now: Long = clock()) {
        startedAt = now
        samples.clear()
    }

    /**
     * Collect a sample. Returns false — and keeps nothing — when the sample is still inside the
     * warm-up echo, or when no measurement is running. Callers use that answer to keep the echo
     * out of the live value on screen as well as out of the settle.
     */
    fun collect(bpm: Int, now: Long = clock()): Boolean {
        val started = startedAt ?: return false
        if (now - started < warmupMs) return false
        samples.add(Sample(bpm, now))
        return true
    }

    /**
     * Contact lost: readings had begun, and then stopped arriving. Never true during the warm-up,
     * since nothing has been collected yet.
     */
    fun contactLost(now: Long = clock()): Boolean {
        val last = samples.lastOrNull() ?: return false
        return now - last.at > contactGapMs
    }

    /**
     * The settled reading: the median of the tail samples that agree with each other — or null if
     * they never did.
     */
    val stableValue: Int?
        get() {
            if (samples.size < minSamples) return null
            val considered = tail()
            val sorted = considered.sorted()
            val median = sorted[sorted.size / 2]
            val cluster = sorted.filter { abs(it - median) <= band }   // stays sorted
            if (cluster.size < considered.size * majority) return null
            return cluster[cluster.size / 2]
        }

    /** The samples the settle judges: the last [settleTailMs] of them, floored at [minSamples]. */
    private fun tail(): List<Int> {
        val newest = samples.last().at
        val byTime = samples.count { newest - it.at <= settleTailMs }
        val take = maxOf(byTime, minSamples).coerceAtMost(samples.size)
        return samples.takeLast(take).map { it.bpm }
    }
}
