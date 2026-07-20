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
 *    within a few beats. A majority of the window must agree ([band], [majority]) or we report nothing:
 *    a heart rate the user has no reason to doubt, but shouldn't trust, is worse than an honest retry.
 */
class HRSampleWindow(private val clock: () -> Long = System::currentTimeMillis) {
    /** Discard window for the cached echo described above. */
    private val warmupMs = 5_000L
    /** A gap this long between collected samples means we've stopped getting real data (ring slipped). */
    private val contactGapMs = 3_000L
    private val minSamples = 6
    private val band = 8            // bpm neighbourhood around the median
    private val majority = 0.6      // this much of the window must sit inside that band

    private var startedAt: Long? = null
    private val samples = mutableListOf<Int>()
    private var lastSampleAt: Long? = null

    /**
     * True once a *real* (post-warm-up) reading has landed — which is what distinguishes a fresh
     * measurement from the stale live value still on screen from the last one.
     */
    val receivedReading: Boolean get() = samples.isNotEmpty()

    fun begin(now: Long = clock()) {
        startedAt = now
        samples.clear()
        lastSampleAt = null
    }

    /** Collect a sample, unless it's still inside the warm-up echo. */
    fun collect(bpm: Int, now: Long = clock()) {
        val started = startedAt ?: return
        if (now - started < warmupMs) return
        samples.add(bpm)
        lastSampleAt = now
    }

    /**
     * Contact lost: readings had begun, and then stopped arriving. Never true during the warm-up,
     * since nothing has been collected yet.
     */
    fun contactLost(now: Long = clock()): Boolean {
        val last = lastSampleAt ?: return false
        return now - last > contactGapMs
    }

    /**
     * The settled reading: the median of the samples that agree with each other — or null if they
     * never did.
     */
    val stableValue: Int?
        get() {
            if (samples.size < minSamples) return null
            val sorted = samples.sorted()
            val median = sorted[sorted.size / 2]
            val cluster = sorted.filter { abs(it - median) <= band }   // stays sorted
            if (cluster.size < samples.size * majority) return null
            return cluster[cluster.size / 2]
        }
}
