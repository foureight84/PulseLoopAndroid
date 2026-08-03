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

    /** Byte 1 of the `0x10` history query is the day count (`triggerActivityReportByDays`). */
    private fun historyDays(sent: List<ByteArray>): List<Int> =
        sent.filter { it[0].toInt() == 0x10 }.map { it[1].toInt() }

    @Test
    fun `Jring pulls a deeper history window once per connection, then one day per pass`() {
        // Issue #43. A single-day request means stored history can only grow one night at a time
        // from install and never recovers what the ring already holds. But runStartup is also the
        // ~30-minute background sync, so the deep window must NOT repeat: 0x10 returns activity as
        // well as sleep, roughly 96 packets per extra day.
        val w = FakeWriter()
        val engine = JringSyncEngine(w)

        engine.runStartup()
        assertEquals(listOf(3), historyDays(w.sent))

        w.sent.clear()
        engine.runStartup()
        engine.refresh()      // routes through runStartup
        engine.querySleep()   // ditto
        assertEquals(listOf(1, 1, 1), historyDays(w.sent))

        // A new connection builds a new engine, which backfills again.
        val reconnected = FakeWriter()
        JringSyncEngine(reconnected).runStartup()
        assertEquals(listOf(3), historyDays(reconnected.sent))
    }

    @Test
    fun `the Jring backfill window stays inside what the command encodes`() {
        // makeHistoryQueryCommand coerces to 0..27; a window above that would silently truncate
        // and the request would no longer mean what the constant says.
        val w = FakeWriter()
        JringSyncEngine(w).runStartup()
        val requested = historyDays(w.sent).single()
        assertTrue("backfill window $requested must survive the 0..27 coerce", requested in 1..27)
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
