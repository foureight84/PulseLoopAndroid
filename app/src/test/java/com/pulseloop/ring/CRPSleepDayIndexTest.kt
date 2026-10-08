package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Which day [CRPDecoder] files a CRP sleep-history reply (`group 2 / cmd 14`) under, for each shape
 * a waking day's sleep can take around midnight, and on either side of the vendor's 20:00 rollover.
 *
 * Prompted by a Colmi R11 (firmware `MOY-R2Z3-2.2.1`) whose first night, waking Sun 2026-09-27, was
 * stored correctly by a morning sync and then again, exactly one day early, by an evening one.
 *
 * The vendor app dates a reply from the moment it syncs: `t3/n.onHistorySleepChange` builds the entity
 * with `f4/e.a(info, …, new Date())`, whose `f4/e.e` moves the date to tomorrow when `f4/e.h` sees the
 * hour at 20 or later, and `j4/h.b` then subtracts the day index. From 20:00 on, day 0 is the night
 * about to begin and last night is day 1. A sleep starting at or after 20:00 therefore belongs to the
 * next waking day, so a reply's evening records are the evening *before* the day it names.
 *
 * Bouts are one minute long; a 60-minute awake run is the shortest gap [CRPDecoder] splits bouts on.
 */
class CRPSleepDayIndexTest {

    private val fdd3 = CRPUUIDs.CHAR_CMD_NOTIFY
    private val utc = ZoneId.of("UTC")

    private val sundayMorning = Instant.parse("2026-09-27T09:00:00Z")
    private val sundayJustBefore8pm = Instant.parse("2026-09-27T19:59:00Z")
    private val sundayEvening = Instant.parse("2026-09-27T21:00:00Z")

    private val awake = 0
    private val light = 1

    /** One vendor `[state, hour, minute]` record: [state] begins at hh:mm. */
    private fun at(state: Int, hour: Int, minute: Int) = byteArrayOf(state.toByte(), hour.toByte(), minute.toByte())

    private fun sleepFrame(dayIndex: Int, vararg records: ByteArray): ByteArray =
        CRPProtocol.frame(
            CRPCommands.GROUP_HISTORY,
            CRPCommands.CMD_QUERY_HISTORY_SLEEP,
            byteArrayOf(dayIndex.toByte()) + records.fold(ByteArray(0)) { acc, r -> acc + r },
        )

    private fun decodeAt(now: Instant, frame: ByteArray): List<RingDecodedEvent.SleepTimeline> {
        val bouts = CRPDecoder.decode(frame, fdd3, now, utc).filterIsInstance<RingDecodedEvent.SleepTimeline>()
        for (bout in bouts) {
            assertFalse(
                "bout dated ${bout._timestamp} starts after the $now sync that reported it",
                bout._timestamp.isAfter(now),
            )
        }
        return bouts
    }

    private fun assertBouts(expectedStarts: List<String>, bouts: List<RingDecodedEvent.SleepTimeline>) {
        assertEquals(expectedStarts.map(Instant::parse), bouts.map { it._timestamp })
        assertTrue(bouts.all { it.stages == listOf(SleepStage.LIGHT) })
    }

    /** One bout either side of midnight: light 23:00–23:01, then light 00:01–00:02. */
    private val acrossMidnight = arrayOf(at(light, 23, 0), at(awake, 23, 1), at(light, 0, 1), at(awake, 0, 2))

    @Test
    fun `1 - one bout before midnight is the evening before the waking day`() {
        val bouts = decodeAt(sundayMorning, sleepFrame(0, at(light, 23, 0), at(awake, 23, 1)))
        assertBouts(listOf("2026-09-26T23:00:00Z"), bouts)
    }

    @Test
    fun `2 - one bout after midnight is on the waking day`() {
        val bouts = decodeAt(sundayMorning, sleepFrame(0, at(light, 1, 0), at(awake, 1, 1)))
        assertBouts(listOf("2026-09-27T01:00:00Z"), bouts)
    }

    @Test
    fun `3 - two bouts before midnight are both the evening before the waking day`() {
        val bouts = decodeAt(
            sundayMorning,
            sleepFrame(0, at(light, 22, 0), at(awake, 22, 1), at(light, 23, 1), at(awake, 23, 2)),
        )
        assertBouts(listOf("2026-09-26T22:00:00Z", "2026-09-26T23:01:00Z"), bouts)
    }

    @Test
    fun `4 - two bouts after midnight are both on the waking day`() {
        val bouts = decodeAt(
            sundayMorning,
            sleepFrame(0, at(light, 1, 0), at(awake, 1, 1), at(light, 2, 1), at(awake, 2, 2)),
        )
        assertBouts(listOf("2026-09-27T01:00:00Z", "2026-09-27T02:01:00Z"), bouts)
    }

    @Test
    fun `5 - a bout before midnight and one after straddle the midnight between them`() {
        val bouts = decodeAt(sundayMorning, sleepFrame(0, *acrossMidnight))
        assertBouts(listOf("2026-09-26T23:00:00Z", "2026-09-27T00:01:00Z"), bouts)
    }

    /** The reported bug: after 20:00 the ring reports last night as day 1, and it must still be
     *  filed under the day it was slept, not the day before. */
    @Test
    fun `6 - last night synced after 8 pm as day 1 is the same night the morning sync stored as day 0`() {
        val morning = decodeAt(sundayMorning, sleepFrame(0, *acrossMidnight))
        val evening = decodeAt(sundayEvening, sleepFrame(1, *acrossMidnight))

        assertBouts(listOf("2026-09-26T23:00:00Z", "2026-09-27T00:01:00Z"), morning)
        assertBouts(listOf("2026-09-26T23:00:00Z", "2026-09-27T00:01:00Z"), evening)
    }

    /** The rollover is at 20:00 exactly: a minute earlier, day 0 is still last night. */
    @Test
    fun `7 - a sync just before 8 pm still reads day 0 as last night`() {
        val bouts = decodeAt(sundayJustBefore8pm, sleepFrame(0, *acrossMidnight))
        assertBouts(listOf("2026-09-26T23:00:00Z", "2026-09-27T00:01:00Z"), bouts)
    }
}
