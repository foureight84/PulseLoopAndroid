package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.SleepStage
import com.pulseloop.ring.SleepStageSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
    fun `timestamped correction replaces collapsed sleep across explicit bounds idempotently`() {
        val start = Instant.parse("2026-07-06T22:30:00Z")
        val end = start.plusSeconds(6 * 60 * 60L)
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        val oldCollapsed = listOf(block("collapsed", startMs, 116, SleepStage.LIGHT.name))
        val segments = listOf(
            SleepStageSegment(SleepStage.UNKNOWN, start, start.plusSeconds(30 * 60L)),
            SleepStageSegment(SleepStage.LIGHT, start.plusSeconds(30 * 60L), start.plusSeconds(90 * 60L)),
            SleepStageSegment(SleepStage.UNKNOWN, start.plusSeconds(90 * 60L), start.plusSeconds(4 * 60 * 60L)),
            SleepStageSegment(SleepStage.DEEP, start.plusSeconds(4 * 60 * 60L), start.plusSeconds(4 * 60 * 60L + 56 * 60L)),
            SleepStageSegment(SleepStage.UNKNOWN, start.plusSeconds(4 * 60 * 60L + 56 * 60L), end),
        )
        val corrected = buildTimestampedStageBlocks("sleep-session", startMs, endMs, segments)

        val first = replaceOverlappingSleepBlocks(oldCollapsed, corrected, startMs, endMs)
        val replay = replaceOverlappingSleepBlocks(first, corrected, startMs, endMs)

        assertEquals(360, first.sumOf { it.durationMinutes })
        assertEquals(listOf("UNKNOWN", "LIGHT", "UNKNOWN", "DEEP", "UNKNOWN"), first.map { it.stageRaw })
        assertEquals(first.map { it.startAt to it.durationMinutes }, replay.map { it.startAt to it.durationMinutes })
        assertTrue(first.none { it.id == "collapsed" })
        assertTrue(first.zipWithNext().all { (a, b) ->
            a.startAt + a.durationMinutes * 60_000L <= b.startAt
        })
        assertTrue(first.all { it.startAt + it.durationMinutes * 60_000L <= endMs })
    }

    @Test
    fun `stitched YCBT night replaces malformed parents with one bounded idempotent group`() {
        val start = Instant.parse("2026-08-24T02:07:46Z")
        val aClassifiedEnd = start.plusSeconds(3_642)
        val bStart = Instant.parse("2026-08-24T05:01:08Z")
        val bClassifiedEnd = bStart.plusSeconds(3_036)
        val cStart = Instant.parse("2026-08-24T06:33:32Z")
        val cClassifiedEnd = cStart.plusSeconds(6_999)
        val end = Instant.parse("2026-08-24T08:30:17Z")
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        val parentA = sleepParent("parent-a", start, Instant.parse("2026-08-24T03:08:32Z"), 60)
        val parentC = sleepParent("parent-c", cStart, end, 116)
        val malformed = listOf(
            block("a-60", startMs, 60, SleepStage.LIGHT.name, parentA.id),
            block("b-under-c-50", bStart.toEpochMilli(), 50, SleepStage.DEEP.name, parentC.id),
            block("c-116", cStart.toEpochMilli(), 116, SleepStage.REM.name, parentC.id),
        )
        assertEquals(166, malformed.filter { it.sessionId == parentC.id }.sumOf { it.durationMinutes })
        assertTrue(malformed.any { it.sessionId == parentC.id && it.startAt < parentC.startAt })

        val replacements = buildTimestampedStageBlocks(
            "stitched",
            startMs,
            endMs,
            listOf(
                SleepStageSegment(SleepStage.LIGHT, start, aClassifiedEnd),
                SleepStageSegment(SleepStage.DEEP, bStart, bClassifiedEnd),
                SleepStageSegment(SleepStage.REM, cStart, cClassifiedEnd),
            ),
        )
        val replacedParentIds = setOf(parentA.id, parentC.id)
        val firstBlocks = replaceOverlappingSleepBlocks(
            malformed, replacements, startMs, endMs, removeSessionIds = replacedParentIds,
        )
        val replayBlocks = replaceOverlappingSleepBlocks(
            firstBlocks, replacements, startMs, endMs, removeSessionIds = replacedParentIds,
        )
        val firstGroups = buildSleepReconcileGroups(firstBlocks, startMs to endMs)
        val replayGroups = buildSleepReconcileGroups(replayBlocks, startMs to endMs)
        val plan = buildSleepReconcilePlan(listOf(parentA, parentC), firstGroups)

        assertEquals(382, (endMs - startMs) / 60_000L)
        assertEquals(382, firstBlocks.sumOf { it.durationMinutes })
        assertEquals(firstBlocks.map { it.startAt to it.durationMinutes }, replayBlocks.map { it.startAt to it.durationMinutes })
        assertEquals(firstGroups, replayGroups)
        assertEquals(1, firstGroups.size)
        assertEquals(startMs to endMs, firstGroups.single().start to firstGroups.single().end)
        assertTrue(firstBlocks.all {
            it.startAt >= startMs && it.startAt + it.durationMinutes * 60_000L <= endMs
        })
        assertEquals(1, plan.matches.size)
        assertEquals(setOf(parentA.id), plan.deleteSessionIds)
    }

    @Test
    fun `timestamped blocks floor partial minutes without crossing explicit end`() {
        val start = Instant.parse("2026-07-06T22:30:00Z")
        val end = start.plusSeconds(119)
        val blocks = buildTimestampedStageBlocks(
            "sleep-session",
            start.toEpochMilli(),
            end.toEpochMilli(),
            listOf(SleepStageSegment(SleepStage.LIGHT, start, end.plusSeconds(30))),
        )

        assertEquals(1, blocks.single().durationMinutes)
        assertTrue(blocks.single().startAt + blocks.single().durationMinutes * 60_000L <= end.toEpochMilli())
    }

    @Test
    fun `timestamped blocks quantize cumulative fractional boundaries without losing coverage`() {
        val start = Instant.parse("2026-07-06T22:30:00Z")
        val firstBoundary = start.plusSeconds(90)
        val secondBoundary = start.plusSeconds(180)
        val end = start.plusSeconds(270)

        val blocks = buildTimestampedStageBlocks(
            "sleep-session",
            start.toEpochMilli(),
            end.toEpochMilli(),
            listOf(
                SleepStageSegment(SleepStage.LIGHT, start, firstBoundary),
                SleepStageSegment(SleepStage.DEEP, firstBoundary, secondBoundary),
                SleepStageSegment(SleepStage.REM, secondBoundary, end),
            ),
        )

        assertEquals(4, blocks.sumOf { it.durationMinutes })
        assertEquals(listOf("LIGHT", "DEEP", "REM"), blocks.map { it.stageRaw })
        assertEquals(listOf(1, 2, 1), blocks.map { it.durationMinutes })
        assertEquals(listOf(0, 1, 3), blocks.map { it.startMinute })
        assertTrue(blocks.zipWithNext().all { (a, b) ->
            a.startAt + a.durationMinutes * 60_000L <= b.startAt
        })
        assertTrue(blocks.all { it.startAt + it.durationMinutes * 60_000L <= end.toEpochMilli() })
    }

    @Test
    fun `thirty second awake transition survives minute quantization`() {
        val start = Instant.parse("2026-07-06T22:30:00Z")
        val awakeStart = start.plusSeconds(30 * 60L)
        val awakeEnd = awakeStart.plusSeconds(30)
        val end = start.plusSeconds(60 * 60L)

        val blocks = buildTimestampedStageBlocks(
            "sleep-session",
            start.toEpochMilli(),
            end.toEpochMilli(),
            listOf(
                SleepStageSegment(SleepStage.LIGHT, start, awakeStart),
                SleepStageSegment(SleepStage.AWAKE, awakeStart, awakeEnd),
                SleepStageSegment(SleepStage.DEEP, awakeEnd, end),
            ),
        )

        assertEquals(60, blocks.sumOf { it.durationMinutes })
        assertEquals(listOf("LIGHT", "AWAKE", "DEEP"), blocks.map { it.stageRaw })
        assertEquals(1, blocks.single { it.stageRaw == "AWAKE" }.durationMinutes)
        assertTrue(blocks.zipWithNext().all { (a, b) ->
            a.startAt + a.durationMinutes * 60_000L <= b.startAt
        })
        assertTrue(blocks.all { it.startAt + it.durationMinutes * 60_000L <= end.toEpochMilli() })
    }

    @Test
    fun `corrected sleep updatedAt advances within the same millisecond`() {
        assertEquals(1_001L, nextSleepUpdatedAt(wallClockNow = 1_000L, existingUpdatedAt = 1_000L))
    }

    @Test
    fun `corrected sleep updatedAt advances across clock rollback and saturates safely`() {
        assertEquals(2_001L, nextSleepUpdatedAt(wallClockNow = 1_000L, existingUpdatedAt = 2_000L))
        assertEquals(Long.MAX_VALUE, nextSleepUpdatedAt(0L, Long.MAX_VALUE))
    }

    @Test
    fun `authoritative night stays separate from a retained nap thirty minutes later`() {
        val nightStart = Instant.parse("2026-07-06T22:30:00Z").toEpochMilli()
        val nightEnd = nightStart + 6 * 60 * 60_000L
        val blocks = listOf(
            block("night", nightStart, 360, SleepStage.LIGHT.name),
            block("nap", nightEnd + 30 * 60_000L, 30, SleepStage.DEEP.name),
        )

        val first = buildSleepReconcileGroups(blocks, nightStart to nightEnd)
        val replay = buildSleepReconcileGroups(first.flatMap { it.blocks }, nightStart to nightEnd)

        assertEquals(2, first.size)
        assertEquals(first, replay)
        assertEquals(nightStart to nightEnd, first[0].start to first[0].end)
        assertTrue(first.all { group ->
            group.blocks.all { block ->
                block.startAt >= group.start &&
                    block.startAt + block.durationMinutes * 60_000L <= group.end
            }
        })
    }

    @Test
    fun `retained block ending at authoritative night start remains separate`() {
        val nightStart = Instant.parse("2026-07-06T22:30:00Z").toEpochMilli()
        val nightEnd = nightStart + 6 * 60 * 60_000L
        val groups = buildSleepReconcileGroups(
            listOf(
                block("before", nightStart - 30 * 60_000L, 30, SleepStage.REM.name),
                block("night", nightStart, 360, SleepStage.LIGHT.name),
            ),
            nightStart to nightEnd,
        )

        assertEquals(2, groups.size)
        assertEquals(listOf("before"), groups[0].blocks.map { it.id })
        assertEquals(listOf("night"), groups[1].blocks.map { it.id })
        assertEquals(nightStart to nightEnd, groups[1].start to groups[1].end)
        assertTrue(groups.all { group ->
            group.blocks.all { block ->
                block.startAt >= group.start &&
                    block.startAt + block.durationMinutes * 60_000L <= group.end
            }
        })
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

    private fun block(
        id: String,
        start: Long,
        duration: Int,
        stage: String,
        sessionId: String = "sleep-session",
    ) =
        SleepStageBlockEntity(
            id = id,
            sessionId = sessionId,
            startAt = start,
            startMinute = 0,
            durationMinutes = duration,
            stageRaw = stage,
        )

    private fun sleepParent(id: String, start: Instant, end: Instant, minutes: Int) =
        SleepSessionEntity(
            id = id,
            date = start.toEpochMilli(),
            startAt = start.toEpochMilli(),
            endAt = end.toEpochMilli(),
            totalMinutes = minutes,
        )
}
