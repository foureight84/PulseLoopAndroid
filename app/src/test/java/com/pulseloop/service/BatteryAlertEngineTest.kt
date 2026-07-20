package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure crossing-engine tests for the ring low-battery alerts (iOS #61a) — no DB, no notifications.
 * State is threaded through consecutive `evaluate()` calls the way the monitor drives it on the
 * sparse battery stream. Ported 1:1 from PulseLoopTests/BatteryAlertEngineTests.swift.
 */
class BatteryAlertEngineTest {
    private val today = "2026-07-08"

    private data class Run(val alerts: List<BatteryAlertKind?>, val state: BatteryAlertState)

    /** Run a sequence of percents through the engine, returning the alert (if any) for each. */
    private fun run(
        percents: List<Int>,
        dateKey: String = today,
        from: BatteryAlertState = BatteryAlertState(),
    ): Run {
        var state = from
        val alerts = mutableListOf<BatteryAlertKind?>()
        for (p in percents) {
            val (alert, next) = BatteryAlertEngine.evaluate(p, state, dateKey)
            alerts.add(alert)
            state = next
        }
        return Run(alerts, state)
    }

    @Test
    fun lowFiresOnceOnCrossing() {
        val alerts = run(listOf(22, 18, 17)).alerts
        assertNull(alerts[0])                                     // above threshold
        assertEquals(BatteryAlertKind.Low(18), alerts[1])        // first below-20
        assertNull(alerts[2])                                     // still low → latched, no re-fire
    }

    @Test
    fun straightDropFiresOnlyCritical() {
        val alerts = run(listOf(25, 8, 15)).alerts
        assertNull(alerts[0])                                     // above everything
        assertEquals(BatteryAlertKind.Critical(8), alerts[1])    // most-severe only, low latched too
        assertNull(alerts[2])                                     // 15 < 20 but low stays latched
    }

    @Test
    fun firstEverSampleBelowThresholdFires() {
        // A fresh state whose first observed sample is already below 20 (jring "connected at 17%").
        val alerts = run(listOf(17)).alerts
        assertEquals(BatteryAlertKind.Low(17), alerts[0])
    }

    @Test
    fun hysteresisReArmsOnRecharge() {
        val alerts = run(listOf(18, 22, 26, 19)).alerts
        assertEquals(BatteryAlertKind.Low(18), alerts[0])        // fires
        assertNull(alerts[1])                                     // 22: still below re-arm (25), latched
        assertNull(alerts[2])                                     // 26: >= re-arm, clears latch (no alert on a rise)
        assertEquals(BatteryAlertKind.Low(19), alerts[3])        // re-armed → fires again
    }

    @Test
    fun criticalReArmsWhileLowStaysLatched() {
        // Fire critical at 8, then rise to 16 (>= criticalRearm 15 but < lowRearm 25): critical
        // re-arms, low stays latched, then drop back below 10 re-fires only critical.
        var r = run(listOf(8))
        assertEquals(BatteryAlertKind.Critical(8), r.alerts[0])
        assertTrue(r.state.firedLow)

        r = run(listOf(16), from = r.state)
        assertNull(r.alerts[0])
        assertFalse(r.state.firedCritical)  // critical re-armed
        assertTrue(r.state.firedLow)        // low still latched (16 < lowRearm 25)

        r = run(listOf(9), from = r.state)
        assertEquals(BatteryAlertKind.Critical(9), r.alerts[0])
    }

    @Test
    fun newDateKeyResetsBothLatches() {
        val first = run(listOf(8))   // fires critical, latches both
        assertEquals(BatteryAlertKind.Critical(8), first.alerts[0])

        // Same low reading on a new day → both latches reset, so it fires again.
        val (alert, state) = BatteryAlertEngine.evaluate(18, first.state, "2026-07-09")
        assertEquals(BatteryAlertKind.Low(18), alert)
        assertEquals("2026-07-09", state.dateKey)
    }

    @Test
    fun placeholderAndOutOfRangeIgnored() {
        for (bad in listOf(0, -1, 101)) {
            val state = BatteryAlertState(dateKey = today, firedLow = false, firedCritical = false)
            val (alert, next) = BatteryAlertEngine.evaluate(bad, state, today)
            assertNull("percent $bad should not alert", alert)
            assertEquals("percent $bad should not mutate state", state, next)
        }
    }

    @Test
    fun thresholdBoundaries() {
        // Each from a fresh state: only strictly-below fires, and 10 is below-20 (low) not below-10.
        assertNull(run(listOf(20)).alerts[0])                        // 20: not below 20
        assertEquals(BatteryAlertKind.Low(19), run(listOf(19)).alerts[0])       // 19: below 20
        assertEquals(BatteryAlertKind.Low(10), run(listOf(10)).alerts[0])       // 10: below 20, not below 10
        assertEquals(BatteryAlertKind.Critical(9), run(listOf(9)).alerts[0])
    }
}
