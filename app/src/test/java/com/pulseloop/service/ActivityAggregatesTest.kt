package com.pulseloop.service

import com.pulseloop.data.entity.ActivitySessionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActivityAggregates.isValidEdit` — the pure guard `applyEdit` runs before touching the DB.
 * Ported from `testInvalidEditsAreRejected` in ActivityEditTests.swift (iOS #57c). The DB-side
 * recompute/rollup behavior those Swift tests also cover (calories follow the new type, samples
 * re-window, the daily rollup moves without drift, GPS distance follows the window) has no Android
 * unit-test equivalent — this app has no in-memory-Room/Robolectric test harness (same gap noted
 * for `LiveWorkoutManager.finish()`) — so that half is covered by runtime verification instead.
 */
class ActivityAggregatesTest {
    private val now = 1_750_000_000_000L
    private val start = now - 7_200_000L // 2h ago

    private fun finishedSession(
        startedAt: Long = start,
        endedAt: Long? = start + 1_800_000L, // 30 min later
        totalPauseSeconds: Double = 0.0,
    ) = ActivitySessionEntity(
        type = "run", statusRaw = "finished", startedAt = startedAt, endedAt = endedAt,
        totalPauseSeconds = totalPauseSeconds,
    )

    @Test
    fun `valid edit within the recorded window is accepted`() {
        val session = finishedSession()
        assertTrue(ActivityAggregates.isValidEdit(session, start, start + 1_800_000L, now))
    }

    @Test
    fun `future end time rejected`() {
        val session = finishedSession()
        assertFalse(ActivityAggregates.isValidEdit(session, start, now + 600_000L, now))
    }

    @Test
    fun `empty window rejected`() {
        val session = finishedSession()
        assertFalse(ActivityAggregates.isValidEdit(session, start, start, now))
    }

    @Test
    fun `window not longer than recorded pauses rejected`() {
        // 10 minutes of pauses but only a 5-minute window — the window must exceed the pauses.
        val session = finishedSession(totalPauseSeconds = 600.0)
        assertFalse(ActivityAggregates.isValidEdit(session, start, start + 300_000L, now))
    }

    @Test
    fun `non-finished session rejected`() {
        val session = finishedSession().copy(statusRaw = "recording")
        assertFalse(ActivityAggregates.isValidEdit(session, start, start + 1_800_000L, now))
    }
}
