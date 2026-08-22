package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingDeviceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPersistenceIdentityTest {
    @Test
    fun `only the client's own connect event is a connection transition`() {
        // RingBLEClient always stamps the resolved family on its connect event...
        for (family in RingDeviceType.entries) assertTrue(isConnectTransition(family))
        // ...and RingEventBridge never does, for any decoder's Status — jring 0x0C, LuckRing
        // dev-info, YCBT status packets, all re-sent by runStartup on every sync pass.
        assertFalse(isConnectTransition(null))
    }

    /**
     * Issue #43. Connecting used to run `DELETE FROM sleep_sessions` / `sleep_stage_blocks` for
     * every family except YCBT and rebuild from the ring, which capped stored history at whatever
     * the ring still held — one day on CRP (`queryHistorySleep(daysAgo = 0)`) and jring
     * (`makeHistoryQueryCommand()`'s 1-day default at `JringDriver.kt:105`).
     *
     * The `when` below is exhaustive on purpose: it is the actual guard. Adding a [ConnectPurge]
     * member that deletes anything stops this file compiling, which is the tripwire the old
     * per-family `preservesSleepOnConnect` boolean never provided.
     */
    @Test
    fun `no connect event may purge anything, for any family`() {
        val everyOrigin: List<RingDeviceType?> = RingDeviceType.entries + null
        for (origin in everyOrigin) {
            val deletesAnything = when (connectPurge(origin)) {
                ConnectPurge.NOTHING -> false
            }
            assertFalse("connect (origin $origin) must not delete anything", deletesAnything)
        }
    }

    /**
     * iOS parity. iOS has no connect-time demo purge anywhere in its source — its only deletion of
     * seeded rows is user-initiated, inside `SeedData` — and it handles the demo/real mix by
     * *detecting* it (`isDemo`, `source == "mock"`, `DataFreshness.demo`) rather than cleaning it
     * up. Android used to clear demo rows on every CONNECTED, which on a paired ring meant every
     * reconnect: measured at roughly one per five minutes on a COLMI R10, and captured live at
     * 09:00:54 taking 772 measurements / 84 activity days / 36 sleep sessions to zero with the
     * phone untouched.
     */
    @Test
    fun `connecting never retires demo rows, first connect or reconnect`() {
        for (family in RingDeviceType.entries) {
            assertEquals(
                "$family must not purge on its first connect",
                ConnectPurge.NOTHING, connectPurge(family),
            )
        }
        // The decoder-Status case — the one that used to fire all session long — purges nothing.
        assertEquals(ConnectPurge.NOTHING, connectPurge(null))
    }

    @Test
    fun `history identity is stable across repeated syncs`() {
        val timestamp = 1_721_234_567_000L

        assertEquals(
            historyMeasurementId(MeasurementKind.HEART_RATE, timestamp),
            historyMeasurementId(MeasurementKind.HEART_RATE, timestamp),
        )
        assertNotEquals(
            historyMeasurementId(MeasurementKind.HEART_RATE, timestamp),
            historyMeasurementId(MeasurementKind.SPO2, timestamp),
        )
    }

    @Test
    fun `revised sleep packet replaces every overlapping stale block`() {
        val start = 1_721_234_000_000L
        val existing = listOf(
            block("old-1", start, 5, "LIGHT"),
            block("old-2", start + 5 * 60_000L, 10, "DEEP"),
            block("later", start + 15 * 60_000L, 5, "REM"),
        )
        val revised = listOf(block("new", start, 15, "LIGHT"))

        val merged = replaceOverlappingSleepBlocks(
            existing = existing,
            replacements = revised,
            replacementStart = start,
            replacementEnd = start + 15 * 60_000L,
        )

        assertEquals(listOf("new", "later"), merged.map { it.id })
    }

    @Test
    fun `packet revision preserves portions outside its interval`() {
        val start = 1_721_234_000_000L
        val existing = listOf(block("old", start, 60, "LIGHT"))
        val revisedStart = start + 15 * 60_000L
        val revisedEnd = start + 30 * 60_000L

        val merged = replaceOverlappingSleepBlocks(
            existing = existing,
            replacements = listOf(block("new", revisedStart, 15, "DEEP")),
            replacementStart = revisedStart,
            replacementEnd = revisedEnd,
        )

        assertEquals(listOf(start, revisedStart, revisedEnd), merged.map { it.startAt })
        assertEquals(listOf(15, 15, 30), merged.map { it.durationMinutes })
        assertEquals(listOf("LIGHT", "DEEP", "LIGHT"), merged.map { it.stageRaw })
    }

    @Test
    fun `short nap cannot replace a longer night on the same waking day`() {
        val nightStart = 1_721_234_000_000L

        assertEquals(
            false,
            shouldReplaceCompleteSleep(
                existingStart = nightStart,
                existingMinutes = 480,
                incomingStart = nightStart + 12 * 60 * 60_000L,
                incomingMinutes = 60,
            ),
        )
        assertEquals(
            true,
            shouldReplaceCompleteSleep(
                existingStart = nightStart + 12 * 60 * 60_000L,
                existingMinutes = 60,
                incomingStart = nightStart,
                incomingMinutes = 480,
            ),
        )
        assertEquals(
            true,
            shouldReplaceCompleteSleep(
                existingStart = nightStart,
                existingMinutes = 480,
                incomingStart = nightStart,
                incomingMinutes = 420,
            ),
        )
    }

    private fun block(id: String, start: Long, duration: Int, stage: String) =
        SleepStageBlockEntity(
            id = id,
            sessionId = "sleep-session",
            startAt = start,
            startMinute = 0,
            durationMinutes = duration,
            stageRaw = stage,
        )
}
