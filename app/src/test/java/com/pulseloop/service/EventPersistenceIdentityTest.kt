package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.MeasurementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EventPersistenceIdentityTest {
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
