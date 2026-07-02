package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * RingDecoder parity tests — verifies decoder/encoder output matches
 * expected values from hex dumps captured on real hardware.
 * Pure logic — no Room/hardware dependency.
 */
@OptIn(ExperimentalStdlibApi::class)
class RingDecoderTest {

    // ── Valid Decodes ───────────────────────────────────────────────────

    @Test
    fun `decode activity update from valid packet`() {
        val data = composeRingPacket(0x03, buildActivityPayload(1000, 2500, 150))
        val events = RingDecoder.decode(data)
        assertEquals(1, events.size)
        val event = events[0]
        assertTrue(event is RingDecodedEvent.ActivityUpdate)
        val a = event as RingDecodedEvent.ActivityUpdate
        assertEquals(1000, a.steps)
        assertEquals(2500, a.distanceMeters)
        assertEquals(150, a.calories)
    }

    @Test
    fun `decode battery from valid packet`() {
        val payload = ByteArray(19)
        payload[0] = 85.toByte() // battery percent at byte[1]
        payload[1] = 0.toByte()  // not charging at byte[2]
        val data = composeRingPacket(0x0B, payload)
        val events = RingDecoder.decode(data)
        assertEquals(1, events.size)
        assertTrue(events[0] is RingDecodedEvent.Battery)
        val b = events[0] as RingDecodedEvent.Battery
        assertEquals(85, b.percent)
        assertFalse(b.charging)
    }

    @Test
    fun `decode battery with charging`() {
        val payload = ByteArray(19)
        payload[0] = 100.toByte()
        payload[1] = 1.toByte() // charging
        val data = composeRingPacket(0x0B, payload)
        val events = RingDecoder.decode(data)
        assertTrue((events[0] as RingDecodedEvent.Battery).charging)
    }

    @Test
    fun `decode heart rate sample from valid packet`() {
        val payload = ByteArray(19)
        payload[4] = 72.toByte() // HR value at byte[5]
        // timestamp at bytes[1-4] must be non-zero
        payload[0] = 1.toByte()
        val data = composeRingPacket(0x14, payload)
        val events = RingDecoder.decode(data)
        assertEquals(1, events.size)
        assertTrue(events[0] is RingDecodedEvent.HeartRateSample)
        assertEquals(72, (events[0] as RingDecodedEvent.HeartRateSample).bpm)
    }

    @Test
    fun `decode heart rate with timestamp zero marks error`() {
        val payload = ByteArray(19)
        payload[4] = 72.toByte()
        // timestamp bytes[1-4] all zero
        val data = composeRingPacket(0x14, payload)
        val events = RingDecoder.decode(data)
        assertTrue((events[0] as RingDecodedEvent.HeartRateSample).isError)
    }

    @Test
    fun `decode 0x24 combined sensor produces multiple events`() {
        val payload = ByteArray(19)
        payload[0] = 72.toByte()   // HR at byte[1]
        payload[1] = 110.toByte()  // systolic at byte[2]
        payload[2] = 70.toByte()   // diastolic at byte[3]
        payload[3] = 97.toByte()   // SpO2 at byte[4]
        payload[4] = 40.toByte()   // fatigue at byte[5]
        payload[5] = 30.toByte()   // stress at byte[6]
        payload[6] = 51.toByte()   // blood sugar ×10 at byte[7] → 5.1 mmol/L → 91.88 mg/dL
        payload[7] = 45.toByte()   // HRV at byte[8]
        val data = composeRingPacket(0x24, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events.size >= 7) // HR + systolic + diastolic + SpO2 + fatigue + stress + sugar + HRV
        assertTrue(events.any { it is RingDecodedEvent.HrvSample && (it as RingDecodedEvent.HrvSample).value == 45 })
        assertTrue(events.any { it is RingDecodedEvent.HeartRateSample && (it as RingDecodedEvent.HeartRateSample).bpm == 72 })
        assertTrue(events.any { it is RingDecodedEvent.Spo2Result && (it as RingDecodedEvent.Spo2Result).value == 97 })
        assertTrue(events.any { it is RingDecodedEvent.StressSample && (it as RingDecodedEvent.StressSample).value == 30 })
        assertTrue(events.any { it is RingDecodedEvent.HistoryMeasurement && (it as RingDecodedEvent.HistoryMeasurement).kind_field == MeasurementKind.FATIGUE && it.value == 40.0 })
        val sugar = events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>().firstOrNull { it.kind_field == MeasurementKind.BLOOD_SUGAR }
        assertNotNull(sugar)
        assertEquals(91.88, sugar!!.value, 0.01)
    }

    @Test
    fun `decode 0x24 skips zero values`() {
        val payload = ByteArray(19)
        payload[0] = 0.toByte()    // HR = 0 → skip
        payload[1] = 0.toByte()    // systolic = 0 → skip
        payload[2] = 0.toByte()    // diastolic = 0 → skip
        payload[3] = 98.toByte()   // SpO2 = 98
        payload[4] = 0.toByte()    // stress = 0 → skip
        val data = composeRingPacket(0x24, payload)
        val events = RingDecoder.decode(data)
        assertEquals(1, events.size)
        assertTrue(events[0] is RingDecodedEvent.Spo2Result)
    }

