package com.pulseloop.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Review of #108: no sleep query is sent close enough to 20:00 for its reply to land on the wrong side. */
class CRPSleepRolloverGuardTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 12, h, m, 30, 0, zone).toInstant()

    @Test
    fun `the minutes around 20 00 are guarded`() {
        assertTrue(CRPDecoder.nearSleepDayRollover(at(19, 59), zone))
        assertTrue(CRPDecoder.nearSleepDayRollover(at(20, 0), zone))
        assertTrue(CRPDecoder.nearSleepDayRollover(at(20, 4), zone))
        assertTrue(CRPDecoder.nearSleepDayRollover(at(19, 56), zone))
    }

    @Test
    fun `the rest of the day is not`() {
        assertFalse(CRPDecoder.nearSleepDayRollover(at(19, 55), zone))
        assertFalse(CRPDecoder.nearSleepDayRollover(at(20, 5), zone))
        assertFalse(CRPDecoder.nearSleepDayRollover(at(7, 30), zone))
        assertFalse(CRPDecoder.nearSleepDayRollover(at(0, 0), zone))
    }
}
