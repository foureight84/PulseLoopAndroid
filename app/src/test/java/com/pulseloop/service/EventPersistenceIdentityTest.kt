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

    /**
     * Issue #63: the ring split one night into two sessions three minutes apart (00:12–03:18 and
     * 03:21–07:24). Once stored, SleepSegmentation has merged them into one row, so on the next
     * sync pass the second record overlaps a row that also holds the first record's blocks. It
     * must retire only its own stale copy — never the first session across the gap.
     */
    @Test
    fun `a complete record does not retire the other session of the same night`() {
        val a = 1_725_408_720_000L                    // 00:12
        val b = a + (3 * 60 + 9) * 60_000L           // 03:21 — three minutes after 03:18
        val existing = listOf(
            block("a-1", a, 124, "LIGHT"),
            block("a-2", a + 124 * 60_000L, 62, "DEEP"),          // ends 03:18
            block("b-1", b, 119, "LIGHT"),
            block("b-2", b + 119 * 60_000L, 124, "DEEP"),         // ends 07:24
        )

        val survivors = completeSessionSurvivors(existing, b, b + 243 * 60_000L)

        assertEquals(listOf("a-1", "a-2"), survivors.map { it.id })
    }

    /** The rule the old block wipe was there for: a shortened re-send of the *same* session must
     *  still retire its stale tail, which abuts the revised interval without a gap. */
    @Test
    fun `a shortened complete record still retires its own stale tail and head`() {
        val start = 1_725_408_720_000L
        val existing = listOf(
            block("head", start, 10, "AWAKE"),                    // revision now starts 10 min later
            block("mid", start + 10 * 60_000L, 100, "LIGHT"),
            block("tail", start + 110 * 60_000L, 20, "LIGHT"),    // revision now ends 20 min earlier
            block("nap", start + 131 * 60_000L, 30, "LIGHT"),     // one-minute gap: a different session
        )

        val survivors = completeSessionSurvivors(existing, start + 10 * 60_000L, start + 110 * 60_000L)

        assertEquals(listOf("nap"), survivors.map { it.id })
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

    /**
     * Issue #60, RC-2: the ring logs each spot reading into its own history, and a later sync
     * imports it next to the row we stored for our settled value. The match rule that lets the
     * ring's copy replace ours must reach a stamp at either end of a 35–63 s measurement and must
     * not reach the ring's own all-day samples five minutes apart.
     */
    @Test
    fun `a history sample adopts only the spot reading it is the ring's copy of`() {
        val ours = listOf(1_000_000L, 1_300_000L)   // two spot readings, five minutes apart
        // The ring stamps to the minute, so its copy may sit up to a measurement's length away.
        assertEquals(listOf(1_000_000L), spotReadingsMatching(ours, 1_000_000L + 60_000))
        assertEquals(listOf(1_000_000L), spotReadingsMatching(ours, 1_000_000L - 60_000))
        // An all-day sample two and a half minutes from either is nobody's copy.
        assertTrue(spotReadingsMatching(ours, 1_150_000L).isEmpty())
        // Nothing of ours: nothing to adopt, whatever the ring sends.
        assertTrue(spotReadingsMatching(emptyList(), 1_000_000L).isEmpty())
    }
}
