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
 * ## Two settle rules, and which ring gets which (issue #59)
 *
 * **A ring that says when it has finished decides the reading itself.** On the YCBT family the
 * ring ends the measurement with `04 0e`, and the vendor app's reaction to that is `syncData()` —
 * it re-reads the value out of the ring's history rather than computing one. Its measure screen
 * (`HeartRateMeasureActivity.onEvent`, `com.yucheng.smarthealthpro`) never settles either: it
 * overwrites the displayed bpm with every realtime frame, dropping only values outside
 * `HEART_RATE_VISIBLE_MIN..MAX` (40..220). So what the user is shown, and what the ring logs, is
 * the **last plausible sample of the run**.
 *
 * The reporter on #59 established that directly rather than by inference: three spot measurements
 * captured with no stop command, each read back out of the ring's own memory before any app
 * touched it, and the stored value equalled the last streamed sample three times out of three
 * (65, 58, 72). It is a discriminating test on this ring, unlike SpO2 where tail and last coincide
 * — the rate is still climbing when the ring stops, so every tail-weighted rule lands *below* the
 * ring's answer, by as much as 18 bpm on those runs. Disagreeing with the ring is not a better
 * number, it is a second number: the ring's copy arrives on the next sync and ours yields to it
 * (issue #60), so a settle that disagrees only shows the user one value and then stores another.
 *
 * Note what this rule does *not* claim. The ring stops while the value is still rising, so its
 * stored sample is the honest answer to "which sample did the firmware choose" and not to "has
 * this converged". The second question is the firmware's to answer, and inventing a better number
 * app-side would be worse than reporting the ring's.
 *
 * **A ring that never says it is done gets the tail rule instead**, because nothing else can end
 * its window: the leg simply runs out, and "whichever sample happened to arrive as the timer
 * expired" is a coincidence rather than a choice. There, [stableValue] still judges the tail of
 * the window with a consistency gate. Judging the *whole* window is what issue #59 opened on: that
 * ring's PPG spends its first ~26 s on a flat pre-converged plateau — 47 47 47, then 46 46 46,
 * against a real rate of 81 — which is both the majority of the window and the most self-consistent
 * thing in it, so a whole-window median returned it and the user was shown a confident number that
 * was never their heart rate. The tail costs nothing on a ring that streams a steady rate
 * throughout: its tail agrees with its head.
 *
 * Widening the last-sample rule to every family would repeat the mistake rc5 had to correct for
 * the ring-copy rule — evidence gathered on one ring, generalised to rings it was never taken from.
 *
 * Samples are appended by the Main collector and judged from whichever thread runs the measuring
 * coroutine (the coach's tools poll from IO), so every member that touches [samples] is
 * synchronised.
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
    val receivedReading: Boolean get() = synchronized(samples) { samples.isNotEmpty() }

    fun begin(now: Long = clock()) {
        synchronized(samples) {
            startedAt = now
            samples.clear()
        }
    }

    /**
     * Collect a sample. Returns false — and keeps nothing — when the sample is still inside the
     * warm-up echo, or when no measurement is running. Callers use that answer to keep the echo
     * out of the live value on screen as well as out of the settle.
     */
    fun collect(bpm: Int, now: Long = clock()): Boolean {
        synchronized(samples) {
            val started = startedAt ?: return false
            if (now - started < warmupMs) return false
            samples.add(Sample(bpm, now))
            return true
        }
    }

    /**
     * Contact lost: readings had begun, and then stopped arriving. Never true during the warm-up,
     * since nothing has been collected yet.
     */
    fun contactLost(now: Long = clock()): Boolean {
        val last = synchronized(samples) { samples.lastOrNull() } ?: return false
        return now - last.at > contactGapMs
    }

    /**
     * The reading this measurement settled on. [ringChoosesLastSample] is
     * `RingSyncEngine.signalsMeasurementCompletion` — a ring that ends its own measurement is one
     * whose vendor app reads the value back out of history rather than deciding it. See the class
     * note for why those are different questions.
     */
    fun settled(ringChoosesLastSample: Boolean): Int? =
        if (ringChoosesLastSample) lastPlausible else stableValue

    /**
     * The last sample inside the vendor's visible band — what its measure screen leaves on the
     * display, and what the ring logs for itself. Null when the run produced no plausible sample
     * at all, which is a failed measurement rather than a reading of zero.
     *
     * The band is the only filter, deliberately: it keeps a trailing dropout frame from becoming
     * the reading without second-guessing a ring that is reporting a real, if unconverged, rate.
     */
    val lastPlausible: Int?
        get() = synchronized(samples) { samples.lastOrNull { it.bpm in PLAUSIBLE }?.bpm }

    /**
     * The settled reading for a ring with no completion signal: the median of the tail samples
     * that agree with each other — or null if they never did.
     */
    val stableValue: Int?
        get() {
            val considered = synchronized(samples) {
                if (samples.size < minSamples) return null
                tail()
            }
            val sorted = considered.sorted()
            val median = sorted[sorted.size / 2]
            val cluster = sorted.filter { abs(it - median) <= band }   // stays sorted
            if (cluster.size < considered.size * majority) return null
            return cluster[cluster.size / 2]
        }

    /** The samples the settle judges: the last [settleTailMs] of them, floored at [minSamples].
     *  Callers hold the [samples] lock. */
    private fun tail(): List<Int> {
        val newest = samples.last().at
        val byTime = samples.count { newest - it.at <= settleTailMs }
        val take = maxOf(byTime, minSamples).coerceAtMost(samples.size)
        return samples.takeLast(take).map { it.bpm }
    }

    companion object {
        /** The vendor's `TransUtils.HEART_RATE_VISIBLE_MIN..MAX` — the band its measure screen
         *  applies to every realtime frame before displaying it. */
        val PLAUSIBLE: IntRange = 40..220
    }
}
