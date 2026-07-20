package com.pulseloop.ring

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from LuckRingHistorySyncTests.swift (iOS #90).
 * The history pager: it walks the catalog one type at a time, advancing when a type's data settles
 * or timing it out when nothing comes, refuses a re-entrant `start`, and signals completion. The K6
 * streams carry no terminal marker, so this time-settled sequencing is the whole of the transfer
 * contract. Uses real (short) millisecond delays -- [LuckRingHistorySync] schedules its own timers on
 * a real dispatcher, matching [YCBTHistoryTransfer]'s identical convention.
 */
class LuckRingHistorySyncTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent.add(command) }

        /** The dataType of each request packet written (head byte [5]); REQUEST frames only. */
        val requestedTypes: List<UByte> get() = sent.map { it[5].toUByte() }
    }

    private class Spy {
        val stages = mutableListOf<String>()
        val didFinish: Boolean get() = stages.contains("done")
    }

    private fun makeSync(writer: FakeWriter, spy: Spy, settleMs: Long, stallMs: Long): LuckRingHistorySync =
        LuckRingHistorySync(writer, settleMs, stallMs) { event ->
            if (event is PulseEvent.SyncProgress) spy.stages.add(event.stage)
        }

    @Test
    fun `sequential advance on data settle`() = runBlocking {
        val writer = FakeWriter()
        val spy = Spy()
        val sync = makeSync(writer, spy, settleMs = 50, stallMs = 5000)

        sync.start(listOf(5u, 6u))
        assertEquals("the first type is requested immediately", listOf<UByte>(5u), writer.requestedTypes)

        sync.noteReceived(5u)
        delay(200)
        assertEquals("the pass advanced once type 5's data settled", listOf<UByte>(5u, 6u), writer.requestedTypes)

        sync.noteReceived(6u)
        delay(200)
        assertFalse("the queue drained", sync.isRunning)
        assertTrue("completion is signalled", spy.didFinish)
    }

    @Test
    fun `unsupported type is skipped on stall`() = runBlocking {
        val writer = FakeWriter()
        val spy = Spy()
        val sync = makeSync(writer, spy, settleMs = 5000, stallMs = 50)

        sync.start(listOf(42u))   // no data will ever arrive
        assertEquals(listOf<UByte>(42u), writer.requestedTypes)

        delay(200)
        assertFalse("a type that never answers is skipped on the stall timeout", sync.isRunning)
        assertTrue(spy.didFinish)
    }

    @Test
    fun `re entrant start is ignored while running`() = runBlocking {
        val writer = FakeWriter()
        val spy = Spy()
        val sync = makeSync(writer, spy, settleMs = 5000, stallMs = 5000)

        sync.start(listOf(5u))
        sync.start(listOf(6u))   // must not interrupt the in-flight pass
        assertEquals(listOf<UByte>(5u), writer.requestedTypes)
        sync.cancel()            // stop the in-flight timer...
        delay(50)                // ...and let the cancelled job retire
    }

    @Test
    fun `cancel stops the pass`() = runBlocking {
        val writer = FakeWriter()
        val spy = Spy()
        val sync = makeSync(writer, spy, settleMs = 50, stallMs = 50)

        sync.start(listOf(5u, 6u, 8u))
        sync.cancel()
        delay(200)
        assertFalse(sync.isRunning)
        assertEquals("cancel halts before any further type is requested", listOf<UByte>(5u), writer.requestedTypes)
    }

    @Test
    fun `catalog and vitals subsets are what we expect`() {
        assertEquals(listOf<UByte>(5u, 6u, 8u, 40u, 41u, 42u, 47u, 53u), LuckRingHistorySync.catalog)
        assertEquals(listOf<UByte>(8u, 40u), LuckRingHistorySync.vitalsTypes)
    }
}
