package com.pulseloop.ring

import java.time.Instant

/**
 * Reassembles CRP command replies (`fdd3`) that span multiple BLE notifications. A logical frame
 * starts with `FD DA …` and its declared total length ([CRPProtocol.frameLength]) tells us when it
 * is complete. Mirrors the vendor's `g1/a.k()`. One assembler instance per connection — a fresh
 * [CRPDriver] is built on every connect, so state always starts clean.
 */
class CRPFrameAssembler {
    private var buffer: ByteArray = ByteArray(0)
    private var expected: Int = 0

    /** Feed one notification chunk. Returns the complete frame when the last chunk lands, else null. */
    fun append(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        if (CRPProtocol.isFrameStart(chunk)) {
            expected = CRPProtocol.frameLength(chunk)
            buffer = ByteArray(0)
        }
        // A continuation chunk with no in-progress frame is noise — drop it.
        if (expected <= 0) return null
        buffer += chunk
        if (buffer.size >= expected) {
            val frame = if (buffer.size == expected) buffer else buffer.copyOf(expected)
            buffer = ByteArray(0)
            expected = 0
            return frame
        }
        return null
    }
}

/**
 * Decodes CRP notifications into [RingDecodedEvent]s. Routing is by source characteristic (the
 * `from` UUID [CRPDriver.ingest] passes through), matching the vendor's `g1/a.a(characteristic)`
 * dispatch:
 *   - `fdd1` → raw current-steps triples (no CRP header)
 *   - `fdd3` → framed `FD DA …` command replies (already reassembled by [CRPFrameAssembler])
 *
 * NOTE: This ring does NOT use the standard `2a37` HR characteristic — all vital results come
 * back as framed replies on `fdd3` with group/cmd routing. The `2a37` path is dead code for CRP
 * rings (removed during port).
 *
 * Group-1 replies (`g1/a.java` lines 664–712) carry real-time vital results:
 *   cmd 9  → HR (payload[0] = bpm, per `e1/f.b()`)
 *   cmd 10 → HRV (payload[0] = ms)
 *   cmd 11 → SpO2 (payload[0] = percent)
 *   cmd 14 → stress (payload[0] = 0..100)
 *   cmd 32 → temperature (payload[0..] = raw)
 *   Other cmd values → command acknowledgment.
 */
object CRPDecoder {

    fun decode(data: ByteArray, from: String, now: Instant = Instant.now()): List<RingDecodedEvent> {
        val src = from.lowercase()
        return when {
            src.contains("fdd1") -> decodeCurrentSteps(data, now)
            CRPProtocol.isFrameStart(data) -> decodeFramedReply(data, now)
            else -> emptyList()
        }
    }

    /** `fdd1` push — little-endian 3-byte triples: [steps][distance][calories]([time]).
     *  From `e1/k.b`. distance is metres, calories kcal (vendor units). */
    private fun decodeCurrentSteps(data: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (data.isEmpty() || data.size % 3 != 0) return emptyList()
        val steps = le3(data, 0)
        val distance = if (data.size >= 6) le3(data, 3) else 0
        val calories = if (data.size >= 9) le3(data, 6) else 0
        return listOf(RingDecodedEvent.ActivityUpdate(now, steps, distance, calories))
    }

    /**
     * Framed `fdd3` reply: `FD DA 10 <len> <group> <cmd> <payload>`.
     * Real-time vital results come on group 1; history queries on group 7; device info on group 7.
     */
    private fun decodeFramedReply(frame: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (frame.size < CRPProtocol.HEADER_SIZE) return emptyList()
        val group = frame[4].toInt() and 0xFF
        val cmd = frame[5].toInt() and 0xFF
        val payload = if (frame.size > CRPProtocol.HEADER_SIZE) frame.copyOfRange(CRPProtocol.HEADER_SIZE, frame.size) else ByteArray(0)

        // Group 1: real-time vital results (decompiled `g1/a.java` lines 664–712).
        if (group == CRPCommands.GROUP_DEVICE) {
            return decodeVitalResult(cmd, payload, now)
        }

        // Group 7: history queries + device info (decompiled `b1/e0` + `b1/r`).
        if (group == CRPCommands.GROUP_DEVICE_INFO) {
            return decodeHistoryOrDeviceInfoResponse(cmd, payload, now)
        }

        // Unknown group/cmd — ack.
        return listOf(RingDecodedEvent.CommandAck(commandId = ((group shl 4) or (cmd and 0x0F)).toUByte()))
    }

