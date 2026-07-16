package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

class YCBTProtocolTest {

    private val streamUUID = YCBTUUIDs.STREAM
    private val commandUUID = YCBTUUIDs.COMMAND

    private fun frameBytes(cmd: Int, payloadLength: Int, fill: Int = 0xaa): ByteArray {
        return YCBTFrame.frame(byteArrayOf(YCBTGroup.HEALTH.toByte(), cmd.toByte()) + ByteArray(payloadLength) { fill.toByte() })
    }

    @Test
    fun `frame builds length and CRC matching captured setTime`() {
        val logical = byteArrayOf(0x01, 0x00, 0xea.toByte(), 0x07, 0x07, 0x06, 0x0c, 0x22, 0x0e, 0x00)
        val framed = YCBTFrame.frame(logical)
        assertEquals("01000e00ea0707060c220e0026c7", framed.toHexString())
    }

    @Test
    fun `CRC16 matches captured setTime body`() {
        val body = hexToBytes("01000e00ea0707060c220e00")
        val crc = YCBTFrame.crc16(body)
        assertEquals(0xc726, crc)
    }

    @Test
    fun `validating rejects bad CRC`() {
        val raw = hexToBytes("01000e00ea0707060c220e0026c7")
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0xff).toByte()
        assertNull(YCBTFrame.validating(raw))
    }

    @Test
    fun `validating rejects wrong declared length`() {
        assertNull(YCBTFrame.validating(hexToBytes("0100ff00ea0707060c220e0026c7")))
    }

    @Test
    fun `frame split across three notifications is reassembled`() {
        val assembler = YCBTFrameAssembler()
        val whole = frameBytes(cmd = 0x15, payloadLength = 60)

        assertTrue(assembler.append(whole.copyOfRange(0, 20), streamUUID).isEmpty())
        assertTrue(assembler.append(whole.copyOfRange(20, 40), streamUUID).isEmpty())
        val done = assembler.append(whole.copyOfRange(40, whole.size), streamUUID)

        assertEquals(1, done.size)
        assertArrayEquals(whole, done[0])
        assertNotNull(YCBTFrame.validating(done[0]))
    }

    @Test
    fun `two frames in one notification both emerge`() {
        val assembler = YCBTFrameAssembler()
        val first = frameBytes(cmd = 0x15, payloadLength = 6)
        val second = frameBytes(cmd = 0x18, payloadLength = 20)

        val done = assembler.append(first + second, streamUUID)
        assertEquals(2, done.size)
        assertArrayEquals(first, done[0])
        assertArrayEquals(second, done[1])
    }

    @Test
    fun `fragment then whole frame in one notification`() {
        val assembler = YCBTFrameAssembler()
        val first = frameBytes(cmd = 0x15, payloadLength = 30)
        val second = frameBytes(cmd = 0x18, payloadLength = 4)

        assertTrue(assembler.append(first.copyOfRange(0, 10), streamUUID).isEmpty())
        val done = assembler.append(first.copyOfRange(10, first.size) + second, streamUUID)
        assertEquals(2, done.size)
        assertArrayEquals(first, done[0])
        assertArrayEquals(second, done[1])
    }

    @Test
    fun `garbage prefix resyncs to next valid frame`() {
        val assembler = YCBTFrameAssembler()
        val good = frameBytes(cmd = 0x15, payloadLength = 6)
        val garbage = byteArrayOf(0xff.toByte(), 0x00, 0xff.toByte(), 0xff.toByte(), 0x7f)

        val done = assembler.append(garbage + good, streamUUID)
        assertEquals(1, done.size)
        assertArrayEquals(good, done[0])
    }

    @Test
    fun `channels buffer independently`() {
        val assembler = YCBTFrameAssembler()
        val streamFrame = frameBytes(cmd = 0x15, payloadLength = 30)
        val commandFrame = YCBTFrame.frame(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64))

        assertTrue(assembler.append(streamFrame.copyOfRange(0, 10), streamUUID).isEmpty())
        val cmdDone = assembler.append(commandFrame, commandUUID)
        assertEquals(1, cmdDone.size)
        assertArrayEquals(commandFrame, cmdDone[0])

        val streamDone = assembler.append(streamFrame.copyOfRange(10, streamFrame.size), streamUUID)
        assertEquals(1, streamDone.size)
        assertArrayEquals(streamFrame, streamDone[0])
    }

    @Test
    fun `reset drops partial frames`() {
        val assembler = YCBTFrameAssembler()
        val whole = frameBytes(cmd = 0x15, payloadLength = 30)

        assertTrue(assembler.append(whole.copyOfRange(0, 10), streamUUID).isEmpty())
        assembler.reset()
        assertTrue(assembler.append(whole.copyOfRange(10, whole.size), streamUUID).isEmpty())
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        require(clean.length % 2 == 0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