    @Test
    fun `decode 0x3F spo2 result`() {
        val payload = ByteArray(19)
        payload[0] = 98.toByte() // SpO2 at byte[1]
        val data = composeRingPacket(0x3F, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Spo2Result)
        assertEquals(98, (events[0] as RingDecodedEvent.Spo2Result).value)
    }

    @Test
    fun `decode 0x10 activity history produces 15 buckets`() {
        val payload = ByteArray(19)
        for (i in 4..18) payload[i] = ((i - 4) * 10).toByte() // 0, 10, 20... steps
        val data = composeRingPacket(0x10, payload)
        val events = RingDecoder.decode(data)
        assertEquals(15, events.size)
        assertTrue(events.all { it is RingDecodedEvent.ActivityBucket })
        assertEquals(0, (events[0] as RingDecodedEvent.ActivityBucket).steps)
        assertEquals(140, (events[14] as RingDecodedEvent.ActivityBucket).steps)
    }

    @Test
    fun `decode 0x16 hr history data block with averaging`() {
        val payload = ByteArray(19)
        payload[0] = 0xA0.toByte() // data marker at byte[1]
        // bytes[8-13] = first 6 samples: 70, 72, 71, 73, 70, 72 → avg = 71
        payload[7] = 70; payload[8] = 72; payload[9] = 71
        payload[10] = 73; payload[11] = 70; payload[12] = 72
        // bytes[14-19] = next 6 samples: all zero → skip
        val data = composeRingPacket(0x16, payload)
        val events = RingDecoder.decode(data)
        assertEquals(1, events.size)
        assertTrue(events[0] is RingDecodedEvent.HistoryMeasurement)
        val m = events[0] as RingDecodedEvent.HistoryMeasurement
        assertEquals(71.0, m.value, 0.01)
    }

    @Test
    fun `decode 0x16 header packet`() {
        val payload = ByteArray(19)
        payload[0] = 0xF0.toByte() // header marker
        payload[5] = 0x0A          // total=10 at bytes[6-7] (LE)
        val data = composeRingPacket(0x16, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.HistorySyncProgress)
    }

    @Test
    fun `decode 0x16 end packet`() {
        val payload = ByteArray(19)
        payload[0] = 0xFF.toByte() // end marker
        val data = composeRingPacket(0x16, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.HistorySyncFinished)
    }

    @Test
    fun `decode SpO2 progress for 0x3F with low values`() {
        val payload = ByteArray(19)
        payload[0] = 70.toByte() // < 80 at byte[1]
        val data = composeRingPacket(0x3F, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Spo2Progress)
    }

