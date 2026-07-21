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
 *   - `2a37` → standard HR-measurement stream
 *   - `fdd3` → framed `FD DA …` command replies (already reassembled by [CRPFrameAssembler])
 *
 * Unverified-against-hardware layouts are decoded conservatively: anything whose byte layout isn't
 * confirmed from the decompile is emitted as [RingDecodedEvent.CommandAck]/[Unknown] rather than
 * fabricating a metric value. Extend [decodeFramedReply] as more command replies are confirmed.
 */
object CRPDecoder {

    fun decode(data: ByteArray, from: String, now: Instant = Instant.now()): List<RingDecodedEvent> {
        val src = from.lowercase()
        return when {
            src.contains("fdd1") -> decodeCurrentSteps(data, now)
            src.contains("2a37") -> decodeHeartRateMeasure(data, now)
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

    /** Standard HR characteristic (`2a37`). From `g1/a.B`: bpm at byte[1], validated by the
     *  `0x0400` marker at bytes[2..3] (big-endian: byte[3] high). */
    private fun decodeHeartRateMeasure(data: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (data.size < 2) return emptyList()
        val bpm = data[1].toInt() and 0xFF
        val markerOk = data.size < 4 ||
            (((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)) == 0x0400
        if (!markerOk || bpm <= 0) return emptyList()
        return listOf(RingDecodedEvent.HeartRateSample(bpm = bpm, _timestamp = now))
    }

    /** Framed `fdd3` reply: `FD DA 10 <len> <group> <cmd> <payload>`. v1 acknowledges recognised
     *  command echoes; richer metric replies (HR/SpO2 results, history) are decoded as more
     *  layouts are confirmed against the decompile/hardware. */
    private fun decodeFramedReply(frame: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (frame.size < CRPProtocol.HEADER_SIZE) return emptyList()
        val group = frame[4].toInt() and 0xFF
        val cmd = frame[5].toInt() and 0xFF
        // Only the command echo is confirmed for the v1 command set; treat as an ack so the
        // raw-notify/debug feed still records it without inventing a metric value.
        return listOf(RingDecodedEvent.CommandAck(commandId = ((group shl 4) or (cmd and 0x0F)).toUByte()))
    }

    /** Little-endian unsigned 3-byte int at [offset]. */
    private fun le3(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}
