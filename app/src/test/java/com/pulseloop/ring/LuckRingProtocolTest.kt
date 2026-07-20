package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from LuckRingProtocolTests.swift (iOS #90).
 * The K6 "Protocol B" framing: the 20-byte packetizer (head / continuation / ACK), the assembler's
 * round-trip and its recovery from a stale head, and the MixInfo TLV. These are the bytes the ring
 * sees and the bytes it sends, so a golden-byte pin here is the whole contract.
 */
class LuckRingProtocolTest {

    // MARK: Packetizer

    @Test
    fun `head packet golden bytes`() {
        // REQUEST devInfo (cmd 3, dataType 2, empty payload), seq 0, devType 1.
        val frame = LuckRingFrame(LuckRingCmdType.REQUEST, 2u, emptyList(), seq = 0u, devType = 1u)
        val packets = LuckRingPacketizer.packets(frame)
        assertEquals(1, packets.size)
        assertEquals(20, packets[0].size)
        assertArrayEquals(
            byteArrayOf(0, 1, 0, 0, 3, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            packets[0],
        )
    }

    @Test
    fun `single packet carries first ten payload bytes`() {
        val frame = LuckRingFrame(LuckRingCmdType.SEND, 24u, listOf<UByte>(1u), seq = 7u, devType = 1u)
        val packets = LuckRingPacketizer.packets(frame)
        assertEquals(1, packets.size)
        val bytes = packets[0]
        assertEquals(0, bytes[0].toInt())       // head marker
        assertEquals(1, bytes[1].toInt())       // devType
        assertEquals(0, bytes[2].toInt())       // continuation pages
        assertEquals(7, bytes[3].toInt())       // seq
        assertEquals(1, bytes[4].toInt())       // cmdType SEND
        assertEquals(24, bytes[5].toInt())      // dataType
        assertEquals(1, bytes[8].toInt())       // payload length LE
        assertEquals(0, bytes[9].toInt())
        assertEquals(1, bytes[10].toInt())      // payload[0]
    }

    @Test
    fun `ack golden bytes`() {
        val ack = LuckRingPacketizer.ack(dataType = 8u, seq = 5u, devType = 1u)
        assertEquals(20, ack.size)
        assertArrayEquals(
            byteArrayOf(0, 1, 0, 5, 4, 8, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            ack,
        )
    }

    @Test
    fun `multi packet split and page count`() {
        // 25-byte payload -> head (first 10) + 1 continuation (next 15 of 19).
        val payload = (0..24).map { it.toUByte() }
        assertEquals(1, LuckRingPacketizer.continuationPages(25))
        val frame = LuckRingFrame(LuckRingCmdType.SEND, 110u, payload, seq = 3u, devType = 1u)
        val packets = LuckRingPacketizer.packets(frame)
        assertEquals(2, packets.size)
        assertEquals(20, packets[0].size)
        assertEquals(1, packets[0][2].toInt())     // one continuation page declared
        assertEquals(1, packets[1][0].toInt())     // continuation index (1-based)
        val expectedTail = payload.subList(10, 25).map { it.toByte() }.toByteArray()
        assertArrayEquals(expectedTail, packets[1].copyOfRange(1, 16))
    }

    // MARK: Assembler round-trip

    @Test
    fun `assembler round trips a single packet`() {
        val assembler = LuckRingFrameAssembler()
        val frame = LuckRingFrame(LuckRingCmdType.SEND, 3u, listOf<UByte>(88u, 1u), seq = 9u, devType = 1u)
        var completed: LuckRingFrame? = null
        for (packet in LuckRingPacketizer.packets(frame)) completed = assembler.append(packet)
        assertEquals(frame, completed)
    }

    @Test
    fun `assembler round trips a multi packet frame`() {
        val assembler = LuckRingFrameAssembler()
        val payload = (0..66).map { (it and 0xff).toUByte() }   // the MixInfo bundle's size
        val frame = LuckRingFrame(LuckRingCmdType.SEND, 110u, payload, seq = 4u, devType = 1u)
        val packets = LuckRingPacketizer.packets(frame)
        assertEquals(4, packets.size)   // head + 3 continuations

        var completed: LuckRingFrame? = null
        for ((i, packet) in packets.withIndex()) {
            val result = assembler.append(packet)
            if (i < packets.size - 1) assertNull("must not complete until the last continuation", result)
            else completed = result
        }
        assertEquals(frame, completed)
    }

    @Test
    fun `assembler trims padding when payload does not fill the last page`() {
        val assembler = LuckRingFrameAssembler()
        // 25 bytes needs one continuation but fills only 15 of its 19 payload slots: the last packet
        // is zero-padded on the wire, and the assembler must cut back to the head's declared length.
        val payload = (1..25).map { it.toUByte() }
        val frame = LuckRingFrame(LuckRingCmdType.SEND, 47u, payload, seq = 3u, devType = 1u)
        var completed: LuckRingFrame? = null
        for (packet in LuckRingPacketizer.packets(frame)) completed = assembler.append(packet)
        assertEquals(25, completed?.payload?.size)
        assertEquals(frame, completed)
    }

    @Test
    fun `assembler recovers from a stale head`() {
        val assembler = LuckRingFrameAssembler()
        // A head that promises continuations, then a *new* head before they arrive: the partial is dropped.
        val abandoned = LuckRingFrame(LuckRingCmdType.SEND, 5u, (0..29).map { it.toUByte() }, seq = 1u, devType = 1u)
        assembler.append(LuckRingPacketizer.packets(abandoned)[0])   // head only, no continuations

        val fresh = LuckRingFrame(LuckRingCmdType.SEND, 3u, listOf<UByte>(77u, 0u), seq = 2u, devType = 1u)
        val completed = assembler.append(LuckRingPacketizer.packets(fresh)[0])
        assertEquals("a fresh head mid-assembly abandons the stale partial and completes", fresh, completed)
    }

    @Test
    fun `assembler drops a continuation with no head`() {
        val assembler = LuckRingFrameAssembler()
        val continuation = ByteArray(20)
        continuation[0] = 1
        assertNull(assembler.append(continuation))
    }

    @Test
    fun `assembler parses a device ack`() {
        val assembler = LuckRingFrameAssembler()
        // A head that is a device ACK: [4]=4, status at [10].
        val ack = ByteArray(20)
        ack[1] = 1; ack[3] = 6; ack[4] = 4; ack[5] = 111; ack[8] = 1; ack[10] = 1
        val frame = assembler.append(ack)
        assertEquals(LuckRingCmdType.ACK, frame?.cmdType)
        assertEquals(111.toUByte(), frame?.dataType)
        assertEquals(listOf<UByte>(1u), frame?.payload)
    }

    // MARK: MixInfo TLV

    @Test
    fun `mixinfo tlv round trip`() {
        val properties = listOf(
            LuckRingMixInfoTLV.Property(102u, listOf<UByte>(1u, 2u, 3u, 4u)),
            LuckRingMixInfoTLV.Property(124u, listOf<UByte>(1u, 0xFFu, 0xFFu, 0u, 0u)),
            LuckRingMixInfoTLV.Property(120u, listOf<UByte>(1u, 0u)),
        )
        val encoded = LuckRingMixInfoTLV.encode(properties)
        // Header: [totalLen u16 LE][itemCount], totalLen = sum propBytes + 1.
        val propBytesLen = (4 + 3) + (5 + 3) + (2 + 3)
        assertEquals(propBytesLen + 1, LuckRingBytes.u16(encoded, 0))
        assertEquals(3.toUByte(), encoded[2])
        assertEquals(properties, LuckRingMixInfoTLV.decode(encoded))
    }
}