    /**
     * Decode group-1 real-time vital result replies. Routed on the INBOUND result opcodes
     * (`CMD_RESULT_*`), which are distinct from the outbound enable-timing commands. Confirmed
     * against the vendor dispatcher `g1/a.java` (lines 664–712) and its per-vital parsers:
     *   cmd 9  → HR    `e1/f.b`  : byte2int(payload[0])                     — guard 40..200
     *   cmd 10 → HRV   `g1/a`    : byte2int(payload[0]) (live path)         — guard 20..200
     *   cmd 11 → SpO2  `e1/d.b`  : byte2int(payload[0])                     — guard 70..100
     *   cmd 14 → stress`g1/a`    : byte2int(payload[0])                     — guard 0..100
     *   cmd 32 → temp  `e1/m.a`  : (payload[1]<<8 | payload[0]) / 10.0 °C   — guard 28.0..50.0
     */
    private fun decodeVitalResult(cmd: Int, payload: ByteArray, now: Instant): List<RingDecodedEvent> {
        fun ack() = listOf(RingDecodedEvent.CommandAck(commandId = ((CRPCommands.GROUP_DEVICE shl 4) or (cmd and 0x0F)).toUByte()))
        if (payload.isEmpty()) return ack()
        val value = payload[0].toInt() and 0xFF

        return when (cmd) {
            CRPCommands.CMD_RESULT_HR ->
                if (value in 40..200) listOf(RingDecodedEvent.HeartRateSample(bpm = value, _timestamp = now))
                else emptyList()
            CRPCommands.CMD_RESULT_HRV ->
                if (value in 20..200) listOf(RingDecodedEvent.HrvSample(value = value, _timestamp = now))
                else emptyList()
            CRPCommands.CMD_RESULT_SPO2 ->
                if (value in 70..100) listOf(RingDecodedEvent.Spo2Result(value = value, _timestamp = now))
                else emptyList()
            CRPCommands.CMD_RESULT_STRESS ->
                if (value in 0..100) listOf(RingDecodedEvent.StressSample(value = value, _timestamp = now))
                else emptyList()
            CRPCommands.CMD_RESULT_TEMP -> {
                // Vendor `e1/m.a(payload[1], payload[0])`: twoBytes2int/10, valid 28.0..50.0 °C.
                if (payload.size < 2) return emptyList()
                val celsius = (((payload[1].toInt() and 0xFF) shl 8) or (payload[0].toInt() and 0xFF)) / 10.0
                if (celsius in 28.0..50.0) listOf(RingDecodedEvent.TemperatureSample(celsius = celsius, _timestamp = now))
                else emptyList()
            }
            else -> ack() // enable/disable acknowledgments and other group-1 replies
        }
    }

    /**
     * Decode group-7 responses: history queries (cmd 4–7, 14, 48) and device info (cmd 0, 1, 13).
     * History layouts are unconfirmed against hardware — emit as CommandAck so the raw-packet feed
     * records them without inventing metric values. Extend [decodeHistoryOrDeviceInfoResponse]
     * as more layouts are confirmed.
     */
    private fun decodeHistoryOrDeviceInfoResponse(cmd: Int, payload: ByteArray, now: Instant): List<RingDecodedEvent> {
        return listOf(RingDecodedEvent.CommandAck(commandId = ((CRPCommands.GROUP_DEVICE_INFO shl 4) or (cmd and 0x0F)).toUByte()))
    }

    /** Little-endian unsigned 3-byte int at [offset]. */
    private fun le3(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}