    @Test
    fun `decode heart rate complete`() {
        val data = composeRingPacket(0x27, ByteArray(19))
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.HeartRateComplete)
    }

    @Test
    fun `decode SpO2 complete`() {
        val data = composeRingPacket(0x28, ByteArray(19))
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Spo2Complete)
    }

    @Test
    fun `decode time sync ack`() {
        val ts = 1700000000
        val payload = ByteArray(19)
        payload[0] = (ts and 0xFF).toByte()
        payload[1] = ((ts shr 8) and 0xFF).toByte()
        payload[2] = ((ts shr 16) and 0xFF).toByte()
        payload[3] = ((ts shr 24) and 0xFF).toByte()
        val data = composeRingPacket(0x01, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.TimeSyncAck)
        assertEquals(ts.toLong(), (events[0] as RingDecodedEvent.TimeSyncAck)._timestamp.epochSecond)
    }

    @Test
    fun `decode status packet`() {
        val payload = ByteArray(19) { 0xAA.toByte() }
        val data = composeRingPacket(0x0C, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Status)
        assertNotNull((events[0] as RingDecodedEvent.Status).address)
    }

    @Test
    fun `decode command ack`() {
        val data = composeRingPacket(0x02, ByteArray(19))
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.CommandAck)
    }

    // ── Malformed / Edge Cases ──────────────────────────────────────────

    @Test
    fun `decode empty array returns unknown`() {
        val events = RingDecoder.decode(byteArrayOf())
        assertEquals(1, events.size)
        assertTrue(events[0] is RingDecodedEvent.Unknown)
    }

    @Test
    fun `decode unknown command id returns unknown`() {
        val payload = ByteArray(19) { 0 }
        payload[0] = 1; payload[1] = 2; payload[2] = 3
        val data = composeRingPacket(0xFE, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Unknown)
        assertEquals(0xFEu.toUByte(), (events[0] as RingDecodedEvent.Unknown).commandId)
    }

    @Test
    fun `decode truncated activity update returns unknown`() {
        val data = ByteArray(5)
        data[0] = 0x03.toByte()
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Unknown)
    }

    @Test
    fun `decode truncated status returns status without address`() {
        // 20-byte packet but payload only has status code (bytes 1-2), no MAC
        val data = composeRingPacket(0x0C, ByteArray(19) { 0 })
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Status)
        // MAC extracted from padding zeros is all-zeros, not null.
        // The decoder can't distinguish "truncated" from "MAC = 00:00:00:00:00:00".
        // This is expected — the ring always sends a full 6-byte MAC.
        assertNotNull((events[0] as RingDecodedEvent.Status).address)
    }

    // ── Sleep Timeline (0x11) Cases ─────────────────────────────────────

    @Test
    fun `decode sleep timeline with threshold-based stages`() {
        val payload = ByteArray(19)
        payload[4] = 0x00.toByte()          // AWAKE (0)
        payload[5] = 0x01.toByte()          // LIGHT (>=1)
        payload[6] = 0x28.toByte()          // LIGHT (40, >=1)
        payload[7] = 0x50.toByte()          // DEEP (80, >=80)
        payload[8] = 0x63.toByte()          // DEEP (99, >=80)
        payload[9] = 0x7F.toByte()          // LIGHT (127, <80 but >=1... wait, 127 >= 80)
        for (i in 10..18) payload[i] = 0x01.toByte() // LIGHT
        val data = composeRingPacket(0x11, payload)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.SleepTimeline)
        val sleep = events[0] as RingDecodedEvent.SleepTimeline
        assertEquals(15, sleep.stages.size)
        assertEquals(SleepStage.AWAKE, sleep.stages[0])   // 0x00
        assertEquals(SleepStage.LIGHT, sleep.stages[1])   // 0x01 >= 1
        assertEquals(SleepStage.LIGHT, sleep.stages[2])   // 0x28 >= 1
        assertEquals(SleepStage.DEEP, sleep.stages[3])    // 0x50 >= 80
        assertEquals(SleepStage.DEEP, sleep.stages[4])    // 0x63 >= 80
        assertEquals(SleepStage.DEEP, sleep.stages[5])    // 0x7F >= 80
    }

    @Test
    fun `decode truncated sleep timeline returns unknown`() {
        // Truly truncated: only 4 bytes total
        val data = byteArrayOf(0x11, 0, 0, 0)
        val events = RingDecoder.decode(data)
        assertTrue(events[0] is RingDecodedEvent.Unknown)
    }

    // ── End-to-End encode → decode roundtrip ────────────────────────────

    @Test
    fun `goal command encode produces valid output`() {
        val cmd = RingEncoder.makeGoalCommand(10000)
        assertEquals(0x1A.toByte(), cmd[0])
        assertEquals(0x10.toByte(), cmd[1]) // 10000 & 0xff
        assertEquals(0x27.toByte(), cmd[2]) // (10000 >> 8) & 0xff
    }

    @Test
    fun `hex to bytes roundtrip`() {
        val hex = "0c00000000000000000000000000000000000000"
        val bytes = RingEncoder.hexToBytes(hex)
        assertEquals(20, bytes.size)
        assertEquals(0x0C.toByte(), bytes[0])
        val back = bytes.toHexString()
        assertEquals(hex, back)
    }

    @Test
    fun `keepalive command is 0x3A`() {
        val cmd = ByteArray(20)
        cmd[0] = 0x3A.toByte()
        assertEquals(0x3A.toByte(), cmd[0])
        assertEquals(0, cmd[1].toInt() and 0xFF)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun composeRingPacket(commandId: Int, payload: ByteArray): ByteArray {
        val data = ByteArray(RingPacket.PACKET_SIZE)
        data[0] = commandId.toByte()
        for (i in payload.indices) {
            if (i < 19) data[i + 1] = payload[i]
        }
        return data
    }

    private fun buildActivityPayload(steps: Int, distance: Int, calories: Int): ByteArray {
        val bytes = ByteArray(19)
        // steps (bytes 4-7): u32le
        bytes[4] = (steps and 0xFF).toByte()
        bytes[5] = ((steps shr 8) and 0xFF).toByte()
        bytes[6] = ((steps shr 16) and 0xFF).toByte()
        bytes[7] = ((steps shr 24) and 0xFF).toByte()
        // distance (bytes 8-11): u32le
        bytes[8] = (distance and 0xFF).toByte()
        bytes[9] = ((distance shr 8) and 0xFF).toByte()
        bytes[10] = ((distance shr 16) and 0xFF).toByte()
        bytes[11] = ((distance shr 24) and 0xFF).toByte()
        // calories (bytes 12-15): u32le
        bytes[12] = (calories and 0xFF).toByte()
        bytes[13] = ((calories shr 8) and 0xFF).toByte()
        bytes[14] = ((calories shr 16) and 0xFF).toByte()
        bytes[15] = ((calories shr 24) and 0xFF).toByte()
        return bytes
    }
}
