package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from LuckRingDriverTests.swift (iOS #90).
 * The driver's inbound routing, and the one thing no decoder can do: **acknowledging a
 * device-initiated SEND**. The ring retransmits an un-ACKed SEND until the app answers, so a missing
 * ACK wedges the link; an ACK for an ACK, or for a SEND_NO_ACK, is wrong the other way.
 *
 * iOS also asserts a `connectionDidStart()` reset discards a partial frame; Android has no such hook
 * — a fresh [LuckRingDriver] (and its [LuckRingFrameAssembler]) is built per connection (see
 * [LuckRingDriver]'s class doc), so there is no reconnect-reuse path to test here.
 */
class LuckRingDriverTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent.add(command) }
    }

    private fun packets(frame: LuckRingFrame): List<ByteArray> = LuckRingPacketizer.packets(frame)

    // MARK: Auto-ACK

    @Test
    fun `device send is acked and decoded`() {
        val writer = FakeWriter()
        val driver = LuckRingDriver(writer)

        val frame = LuckRingFrame(LuckRingCmdType.SEND, LuckRingDataType.BATTERY, listOf<UByte>(90u, 1u), seq = 5u, devType = 2u)
        val events = driver.ingest(packets(frame)[0], LuckRingUUIDs.NOTIFY)

        assertEquals("a device SEND must be ACKed", 1, writer.sent.size)
        val ack = writer.sent[0]
        assertEquals("ACK cmdType", 4, ack[4].toInt())
        assertEquals("ACK echoes the frame's dataType", LuckRingDataType.BATTERY.toInt(), ack[5].toInt())
        assertEquals("ACK echoes the frame's seq", 5, ack[3].toInt())
        assertEquals("ACK echoes the frame's devType", 2, ack[1].toInt())

        val battery = events.first() as? RingDecodedEvent.Battery ?: return fail("expected battery, got $events")
        assertEquals(90, battery.percent)
    }

    @Test
    fun `device ack is not acked`() {
        val writer = FakeWriter()
        val driver = LuckRingDriver(writer)

        val ack = ByteArray(20)
        ack[1] = 1; ack[4] = 4; ack[5] = LuckRingDataType.MIX_INFO.toByte(); ack[8] = 1; ack[10] = 1
        driver.ingest(ack, LuckRingUUIDs.NOTIFY)

        assertTrue("an ACK must never be ACKed", writer.sent.isEmpty())
    }

    @Test
    fun `send no ack is not acked`() {
        val writer = FakeWriter()
        val driver = LuckRingDriver(writer)

        val payload = LuckRingBytes.le16(1) + listOf<UByte>(1u) + LuckRingBytes.le32(1_700_000_000) + listOf<UByte>(70u)
        val frame = LuckRingFrame(LuckRingCmdType.SEND_NO_ACK, LuckRingDataType.REAL_HEART, payload, seq = 0u, devType = 1u)
        driver.ingest(packets(frame)[0], LuckRingUUIDs.NOTIFY)

        assertTrue("a SEND_NO_ACK expects no reply", writer.sent.isEmpty())
    }

    // MARK: Reassembly

    @Test
    fun `multi packet frame only acks and decodes once complete`() {
        val writer = FakeWriter()
        val driver = LuckRingDriver(writer)

        // A 2-record HR history frame that spans a head + one continuation.
        val payload = LuckRingBytes.le16(2) + listOf<UByte>(2u) +
            LuckRingBytes.le32(1_700_000_000) + listOf<UByte>(72u) +
            LuckRingBytes.le32(1_700_000_060) + listOf<UByte>(75u)
        val frame = LuckRingFrame(LuckRingCmdType.SEND, LuckRingDataType.HISTORY_HEART, payload, seq = 1u, devType = 1u)
        val wire = packets(frame)
        assertTrue(wire.size > 1)

        val firstEvents = driver.ingest(wire[0], LuckRingUUIDs.NOTIFY)
        assertTrue("no decode until the frame is whole", firstEvents.isEmpty())
        assertTrue("no ACK until the frame is whole", writer.sent.isEmpty())

        val lastEvents = driver.ingest(wire[1], LuckRingUUIDs.NOTIFY)
        assertEquals("ACK once, on completion", 1, writer.sent.size)
        assertEquals("both HR records decode", 2, lastEvents.size)
    }
}
