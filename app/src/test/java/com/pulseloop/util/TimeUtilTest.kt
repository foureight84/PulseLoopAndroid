package com.pulseloop.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeUtilTest {
    private val zone = ZoneId.of("America/New_York")

    private fun millis(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun localMidnight(year: Int, month: Int, day: Int): Long =
        millis(LocalDateTime.of(year, month, day, 0, 0))

    @Test
    fun `sleep starting at 11pm keys to the next morning`() {
        val start = millis(LocalDateTime.of(2026, 7, 4, 23, 30))
        assertEquals(localMidnight(2026, 7, 5), TimeUtil.wakingDayLocal(start, zone))
    }

    @Test
    fun `sleep starting exactly at 7pm rolls to the next day`() {
        val start = millis(LocalDateTime.of(2026, 7, 4, 19, 0))
        assertEquals(localMidnight(2026, 7, 5), TimeUtil.wakingDayLocal(start, zone))
    }

    @Test
    fun `sleep starting just before 7pm stays on the current day`() {
        val start = millis(LocalDateTime.of(2026, 7, 4, 18, 59))
        assertEquals(localMidnight(2026, 7, 4), TimeUtil.wakingDayLocal(start, zone))
    }

    @Test
    fun `small-hours sleep stays on its own day`() {
        // 2 AM continuation packets of a night that started before midnight must land on the
        // same waking day as the 11 PM start, so the night is stitched into ONE session.
        val start = millis(LocalDateTime.of(2026, 7, 5, 2, 0))
        assertEquals(localMidnight(2026, 7, 5), TimeUtil.wakingDayLocal(start, zone))
    }

    @Test
    fun `daytime nap stays on the current day`() {
        val start = millis(LocalDateTime.of(2026, 7, 4, 14, 0))
        assertEquals(localMidnight(2026, 7, 4), TimeUtil.wakingDayLocal(start, zone))
    }

    @Test
    fun `cross-midnight night groups both packets onto one day key`() {
        val eveningPacket = millis(LocalDateTime.of(2026, 7, 4, 23, 45))
        val morningPacket = millis(LocalDateTime.of(2026, 7, 5, 0, 15))
        assertEquals(
            TimeUtil.wakingDayLocal(eveningPacket, zone),
            TimeUtil.wakingDayLocal(morningPacket, zone),
        )
    }
}
