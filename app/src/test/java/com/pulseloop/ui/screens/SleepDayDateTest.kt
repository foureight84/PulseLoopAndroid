package com.pulseloop.ui.screens

import com.pulseloop.ui.viewmodels.SleepViewModel
import com.pulseloop.util.TimeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class SleepDayDateTest {
    private val zones = listOf("America/New_York", "UTC", "Asia/Tokyo").map(ZoneId::of)

    private fun at(dateTime: String, zone: ZoneId): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    private fun midnight(date: String, zone: ZoneId): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `initial empty sleep state has a reference date without database or ring data`() {
        val originalZone = TimeZone.getDefault()
        try {
            for (zone in zones) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val before = TimeUtil.referenceNightLocal()
                val state = SleepViewModel.SleepState()
                val after = TimeUtil.referenceNightLocal()

                assertTrue("Initial date in $zone", state.shownDayMillis in setOf(before, after))
                assertTrue(state.daySessions.isEmpty())
                assertEquals(state.shownDayMillis, resolvedSleepDayMillis(state.shownDayMillis))
            }
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `zero and negative dates resolve to today from 4am in every zone`() {
        for (zone in zones) {
            val now = at("2026-11-04T04:00:00", zone)
            for (invalid in listOf(0L, -1L, Long.MIN_VALUE)) {
                assertEquals("Invalid $invalid in $zone", midnight("2026-11-04", zone),
                    resolvedSleepDayMillis(invalid, now, zone))
            }
        }
    }

    @Test
    fun `missing dates before 4am resolve to the previous reference night`() {
        for (zone in zones) {
            val now = at("2026-11-04T03:59:59", zone)
            assertEquals(midnight("2026-11-03", zone), resolvedSleepDayMillis(0L, now, zone))
        }
    }

    @Test
    fun `fallback respects local midnight across daylight saving transitions`() {
        val zone = ZoneId.of("America/New_York")
        for ((now, expectedDay) in listOf(
            "2026-03-09T01:30:00" to "2026-03-08",
            "2026-11-02T01:30:00" to "2026-11-01",
        )) {
            assertEquals(midnight(expectedDay, zone), resolvedSleepDayMillis(0L, at(now, zone), zone))
        }
    }

    @Test
    fun `valid historical selection is preserved for header and picker`() {
        for (zone in zones) {
            val selected = midnight("2026-10-25", zone)
            val now = at("2026-11-04T12:00:00", zone)
            assertEquals(selected, resolvedSleepDayMillis(selected, now, zone))
        }
    }
}
