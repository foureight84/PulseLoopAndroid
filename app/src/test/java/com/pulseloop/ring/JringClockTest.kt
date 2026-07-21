package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * JringClock parity tests — verifies the local-wall-clock offset latch/convert contract that
 * [RingEncoder.makeTimeSyncCommand] and [RingDecoder]'s ring-stamped timestamp decoding share.
 * Pure logic — no Room/hardware dependency.
 */
class JringClockTest {

    private val pacific = TimeZone.getTimeZone("America/Los_Angeles")
    // A fixed instant outside DST so the offset is a stable -8h (-28800s).
    private val winterInstant = Instant.parse("2026-01-15T12:00:00Z")

    @Test
    fun `offset is captured at construction`() {
        val clock = JringClock(timeZone = pacific, now = winterInstant)
        assertEquals(-28800L, clock.offsetSeconds)
    }

    @Test
    fun `capture re-latches the offset`() {
        val clock = JringClock(timeZone = TimeZone.getTimeZone("UTC"), now = winterInstant)
        assertEquals(0L, clock.offsetSeconds)
        clock.capture(timeZone = pacific, now = winterInstant)
        assertEquals(-28800L, clock.offsetSeconds)
    }

    @Test
    fun `date subtracts the latched offset from a ring-stamped epoch`() {
        val clock = JringClock(timeZone = pacific, now = winterInstant)
        // The ring stamps local wall-clock seconds: true UTC epoch + offset.
        val ringEpoch = winterInstant.epochSecond + clock.offsetSeconds
        assertEquals(winterInstant, clock.date(ringEpoch))
    }
}
