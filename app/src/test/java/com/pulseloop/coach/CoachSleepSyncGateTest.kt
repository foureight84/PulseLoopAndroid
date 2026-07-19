package com.pulseloop.coach.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from CoachSleepGateTests + CoachSleepDataSyncedTests in
 * CoachWS3Tests.swift (iOS #65).
 */
class CoachSleepSyncGateTest {

    private val hour = 3_600_000L

    // ── sleepDataSynced (morning notification gate) ─────────────────────────

    @Test
    fun testNoSessionPasses() {
        assertTrue(CoachSleepSyncGate.sleepDataSynced(null, null, now = 0L))
    }

    @Test
    fun testNoDeviceStampPasses() {
        val now = 100 * hour
        val end = now - 8 * hour
        assertTrue(CoachSleepSyncGate.sleepDataSynced(end, null, now))
    }

    @Test
    fun testFullSyncBeforeEndFails() {
        val now = 100 * hour
        val end = now - 8 * hour
        val fullSync = end - 20 * 60_000L
        assertFalse(CoachSleepSyncGate.sleepDataSynced(end, fullSync, now))
    }

    @Test
    fun testFullSyncAfterEndPasses() {
        val now = 100 * hour
        val end = now - 8 * hour
        val fullSync = end + 30 * 60_000L
        assertTrue(CoachSleepSyncGate.sleepDataSynced(end, fullSync, now))
    }

    @Test
    fun testStaleNightPassesRegardlessOfSync() {
        val now = 100 * hour
        val end = now - 40 * hour   // older than the 36h stale window
        val fullSync = end - hour   // would otherwise fail the gate
        assertTrue(CoachSleepSyncGate.sleepDataSynced(end, fullSync, now))
    }

    // ── sleepDaySafeToSummarize (sleep-card generation gate) ────────────────

    @Test
    fun testNoFullSyncStampFallsBackToTwoHours() {
        val end = 0L
        assertFalse(CoachSleepSyncGate.sleepDaySafeToSummarize(end, null, now = 40 * 60_000L))
        assertTrue(CoachSleepSyncGate.sleepDaySafeToSummarize(end, null, now = 2 * hour + 60_000L))
    }

    @Test
    fun testFullSyncBeforeEndIsGated() {
        val end = 10 * hour
        val fullSync = end - 30 * 60_000L
        assertFalse(CoachSleepSyncGate.sleepDaySafeToSummarize(end, fullSync, now = end + 3 * hour))
    }

    @Test
    fun testFullSyncAfterEndGenerates() {
        val end = 10 * hour
        val fullSync = end + 10 * 60_000L
        assertTrue(CoachSleepSyncGate.sleepDaySafeToSummarize(end, fullSync, now = end + 45 * 60_000L))
    }

    @Test
    fun testBeforeThirtyMinuteFloorIsGated() {
        val end = 10 * hour
        val fullSync = end + 5 * 60_000L
        assertFalse(CoachSleepSyncGate.sleepDaySafeToSummarize(end, fullSync, now = end + 10 * 60_000L))
    }
}
