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

    // ---- Real-time vital results: framed group-1 replies on fdd3 (g1/a.java 664–712). ----
    // This ring does NOT use the standard 2a37 characteristic — HR comes back as a framed reply.

    @Test
    fun `group1 cmd9 framed reply decodes heart rate from payload0`() {
        // The exact frame the ring returned for zaggash's Measure press (issue #29): HR = 0x4a = 74.
        val frame = CRPProtocol.frame(1, CRPCommands.CMD_RESULT_HR, byteArrayOf(0x4a))
        val hrs = CRPDecoder.decode(frame, fdd3)[0] as RingDecodedEvent.HeartRateSample
        assertEquals(74, hrs.bpm)
    }

    @Test
    fun `group1 cmd9 out-of-range bpm is dropped`() {
        assertTrue(CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_HR, byteArrayOf(0)), fdd3).isEmpty())
    }

    @Test
    fun `group1 cmd10 decodes hrv, cmd11 spo2, cmd14 stress from payload0`() {
        val hrv = CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_HRV, byteArrayOf(45)), fdd3)[0]
        assertEquals(45, (hrv as RingDecodedEvent.HrvSample).value)
        val spo2 = CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_SPO2, byteArrayOf(98)), fdd3)[0]
        assertEquals(98, (spo2 as RingDecodedEvent.Spo2Result).value)
        val stress = CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_STRESS, byteArrayOf(37)), fdd3)[0]
        assertEquals(37, (stress as RingDecodedEvent.StressSample).value)
    }

    @Test
    fun `group1 cmd32 decodes temperature as two-byte tenths of a degree`() {
        // e1/m.a: (payload[1]<<8 | payload[0]) / 10 -> 365 => 36.5 C
        val frame = CRPProtocol.frame(1, CRPCommands.CMD_RESULT_TEMP, byteArrayOf(0x6D, 0x01))
        val temp = CRPDecoder.decode(frame, fdd3)[0] as RingDecodedEvent.TemperatureSample
        assertEquals(36.5, temp.celsius, 0.001)
    }

    @Test
    fun `group1 out-of-range temperature is dropped`() {
        // 0x0064 = 100 -> 10.0 C, below the 28..50 validity window.
        assertTrue(CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_TEMP, byteArrayOf(0x64, 0x00)), fdd3).isEmpty())
    }

    @Test
    fun `unrecognised group1 cmd is acked, not dropped`() {
        // cmd 0 (set-user-info ack) isn't a vital result -> CommandAck, no fabricated sample.
        val ev = CRPDecoder.decode(CRPProtocol.frame(1, 0, byteArrayOf(0)), fdd3)[0]
        assertTrue(ev is RingDecodedEvent.CommandAck)
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
