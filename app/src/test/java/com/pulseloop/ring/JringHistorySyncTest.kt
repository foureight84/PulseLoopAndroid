package com.pulseloop.ring

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The jring history pager (issue #73, second half).
 *
 * The bug this guards against is not "the wrong day was requested" — that was the first half, and
 * [ExistingFamilyRefreshContractTest] covers it. It is that **the requests were sent together**:
 * an SR08 capture showed `10 00`, `10 01`, `10 02` and `16 00` inside 176 ms, with `10 02` landing
 * between two of today's sleep packets, and only four `0x11` packets arriving in all. So the
 * discriminating assertion here is the negative one — that nothing further is sent *while a stream
 * is still arriving*.
 *
 * Uses real (short) millisecond delays: [JringHistorySync] schedules on a real dispatcher, matching
 * the convention in [LuckRingHistorySyncTest] and [YCBTHistoryTransfer].
 */
class JringHistorySyncTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent += command.copyOf() }

        /** Each history request as `opcode to dayOffset`, in the order it went out. */
        val requests: List<Pair<Int, Int>>
            get() = sent.map { (it[0].toInt() and 0xFF) to (it[1].toInt() and 0xFF) }
    }

    private fun activityFrame() = ByteArray(20).also { it[0] = 0x10 }
    private fun sleepFrame() = ByteArray(20).also { it[0] = 0x11 }
    private fun hrFrame() = ByteArray(20).also { it[0] = 0x16; it[1] = 0xA0.toByte() }
    private fun hrFinishedFrame() = ByteArray(20).also { it[0] = 0x16; it[1] = 0xFF.toByte() }

    private fun makeSync(writer: FakeWriter, settleMs: Long = 50, stallMs: Long = 5_000) =
        JringHistorySync(writer, settleMs = settleMs, stallMs = stallMs)

    /** Only the history requests: a pass driven through [JringDriver] carries the connect
     *  handshake (status, time sync, locale, user info, auto-HR, band function) as well. */
    private fun historyRequests(writer: FakeWriter): List<Pair<Int, Int>> =
        writer.requests.filter { it.first == 0x10 || it.first == 0x16 }

    @Test
    fun `a day's next request is held while its stream is still arriving`() = runBlocking {
        // THE regression test for the RC1 capture. Sleep packets keep arriving past what would
        // have been the settle deadline; nothing may be sent on top of them.
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 200)

        sync.start(listOf(0, 1, 2))
        assertEquals("only today is requested up front", listOf(0x10 to 0), writer.requests)

        repeat(5) {
            sync.noteFrame(sleepFrame())
            delay(50)   // well inside the settle window: the stream is live, never quiet
        }
        assertEquals(
            "no second request while today's sleep is still streaming",
            listOf(0x10 to 0),
            writer.requests,
        )

        delay(500)
        assertEquals(
            "once the stream goes quiet, today's HR follows — and only then",
            listOf(0x10 to 0, 0x16 to 0),
            writer.requests,
        )
        sync.cancel()
    }

    @Test
    fun `each day is one activity request then that day's heart rate`() = runBlocking {
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 60)

        sync.start(listOf(0, 1))

        sync.noteFrame(activityFrame())
        sync.noteFrame(sleepFrame())
        delay(150)
        assertEquals(listOf(0x10 to 0, 0x16 to 0), writer.requests)

        sync.noteFrame(hrFinishedFrame())
        delay(50)
        assertEquals(
            "the next day only starts after the previous day's HR finished",
            listOf(0x10 to 0, 0x16 to 0, 0x10 to 1),
            writer.requests,
        )

        sync.noteFrame(activityFrame())
        delay(150)
        sync.noteFrame(hrFinishedFrame())
        delay(50)
        assertEquals(listOf(0x10 to 0, 0x16 to 0, 0x10 to 1, 0x16 to 1), writer.requests)
        assertFalse("the queue drained", sync.isRunning)
    }

    @Test
    fun `heart rate's own end marker advances the day without waiting out the timer`() = runBlocking {
        // 0x16/0xFF is the one real end-of-stream signal in this protocol (RingDecoder decodes it
        // to HistorySyncFinished). The settle window here is a full second, so an advance that
        // lands 50 ms after the marker can only have come from the marker.
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 1_000, stallMs = 30_000)

        sync.start(listOf(0, 1))
        sync.noteFrame(activityFrame())
        delay(1_200)   // today's activity/sleep settles into today's HR
        assertEquals(listOf(0x10 to 0, 0x16 to 0), writer.requests)

        sync.noteFrame(hrFrame())          // data: re-arms the 1 s settle
        sync.noteFrame(hrFinishedFrame())  // marker: must not wait for it
        delay(50)
        assertEquals(listOf(0x10 to 0, 0x16 to 0, 0x10 to 1), writer.requests)
        sync.cancel()
    }

    @Test
    fun `a day that answers nothing still gets its heart rate request, then moves on`() = runBlocking {
        // `16 00` used to be sent unconditionally, and it is the request that always worked. A
        // silent 0x10 must not take today's HR down with it.
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 5_000, stallMs = 40)

        sync.start(listOf(0))
        delay(200)
        assertEquals(listOf(0x10 to 0, 0x16 to 0), writer.requests)
        assertFalse("a day nothing answers cannot hang the queue", sync.isRunning)
    }

    @Test
    fun `a re-entrant start is declined, not merged`() = runBlocking {
        // runStartup is also the ~30-minute background sync, so this lands mid-backfill in normal
        // use. Restarting would abandon the in-flight day mid-stream — the exact truncation.
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 5_000, stallMs = 5_000)

        assertTrue("the first pass begins", sync.start(listOf(0, 1, 2)))
        assertFalse("a pass already in flight wins", sync.start(listOf(0)))
        assertEquals(listOf(0x10 to 0), writer.requests)

        sync.cancel()
        delay(50)
        assertTrue("a pass may begin again once the previous one is done", sync.start(listOf(0)))
        sync.cancel()
    }

    @Test
    fun `cancel halts the pass where it stands`() = runBlocking {
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 30, stallMs = 30)

        sync.start(listOf(0, 1, 2))
        sync.cancel()
        delay(200)
        assertFalse(sync.isRunning)
        assertEquals("no further day is requested after cancel", listOf(0x10 to 0), writer.requests)
    }

    @Test
    fun `a frame arriving as the settle fires cannot advance the day twice`() = runBlocking {
        // The epoch guard in armTimer: a settle coroutine already past its delay and waiting on
        // the monitor must not act once an arriving frame has re-armed the leg.
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 20, stallMs = 5_000)

        sync.start(listOf(0, 1))
        repeat(20) {
            sync.noteFrame(sleepFrame())
            delay(2)
        }
        delay(200)
        assertEquals(
            "exactly one HR request for today, however the timers raced",
            listOf(0x10 to 0, 0x16 to 0),
            writer.requests,
        )
        sync.cancel()
    }

    // MARK: - Wiring: the driver is what feeds and ends a pass

    @Test
    fun `the driver feeds inbound frames to the pager`() = runBlocking {
        // The wiring that makes all of the above reach production: JringDriver.ingest calls
        // noteFrame. Fed through the real driver rather than the pager directly, because an
        // engine-level test cannot see a frame at all.
        val writer = FakeWriter()
        val driver = JringDriver(writer, makeSync(writer, settleMs = 150, stallMs = 30_000))
        driver.makeSyncEngine().runStartup()
        assertEquals(listOf(0x10 to 0), historyRequests(writer))

        repeat(4) {
            driver.ingest(sleepFrame(), RingUUIDs.NOTIFY)
            delay(40)
        }
        assertEquals(
            "a stream arriving through ingest holds the next request",
            listOf(0x10 to 0),
            historyRequests(writer),
        )

        delay(300)
        assertEquals(listOf(0x10 to 0, 0x16 to 0), historyRequests(writer))
        driver.connectionDidEnd()
    }

    @Test
    fun `a disconnect drops the in-flight pass`() = runBlocking {
        // The writer outlives the driver, so a timer surviving the disconnect would land a stray
        // 0x10 in the *next* connection — issue #73's truncation, from a connection that ended.
        val writer = FakeWriter()
        val driver = JringDriver(writer, makeSync(writer, settleMs = 10, stallMs = 10))
        driver.makeSyncEngine().runStartup()
        assertEquals(listOf(0x10 to 0), historyRequests(writer))

        driver.connectionDidEnd()
        delay(300)
        assertEquals(
            "no further history request survives the disconnect",
            listOf(0x10 to 0),
            historyRequests(writer),
        )
    }

    @Test
    fun `a frame too short to be a packet does not hold the stream open`() = runBlocking {
        // RingPacket.fromData rejects anything that isn't exactly 20 bytes, and the decoder with
        // it — so the pager must not treat a fragment as a live frame either, or it re-arms the
        // settle window on data the app will never read and the day stalls out to the full settle.
        val writer = FakeWriter()
        val sync = JringHistorySync(writer, settleMs = 5_000, stallMs = 100)

        sync.start(listOf(0))
        sync.noteFrame(ByteArray(19).also { it[0] = 0x11 })   // one byte short of a packet
        delay(300)
        assertEquals(
            "the fragment is not a frame: the leg stalls out on schedule",
            listOf(0x10 to 0, 0x16 to 0),
            writer.requests,
        )
        sync.cancel()
    }

    @Test
    fun `frames for a leg that is not in flight are ignored`() = runBlocking {
        val writer = FakeWriter()
        val sync = makeSync(writer, settleMs = 40, stallMs = 5_000)

        sync.noteFrame(sleepFrame())   // before any pass
        assertEquals(emptyList<Pair<Int, Int>>(), writer.requests)

        sync.start(listOf(0))
        sync.noteFrame(hrFrame())      // HR frame during the activity leg: not this leg's business
        delay(150)
        assertEquals(
            "a stray HR frame does not settle the activity leg early",
            listOf(0x10 to 0),
            writer.requests,
        )
        sync.cancel()
    }
}
