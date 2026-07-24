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
    /** Longest plausible single sleep segment; a longer one is a corrupt record, not real sleep. */
    private const val MAX_SLEEP_MINUTES = 24 * 60
    /** Awake run that separates two sleep bouts (night vs nap) — matches the persistence layer. */
    private const val SESSION_GAP_MINUTES = 60
    /** CRPHistoryDay caps at 14 days ago; a larger dayIndex is a corrupt reply. */
    private const val MAX_HISTORY_DAY = 14

    fun decode(
        data: ByteArray,
        from: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<RingDecodedEvent> {
        val src = from.lowercase()
        return when {
            src.contains("fdd1") -> decodeCurrentSteps(data, now)
            CRPProtocol.isFrameStart(data) -> decodeFramedReply(data, now, zone)
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
    private fun decodeFramedReply(frame: ByteArray, now: Instant, zone: ZoneId): List<RingDecodedEvent> {
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

        // Group 2: sleep + the all-day "timing" vital timelines + temperature history.
        //   cmd 14        → sleep (decompiled `e1/j`), confirmed against a hardware capture.
        //   cmd 15/16/17/47 → HR/HRV/SpO2/stress all-day timeline (decompiled `e1/{f,g,d,l}`),
        //                     confirmed against zaggash's R11 rc2 capture (issue #29).
        //   cmd 48        → temperature history, still an ack until a non-empty capture pins it.
        if (group == CRPCommands.GROUP_HISTORY) {
            if (cmd == CRPCommands.CMD_QUERY_HISTORY_SLEEP) {
                return decodeSleep(payload, now, zone)
            }
            decodeTimingHistory(cmd, payload, now, zone)?.let { return it }
            return listOf(RingDecodedEvent.CommandAck(commandId = ((group shl 4) or (cmd and 0x0F)).toUByte()))
        }

        // Group 3: power control + the autonomous wear-state push (vendor `g1/a.java` case 3→7,
        // `onWearStateChange(payload[0] > 0)`). Confirmed against zaggash's R11 (issue #29): a spot
        // measure returns nothing while `payload[0] == 0` (ring off the finger).
        if (group == CRPCommands.GROUP_POWER) {
            if (cmd == CRPCommands.CMD_WEAR_STATE && payload.isNotEmpty()) {
                return listOf(RingDecodedEvent.WearingStatus(worn = (payload[0].toInt() and 0xFF) != 0, _timestamp = now))
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

    /** All-day timeline frames carry 144 sample-slots at a fixed 5-minute cadence (`w0.b.a() / 5`
     *  in the vendor). Two slot widths: HR/SpO2/stress store one byte per slot (144 slots/frame,
     *  terminal frame index 1); HRV stores a little-endian 2-byte value per slot (72 slots/frame,
     *  terminal index 3). Both reassemble to a 288-slot (24 h) day across their frames. */
    private const val TIMING_SLOT_MINUTES = 5
    private const val TIMING_SLOTS_PER_FRAME_1BYTE = 144
    private const val TIMING_SLOTS_PER_FRAME_2BYTE = 72

    /**
     * Decode a CRP all-day "timing" vital-history reply (group 2). Returns null for a non-timing
     * group-2 cmd (e.g. temp cmd 48) so the caller falls back to an ack. Layout, confirmed against
     * zaggash's R11 rc2 capture and the vendor parsers `e1/{f,g,d,l}.java`:
     *   `[day][frameIndex][slot samples…]` — one 5-minute slot per sample, `0` = no reading.
     * HR/SpO2/stress use one byte per slot; HRV uses a little-endian 2-byte value per slot. Each
     * slot's absolute time is `localMidnight(today − day) + (frameIndex*slotsPerFrame + slot)*5min`,
     * matching the vendor's `w0.b.a()/5` slot indexing. Emits a [RingDecodedEvent.HistoryMeasurement]
     * per valid slot (invalid/zero slots dropped, per the vendor's per-vital clamp) plus a trailing
     * [RingDecodedEvent.TimingHistoryFrame] that drives the engine's next-frame follow-up.
     */
    private fun decodeTimingHistory(cmd: Int, payload: ByteArray, now: Instant, zone: ZoneId): List<RingDecodedEvent>? {
        // (kind, sample byte-width, validity predicate) per vital. Ranges mirror the vendor clamps:
        // HR 40..200 (`e1/f.e`), SpO2 1..100 (`e1/d.e`, >100→0), HRV any positive (`e1/g.d`, no clamp),
        // stress 1..100 (`e1/l.d`, no clamp; 0 treated as no-reading). Zero is always "no sample".
        val kind: MeasurementKind
        val twoByte: Boolean
        val valid: (Int) -> Boolean
        when (cmd) {
            CRPCommands.CMD_QUERY_TIMING_HR -> { kind = MeasurementKind.HEART_RATE; twoByte = false; valid = { it in 40..200 } }
            CRPCommands.CMD_QUERY_TIMING_SPO2 -> { kind = MeasurementKind.SPO2; twoByte = false; valid = { it in 1..100 } }
            CRPCommands.CMD_QUERY_TIMING_HRV -> { kind = MeasurementKind.HRV; twoByte = true; valid = { it in 1..300 } }
            CRPCommands.CMD_QUERY_TIMING_STRESS -> { kind = MeasurementKind.STRESS; twoByte = false; valid = { it in 1..100 } }
            else -> return null
        }
        // [day][frameIndex] header; anything shorter is malformed.
        if (payload.size < 2) return emptyList()
        val day = payload[0].toInt() and 0xFF
        val frameIndex = payload[1].toInt() and 0xFF
        // A wilder day than CRPHistoryDay allows is a corrupt reply — ack without inventing samples.
        if (day > MAX_HISTORY_DAY) {
            return listOf(RingDecodedEvent.CommandAck(commandId = ((CRPCommands.GROUP_HISTORY shl 4) or (cmd and 0x0F)).toUByte()))
        }

        val midnight = now.atZone(zone).toLocalDate().minusDays(day.toLong()).atStartOfDay(zone).toInstant()
        val slotsPerFrame = if (twoByte) TIMING_SLOTS_PER_FRAME_2BYTE else TIMING_SLOTS_PER_FRAME_1BYTE
        val events = mutableListOf<RingDecodedEvent>()
        val step = if (twoByte) 2 else 1
        var slot = 0
        var i = 2
        while (i + step - 1 < payload.size) {
            val value = if (twoByte) {
                (payload[i].toInt() and 0xFF) or ((payload[i + 1].toInt() and 0xFF) shl 8)
            } else {
                payload[i].toInt() and 0xFF
            }
            if (valid(value)) {
                val globalSlot = frameIndex * slotsPerFrame + slot
                val ts = midnight.plusSeconds(globalSlot.toLong() * TIMING_SLOT_MINUTES * 60)
                events.add(RingDecodedEvent.HistoryMeasurement(kind, value.toDouble(), ts))
            }
            i += step
            slot++
        }
        // Drive the vendor's sequential next-frame pull (see [RingDecodedEvent.TimingHistoryFrame]).
        events.add(RingDecodedEvent.TimingHistoryFrame(cmd = cmd, day = day, frameIndex = frameIndex))
        return events
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
     * Emitted as [RingDecodedEvent.SleepTimeline]s whose `stages` lists are one entry per minute
     * (the shape [EventPersistenceSubscriber] expects), matching [ColmiDecoder.decodeSleep]. A day's
     * reply can hold more than one sleep bout (a night plus a nap), so we split into a separate
     * timeline at any awake run of [SESSION_GAP_MINUTES]+ (the same gap the persistence layer uses
     * to separate sessions). Short mid-night wakes stay inside their bout as awake minutes.
     *
     * Two deliberate departures from the vendor:
     *  - The vendor extends the final record's state to the current wall-clock when it isn't awake
     *    (an in-progress sleep). We don't — a completed night always ends on an awake record (which
     *    contributes no sleep), so the only case affected is a sync taken mid-sleep, where we'd
     *    rather show the night up to the last recorded transition than invent minutes up to "now".
     *  - Session-start anchoring is ours, not the vendor's (its parser keeps minute-of-day only and
     *    lets the UI place the date from `dayIndex`). We anchor the FIRST record on the wake day
     *    (`today - dayIndex`) with the same evening-rollover rule as Colmi — a first record later in
     *    the clock than the last means the night began before midnight — then place every later bout
     *    by its elapsed offset from that anchor, which carries naps onto the correct day for free.
     *    NOTE: this assumes `dayIndex` is the WAKE day — verified against a post-midnight capture; an
     *    evening-start night is not yet capture-confirmed.
     */
    private fun decodeSleep(payload: ByteArray, now: Instant, zone: ZoneId): List<RingDecodedEvent> {
        // [dayIndex] + N*[state,hour,minute]; the vendor rejects any other shape outright.
        if (payload.size < 4 || payload.size % 3 != 1) return emptyList()
        val dayIndex = payload[0].toInt() and 0xFF
        // CRPHistoryDay tops out at 14 days ago; a wilder value is a corrupt reply, not a real day.
        if (dayIndex > MAX_HISTORY_DAY) return emptyList()
        val recordCount = (payload.size - 1) / 3

        // Pass 1: fold the records into monotonic transition points, each an elapsed-minute offset
        // from the first valid record and the state that begins there. Out-of-order / corrupt
        // records are skipped without advancing the cursor, matching the vendor's `iA >= 0` guard.
        val transitions = mutableListOf<Transition>()
        var firstMinuteOfDay = -1
        var lastMinuteOfDay = 0
        var elapsed = 0
        var prevHour = 0
        var prevMinute = 0
        for (k in 0 until recordCount) {
            val off = 1 + k * 3
            val state = payload[off].toInt() and 0xFF
            val hour = payload[off + 1].toInt() and 0xFF
            val minute = payload[off + 2].toInt() and 0xFF
            if (hour > 23 || minute > 59) continue
            if (transitions.isEmpty()) {
                firstMinuteOfDay = hour * 60 + minute
                lastMinuteOfDay = firstMinuteOfDay
                transitions.add(Transition(0, state))
            } else {
                val duration = sleepSegmentMinutes(prevHour, prevMinute, hour, minute)
                if (duration < 0 || duration > MAX_SLEEP_MINUTES) continue
                elapsed += duration
                lastMinuteOfDay = hour * 60 + minute
                transitions.add(Transition(elapsed, state))
            }
            prevHour = hour
            prevMinute = minute
        }
        if (transitions.size < 2) return emptyList()

        // Anchor the first record; every bout is then just an offset from it.
        val startOffset = if (firstMinuteOfDay > lastMinuteOfDay) firstMinuteOfDay - 1440 else firstMinuteOfDay
        val wakeDay = now.atZone(zone).toLocalDate().minusDays(dayIndex.toLong())
        val anchor = wakeDay.atStartOfDay(zone).plusMinutes(startOffset.toLong()).toInstant()

        // Pass 2: each transition's state runs until the next; split bouts on a long awake gap.
        val events = mutableListOf<RingDecodedEvent>()
        var boutStages = mutableListOf<SleepStage>()
        var boutStartElapsed = 0
        for (i in 0 until transitions.size - 1) {
            val segment = transitions[i]
            val duration = transitions[i + 1].elapsed - segment.elapsed
            if (duration <= 0) continue
            val stage = mapSleepState(segment.state)
            if (stage == SleepStage.AWAKE && duration >= SESSION_GAP_MINUTES) {
                emitSleepBout(events, anchor, boutStartElapsed, boutStages)
                boutStages = mutableListOf()
                continue
            }
            if (boutStages.isEmpty()) boutStartElapsed = segment.elapsed
            repeat(duration) { boutStages.add(stage) }
        }
        emitSleepBout(events, anchor, boutStartElapsed, boutStages)
        return events
    }

    private class Transition(val elapsed: Int, val state: Int)

    /** Emit a bout as a SleepTimeline, unless it holds no actual sleep (awake-only). */
    private fun emitSleepBout(into: MutableList<RingDecodedEvent>, anchor: Instant, startElapsed: Int, stages: List<SleepStage>) {
        if (stages.none { it != SleepStage.AWAKE }) return
        into.add(RingDecodedEvent.SleepTimeline(_timestamp = anchor.plusSeconds(startElapsed * 60L), stages = stages))
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

    /** Little-endian unsigned 3-byte int at [offset]. */
    private fun le3(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}
