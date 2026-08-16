package com.pulseloop.health

import com.pulseloop.health.HealthConnectTypeMappings.ACT_DIST
import com.pulseloop.health.HealthConnectTypeMappings.ACT_ENERGY
import com.pulseloop.health.HealthConnectTypeMappings.ACT_STEPS
import com.pulseloop.health.HealthConnectTypeMappings.NettableSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Phase 3 (docs/health-connect-integration.md): the daily-activity identity scheme, the day-span
 * clamp, and the workout-netting port of iOS `HealthSyncService.workoutNetting`
 * (`HealthSyncService.swift:315-331`). All pure — no database, no HealthConnectClient.
 */
class HealthConnectActivityMappingTest {

    private val utc = ZoneOffset.UTC
    private val la = ZoneId.of("America/Los_Angeles")
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun dayStart(date: String, zone: ZoneId): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    // ── id builder ──

    @Test
    fun activityRecordIdUsesTheIosMetricTokens() {
        assertEquals("pl-act-steps-1786896000000", HealthConnectTypeMappings.activityRecordId(ACT_STEPS, 1786896000000L))
        assertEquals("pl-act-energy-1786896000000", HealthConnectTypeMappings.activityRecordId(ACT_ENERGY, 1786896000000L))
        assertEquals("pl-act-dist-1786896000000", HealthConnectTypeMappings.activityRecordId(ACT_DIST, 1786896000000L))
    }

    @Test
    fun activityRecordIdIsStableAcrossRebuilds() {
        // The whole upsert story: the same day must produce byte-identical ids on every pass.
        val a = HealthConnectTypeMappings.activityRecordId(ACT_STEPS, 1786896000000L)
        val b = HealthConnectTypeMappings.activityRecordId(ACT_STEPS, 1786896000000L)
        assertEquals(a, b)
    }

    // ── day span clamp ──

    @Test
    fun dayEndIsLastMillisecondOfAPastDay() {
        val start = dayStart("2026-08-10", utc)
        val now = dayStart("2026-08-16", utc)
        assertEquals(start + day - 1L, HealthConnectTypeMappings.activityDayEndMs(start, now, utc))
    }

    @Test
    fun dayEndClampsTodayToNowSoItNeverEndsInTheFuture() {
        val start = dayStart("2026-08-16", utc)
        val now = start + 9 * hour
        assertEquals(now, HealthConnectTypeMappings.activityDayEndMs(start, now, utc))
    }

    @Test
    fun dayEndIsNullWhenTheDayHasNotStarted() {
        val start = dayStart("2026-08-17", utc)
        val now = dayStart("2026-08-16", utc) + 9 * hour
        assertNull(HealthConnectTypeMappings.activityDayEndMs(start, now, utc))
    }

    @Test
    fun dayEndIsNullAtExactlyMidnight() {
        // start == end would be rejected by every IntervalRecord constructor.
        val start = dayStart("2026-08-16", utc)
        assertNull(HealthConnectTypeMappings.activityDayEndMs(start, start, utc))
    }

    @Test
    fun dayEndFollowsTheCalendarAcrossDstNotAFixed24Hours() {
        // 2026-03-08 is the US spring-forward day: 23 hours long in America/Los_Angeles. A
        // `+ 86_400_000` implementation would spill an hour into the next day.
        val start = dayStart("2026-03-08", la)
        val now = dayStart("2026-08-16", la)
        assertEquals(start + 23 * hour - 1L, HealthConnectTypeMappings.activityDayEndMs(start, now, la))
    }

    @Test
    fun dayEndFollowsTheCalendarOnTheFallBackDay() {
        // 2026-11-01 is 25 hours long in America/Los_Angeles.
        val start = dayStart("2026-11-01", la)
        val now = dayStart("2026-12-01", la)
        assertEquals(start + 25 * hour - 1L, HealthConnectTypeMappings.activityDayEndMs(start, now, la))
    }

    // ── plausibility guards (the platform rejects the insert otherwise) ──

    @Test
    fun stepsGuardStopsAtTheAppsOwnCorruptionThreshold() {
        assertFalse(HealthConnectTypeMappings.isPlausibleSteps(0L))
        assertFalse(HealthConnectTypeMappings.isPlausibleSteps(-5L))
        assertTrue(HealthConnectTypeMappings.isPlausibleSteps(1L))
        assertTrue(HealthConnectTypeMappings.isPlausibleSteps(200_000L))
        // EventPersistenceSubscriber self-heals a day above 200_000 as garbage — a write-only
        // export must not publish one before that heal runs, even though the platform would
        // accept it up to 1_000_000.
        assertFalse(HealthConnectTypeMappings.isPlausibleSteps(200_001L))
        assertFalse(HealthConnectTypeMappings.isPlausibleSteps(900_000L))
    }

