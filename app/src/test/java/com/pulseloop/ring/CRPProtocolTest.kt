package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the CRP ("crrepa") framing + command builders ([CRPProtocol]). Pure byte-level
 * checks against the decompiled Moyoung "Da Rings" builders (`b1/q.java`, `b1/e.java`, `b1/k.java`,
 * `b1/t.java`, `b1/c0.java`, `b1/l.java`); no BLE stack needed. See `decompiled-moyoung-official/`.
 */
class CRPProtocolTest {

    @Test
    fun `frame lays out FD DA 10 len group cmd payload`() {
        val f = CRPProtocol.frame(group = 1, cmd = 9, payload = byteArrayOf(1))
        // FD DA 10 | len=7 | group=1 | cmd=9 | payload=01
        assertArrayEquals(
            byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 7, 1, 9, 1),
            f,
        )
    }

    @Test
    fun `frame length equals payload plus six-byte header`() {
        assertEquals(6, CRPProtocol.frame(3, 0).size)             // no payload
        assertEquals(11, CRPProtocol.frame(1, 0, ByteArray(5)).size)
    }

    @Test
    fun `isFrameStart recognises the FD DA magic only`() {
        assertTrue(CRPProtocol.isFrameStart(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 6)))
        assertFalse(CRPProtocol.isFrameStart(byteArrayOf(0xFD.toByte(), 0x00)))
        assertFalse(CRPProtocol.isFrameStart(byteArrayOf(0xDA.toByte())))
    }

    @Test
    fun `frameLength reads byte3 with the 9th bit from byte2`() {
        // Short frame: byte[2]=0x10 (bit0 clear) => length is byte[3].
        assertEquals(20, CRPProtocol.frameLength(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 20)))
        // Long frame: bit0 of byte[2] set => +256.
        assertEquals(
            256 + 5,
            CRPProtocol.frameLength(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x11, 5)),
        )
    }

    @Test
    fun `setUserInfo matches vendor b1_k_a layout`() {
        // b1/k.a: q.c(1, 0, [height, weight, age, gender, strideLen])
        val f = CRPProtocol.setUserInfo(heightCm = 175, weightKg = 70, ageYears = 30, gender = 1, strideCm = 75)
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 11, 1, 0, 175.toByte(), 70, 30, 1, 75), f)
    }

    @Test
    fun `setTime is group1 cmd1 with 4-byte little-endian epoch and tz byte 8`() {
        val f = CRPProtocol.setTime()
        assertEquals(0xFD.toByte(), f[0]); assertEquals(0xDA.toByte(), f[1]); assertEquals(0x10.toByte(), f[2])
        assertEquals(11, f[3].toInt())            // 5 payload + 6 header
        assertEquals(1, f[4].toInt())             // group
        assertEquals(1, f[5].toInt())             // cmd
        assertEquals(8, f[10].toInt())            // trailing timezone byte
        // Epoch is little-endian: reconstruct and sanity-check it's a plausible 2020s timestamp.
        val epoch = (f[6].toLong() and 0xFF) or ((f[7].toLong() and 0xFF) shl 8) or
            ((f[8].toLong() and 0xFF) shl 16) or ((f[9].toLong() and 0xFF) shl 24)
        assertTrue("epoch $epoch out of expected range", epoch in 1_577_836_800L..4_102_444_800L)
    }

    @Test
    fun `heart rate start and stop toggle the enable byte on group1 cmd9`() {
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 7, 1, 9, 1), CRPProtocol.measureHeartRate(true))
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 7, 1, 9, 0), CRPProtocol.measureHeartRate(false))
    }

    @Test
    fun `spo2 uses group1 cmd11`() {
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 7, 1, 11, 1), CRPProtocol.measureSpO2(true))
    }

    @Test
    fun `findDevice is group9 cmd2`() {
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 7, 9, 2, 1), CRPProtocol.findDevice(true))
    }

    @Test
    fun `factoryReset is group3 cmd0 with no payload`() {
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xDA.toByte(), 0x10, 6, 3, 0), CRPProtocol.factoryReset())
    }
}
