package com.pulseloop.ring

import java.time.Instant
import java.time.ZoneId

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

        // Group 2: sleep + temperature history (decompiled `b1/e0.c`/`e0.d`). Sleep is confirmed
        // against a hardware capture (zaggash's R11, issue #29); temp history stays an ack until a
        // non-empty capture pins the layout.
        if (group == CRPCommands.GROUP_HISTORY) {
            if (cmd == CRPCommands.CMD_QUERY_HISTORY_SLEEP) {
                return decodeSleep(payload, now, ZoneId.systemDefault())
            }
            return listOf(RingDecodedEvent.CommandAck(commandId = ((group shl 4) or (cmd and 0x0F)).toUByte()))
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

    /**
     * Decode a sleep-history reply (`group 2 / cmd 14`), a faithful port of the vendor parser
     * `e1/j.b` (Moyoung "Da Rings"). Layout: `[dayIndex]` then repeating 3-byte records
     * `[state, hour, minute]`, where a record marks the moment sleep entered `state` and that state
     * runs until the NEXT record's timestamp (state 0=awake, 1=light, 2=deep, 3=rem). The vendor
     * requires `length % 3 == 1` (one day byte + N whole records); anything else is malformed.
     *
     * Confirmed against a hardware capture (issue #29): a `dayIndex 0` reply of 26 records decoded
     * to a clean 01:07→08:05 night (245 light / 110 deep / 63 REM minutes).
     *
     * Emitted as one [RingDecodedEvent.SleepTimeline] whose `stages` list is one entry per minute
     * (the shape [EventPersistenceSubscriber] expects), matching [ColmiDecoder.decodeSleep]. The
     * session start is anchored on the wake day (`today - dayIndex`) using the same evening-rollover
     * rule Colmi uses: a first record later in the clock than the last means the night began before
     * midnight, so the start offset is pulled back a day.
     */
    private fun decodeSleep(payload: ByteArray, now: Instant, zone: ZoneId): List<RingDecodedEvent> {
        // [dayIndex] + N*[state,hour,minute]; the vendor rejects any other shape outright.
        if (payload.size < 4 || payload.size % 3 != 1) return emptyList()
        val dayIndex = payload[0].toInt() and 0xFF
        val recordCount = (payload.size - 1) / 3

        val stages = mutableListOf<SleepStage>()
        var firstMinuteOfDay = -1
        var lastMinuteOfDay = 0
        var prevState = -1
        var prevHour = 0
        var prevMinute = 0
        for (k in 0 until recordCount) {
            val off = 1 + k * 3
            val state = payload[off].toInt() and 0xFF
            val hour = payload[off + 1].toInt() and 0xFF
            val minute = payload[off + 2].toInt() and 0xFF
            if (prevState >= 0) {
                // The segment ending at this record carries the PREVIOUS record's state.
                val duration = sleepSegmentMinutes(prevHour, prevMinute, hour, minute)
                if (duration in 1..MAX_SLEEP_MINUTES) {
                    val stage = mapSleepState(prevState)
                    repeat(duration) { stages.add(stage) }
                }
            }
            if (firstMinuteOfDay < 0) firstMinuteOfDay = hour * 60 + minute
            lastMinuteOfDay = hour * 60 + minute
            prevState = state
            prevHour = hour
            prevMinute = minute
        }
        if (stages.isEmpty()) return emptyList()

        val startOffset = if (firstMinuteOfDay > lastMinuteOfDay) firstMinuteOfDay - 1440 else firstMinuteOfDay
        val wakeDay = now.atZone(zone).toLocalDate().minusDays(dayIndex.toLong())
        val start = wakeDay.atStartOfDay(zone).plusMinutes(startOffset.toLong()).toInstant()
        return listOf(RingDecodedEvent.SleepTimeline(_timestamp = start, stages = stages))
    }

    /** Minutes from a previous `hh:mm` to this one, wrapping across midnight (vendor `e1/j.a`). */
    private fun sleepSegmentMinutes(prevHour: Int, prevMinute: Int, hour: Int, minute: Int): Int {
        val wrappedHour = if (prevHour > hour) hour + 24 else hour
        return ((wrappedHour - prevHour) * 60 + minute) - prevMinute
    }

    /** Vendor `e1/j.c` state codes → shared [SleepStage]. */
    private fun mapSleepState(state: Int): SleepStage = when (state) {
        0 -> SleepStage.AWAKE
        1 -> SleepStage.LIGHT
        2 -> SleepStage.DEEP
        3 -> SleepStage.REM
        else -> SleepStage.UNKNOWN
    }

    private const val MAX_SLEEP_MINUTES = 24 * 60

    /** Little-endian unsigned 3-byte int at [offset]. */
    private fun le3(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}
