package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

private class RecordingWriter : RingCommandWriter {
    val sent = mutableListOf<ByteArray>()
    override fun enqueue(command: ByteArray) { sent.add(command) }
}

/** Tests for the [YCBTHistoryTransfer] protocol-driven state machine (iOS #82). Uses a very long
 *  watchdog window so timing never interferes — these tests exercise only the deterministic,
 *  frame-driven transitions. */
class YCBTHistoryTransferTest {

    private fun newTransfer(writer: RingCommandWriter) =
        YCBTHistoryTransfer(writer, inactivityMs = 60_000, absoluteCapMs = 120_000)

    /** `[totalPackets:u16][totalBytes:u16][crc16:u16]` — only the CRC field is read by the
     *  transfer machine, so the packet/byte counts are zero-filled. */
    private fun terminalPayload(buffer: List<UByte>): List<UByte> {
        val crc = YCBTFrame.crc16(buffer)
        return listOf(0u, 0u, 0u, 0u, (crc and 0xff).toUByte(), ((crc shr 8) and 0xff).toUByte())
    }

    @Test
    fun `full happy path requests header data terminal ack and decodes`() {
        val writer = RecordingWriter()
        val transfer = newTransfer(writer)

        transfer.start(listOf(YCBTHistoryType.HEART))
        // The query for HEART went out.
        assertEquals(1, writer.sent.size)
        assertArrayEquals(YCBTHealthCommand.historyRequest(YCBTHistoryType.HEART).toRawByteArray(), writer.sent[0])

        // Header: recordCount=1, totalPackets=1, totalBytes=6 (one 6-byte HR record).
        val header = listOf<UByte>(1u, 0u, 1u, 0u, 0u, 0u, 6u, 0u, 0u, 0u)
        assertTrue(transfer.handle(YCBTHistoryType.HEART.queryKey, header).any { it is RingDecodedEvent.HistorySyncProgress })

        // One HR record: ts=0, mode=0, hr=70.
        val record = listOf<UByte>(0u, 0u, 0u, 0u, 0u, 70u)
        assertTrue(transfer.handle(YCBTHistoryType.HEART.ackKey, record).isEmpty())

        val terminal = transfer.handle(YCBTHealth.TERMINAL_BLOCK, terminalPayload(record))
        assertTrue(terminal.any { it is RingDecodedEvent.HistoryMeasurement && it.kind_field == MeasurementKind.HEART_RATE })
        assertTrue(terminal.any { it is RingDecodedEvent.HistorySyncFinished })

        // The terminal ACK (accepted) went out before decoding, per the protocol contract.
        val ackFrame = writer.sent.last()
        assertArrayEquals(YCBTHealthCommand.historyBlockAck(YCBTHealth.ACK_ACCEPTED).toRawByteArray(), ackFrame)
        assertFalse(transfer.isActive)
    }

    @Test
    fun `empty header advances without treating it as a terminal`() {
        val writer = RecordingWriter()
        val transfer = newTransfer(writer)
        transfer.start(listOf(YCBTHistoryType.HEART, YCBTHistoryType.SPO2))

        // A <=9-byte payload on the query key means "no stored data" — advance to the next type.
        val events = transfer.handle(YCBTHistoryType.HEART.queryKey, listOf(0u, 0u))
        assertTrue(events.none { it is RingDecodedEvent.HistorySyncFinished })
        // Now requesting SPO2.
        assertArrayEquals(YCBTHealthCommand.historyRequest(YCBTHistoryType.SPO2).toRawByteArray(), writer.sent.last())
    }

    @Test
    fun `crc mismatch retries once then skips`() {
        val writer = RecordingWriter()
        val transfer = newTransfer(writer)
        transfer.start(listOf(YCBTHistoryType.HEART))
        transfer.handle(YCBTHistoryType.HEART.queryKey, listOf(1u, 0u, 1u, 0u, 0u, 0u, 6u, 0u, 0u, 0u))
        transfer.handle(YCBTHistoryType.HEART.ackKey, listOf(0u, 0u, 0u, 0u, 0u, 70u))

        // Wrong CRC bytes (6-byte terminal shape, deliberately bad CRC).
        val badTerminal = listOf<UByte>(0u, 0u, 0u, 0u, 0xDEu, 0xADu)
        val firstTerminal = transfer.handle(YCBTHealth.TERMINAL_BLOCK, badTerminal)
        assertTrue(firstTerminal.isEmpty())
        // A re-request for HEART must have gone out (the retry).
        assertEquals(YCBTHealthCommand.historyRequest(YCBTHistoryType.HEART).toRawByteArray().toList(), writer.sent.last().toList())
        assertTrue(transfer.isActive)

        // Header + data again, then a second bad terminal — this time it gives up (advances).
        transfer.handle(YCBTHistoryType.HEART.queryKey, listOf(1u, 0u, 1u, 0u, 0u, 0u, 6u, 0u, 0u, 0u))
        transfer.handle(YCBTHistoryType.HEART.ackKey, listOf(0u, 0u, 0u, 0u, 0u, 70u))
        val secondTerminal = transfer.handle(YCBTHealth.TERMINAL_BLOCK, badTerminal)
        assertTrue(secondTerminal.any { it is RingDecodedEvent.HistorySyncFinished })
    }

    @Test
    fun `permanent error skips the type and is never asked again this session`() {
        val writer = RecordingWriter()
        val transfer = newTransfer(writer)
        transfer.start(listOf(YCBTHistoryType.HEART))

        // 0xFC = unsupported key -> permanent.
        val events = transfer.handle(YCBTHistoryType.HEART.queryKey, listOf(YCBTFrameError.UNSUPPORTED_KEY.rawValue))
        assertTrue(events.any { it is RingDecodedEvent.HistorySyncFinished })

        // Starting again with the same type must skip it outright (no query re-sent).
        writer.sent.clear()
        transfer.start(listOf(YCBTHistoryType.HEART))
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `a transfer already in flight wins over a second start`() {
        val writer = RecordingWriter()
        val transfer = newTransfer(writer)
        transfer.start(listOf(YCBTHistoryType.HEART))
        writer.sent.clear()

        transfer.start(listOf(YCBTHistoryType.SPO2))   // should be ignored — HEART is in flight
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `frames while idle are ignored`() {
        val writer = RecordingWriter()
        val transfer = newTransfer(writer)
        assertTrue(transfer.handle(YCBTHistoryType.HEART.ackKey, listOf(1u, 2u, 3u)).isEmpty())
        assertTrue(writer.sent.isEmpty())
    }
}
