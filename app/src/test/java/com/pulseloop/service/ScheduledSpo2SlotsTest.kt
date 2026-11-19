package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Issue #103: the app's own spot SpO₂ lands between the ring's readings, never on top of them. */
class ScheduledSpo2SlotsTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 21, month: Int = 10): ZonedDateTime =
        LocalDateTime.of(2026, month, day, h, m, s).atZone(zone)

    @Test
    fun `hourly lands at half past`() {
        // Just after the ring's own 10:00:42 reading — the reporter's timing.
        assertEquals(at(10, 30), ScheduledSpo2Slots.nextSlot(at(10, 0, 42), 60))
        assertEquals(at(10, 30), ScheduledSpo2Slots.nextSlot(at(9, 30, 1), 60))
    }

    @Test
    fun `next slot is strictly after now`() {
        assertEquals(at(11, 30), ScheduledSpo2Slots.nextSlot(at(10, 30), 60))
    }

    @Test
    fun `half hour interval lands at quarter past and quarter to`() {
        assertEquals(at(10, 15), ScheduledSpo2Slots.nextSlot(at(10, 5), 30))
        assertEquals(at(10, 45), ScheduledSpo2Slots.nextSlot(at(10, 20), 30))
    }

    @Test
    fun `last slot of the day rolls over to the first of the next`() {
        assertEquals(at(0, 30, day = 22), ScheduledSpo2Slots.nextSlot(at(23, 45), 60))
    }

    @Test
    fun `interval that does not divide the day restarts its grid at midnight`() {
        // 35 min: grid …23:20, 23:55, then 00:00; slots 23:37:30, then 00:17:30 the next day.
        assertEquals(at(23, 37, 30), ScheduledSpo2Slots.nextSlot(at(23, 20), 35))
        assertEquals(at(0, 17, 30, day = 22), ScheduledSpo2Slots.nextSlot(at(23, 40), 35))
        // 23:57 sits two minutes after the 23:55 reading.
        assertFalse(ScheduledSpo2Slots.isClear(at(23, 57), 35))
    }

    @Test
    fun `slots stay on the half hour across a DST change`() {
        // Europe/Paris falls back on 2026-10-25 (03:00 → 02:00): the slot after 01:40 is still 02:30.
        val slot = ScheduledSpo2Slots.nextSlot(at(1, 40, day = 25), 60)
        assertEquals(2, slot.hour)
        assertEquals(30, slot.minute)
    }

    @Test
    fun `a late run near the top of the hour is not clear`() {
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 30), 60))
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 2), 60))
        // 3 min before 11:00 gives 180 s >= 105 s for connect + sync + 75 s measurement.
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 57), 60))
        // 2 min before 11:00 gives 120 s >= 105 s.
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 58), 60))
        // Exact 105 s boundary.
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 58, 15), 60))
        // 104 s < 105 s.
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 58, 16), 60))
        // 60 s < 105 s.
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 59), 60))
    }

    @Test
    fun `short intervals use a smaller margin`() {
        // 15 min: margin 3:45, so a run at 10:07:30 (the slot) is clear and 10:02 is not.
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 7, 30), 15))
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 2), 15))
    }

    @Test
    fun `every slot is itself clear`() {
        for (interval in listOf(15, 20, 30, 35, 45, 60)) {
            var t = at(0, 0)
            repeat(200) {
                t = ScheduledSpo2Slots.nextSlot(t, interval)
                assertTrue("interval $interval slot $t", ScheduledSpo2Slots.isClear(t, interval))
            }
        }
    }

    @Test
    fun `two hour app interval keeps the half past slot on an hourly ring grid`() {
        // The app's own grid starts at :30 (half the ring's interval) and repeats every 2 h.
        assertEquals(at(10, 30), ScheduledSpo2Slots.nextSlot(at(10, 0, 42), 120, 60))
        assertEquals(at(12, 30), ScheduledSpo2Slots.nextSlot(at(10, 30), 120, 60))
        assertEquals(at(0, 30, day = 22), ScheduledSpo2Slots.nextSlot(at(22, 31), 120, 60))
    }

    @Test
    fun `three hour app interval slots every third hour`() {
        assertEquals(at(12, 30), ScheduledSpo2Slots.nextSlot(at(10, 0, 42), 180, 60))
        // 00:30 + 8 × 3 h wraps exactly onto 00:30, so the offset survives the rollover.
        assertEquals(at(0, 30, day = 22), ScheduledSpo2Slots.nextSlot(at(23, 45), 180, 60))
    }

    @Test
    fun `every 2 and 3 hour slot stays clear of an hourly ring grid`() {
        for (appInterval in listOf(120, 180)) {
            var t = at(0, 0)
            repeat(40) {
                t = ScheduledSpo2Slots.nextSlot(t, appInterval, 60)
                assertTrue("app $appInterval slot $t", ScheduledSpo2Slots.isClear(t, 60))
            }
        }
    }

    @Test
    fun `half hour ring grid puts the two hour slots at quarter past`() {
        assertEquals(at(10, 15), ScheduledSpo2Slots.nextSlot(at(10, 5), 120, 30))
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 15), 30))
    }

    @Test
    fun `two hour ring grid with two hour app interval lands at odd hours`() {
        assertEquals(at(11, 0), ScheduledSpo2Slots.nextSlot(at(10, 5), 120, 120))
        assertEquals(at(13, 0), ScheduledSpo2Slots.nextSlot(at(11, 0), 120, 120))
        assertTrue(ScheduledSpo2Slots.isClear(at(11, 0), 120))
        // Late run at 22:25 (delayed from 22:00) is clear of 00:00 ring reading
        assertTrue(ScheduledSpo2Slots.isClear(at(22, 25), 120))
        // 23:59 is 1 min before midnight reading => not clear
        assertFalse(ScheduledSpo2Slots.isClear(at(23, 59), 120))
    }

    @Test
    fun `two hour ring grid with hourly app interval lands at half past`() {
        assertEquals(at(10, 30), ScheduledSpo2Slots.nextSlot(at(10, 5), 60, 120))
        assertEquals(at(11, 30), ScheduledSpo2Slots.nextSlot(at(10, 30), 60, 120))
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 30), 120))
        assertTrue(ScheduledSpo2Slots.isClear(at(11, 30), 120))
    }

    @Test
    fun `two hour ring grid with three hour app interval lands at half past and stays clear`() {
        assertEquals(at(12, 30), ScheduledSpo2Slots.nextSlot(at(10, 5), 180, 120))
        var t = at(0, 0)
        repeat(40) {
            t = ScheduledSpo2Slots.nextSlot(t, 180, 120)
            assertTrue("180m app slot $t on 120m ring grid", ScheduledSpo2Slots.isClear(t, 120))
        }
    }

    @Test
    fun `isClearToMeasure only needs the 75 s measurement window before a reading`() {
        // 75 s before 11:00 — exactly the measurement duration.
        assertTrue(ScheduledSpo2Slots.isClearToMeasure(at(10, 58, 45), 60))
        // 74 s before 11:00 — the measurement would still be running at the reading.
        assertFalse(ScheduledSpo2Slots.isClearToMeasure(at(10, 58, 46), 60))
        assertFalse(ScheduledSpo2Slots.isClearToMeasure(at(10, 59), 60))
        // 90 s before 11:00: too late to start connect + sync, but fine to still be measuring.
        assertTrue(ScheduledSpo2Slots.isClearToMeasure(at(10, 58, 30), 60))
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 58, 30), 60))
        // Still inside the ring's own post-reading window.
        assertFalse(ScheduledSpo2Slots.isClearToMeasure(at(10, 2), 60))
    }

    @Test
    fun `nextClear just before the hour on a 60 min grid`() {
        assertEquals(at(1, 5), ScheduledSpo2Slots.nextClear(at(0, 59), 60))
        assertEquals(at(7, 5), ScheduledSpo2Slots.nextClear(at(6, 59), 60))
        assertEquals(at(11, 5), ScheduledSpo2Slots.nextClear(at(10, 58, 30), 60))
    }

    @Test
    fun `nextClear just after the hour on a 60 min grid`() {
        assertEquals(at(1, 5), ScheduledSpo2Slots.nextClear(at(1, 2), 60))
    }

    @Test
    fun `nextClear just before midnight rolls over to the next day`() {
        assertEquals(at(0, 5, day = 22), ScheduledSpo2Slots.nextClear(at(23, 59), 60))
        // 35 min grid: 23:55 + 5 min lands on midnight's own grid reset, so it slides to 00:05.
        assertEquals(at(0, 5, day = 22), ScheduledSpo2Slots.nextClear(at(23, 54), 35))
        assertEquals(at(0, 5, day = 22), ScheduledSpo2Slots.nextClear(at(23, 57), 35))
    }

    @Test
    fun `nextClear always lands strictly after now on a clear moment`() {
        for (interval in listOf(15, 20, 30, 35, 45, 60, 120)) {
            for (minuteOfDay in 0 until 24 * 60) {
                val t = at(minuteOfDay / 60, minuteOfDay % 60)
                if (!ScheduledSpo2Slots.isClear(t, interval)) {
                    val retry = ScheduledSpo2Slots.nextClear(t, interval)
                    assertTrue(
                        "interval $interval retry $retry is not after $t",
                        retry.isAfter(t)
                    )
                    assertTrue(
                        "interval $interval retry $retry is not clear",
                        ScheduledSpo2Slots.isClear(retry, interval)
                    )
                }
            }
        }
    }

    @Test
    fun `a nextClear retry recovers onto the regular slot grid`() {
        // A retry at 07:05 finishes measuring at 07:06:30; the next regular slot is 08:30.
        assertEquals(at(8, 30), ScheduledSpo2Slots.nextSlot(at(7, 6, 30), 120, 60))
    }
}
