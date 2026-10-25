package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #103: tests for ScheduledSpo2Worker interval computation and scheduling guards.
 */
class ScheduledSpo2WorkerTest {

    @Test
    fun `ycbt uses hr interval even when spo2 is disabled`() {
        val interval = ScheduledSpo2Worker.computeRingIntervalMinutes(
            isYcbt = true,
            hrIntervalMinutes = 120,
            spo2IntervalMinutes = 0,
            spo2Enabled = false,
        )
        // Turning off the ring's fake SpO2 toggle must not cancel the app's spot SpO2 schedule
        assertEquals(120, interval)
    }

    @Test
    fun `ycbt ignores spo2 interval and uses hr interval`() {
        val interval = ScheduledSpo2Worker.computeRingIntervalMinutes(
            isYcbt = true,
            hrIntervalMinutes = 120,
            spo2IntervalMinutes = 30,
            spo2Enabled = true,
        )
        // Ring ignores 01 26 and measures on HR cadence, so interval must be 120 to avoid false skips
        assertEquals(120, interval)
    }

    @Test
    fun `ycbt defaults to 60 min when hr interval is unset`() {
        val interval = ScheduledSpo2Worker.computeRingIntervalMinutes(
            isYcbt = true,
            hrIntervalMinutes = 0,
            spo2IntervalMinutes = 30,
            spo2Enabled = true,
        )
        assertEquals(60, interval)
    }

    @Test
    fun `non ycbt returns null when spo2 is disabled`() {
        val interval = ScheduledSpo2Worker.computeRingIntervalMinutes(
            isYcbt = false,
            hrIntervalMinutes = 60,
            spo2IntervalMinutes = 30,
            spo2Enabled = false,
        )
        assertNull(interval)
    }

    @Test
    fun `non ycbt uses spo2 interval when enabled`() {
        val interval = ScheduledSpo2Worker.computeRingIntervalMinutes(
            isYcbt = false,
            hrIntervalMinutes = 60,
            spo2IntervalMinutes = 30,
            spo2Enabled = true,
        )
        assertEquals(30, interval)
    }

    @Test
    fun `syncing and measuring lock flags default to false and are observable`() {
        assertEquals(false, RingSyncWorker.isSyncing)
        assertEquals(false, ScheduledSpo2Worker.isMeasuring)
    }
}
