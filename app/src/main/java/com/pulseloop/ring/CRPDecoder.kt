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
     * Decode group-1 vital result replies. Confirmed against `g1/a.java` and `e1/f.java` (HR),
     * `e1/g.java` (HRV), `e1/d.java` (SpO2), `e1/h.java` (stress/physical strength), and
     * the vendor's `onMeasureComplete` flow for temperature (cmd 32).
     *
     * Layout: `payload[0]` is the metric value for all types. Plausibility guards prevent
     * garbage samples (HR 40–200, SpO2 70–100, stress 0–100, HRV 20–200).
     */
    private fun decodeVitalResult(cmd: Int, payload: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (payload.isEmpty()) return listOf(RingDecodedEvent.CommandAck(commandId = ((CRPCommands.GROUP_DEVICE shl 4) or (cmd and 0x0F)).toUByte()))
        val value = payload[0].toInt() and 0xFF

        return when (cmd) {
            CRPCommands.CMD_MEASURE_HR -> {
                // HR from `e1/f.b()`: byte2int(payload[0]).
                if (value in 40..200) listOf(RingDecodedEvent.HeartRateSample(bpm = value, _timestamp = now))
                else emptyList()
            }
            CRPCommands.CMD_ENABLE_TIMING_HRV -> {
                // HRV from `e1/g.d()`: twoBytes2int(payload[1], payload[0]), but vendor's
                // onHrv() callback receives byte2int(payload[0]) for the live measurement path.
                // We accept either layout: single-byte if payload is 1 byte, two-byte otherwise.
                val hrvValue = if (payload.size >= 2) (
                    (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                ) else value
                if (hrvValue in 20..200) listOf(RingDecodedEvent.HrvSample(value = hrvValue, _timestamp = now))
                else emptyList()
            }
            CRPCommands.CMD_ENABLE_TIMING_SPO2 -> {
                // SpO2 from `e1/d.b()`: byte2int(payload[0]).
                if (value in 70..100) listOf(RingDecodedEvent.Spo2Result(value = value, _timestamp = now))
                else emptyList()
            }
            CRPCommands.CMD_ENABLE_TIMING_STRESS -> {
                // Stress/physical strength from `e1/h.c()`: byte2int(payload[0]).
                if (value in 0..100) listOf(RingDecodedEvent.StressSample(value = value, _timestamp = now))
                else emptyList()
            }
            CRPCommands.CMD_ENABLE_TIMING_TEMP -> {
                // Temperature: vendor uses onMeasureComplete with payload. Layout unconfirmed.
                // Emit as temperature_sample with raw byte as placeholder until verified.
                listOf(RingDecodedEvent.TemperatureSample(celsius = value.toDouble(), _timestamp = now))
            }
            else -> {
                // Acknowledgment for enable/disable commands.
                listOf(RingDecodedEvent.CommandAck(commandId = ((CRPCommands.GROUP_DEVICE shl 4) or (cmd and 0x0F)).toUByte()))
            }
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
