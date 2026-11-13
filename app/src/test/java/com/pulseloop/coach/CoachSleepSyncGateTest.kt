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

    // ── #140: partial-night cards ───────────────────────────────────────────

    /** Nov 9: first record ended 00:07, sync at 02:04 — Bedtime still on, so no card yet. */
    @Test
    fun testOpenBedtimeWindowDefersCard() {
        val end = 10 * hour
        val fullSync = end + 2 * hour
        assertFalse(CoachSleepSyncGate.sleepDaySafeToSummarize(end, fullSync, end + 2 * hour, nightInProgress = true))
        assertTrue(CoachSleepSyncGate.sleepDaySafeToSummarize(end, fullSync, end + 2 * hour, nightInProgress = false))
    }

    @Test
    fun testOpenBedtimeWindowDefersAtMostTheCap() {
        val end = 10 * hour
        val now = end + CoachSleepSyncGate.NIGHT_IN_PROGRESS_CAP_MS
        assertTrue(CoachSleepSyncGate.sleepDaySafeToSummarize(end, now, now, nightInProgress = true))
    }

    @Test
    fun testGrownNightRewritesInsideFloor() {
        val floor = 2 * hour
        // 63 asleep minutes at 02:04, 530 after the morning sync 30 min later.
        assertTrue(CoachSleepSyncGate.sleepCardRewriteDue("63|10|50|3|40", 0L, "530|90|380|12|75", 30 * 60_000L, floor))
    }

    @Test
    fun testShrunkOrSameLengthNightWaitsForFloor() {
        val floor = 2 * hour
        assertFalse(CoachSleepSyncGate.sleepCardRewriteDue("530|90|380|12|75", 0L, "520|90|370|12|75", hour, floor))
        assertFalse(CoachSleepSyncGate.sleepCardRewriteDue("530|90|380|12|75", 0L, "530|95|375|12|75", hour, floor))
        assertTrue(CoachSleepSyncGate.sleepCardRewriteDue("530|90|380|12|75", 0L, "520|90|370|12|75", floor, floor))
    }

    @Test
    fun testUnchangedSignatureNeverRewrites() {
        assertFalse(CoachSleepSyncGate.sleepCardRewriteDue("530|1", 0L, "530|1", 10 * hour, 2 * hour))
    }
}
