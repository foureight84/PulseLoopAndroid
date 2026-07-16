package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

class YCBTDriverTest {

    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent.add(command.copyOf()) }
    }

    private val streamUUID = YCBTUUIDs.STREAM

    @Test
    fun `DevControl push is acked and decoded`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        val push = YCBTFrame.frame(byteArrayOf(0x04, 0x13, 0x00, 0x01, 72))
        val events = driver.ingest(push, streamUUID)

        assertEquals(1, writer.sent.size)
        assertArrayEquals(byteArrayOf(0x04, 0x13, 0x00), writer.sent[0])
        val hr = events.first() as RingDecodedEvent.HeartRateSample
        assertEquals(72, hr.bpm)
    }

    @Test
    fun `unhandled DevControl push is still acked`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        val events = driver.ingest(YCBTFrame.frame(byteArrayOf(0x04, 0x00, 0x01)), streamUUID)

        assertEquals(1, writer.sent.size)
        assertArrayEquals(byteArrayOf(0x04, 0x00, 0x00), writer.sent[0])
        assertTrue(events.first() is RingDecodedEvent.CommandAck)
    }

    @Test
    fun `DevControl error frame is not acked`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        driver.ingest(YCBTFrame.frame(byteArrayOf(0x04, 0x13, 0xfc.toByte())), streamUUID)
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `live stream frames are not acked`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        driver.ingest(YCBTFrame.frame(byteArrayOf(0x06, 0x01, 82)), streamUUID)
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `connectionDidEnd abandons the in flight history transfer`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)
        val engine = driver.makeSyncEngine()

        engine.syncHistory()
        assertEquals(1, writer.sent.size)
        assertArrayEquals(byteArrayOf(0x05, 0x02), writer.sent[0])

        writer.sent.clear()
        engine.syncHistory()
        assertTrue(writer.sent.isEmpty())

        driver.connectionDidEnd()
        engine.syncHistory()
        assertEquals(1, writer.sent.size)
        assertArrayEquals(byteArrayOf(0x05, 0x02), writer.sent[0])
    }

    @Test
    fun `driver pairs a refusal with the start it sent and only once`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        val refusal = YCBTFrame.frame(byteArrayOf(0x03, 0x2f, 0x01))

        driver.frame(byteArrayOf(0x03, 0x2f, 0x01, 0x0a))
        val events1 = driver.ingest(refusal, streamUUID)
        assertTrue(events1.first() is RingDecodedEvent.MeasurementRejected)
        assertEquals(0x0a, (events1.first() as RingDecodedEvent.MeasurementRejected).mode)

        val events2 = driver.ingest(refusal, streamUUID)
        assertTrue(events2.first() is RingDecodedEvent.CommandAck)

        driver.frame(byteArrayOf(0x03, 0x2f, 0x00, 0x0a))
        val events3 = driver.ingest(refusal, streamUUID)
        assertTrue(events3.first() is RingDecodedEvent.CommandAck)
    }

    @Test
    fun `a stops reply does not consume the mode of the start queued behind it`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        driver.frame(byteArrayOf(0x03, 0x2f, 0x00, 0x02)) // stop SpO2
        driver.frame(byteArrayOf(0x03, 0x2f, 0x01, 0x00)) // start HR

        val ackStop = driver.ingest(YCBTFrame.frame(byteArrayOf(0x03, 0x2f, 0x00)), streamUUID)
        assertTrue(ackStop.first() is RingDecodedEvent.CommandAck)

        val refusal = driver.ingest(YCBTFrame.frame(byteArrayOf(0x03, 0x2f, 0x01)), streamUUID)
        assertTrue(refusal.first() is RingDecodedEvent.MeasurementRejected)
        assertEquals(0x00, (refusal.first() as RingDecodedEvent.MeasurementRejected).mode)
    }

    @Test
    fun `a NAKed stop cannot cancel the measurement started behind it`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        driver.frame(byteArrayOf(0x03, 0x2f, 0x00, 0x0a)) // stop HRV
        driver.frame(byteArrayOf(0x03, 0x2f, 0x01, 0x00)) // start HR

        val nak = driver.ingest(YCBTFrame.frame(byteArrayOf(0x03, 0x2f, 0x02)), streamUUID)
        assertTrue(nak.first() is RingDecodedEvent.CommandAck)

        val refusal = driver.ingest(YCBTFrame.frame(byteArrayOf(0x03, 0x2f, 0x01)), streamUUID)
        assertTrue(refusal.first() is RingDecodedEvent.MeasurementRejected)
        assertEquals(0x00, (refusal.first() as RingDecodedEvent.MeasurementRejected).mode)
    }

    @Test
    fun `a reconnect drops the commands the old link never answered`() {
        val writer = FakeWriter()
        val driver = YCBTDriver(writer)

        driver.frame(byteArrayOf(0x03, 0x2f, 0x01, 0x0a))
        driver.connectionDidEnd()
        driver.connectionDidStart()

        val events = driver.ingest(YCBTFrame.frame(byteArrayOf(0x03, 0x2f, 0x01)), streamUUID)
        assertTrue(events.first() is RingDecodedEvent.CommandAck)
    }
}
