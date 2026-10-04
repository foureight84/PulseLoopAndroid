package com.pulseloop.service

import com.pulseloop.ring.SpotVital

/**
 * The streamed values of one on-demand HRV / stress / temperature measurement, and the rule for
 * when they have settled into a reading.
 *
 * ## The vendor's rule
 *
 * QRing's three measure screens (`HrvActivity`, `DayPressureFragment`, `TemperatureActivity` in
 * decompiled-qring-official/) share one shape: a 75 s countdown, then every value above zero
 * replaces the one on screen and re-posts the finishing step one second ahead
 * (`removeCallbacks` + `postDelayed(…, 1000)`). The measurement therefore ends **one second after
 * the ring stops streaming**, on the last value it streamed.
 *
 * A Ring 2 Pro capture shows the ring doing its part: after ~25 s of zero-valued warm-up frames it
 * streams a value about every 0.5 s for a few seconds (HRV: eleven values over 5.5 s), then goes
 * quiet, and QRing stops ~1.2 s later. The ring's `0x6A` stop acknowledgement echoes that same last
 * value — so the last sample is also the ring's own answer, the agreement issue #59 settled on for
 * SpO₂.
 */
class SpotVitalWindow {
    private val lock = Any()
    private var vital: SpotVital? = null
    private var last: Double? = null
    private var lastAtMs = 0L

    fun begin(vital: SpotVital) = synchronized(lock) {
        this.vital = vital
        last = null
        lastAtMs = 0L
    }

    /** Keep [value] if it belongs to the run in flight and is plausible; returns whether it was kept. */
    fun collect(vital: SpotVital, value: Double, atMs: Long): Boolean = synchronized(lock) {
        if (vital != this.vital || value !in plausible(vital)) return false
        last = value
        lastAtMs = atMs
        true
    }

    val receivedReading: Boolean get() = synchronized(lock) { last != null }

    /** The last plausible value streamed, or null if none arrived. */
    val settled: Double? get() = synchronized(lock) { last }

    /** True once a value has arrived and the ring has been quiet for [QUIET_MS] since. */
    fun isSettled(nowMs: Long): Boolean = synchronized(lock) {
        last != null && nowMs - lastAtMs >= QUIET_MS
    }

    companion object {
        /** QRing's `postDelayed(…, 1000)`. */
        const val QUIET_MS = 1_000L

        /** The same bands `RingEventBridge` applies to the stored kinds. */
        fun plausible(vital: SpotVital): ClosedFloatingPointRange<Double> = when (vital) {
            SpotVital.HRV -> 1.0..300.0
            SpotVital.STRESS -> 1.0..100.0
            SpotVital.TEMPERATURE -> 30.0..45.0
        }
    }
}
