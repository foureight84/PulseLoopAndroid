package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/** Wire-format tests for the YCBT frame/CRC/byte helpers (iOS #82). Oracle values from
 *  docs/YCBT-Protocol.md (ported from the decompiled vendor SDK). */
class YCBTFrameTest {

    @Test
    fun `crc16 check value matches the CCITT-FALSE oracle`() {
        // Standard CRC-16/CCITT-FALSE check value: "123456789" -> 0x29B1.
        val ascii = "123456789".map { it.code.toUByte() }
        assertEquals(0x29B1, YCBTFrame.crc16(ascii))
    }

    @Test
    fun `frame inserts total length and appends little-endian crc`() {
        // Worked example from the protocol doc: 05 06 (health, heart-rate query, empty payload)
        // frames to 05 06 06 00 83 20 (len=6 LE, crc=0x2083 LE).
        val framed = YCBTFrame.frame(listOf(YCBTGroup.HEALTH, 0x06u))
        assertArrayEquals(byteArrayOf(0x05, 0x06, 0x06, 0x00, 0x83.toByte(), 0x20), framed)
    }

    @Test
    fun `validating round-trips a framed command`() {
        val framed = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, 0x47u, 0x43u))
        val parsed = YCBTFrame.validating(framed)
        assertNotNull(parsed)
        assertEquals(YCBTGroup.GET, parsed!!.type)
        assertEquals(YCBTCommand.GET_DEVICE_INFO, parsed.cmd)
        assertEquals(listOf<UByte>(0x47u, 0x43u), parsed.payload)
    }

    @Test
    fun `validating rejects a length mismatch`() {
        val framed = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO)).toMutableList()
        framed[2] = (framed[2] + 1).toByte()   // corrupt the declared length
        assertNull(YCBTFrame.validating(framed.toByteArray()))
    }

    @Test
    fun `validating rejects a crc mismatch`() {
        val framed = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO)).toMutableList()
        framed[framed.size - 1] = (framed[framed.size - 1] + 1).toByte()   // flip a CRC byte
        assertNull(YCBTFrame.validating(framed.toByteArray()))
    }

    @Test
    fun `validating rejects a frame shorter than the header`() {
        assertNull(YCBTFrame.validating(byteArrayOf(0x05, 0x06, 0x04)))
    }

    // MARK: - YCBTBytes epoch round-trip

    @Test
    fun `ring seconds round-trip through date`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.Instant.now().let { java.time.Instant.ofEpochSecond(it.epochSecond) }
        val ringSeconds = YCBTBytes.ringSeconds(now, zone)
        val back = YCBTBytes.date(ringSeconds, zone)
        assertEquals(now, back)
    }

    @Test
    fun `u16 u24 u32 read little-endian`() {
        val bytes = listOf<UByte>(0x01u, 0x02u, 0x03u, 0x04u, 0x05u)
        assertEquals(0x0201, YCBTBytes.u16(bytes, 0))
        assertEquals(0x030201, YCBTBytes.u24(bytes, 0))
        assertEquals(0x04030201L, YCBTBytes.u32(bytes, 0))
    }
}

/** Tests for [YCBTFrameAssembler]'s fragmentation/resync behavior. */
class YCBTFrameAssemblerTest {

