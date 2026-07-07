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
    fun `reference night before 4am is last night`() {
        val now = millis(LocalDateTime.of(2026, 7, 5, 2, 0))
        assertEquals(localMidnight(2026, 7, 4), TimeUtil.referenceNightLocal(now, zone))
    }

    @Test
    fun `reference night from 4am on is today`() {
        val now = millis(LocalDateTime.of(2026, 7, 5, 10, 0))
        assertEquals(localMidnight(2026, 7, 5), TimeUtil.referenceNightLocal(now, zone))
    }

    @Test
    fun `reference night after fall-back DST spans the 25-hour day`() {
        // DST ends Nov 1 2026 (25-hour local day). At 01:30 on Nov 2 the reference must be
        // Nov 1's true local midnight — a fixed -24h would land at 01:00 Nov 1 and miss the
        // stored day key entirely.
        val now = millis(LocalDateTime.of(2026, 11, 2, 1, 30))
        assertEquals(localMidnight(2026, 11, 1), TimeUtil.referenceNightLocal(now, zone))
    }

    @Test
    fun `reference night after spring-forward DST spans the 23-hour day`() {
        // DST starts Mar 8 2026 (23-hour local day). At 01:30 on Mar 9 the reference must be
        // Mar 8's true local midnight.
        val now = millis(LocalDateTime.of(2026, 3, 9, 1, 30))
        assertEquals(localMidnight(2026, 3, 8), TimeUtil.referenceNightLocal(now, zone))
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