    @Test
    fun energyAndDistanceGuardsRejectZeroAndNegative() {
        assertFalse(HealthConnectTypeMappings.isPlausibleActiveCalories(0.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleActiveCalories(-0.1))
        assertTrue(HealthConnectTypeMappings.isPlausibleActiveCalories(412.5))
        assertFalse(HealthConnectTypeMappings.isPlausibleDistanceMeters(0.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleDistanceMeters(-1.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleDistanceMeters(6_400.0))
    }

    // ── workout netting (iOS HealthSyncService.swift:315-331) ──

    /** A 10-minute session starting at the top of its day — comfortably credit-eligible, so
     *  the existing netting assertions test the sums, not the eligibility check. */
    private fun session(dayMs: Long, kcal: Double?, meters: Double?, gps: Boolean = true) =
        NettableSession(
            dayStartMs = dayMs,
            calories = kcal,
            distanceMeters = meters,
            useGps = gps,
            startedAtMs = dayMs + hour,
            endedAtMs = dayMs + hour + 10 * 60_000L,
            totalPauseSeconds = 0.0,
        )

    @Test
    fun nettingSkipsSessionsActivityRollupNeverCredited() {
        // ActivityRollup.credit early-returns when the session has no full active minute — its
        // energy and metres never reach the daily row, so netting must not subtract them
        // (Phase 3 imperfection #1, resolved in Phase 4).
        val d = dayStart("2026-08-16", utc)
        val out = HealthConnectTypeMappings.workoutNetting(
            listOf(
                // 59.9 s: credit() skips it → netting skips it too.
                session2(d, 100.0, 3_000.0, started = d + hour, ended = d + hour + 59_900L, pause = 0.0),
                // Exactly 60 s: credit() folds it in → netting subtracts it.
                session2(d, 100.0, 3_000.0, started = d + hour, ended = d + hour + 60_000L, pause = 0.0),
                // 90 s wall clock but 61 s of it paused: no full ACTIVE minute → skipped.
                session2(d, 100.0, 3_000.0, started = d + hour, ended = d + hour + 90_000L, pause = 61.0),
                // 2 minutes, 55 s paused: 65 s of active time → one full minute → credited.
                session2(d, 100.0, 3_000.0, started = d + hour, ended = d + hour + 120_000L, pause = 55.0),
            ),
        )
        assertEquals(200.0, out.kcal(d), 0.0001)
        assertEquals(6_000.0, out.meters(d), 0.0001)
    }

    private fun session2(
        dayMs: Long,
        kcal: Double?,
        meters: Double?,
        started: Long,
        ended: Long?,
        pause: Double,
        gps: Boolean = true,
    ) = NettableSession(dayMs, kcal, meters, gps, started, ended, pause)

    @Test
    fun creditedActiveMinutesPortsActivityRollupMinutesFor() {
        val s = 1_700_000_000_000L
        assertEquals(1, HealthConnectTypeMappings.creditedActiveMinutes(s, s + 60_000L, 0.0))
        assertEquals(0, HealthConnectTypeMappings.creditedActiveMinutes(s, s + 59_999L, 0.0))
        assertEquals(1, HealthConnectTypeMappings.creditedActiveMinutes(s, s + 120_000L, 60.0))
        assertEquals(0, HealthConnectTypeMappings.creditedActiveMinutes(s, s + 120_000L, 61.0))
        assertEquals(1, HealthConnectTypeMappings.creditedActiveMinutes(s, s + 120_000L, 59.9))
        assertEquals(0, HealthConnectTypeMappings.creditedActiveMinutes(s, null, 0.0))
        assertEquals(0, HealthConnectTypeMappings.creditedActiveMinutes(s, s - 5_000L, 0.0)) // negative clamps
    }

    @Test
    fun nettingSumsEnergyForEveryFinishedSessionOfTheDay() {
        val d = dayStart("2026-08-16", utc)
        val out = HealthConnectTypeMappings.workoutNetting(
            listOf(session(d, 120.0, 1_000.0), session(d, 80.0, 500.0)),
        )
        assertEquals(200.0, out.kcal(d), 0.0001)
    }

    @Test
    fun nettingCountsDistanceOnlyForGpsSessions() {
        // ActivityRollup.credit folds a session's distance into the daily row only when useGps —
        // netting must subtract exactly that set, no more.
        val d = dayStart("2026-08-16", utc)
        val out = HealthConnectTypeMappings.workoutNetting(
            listOf(session(d, 100.0, 3_000.0, gps = true), session(d, 50.0, 2_000.0, gps = false)),
        )
        assertEquals(3_000.0, out.meters(d), 0.0001)
        // …but a non-GPS session's energy still nets: the ring's all-day figure covered it.
        assertEquals(150.0, out.kcal(d), 0.0001)
    }

    @Test
    fun nettingKeepsDaysSeparate() {
        val d1 = dayStart("2026-08-15", utc)
        val d2 = dayStart("2026-08-16", utc)
        val out = HealthConnectTypeMappings.workoutNetting(
            listOf(session(d1, 100.0, 1_000.0), session(d2, 250.0, 4_000.0)),
        )
        assertEquals(100.0, out.kcal(d1), 0.0001)
        assertEquals(250.0, out.kcal(d2), 0.0001)
        assertEquals(1_000.0, out.meters(d1), 0.0001)
        assertEquals(4_000.0, out.meters(d2), 0.0001)
    }

    @Test
    fun nettingIgnoresNullAndNonPositiveValues() {
        val d = dayStart("2026-08-16", utc)
        val out = HealthConnectTypeMappings.workoutNetting(
            listOf(session(d, null, null), session(d, 0.0, 0.0), session(d, -10.0, -10.0)),
        )
        assertEquals(0.0, out.kcal(d), 0.0001)
        assertEquals(0.0, out.meters(d), 0.0001)
    }

    @Test
    fun nettingReturnsZeroForADayWithNoWorkouts() {
        val d = dayStart("2026-08-16", utc)
        assertEquals(0.0, HealthConnectTypeMappings.WorkoutNetting.EMPTY.kcal(d), 0.0001)
        assertEquals(0.0, HealthConnectTypeMappings.WorkoutNetting.EMPTY.meters(d), 0.0001)
        val out = HealthConnectTypeMappings.workoutNetting(emptyList())
        assertEquals(0.0, out.kcal(d), 0.0001)
        assertEquals(0.0, out.meters(d), 0.0001)
    }

    // ── leftover subtraction (the part that can silently under-report) ──

    @Test
    fun leftoverSubtractsTheWorkoutTotal() {
        assertEquals(340.0, HealthConnectTypeMappings.activityLeftover(520.0, 180.0), 0.0001)
        assertEquals(4_400.0, HealthConnectTypeMappings.activityLeftover(6_400.0, 2_000.0), 0.0001)
    }

    @Test
    fun leftoverIsUnchangedWhenNothingIsNetted() {
        assertEquals(520.0, HealthConnectTypeMappings.activityLeftover(520.0, 0.0), 0.0001)
    }

    @Test
    fun leftoverGoesNegativeAndTheGuardDropsIt() {
        // A workout claiming more energy than the ring's day total must not produce a record —
        // the platform floor is 0 and a negative Energy is meaningless.
        val leftover = HealthConnectTypeMappings.activityLeftover(400.0, 600.0)
        assertEquals(-200.0, leftover, 0.0001)
        assertFalse(HealthConnectTypeMappings.isPlausibleActiveCalories(leftover))
    }

    @Test
    fun leftoverOfExactlyZeroIsDropped() {
        val leftover = HealthConnectTypeMappings.activityLeftover(180.0, 180.0)
        assertEquals(0.0, leftover, 0.0001)
        assertFalse(HealthConnectTypeMappings.isPlausibleActiveCalories(leftover))
        assertFalse(HealthConnectTypeMappings.isPlausibleDistanceMeters(leftover))
    }

    @Test
    fun nettingKeysOnTheDayTheSessionStarted() {
        // A workout that crosses midnight nets entirely against the day it began (iOS keys on
        // startedAt), so the caller's dayStartMs is authoritative here.
        val d1 = dayStart("2026-08-15", utc)
        val out = HealthConnectTypeMappings.workoutNetting(listOf(session(d1, 300.0, 5_000.0)))
        assertEquals(300.0, out.kcal(d1), 0.0001)
        assertEquals(0.0, out.kcal(dayStart("2026-08-16", utc)), 0.0001)
    }
}
