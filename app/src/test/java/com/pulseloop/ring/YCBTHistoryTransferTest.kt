package com.pulseloop.ring

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YCBTHistoryTransferTest {

    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent.add(command.copyOf()) }
    }

    private val heartQuery = byteArrayOf(0x05, 0x06)
    private val allQuery = byteArrayOf(0x05, 0x09)
    private val ackAccepted = byteArrayOf(0x05, 0x80.toByte(), 0x00)
    private val ackCrcFailure = byteArrayOf(0x05, 0x80.toByte(), 0x04)

    private fun header(records: Int, packets: Int, bytes: Int): ByteArray {
        return byteArrayOf(
            (records and 0xFF).toByte(), (records shr 8).toByte(),
            (packets and 0xFF).toByte(), (packets shr 8).toByte(), 0, 0,
            (bytes and 0xFF).toByte(), (bytes shr 8).toByte(), 0, 0,
        )
    }

    private fun terminal(packets: Int, buffer: ByteArray, crc: Int? = null): ByteArray {
        val checksum = crc ?: YCBTFrame.crc16(buffer)
        return byteArrayOf(
            (packets and 0xFF).toByte(), (packets shr 8).toByte(),
            (buffer.size and 0xFF).toByte(), ((buffer.size shr 8) and 0xFF).toByte(),
            (checksum and 0xFF).toByte(), ((checksum shr 8) and 0xFF).toByte(),
        )
    }

    private val heartBuffer = byteArrayOf(
        0x1c, 0xf0.toByte(), 0xde.toByte(), 0x31, 0x00, 0x47,
        0x1a, 0xfe.toByte(), 0xde.toByte(), 0x31, 0x00, 0x42,
    )

    private fun heartRates(events: List<RingDecodedEvent>): List<Double> {
        return events.mapNotNull { event ->
            if (event is RingDecodedEvent.HistoryMeasurement && event.kind_field == MeasurementKind.HEART_RATE) event.value else null
        }
    }

    private fun drainNoData(writer: FakeWriter, transfer: YCBTHistoryTransfer): List<Int> {
        val requested = mutableListOf<Int>()
        while (true) {
            val query = writer.sent.firstOrNull {
                it.size == 2 && it[0] == YCBTGroup.HEALTH.toByte()
            } ?: break
            writer.sent.clear()
            val key = query[1].toInt() and 0xFF
            requested += key
            transfer.handle(cmd = key, payload = byteArrayOf(0x00))
        }
        return requested
    }

    @Test
    fun `full cycle acks and advances to next type`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)

        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))
        assertEquals(1, writer.sent.size)
        assertArrayEquals(heartQuery, writer.sent[0])

        val progress = transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        assertTrue(progress.first() is RingDecodedEvent.HistorySyncProgress)
        assertEquals("Syncing heart rate…", (progress.first() as RingDecodedEvent.HistorySyncProgress).stage)

        assertTrue(transfer.handle(cmd = 0x15, payload = heartBuffer).isEmpty())

        writer.sent.clear()
        val done = transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = heartBuffer))

        assertEquals(listOf(71.0, 66.0), heartRates(done))
        assertEquals(2, writer.sent.size)
        assertArrayEquals(ackAccepted, writer.sent[0])
        assertArrayEquals(allQuery, writer.sent[1])
    }

    @Test
    fun `record straddling two data frames survives`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        transfer.start(types = listOf(YCBTHistoryType.HEART))

        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 2, bytes = heartBuffer.size))
        transfer.handle(cmd = 0x15, payload = heartBuffer.copyOfRange(0, 9))
        transfer.handle(cmd = 0x15, payload = heartBuffer.copyOfRange(9, heartBuffer.size))
        val done = transfer.handle(cmd = 0x80, payload = terminal(packets = 2, buffer = heartBuffer))

        assertEquals(listOf(71.0, 66.0), heartRates(done))
    }

    @Test
    fun `CRC mismatch nacks and retries the type once`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))

        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        transfer.handle(cmd = 0x15, payload = heartBuffer)

        writer.sent.clear()
        val first = transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = heartBuffer, crc = 0xdead))
        assertTrue(heartRates(first).isEmpty())
        assertEquals(2, writer.sent.size)
        assertArrayEquals(ackCrcFailure, writer.sent[0])
        assertArrayEquals(heartQuery, writer.sent[1])

        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        transfer.handle(cmd = 0x15, payload = heartBuffer)
        writer.sent.clear()
        transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = heartBuffer, crc = 0xdead))
        assertEquals(2, writer.sent.size)
        assertArrayEquals(ackCrcFailure, writer.sent[0])
        assertArrayEquals(allQuery, writer.sent[1])
    }

    @Test
    fun `no data header advances without acking`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))

        writer.sent.clear()
        transfer.handle(cmd = 0x06, payload = byteArrayOf(0x00))
        assertEquals(1, writer.sent.size)
        assertArrayEquals(allQuery, writer.sent[0])
    }

    @Test
    fun `error frame advances and unsupported type is not requested again`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))

        writer.sent.clear()
        transfer.handle(cmd = 0x06, payload = byteArrayOf(0xfc.toByte()))
        assertEquals(1, writer.sent.size)
        assertArrayEquals(allQuery, writer.sent[0])

        transfer.handle(cmd = 0x09, payload = byteArrayOf(0xfc.toByte()))
        writer.sent.clear()
        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `late terminal from skipped type cannot corrupt the next transfer`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        val allBuffer = ByteArray(20) { it.toByte() }

        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))
        transfer.handle(cmd = 0x06, payload = byteArrayOf(0xfc.toByte()))
        transfer.handle(cmd = 0x09, payload = header(records = 1, packets = 2, bytes = allBuffer.size))
        transfer.handle(cmd = 0x18, payload = allBuffer)
        writer.sent.clear()

        val events = transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = heartBuffer))

        assertTrue(events.isEmpty())
        assertTrue(writer.sent.isEmpty())
        assertTrue(transfer.isActive)
    }

    @Test
    fun `startup requests only baseline history until capabilities arrive`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        val engine = YCBTSyncEngine(writer = writer, transfer = transfer)
        engine.runStartup()

        val requested = mutableListOf<Int>()
        repeat(4) {
            val query = writer.sent.lastOrNull { it.size == 2 && it[0] == 0x05.toByte() }
            if (query == null) return@repeat
            requested.add(query[1].toInt() and 0xFF)
            transfer.handle(cmd = query[1].toInt() and 0xFF, payload = byteArrayOf(0x00))
        }
        assertEquals(listOf(0x02, 0x04, 0x06, 0x09), requested)
    }

    @Test
    fun `sync vitals history uses heart and combined records`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        val engine = YCBTSyncEngine(writer = writer, transfer = transfer)
        engine.syncVitalsHistory()

        val requested = mutableListOf<Int>()
        while (true) {
            val query = writer.sent.lastOrNull { it.size == 2 && it[0] == 0x05.toByte() } ?: break
            requested.add(query[1].toInt() and 0xFF)
            writer.sent.clear()
            transfer.handle(cmd = query[1].toInt() and 0xFF, payload = byteArrayOf(0x00))
        }
        assertEquals(listOf(0x06, 0x09), requested)
    }

    @Test
    fun `sync history reruns the full catalog`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        val engine = YCBTSyncEngine(writer = writer, transfer = transfer)
        engine.refresh()

        assertEquals(2, writer.sent.size)
        assertArrayEquals(byteArrayOf(0x03, 0x09, 0x01, 0x00, 0x02), writer.sent[0])
        assertArrayEquals(byteArrayOf(0x05, 0x02), writer.sent[1])
    }

    @Test
    fun `start is ignored while a transfer is in flight`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))
        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        assertTrue(transfer.isActive)

        writer.sent.clear()
        transfer.start(types = listOf(YCBTHistoryType.SLEEP))
        assertTrue(writer.sent.isEmpty())

        transfer.handle(cmd = 0x15, payload = heartBuffer)
        val done = transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = heartBuffer))
        assertEquals(listOf(71.0, 66.0), heartRates(done))
        assertEquals(2, writer.sent.size)
        assertArrayEquals(ackAccepted, writer.sent[0])
        assertArrayEquals(allQuery, writer.sent[1])
    }

    @Test
    fun `append preserves the active transfer and adds new types once`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)

        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))
        writer.sent.clear()
        transfer.append(
            listOf(
                YCBTHistoryType.HEART,
                YCBTHistoryType.ALL,
                YCBTHistoryType.SLEEP,
                YCBTHistoryType.SLEEP,
            ),
        )
        assertTrue(writer.sent.isEmpty())

        transfer.handle(cmd = 0x06, payload = byteArrayOf(0x00))
        assertEquals(listOf(0x09, 0x04), drainNoData(writer, transfer))
    }

    @Test
    fun `append while idle starts the added history`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)

        transfer.append(listOf(YCBTHistoryType.SLEEP))

        assertArrayEquals(byteArrayOf(0x05, 0x04), writer.sent.single())
        assertTrue(transfer.isActive)
    }

    @Test
    fun `frames while idle are ignored`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        assertTrue(transfer.handle(cmd = 0x15, payload = heartBuffer).isEmpty())
        assertTrue(transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = byteArrayOf())).isEmpty())
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `duplicate final terminal after completion is ignored`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)
        val done = terminal(packets = 1, buffer = heartBuffer)

        transfer.start(types = listOf(YCBTHistoryType.HEART))
        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        transfer.handle(cmd = 0x15, payload = heartBuffer)
        transfer.handle(cmd = 0x80, payload = done)

        writer.sent.clear()
        assertTrue(transfer.handle(cmd = 0x80, payload = done).isEmpty())
        assertTrue(writer.sent.isEmpty())
        assertFalse(transfer.isActive)
    }

    @Test
    fun `terminal after cancel is ignored`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer)

        transfer.start(types = listOf(YCBTHistoryType.HEART))
        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        transfer.handle(cmd = 0x15, payload = heartBuffer)
        transfer.cancel()

        writer.sent.clear()
        assertTrue(transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = heartBuffer)).isEmpty())
        assertTrue(writer.sent.isEmpty())
        assertFalse(transfer.isActive)
    }

    @Test
    fun `watchdog skips a stalled type and never acks`() = runBlocking {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer, inactivitySeconds = 0.05, absoluteCapSeconds = 0.2)

        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))
        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        transfer.handle(cmd = 0x15, payload = heartBuffer)

        delay(300)

        assertEquals(2, writer.sent.size)
        assertArrayEquals(heartQuery, writer.sent[0])
        assertArrayEquals(allQuery, writer.sent[1])
        assertFalse(writer.sent.any { it.size >= 2 && it[0] == 0x05.toByte() && it[1] == 0x80.toByte() })
    }

    @Test
    fun `watchdog is not armed after completion`() = runBlocking {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer, inactivitySeconds = 0.05, absoluteCapSeconds = 0.2)

        transfer.start(types = listOf(YCBTHistoryType.HEART))
        transfer.handle(cmd = 0x06, payload = header(records = 2, packets = 1, bytes = heartBuffer.size))
        transfer.handle(cmd = 0x15, payload = heartBuffer)
        transfer.handle(cmd = 0x80, payload = terminal(packets = 1, buffer = heartBuffer))

        writer.sent.clear()
        delay(300)
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `cancel stops the watchdog from walking the queue`() = runBlocking {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer = writer, inactivitySeconds = 0.05, absoluteCapSeconds = 0.2)

        transfer.start(types = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL))
        transfer.cancel()
        writer.sent.clear()

        delay(300)
        assertTrue(writer.sent.isEmpty())
        assertFalse(transfer.isActive)
    }

    @Test
    fun `final watchdog completion is emitted through the out of band event sink`() = runBlocking {
        val writer = FakeWriter()
        val emitted = mutableListOf<RingDecodedEvent>()
        val transfer = YCBTHistoryTransfer(
            writer = writer,
            inactivitySeconds = 0.05,
            absoluteCapSeconds = 0.1,
            onOutOfBandEvents = { emitted.addAll(it) },
        )

        transfer.start(types = listOf(YCBTHistoryType.HEART))
        delay(200)

        assertEquals(listOf(RingDecodedEvent.HistorySyncFinished), emitted)
        assertFalse(transfer.isActive)
    }
}
