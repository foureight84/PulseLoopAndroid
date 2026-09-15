package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingFamilyRefreshContractTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) {
            sent += command.copyOf()
        }
    }

    @Test
    fun `Jring refresh and query sleep retain startup behavior`() {
        // Each capture builds a fresh engine, i.e. a fresh connection — so all three take the
        // first-pass branch and must still agree. See the backfill test below for the warm case.
        val startup = capture { JringSyncEngine(it).runStartup() }
        val refresh = capture { JringSyncEngine(it).refresh() }
        val sleep = capture { JringSyncEngine(it).querySleep() }

        assertEquals(startup, refresh)
        assertEquals(startup, sleep)
    }

    /** Byte 1 of each `0x10` history query: the **day offset** it asks for — `0` today, `1`
     *  yesterday (issue #73). One request per day, so this is a list, not a single count. */
    private fun historyDayOffsets(sent: List<ByteArray>): List<Int> =
        sent.filter { it[0].toInt() == 0x10 }.map { it[1].toInt() }

    @Test
    fun `Jring always asks for today, on every pass`() {
        // Issue #73, and the whole bug: byte 1 is a day offset, so the old count reading asked
        // `0x10/03` (the day before last) on the first pass and `0x10/01` (yesterday) after it.
        // Today was never requested by any pass, and `0x10` is the only source of sleep — so on an
        // SR08 last night's sleep could not arrive at all, however many times the app synced.
        val w = FakeWriter()
        val engine = JringSyncEngine(w)

        engine.runStartup()
        assertTrue("the first pass must ask for today", 0 in historyDayOffsets(w.sent))

        w.sent.clear()
        engine.runStartup()
        engine.refresh()      // routes through runStartup
        engine.querySleep()   // ditto
        assertEquals("every later pass asks for today and nothing else", listOf(0, 0, 0), historyDayOffsets(w.sent))
    }

    @Test
    fun `Jring pulls a deeper history window once per connection, then today only`() {
        // Issue #43. A single-day request means stored history can only grow one night at a time
        // from install and never recovers what the ring already holds. But runStartup is also the
        // ~30-minute background sync, so the deep window must NOT repeat: 0x10 returns activity as
        // well as sleep, roughly 96 packets per extra day.
        val w = FakeWriter()
        val engine = JringSyncEngine(w)

        engine.runStartup()
        assertEquals(listOf(0, 1, 2), historyDayOffsets(w.sent))

        w.sent.clear()
        engine.runStartup()
        assertEquals(listOf(0), historyDayOffsets(w.sent))

        // A new connection builds a new engine, which backfills again.
        val reconnected = FakeWriter()
        JringSyncEngine(reconnected).runStartup()
        assertEquals(listOf(0, 1, 2), historyDayOffsets(reconnected.sent))
    }

    @Test
    fun `the Jring backfill asks newest day first`() {
        // Today leads the window: it is the day the user opened the app to see, and if a ring ever
        // truncates the run when the next request lands, the day that survives is the one that
        // matters. The vendor counts *down* to today instead — see historyDayOffsetsForThisPass
        // for why we diverge and what would make the vendor's chain necessary.
        val w = FakeWriter()
        JringSyncEngine(w).runStartup()
        val offsets = historyDayOffsets(w.sent)
        assertEquals("today must be requested first", 0, offsets.first())
        assertEquals("offsets must be distinct and ascending", offsets.sorted().distinct(), offsets)
    }

    @Test
    fun `the Jring backfill window stays inside what the command encodes`() {
        // makeHistoryQueryCommand coerces to 0..27; an offset above that would silently truncate
        // and the request would ask for a different day than the caller meant.
        val w = FakeWriter()
        JringSyncEngine(w).runStartup()
        val offsets = historyDayOffsets(w.sent)
        assertTrue("every requested offset $offsets must survive the 0..27 coerce", offsets.all { it in 0..27 })
    }

    @Test
    fun `Colmi refresh and query sleep retain startup behavior`() {
        val startup = captureColmi { it.runStartup() }
        val refresh = captureColmi { it.refresh() }
        val sleep = captureColmi { it.querySleep() }

        assertEquals(startup, refresh)
        assertEquals(startup, sleep)
    }

    private fun capture(action: (FakeWriter) -> Unit): List<List<Byte>> {
        val writer = FakeWriter()
        action(writer)
        return writer.sent.map(ByteArray::toList)
    }

    private fun captureColmi(action: (ColmiSyncEngine) -> Unit): List<List<Byte>> {
        val writer = FakeWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        action(engine)
        val commands = writer.sent.map(ByteArray::toList)
        engine.destroy()
        return commands
    }
}