    @Test
    fun `reassembles a frame split across two notifications`() {
        val assembler = YCBTFrameAssembler()
        val whole = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, 0x47u, 0x43u))
        val part1 = whole.copyOfRange(0, 3)
        val part2 = whole.copyOfRange(3, whole.size)

        assertTrue(assembler.append(part1, YCBTUUIDs.COMMAND).isEmpty())
        val completed = assembler.append(part2, YCBTUUIDs.COMMAND)
        assertEquals(1, completed.size)
        assertArrayEquals(whole, completed[0])
    }

    @Test
    fun `splits two short frames delivered in one notification`() {
        val assembler = YCBTFrameAssembler()
        val a = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO))
        val b = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_SUPPORT_FUNCTION))
        val completed = assembler.append(a + b, YCBTUUIDs.COMMAND)
        assertEquals(2, completed.size)
        assertArrayEquals(a, completed[0])
        assertArrayEquals(b, completed[1])
    }

    @Test
    fun `keeps command and stream channel buffers independent`() {
        val assembler = YCBTFrameAssembler()
        val whole = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, 0x47u, 0x43u))
        // Feed the command channel's first half, then a stream-channel frame — the stream frame
        // must not be treated as a continuation of the command channel's partial buffer.
        assembler.append(whole.copyOfRange(0, 3), YCBTUUIDs.COMMAND)
        val streamFrame = YCBTFrame.frame(listOf(YCBTGroup.REAL, YCBTCommand.LIVE_HEART_RATE, 70u))
        val streamCompleted = assembler.append(streamFrame, YCBTUUIDs.STREAM)
        assertArrayEquals(streamFrame, streamCompleted[0])

        // The command channel's partial buffer is still intact and completes normally.
        val commandCompleted = assembler.append(whole.copyOfRange(3, whole.size), YCBTUUIDs.COMMAND)
        assertArrayEquals(whole, commandCompleted[0])
    }

    @Test
    fun `resyncs by dropping one byte at a time on garbage`() {
        val assembler = YCBTFrameAssembler()
        val whole = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO))
        // Two garbage bytes (not a plausible group byte) ahead of a real frame.
        val garbage = byteArrayOf(0xAA.toByte(), 0xBB.toByte()) + whole
        val completed = assembler.append(garbage, YCBTUUIDs.COMMAND)
        assertEquals(1, completed.size)
        assertArrayEquals(whole, completed[0])
    }

    @Test
    fun `reset drops partial frames`() {
        val assembler = YCBTFrameAssembler()
        val whole = YCBTFrame.frame(listOf(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, 0x47u, 0x43u))
        assembler.append(whole.copyOfRange(0, 3), YCBTUUIDs.COMMAND)
        assembler.reset()
        // The old partial bytes must not be prepended to a fresh frame's tail.
        val completed = assembler.append(whole.copyOfRange(3, whole.size), YCBTUUIDs.COMMAND)
        assertTrue(completed.isEmpty())
    }
}

/** Tests for [YCBTSupportFunction]'s capability-bitmap parsing. */
class YCBTSupportFunctionTest {

    private fun payload(size: Int, vararg setBits: Pair<Int, Int>): List<UByte> {
        val bytes = MutableList(size) { 0u.toUByte() }
        for ((byte, bit) in setBits) {
            bytes[byte] = (bytes[byte].toInt() or (1 shl bit)).toUByte()
        }
        return bytes
    }

    @Test
    fun `too-short payload yields no capabilities`() {
        assertTrue(YCBTSupportFunction.capabilities(payload(10)).isEmpty())
    }

    @Test
    fun `heart rate bit maps to heart rate capability`() {
        val caps = YCBTSupportFunction.capabilities(payload(14, 0 to 3))
        assertTrue(caps.contains(WearableCapability.HEART_RATE))
    }

    @Test
    fun `stress bit also grants fatigue since they share one record`() {
        val caps = YCBTSupportFunction.capabilities(payload(23, 22 to 6))
        assertTrue(caps.contains(WearableCapability.STRESS))
        assertTrue(caps.contains(WearableCapability.FATIGUE))
    }

    @Test
    fun `manual heart rate bit requires the sdk's own 18-byte gate, not just physical presence`() {
        // Byte 15 is physically readable in a 17-byte payload (indices 0..16), but the SDK's own
        // gate (minLength 18) still refuses to read it below that — the gate is stricter than
        // sheer byte-count availability.
        assertTrue(YCBTSupportFunction.capabilities(payload(17, 15 to 1)).isEmpty())
        assertTrue(YCBTSupportFunction.capabilities(payload(18, 15 to 1)).contains(WearableCapability.MANUAL_HEART_RATE))
    }

    @Test
    fun `every bitmap-gated capability in both coordinators is derivable from a bit`() {
        // A gate no bit can ever satisfy is a dead promise (mirrors iOS's PairingMatchingTests
        // invariant) — build a payload with every mapped bit set and confirm it covers each
        // coordinator's gated set.
        val allBitsPayload = payload(
            24,
            0 to 7, 0 to 6, 0 to 3, 0 to 0, 1 to 3, 1 to 1, 8 to 0, 17 to 3, 22 to 6,
            6 to 4, 15 to 1, 15 to 2, 15 to 3, 23 to 0,
        )
        val derivable = YCBTSupportFunction.capabilities(allBitsPayload)
        for (cap in TK5Coordinator.bitmapGatedCapabilities) {
            assertTrue("TK5 gated capability $cap has no satisfying bit", derivable.contains(cap))
        }
        for (cap in ColmiSmartHealthCoordinator.bitmapGatedCapabilities) {
            assertTrue("SmartHealth-Colmi gated capability $cap has no satisfying bit", derivable.contains(cap))
        }
    }
}
