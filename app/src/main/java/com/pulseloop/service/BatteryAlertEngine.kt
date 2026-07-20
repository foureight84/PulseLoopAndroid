package com.pulseloop.service

import java.time.LocalDate

/**
 * Ported from BatteryAlertMonitor.swift (BatteryAlertState / BatteryAlertKind / BatteryAlertEngine)
 * in the iOS app (#61a). A pure, latched crossing engine for the ring low-battery alerts — no
 * Android dependencies, so it's unit-tested directly against the iOS BatteryAlertEngineTests oracle.
 */

/**
 * Per-day latch state for the two battery thresholds. Persisted (as three primitives) by the monitor
 * so a fired alert isn't repeated on the next battery sample of the same day.
 */
data class BatteryAlertState(
    val dateKey: String = "",
    val firedLow: Boolean = false,       // below-20 fired today
    val firedCritical: Boolean = false,  // below-10 fired today
)

sealed class BatteryAlertKind {
    abstract val percent: Int
    data class Low(override val percent: Int) : BatteryAlertKind()
    data class Critical(override val percent: Int) : BatteryAlertKind()
}

/**
 * Pure transition function: (new sample, state) -> (alert?, new state).
 *
 * Level-triggered + latched: fires when a sample is observed below a threshold that hasn't fired yet
 * — battery samples are sparse (jring reports only on connect), so an edge trigger would miss
 * "connected already at 17%".
 */
object BatteryAlertEngine {
    const val LOW_THRESHOLD = 20
    const val CRITICAL_THRESHOLD = 10

    /** Re-arm bands (threshold + 5): a recharge above these clears the latch, so bouncing
     *  19 -> 21 -> 19 can't re-fire but a real recharge re-arms. */
    const val LOW_REARM = 25
    const val CRITICAL_REARM = 15

    data class Result(val alert: BatteryAlertKind?, val state: BatteryAlertState)

    fun evaluate(percent: Int, state: BatteryAlertState, dateKey: String): Result {
        if (percent !in 1..100) return Result(null, state)   // 0 = ring's unknown placeholder
        var s = if (state.dateKey != dateKey) BatteryAlertState(dateKey = dateKey) else state
        if (percent >= LOW_REARM) s = s.copy(firedLow = false)
        if (percent >= CRITICAL_REARM) s = s.copy(firedCritical = false)
        if (percent < CRITICAL_THRESHOLD && !s.firedCritical) {
            // most severe only: 25 -> 8 fires just the critical alert, low latched too
            s = s.copy(firedCritical = true, firedLow = true)
            return Result(BatteryAlertKind.Critical(percent), s)
        }
        if (percent < LOW_THRESHOLD && !s.firedLow) {
            s = s.copy(firedLow = true)
            return Result(BatteryAlertKind.Low(percent), s)
        }
        return Result(null, s)
    }

    /** A calendar-day key (yyyy-MM-dd, device-local) so the latch resets each day. */
    fun currentDateKey(date: LocalDate = LocalDate.now()): String = date.toString()
}
