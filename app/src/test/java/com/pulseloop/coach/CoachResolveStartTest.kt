package com.pulseloop.coach.tools

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** iOS 6d006ec: `log_activity`'s `start_time` kept only its date part, so `19:00` became noon. */
class CoachResolveStartTest {

    private val utc = ZoneOffset.UTC
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 14, h, m).toInstant(utc).toEpochMilli()

    @Test
    fun `24-hour and 12-hour clock times land on the given date`() {
        assertEquals(at(19, 0), CoachDataAccess.resolveStart("2026-10-14", "19:00", utc))
        assertEquals(at(7, 5), CoachDataAccess.resolveStart("2026-10-14", "7:05", utc))
        assertEquals(at(19, 0), CoachDataAccess.resolveStart("2026-10-14", "7:00 PM", utc))
        assertEquals(at(19, 0), CoachDataAccess.resolveStart("2026-10-14", "7:00pm", utc))
        assertEquals(at(0, 30), CoachDataAccess.resolveStart("2026-10-14", "12:30 AM", utc))
        assertEquals(at(19, 0), CoachDataAccess.resolveStart("2026-10-14", "19:00:00", utc))
    }

    @Test
    fun `an ISO timestamp keeps its time, with or without an offset`() {
        assertEquals(at(18, 15), CoachDataAccess.resolveStart("2026-10-14", "2026-10-14T18:15:00Z", utc))
        assertEquals(at(18, 15), CoachDataAccess.resolveStart("2026-10-14", "2026-10-14T20:15:00+02:00", utc))
        assertEquals(at(18, 15), CoachDataAccess.resolveStart("2026-10-14", "2026-10-14T18:15", utc))
        assertEquals(at(18, 15), CoachDataAccess.resolveStart("2026-10-14", "2026-10-14 18:15", utc))
        assertEquals(at(18, 15), CoachDataAccess.resolveStart("2026-10-14", "2026-10-14 18:15:00Z", utc))
    }

    @Test
    fun `blank or unreadable times return null so the caller defaults`() {
        assertNull(CoachDataAccess.resolveStart("2026-10-14", null, utc))
        assertNull(CoachDataAccess.resolveStart("2026-10-14", "  ", utc))
        assertNull(CoachDataAccess.resolveStart("2026-10-14", "evening", utc))
        assertNull(CoachDataAccess.resolveStart("not a date", "19:00", utc))
    }
}
