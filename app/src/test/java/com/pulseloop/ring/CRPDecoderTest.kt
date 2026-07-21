package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CRP inbound decoding + reassembly ([CRPDecoder], [CRPFrameAssembler]) and the
 * [CRPDriver.ingest] routing. Byte layouts are from the decompiled Moyoung app (`e1/k.b` steps,
 * `g1/a.B` heart rate, `g1/a.k` frame reassembly). No BLE stack needed.
 */
class CRPDecoderTest {

    private val fdd1 = CRPUUIDs.CHAR_STEPS_NOTIFY
    private val fdd3 = CRPUUIDs.CHAR_CMD_NOTIFY
    private val hr = CRPUUIDs.CHAR_HEART_RATE_MEASURE

    @Test
    fun `current-steps push decodes little-endian steps distance calories`() {
        // steps=1000 (E8 03 00), distance=500 (F4 01 00), calories=42 (2A 00 00)
        val data = byteArrayOf(
            0xE8.toByte(), 0x03, 0x00,
            0xF4.toByte(), 0x01, 0x00,
            0x2A, 0x00, 0x00,
        )
        val events = CRPDecoder.decode(data, fdd1)
        assertEquals(1, events.size)
        val a = events[0] as RingDecodedEvent.ActivityUpdate
        assertEquals(1000, a.steps)
        assertEquals(500, a.distanceMeters)
        assertEquals(42, a.calories)
    }

    @Test
    fun `steps push with only the step triple still decodes, distance and calories zero`() {
        val a = CRPDecoder.decode(byteArrayOf(0x0A, 0x00, 0x00), fdd1)[0] as RingDecodedEvent.ActivityUpdate
        assertEquals(10, a.steps)
        assertEquals(0, a.distanceMeters)
        assertEquals(0, a.calories)
    }

    @Test
    fun `steps push of non-multiple-of-three length is rejected`() {
        assertTrue(CRPDecoder.decode(byteArrayOf(1, 2), fdd1).isEmpty())
    }

    @Test
    fun `heart rate 2a37 reads bpm from byte1 when the 0x0400 marker is present`() {
        // [status, bpm=72, 0x00, 0x04] -> marker bytes[2..3] == 0x0400
        val hrs = CRPDecoder.decode(byteArrayOf(0x00, 72, 0x00, 0x04), hr)[0] as RingDecodedEvent.HeartRateSample
        assertEquals(72, hrs.bpm)
    }

    @Test
    fun `heart rate 2a37 with wrong marker is dropped`() {
        assertTrue(CRPDecoder.decode(byteArrayOf(0x00, 72, 0x00, 0x08), hr).isEmpty())
    }

    @Test
    fun `heart rate 2a37 with zero bpm is dropped`() {
        assertTrue(CRPDecoder.decode(byteArrayOf(0x00, 0, 0x00, 0x04), hr).isEmpty())
    }

    @Test
    fun `assembler returns a single-packet frame immediately`() {
        val a = CRPFrameAssembler()
        val frame = CRPProtocol.frame(1, 9, byteArrayOf(0x50)) // len 7
        assertArrayEquals(frame, a.append(frame))
    }

    @Test
    fun `assembler reassembles a frame split across two notifications`() {
        val a = CRPFrameAssembler()
        // A 10-byte frame: FD DA 10 0A 02 05 + 4 payload bytes, delivered as 6 + 4.
        val payload = byteArrayOf(1, 2, 3, 4)
        val full = CRPProtocol.frame(2, 5, payload) // size 10
        assertNull(a.append(full.copyOfRange(0, 6)))          // header only — not complete
        val done = a.append(full.copyOfRange(6, 10))          // continuation completes it
        assertArrayEquals(full, done)
    }

    @Test
    fun `assembler drops a continuation with no in-progress frame`() {
        val a = CRPFrameAssembler()
        assertNull(a.append(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `driver routes fdd1 to steps and reassembles fdd3 replies`() {
        val driver = CRPDriver(writer = null)
        val steps = driver.ingest(byteArrayOf(0x05, 0x00, 0x00), fdd1)
        assertTrue(steps.single() is RingDecodedEvent.ActivityUpdate)

        // A framed reply split across two fdd3 notifications yields exactly one decoded event.
        val full = CRPProtocol.frame(1, 9, byteArrayOf(0x50))
        assertTrue(driver.ingest(full.copyOfRange(0, 4), fdd3).isEmpty())
        assertEquals(1, driver.ingest(full.copyOfRange(4, full.size), fdd3).size)
    }
}
