package com.pulseloop.ring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Oracles for the RWfit wire codecs, taken from the vendor app
 * (`decompiled-rwfit-official/sources/`) rather than from iOS or from the implementation itself.
 *
 * These exist because the first RWfit driver (PR #45) invented its framing and nothing caught it:
 * every assertion below fails against that implementation.
 */
class RWfitCodecTest {

    // ── Checksums ────────────────────────────────────────────────────────────────

    @Test
    fun `xor checksum folds the whole payload`() {
        // y5/b.java o(): seed with [0], xor the rest.
        assertEquals(0x00.toByte(), RWfitProtocol.xorChecksum(byteArrayOf(0x0F, 0x0F)))
        assertEquals(0x01.toByte(), RWfitProtocol.xorChecksum(byteArrayOf(0x01)))
        assertEquals(0x07.toByte(), RWfitProtocol.xorChecksum(byteArrayOf(0x01, 0x02, 0x04)))
    }

    @Test
    fun `crc16 matches the CRC-16 ARC published vectors`() {
        // The vendor ships the table form (y5/d.java f20005a); these are the standard ARC vectors
        // that identify it — table[1] = 0xC0C1 etc.
        assertEquals(0xBB3D, RWfitProtocol.crc16Arc("123456789".toByteArray()))
        assertEquals(0x0000, RWfitProtocol.crc16Arc(ByteArray(0)))
        assertEquals(0xC0C1, RWfitProtocol.crc16Arc(byteArrayOf(0x01)))
        assertEquals(0xC181, RWfitProtocol.crc16Arc(byteArrayOf(0x02)))
        assertEquals(0x0140, RWfitProtocol.crc16Arc(byteArrayOf(0x03)))
    }

    // ── Legacy 0x7E ──────────────────────────────────────────────────────────────

    @Test
    fun `legacy encode matches the vendor's 8-byte header`() {
        // x5/d.java j(): 7E 01 cmd flags dataLen serHi serLo xor <payload>
        val codec = RWfitLegacyCodec()
        val out = codec.encode(RWfitProtocol.Legacy.SET_TIME, byteArrayOf(0x07, 0xE9.toByte(), 0x08, 0x09))
        assertEquals(1, out.serial)   // the vendor's counter starts at 1
        assertArrayEquals(
            byteArrayOf(
                0x7E, 0x01, 0x21, 0x00, 0x04, 0x00, 0x01,
                (0x07 xor 0xE9 xor 0x08 xor 0x09).toByte(),
                0x07, 0xE9.toByte(), 0x08, 0x09,
            ),
            out.frame,
        )
    }

    @Test
    fun `legacy encode writes zero checksum for an empty payload`() {
        // x5/d.java j() only sets bArr2[7] when payload.length > 0 — history requests are empty.
        val out = RWfitLegacyCodec().encode(RWfitProtocol.Legacy.HEART_RATE_HISTORY)
        assertArrayEquals(byteArrayOf(0x7E, 0x01, 0xA3.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00), out.frame)
    }

    @Test
    fun `legacy serials increment per frame and wrap at 65535`() {
        val codec = RWfitLegacyCodec()
        assertEquals(1, codec.encode(0x00).serial)
        assertEquals(2, codec.encode(0x00).serial)
        assertEquals(3, codec.encode(0x00).serial)
    }

    @Test
    fun `legacy decode yields ack-before-frame in the vendor's order`() {
        // x5/d.java h() calls b(...) (the app ACK) before handing the body onward.
        val codec = RWfitLegacyCodec()
        val payload = byteArrayOf(0x00, 0x01, 0x55)
        val frame = byteArrayOf(0x7E, 0x01, 0x01, 0x00, 0x03, 0x12, 0x34, RWfitProtocol.xorChecksum(payload)) + payload

        val events = codec.decode(frame)

        assertEquals(2, events.size)
        assertEquals(RWfitLegacyInbound.AckNeeded(0x01, 0x1234), events[0])
        assertEquals(RWfitLegacyInbound.Frame(0x01, payload), events[1])
    }

    @Test
    fun `legacy decode reports a checksum failure instead of the frame`() {
        val codec = RWfitLegacyCodec()
        val frame = byteArrayOf(0x7E, 0x01, 0x01, 0x00, 0x02, 0x00, 0x09, 0x7F, 0x11, 0x22)
        assertEquals(listOf(RWfitLegacyInbound.ChecksumFailed(0x01, 9)), codec.decode(frame))
    }

    @Test
    fun `legacy device ack is parsed and never itself acked`() {
        // x5/d.java i(): 0xFE payload is [serHi, serLo, cmd, status]; h() skips b(...) for 0xFE.
        val codec = RWfitLegacyCodec()
        val body = byteArrayOf(0x00, 0x2A, 0x21, 0x00)
        val frame = byteArrayOf(0x7E, 0x01, 0xFE.toByte(), 0x00, 0x04, 0x00, 0x05, RWfitProtocol.xorChecksum(body)) + body

        val events = codec.decode(frame)

        assertEquals(listOf(RWfitLegacyInbound.DeviceAck(cmd = 0x21, serial = 42, status = 0x00)), events)
    }

    @Test
    fun `legacy app ack carries the inbound serial and a fresh header serial`() {
        // x5/d.java b(): j(0xFF, [serHi, serLo, cmd, status]).
        val codec = RWfitLegacyCodec()
        val ack = codec.ack(cmd = 0x01, inboundSerial = 0x1234, status = 0)

        assertEquals(0xFF.toByte(), ack[2])           // cmd
        assertEquals(0x00.toByte(), ack[5])           // this frame's own serial, BE hi
        assertEquals(0x01.toByte(), ack[6])           // ... lo — first frame of the session
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x01, 0x00), ack.copyOfRange(8, 12))
    }

    @Test
    fun `legacy multi-packet frames reassemble in index order`() {
        val codec = RWfitLegacyCodec()
        fun chunk(index: Int, total: Int, body: ByteArray) = byteArrayOf(
            0x7E, 0x01, 0xA3.toByte(), 0x08, (body.size and 0xFF).toByte(), 0x00, index.toByte(),
            RWfitProtocol.xorChecksum(body),
            0x00, total.toByte(), 0x00, index.toByte(),
        ) + body

        val first = codec.decode(chunk(1, 2, byteArrayOf(0x11, 0x22)))
        assertEquals(listOf(RWfitLegacyInbound.AckNeeded(0xA3.toByte(), 1)), first)

        val second = codec.decode(chunk(2, 2, byteArrayOf(0x33)))
        assertEquals(2, second.size)
        assertEquals(
            RWfitLegacyInbound.Frame(0xA3.toByte(), byteArrayOf(0x11, 0x22, 0x33)),
            second[1],
        )
    }

    @Test
    fun `legacy reset drops a half-assembled frame`() {
        val codec = RWfitLegacyCodec()
        val body = byteArrayOf(0x11)
        codec.decode(
            byteArrayOf(0x7E, 0x01, 0xA3.toByte(), 0x08, 0x01, 0x00, 0x01, RWfitProtocol.xorChecksum(body), 0x00, 0x02, 0x00, 0x01) + body
        )
        codec.reset()

        val body2 = byteArrayOf(0x22)
        val events = codec.decode(
            byteArrayOf(0x7E, 0x01, 0xA3.toByte(), 0x08, 0x01, 0x00, 0x02, RWfitProtocol.xorChecksum(body2), 0x00, 0x02, 0x00, 0x02) + body2
        )

        // Only the ACK — the stale chunk from the previous link must not complete this frame.
        assertEquals(1, events.size)
        assertTrue(events.single() is RWfitLegacyInbound.AckNeeded)
    }

    @Test
    fun `legacy decode ignores non-7E notifications`() {
        assertTrue(RWfitLegacyCodec().decode(byteArrayOf(0xAB.toByte(), 0x01, 0x00, 0x03)).isEmpty())
    }

    // ── JieLi 0xAB ───────────────────────────────────────────────────────────────

    @Test
    fun `jieli encode matches the vendor's 6-byte header with big-endian crc`() {
        // x5/c.java g(): AB flag lenHi lenLo crcHi crcLo <body>, len and crc both over the body
        // *including* the {cmd,key,keyFlag} triple.
        val codec = RWfitJLCodec()
        val frame = codec.encode(RWfitProtocol.JieLi.BATTERY)

        val body = byteArrayOf(0x02, 0x03, 0x10)
        val crc = RWfitProtocol.crc16Arc(body)
        assertArrayEquals(
            byteArrayOf(
                0xAB.toByte(), 0x01, 0x00, 0x03,
                ((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte(),
                0x02, 0x03, 0x10,
            ),
            frame,
        )
    }

    @Test
    fun `jieli length and crc cover the triple as part of the body`() {
        val frame = RWfitJLCodec().encode(RWfitProtocol.JieLi.historySync(RWfitProtocol.JLDataType.SLEEP), byteArrayOf(0x07))
        assertEquals(4, RWfitProtocol.readU16BE(frame, 2))                      // 3 triple + 1 payload
        assertEquals(
            RWfitProtocol.crc16Arc(byteArrayOf(0x05, 0x05, 0x10, 0x07)),
            RWfitProtocol.readU16BE(frame, 4),
        )
    }

    @Test
    fun `jieli round-trips through decode`() {
        val codec = RWfitJLCodec()
        val frame = codec.encode(RWfitProtocol.JieLi.DEVICE_INFO, byteArrayOf(0x01, 0x02))

        val decoded = codec.decode(frame).single() as RWfitJLInbound.Frame

        assertEquals(RWfitProtocol.JieLi.DEVICE_INFO, decoded.triple)
        assertArrayEquals(byteArrayOf(0x01, 0x02), decoded.payload)
        assertTrue(!decoded.isAck)
    }

    @Test
    fun `jieli ack uses flag 0x11 and echoes the triple`() {
        val codec = RWfitJLCodec()
        val ack = codec.ack(RWfitProtocol.JieLi.BATTERY)

        assertEquals(RWfitJLCodec.FLAG_ACK, ack[1])
        assertArrayEquals(byteArrayOf(0x02, 0x03, 0x10), ack.copyOfRange(6, 9))
        assertEquals(3, RWfitProtocol.readU16BE(ack, 2))
    }

    @Test
    fun `jieli ack for the realtime-measure triple carries the extra zero byte`() {
        // r5/b.java:436-443 — the one special case in the vendor's ACK builder.
        val codec = RWfitJLCodec()
        val ack = codec.ack(RWfitProtocol.JieLi.REALTIME_MEASURE)

        assertEquals(4, RWfitProtocol.readU16BE(ack, 2))
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x00, 0x00), ack.copyOfRange(6, 10))
    }

    @Test
    fun `jieli reports a crc failure`() {
        val codec = RWfitJLCodec()
        val frame = codec.encode(RWfitProtocol.JieLi.BATTERY).copyOf()
        frame[4] = (frame[4] + 1).toByte()

        assertTrue(codec.decode(frame).single() is RWfitJLInbound.ChecksumFailed)
    }

    @Test
    fun `jieli reassembles a body split across continuation packets`() {
        // r5/b.java: the header packet carries the first chunk; later packets are raw body bytes.
        val codec = RWfitJLCodec()
        val payload = ByteArray(30) { (it + 1).toByte() }
        val whole = codec.encode(RWfitProtocol.JieLi.historySync(RWfitProtocol.JLDataType.HEART_RATE), payload)

        val head = whole.copyOfRange(0, 20)
        val tail = whole.copyOfRange(20, whole.size)

        assertTrue(codec.decode(head).isEmpty())
        val decoded = codec.decode(tail).single() as RWfitJLInbound.Frame
        assertArrayEquals(payload, decoded.payload)
    }

    // ── Advertisement recognition ────────────────────────────────────────────────

    @Test
    fun `advertisement patterns are the vendor's four scan signatures`() {
        // r5/d.java c() — and deliberately no name pattern among them.
        assertEquals(
            listOf("02010603030aa0", "d6050200", "15ffd6054154", "d6060200"),
            RWfitProtocol.ADVERTISEMENT_HEX_PATTERNS,
        )
    }
}
