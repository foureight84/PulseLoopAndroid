package com.pulseloop.ring

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingFamilyRefreshContractTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) {
            sent += command.copyOf()
        }
    }

    /** A jring engine whose pager runs on millisecond timers, so a whole pass drains inside a
     *  test. The pager holds each day's request until the previous day's stream goes quiet
     *  (issue #73), so these tests must let it finish before reading the writer — the *window*
     *  is what they assert, and [JringHistorySyncTest] owns the *schedule*. */
    private fun fastJring(w: FakeWriter) =
        JringSyncEngine(w, historySync = JringHistorySync(w, settleMs = 10, stallMs = 10))

    /** Long enough for a three-day pass (six legs) to drain with no ring answering. */
    private suspend fun drain() = delay(400)

    @Test
    fun `Jring refresh and query sleep retain startup behavior`() = runBlocking {
        // Each capture builds a fresh engine, i.e. a fresh connection — so all three take the
        // first-pass branch and must still agree. See the backfill test below for the warm case.
        val startup = FakeWriter().also { fastJring(it).runStartup() }
        val refresh = FakeWriter().also { fastJring(it).refresh() }
        val sleep = FakeWriter().also { fastJring(it).querySleep() }
        drain()

        assertEquals(startup.sent.map { it.toList() }, refresh.sent.map { it.toList() })
        assertEquals(startup.sent.map { it.toList() }, sleep.sent.map { it.toList() })
    }

    /** Byte 1 of each `0x10` history query: the **day offset** it asks for — `0` today, `1`
     *  yesterday (issue #73). One request per day, so this is a list, not a single count. */
    private fun historyDayOffsets(sent: List<ByteArray>): List<Int> =
        sent.filter { it[0].toInt() == 0x10 }.map { it[1].toInt() }

    /** The same for the `0x16` heart-rate history request, which takes the same offset. */
    private fun heartRateDayOffsets(sent: List<ByteArray>): List<Int> =
        sent.filter { it[0].toInt() == 0x16 }.map { it[1].toInt() }

    @Test
    fun `Jring always asks for today, on every pass`() = runBlocking {
        // Issue #73, and the whole bug: byte 1 is a day offset, so the old count reading asked
        // `0x10/03` (the day before last) on the first pass and `0x10/01` (yesterday) after it.
        // Today was never requested by any pass, and `0x10` is the only source of sleep — so on an
        // SR08 last night's sleep could not arrive at all, however many times the app synced.
        val w = FakeWriter()
        val engine = fastJring(w)

        engine.runStartup()
        drain()
        assertTrue("the first pass must ask for today", 0 in historyDayOffsets(w.sent))

        w.sent.clear()
        engine.runStartup(); drain()
        engine.refresh(); drain()      // routes through runStartup
        engine.querySleep(); drain()   // ditto
        assertEquals("every later pass asks for today and nothing else", listOf(0, 0, 0), historyDayOffsets(w.sent))
    }

    @Test
    fun `Jring pulls a deeper history window once per connection, then today only`() = runBlocking {
        // Issue #43. A single-day request means stored history can only grow one night at a time
        // from install and never recovers what the ring already holds. But runStartup is also the
        // ~30-minute background sync, so the deep window must NOT repeat: 0x10 returns activity as
        // well as sleep, roughly 96 packets per extra day.
        val w = FakeWriter()
        val engine = fastJring(w)

        engine.runStartup()
        drain()
        assertEquals(listOf(0, 1, 2), historyDayOffsets(w.sent))

        w.sent.clear()
        engine.runStartup()
        drain()
        assertEquals(listOf(0), historyDayOffsets(w.sent))

        // A new connection builds a new engine, which backfills again.
        val reconnected = FakeWriter()
        fastJring(reconnected).runStartup()
        drain()
        assertEquals(listOf(0, 1, 2), historyDayOffsets(reconnected.sent))
    }

    @Test
    fun `a declined backfill is still owed`() = runBlocking {
        // The once-per-connection gate is spent by a pass that *ran*. runStartup is also the
        // ~30-minute background sync, so a second pass landing while the first is still draining
        // is normal; if that no-op consumed the gate, a connection could lose its backfill
        // entirely and never ask for the older days again.
        val w = FakeWriter()
        val engine = JringSyncEngine(w, historySync = JringHistorySync(w, settleMs = 10, stallMs = 10_000))

        engine.runStartup()          // day 0 in flight, and it will not answer
        engine.runStartup()          // declined: a pass is already running
        assertEquals("the second pass adds nothing", listOf(0), historyDayOffsets(w.sent))

        val resumed = FakeWriter()
        fastJring(resumed).runStartup()
        drain()
        assertEquals("the window is still the full backfill", listOf(0, 1, 2), historyDayOffsets(resumed.sent))
    }

    @Test
    fun `the Jring backfill asks newest day first`() = runBlocking {
        // Today leads the window: it is the day the user opened the app to see, and if a ring ever
        // truncates the run when the next request lands, the day that survives is the one that
        // matters. The vendor counts *down* to today instead — see historyDayOffsetsForThisPass
        // for why we diverge.
        val w = FakeWriter()
        fastJring(w).runStartup()
        drain()
        val offsets = historyDayOffsets(w.sent)
        assertEquals("today must be requested first", 0, offsets.first())
        assertEquals("offsets must be distinct and ascending", offsets.sorted().distinct(), offsets)
    }

    @Test
    fun `each backfilled day gets its own heart rate request, after that day's history`() = runBlocking {
        // The vendor reaches both commands through getDataByDay(type, day), so 0x16 takes the same
        // offset as 0x10. Before the pager this was a single hardcoded `16 00` fired alongside the
        // whole window — which is also the request seen landing on top of the SR08's sleep stream.
        val w = FakeWriter()
        fastJring(w).runStartup()
        drain()
        assertEquals(listOf(0, 1, 2), heartRateDayOffsets(w.sent))

        val history = w.sent.filter { it[0].toInt() == 0x10 || it[0].toInt() == 0x16 }
            .map { (it[0].toInt() and 0xFF) to it[1].toInt() }
        assertEquals(
            "each day is asked for as activity-then-HR, one day at a time",
            listOf(0x10 to 0, 0x16 to 0, 0x10 to 1, 0x16 to 1, 0x10 to 2, 0x16 to 2),
            history,
        )
    }

    @Test
    fun `the Jring backfill window stays inside what the command encodes`() = runBlocking {
        // makeHistoryQueryCommand coerces to 0..27; an offset above that would silently truncate
        // and the request would ask for a different day than the caller meant.
        val w = FakeWriter()
        fastJring(w).runStartup()
        drain()
        val offsets = historyDayOffsets(w.sent) + heartRateDayOffsets(w.sent)
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

    private fun captureColmi(action: (ColmiSyncEngine) -> Unit): List<List<Byte>> {
        val writer = FakeWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        action(engine)
        val commands = writer.sent.map(ByteArray::toList)
        engine.destroy()
        return commands
    }
}
