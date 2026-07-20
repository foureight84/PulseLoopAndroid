package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from YCBTDecoder.swift (iOS #82).
 * Decodes inbound YCBT frames into the shared [RingDecodedEvent]. Frames arrive already
 * reassembled ([YCBTFrameAssembler]) and CRC-validated ([YCBTFrame]), from either the command
 * channel (be940001) or the async stream (be940003); this decoder dispatches on `(type, cmd)`
 * regardless of channel.
 *
 * **Health-group (`0x05`) frames never reach here** — the driver routes them into
 * [YCBTHistoryTransfer], which reassembles a whole transfer before [YCBTHealthRecords] cuts it
 * into records. History records are packed back-to-back and chopped at arbitrary frame
 * boundaries, so decoding one per-frame loses every record that straddles two.
 */
object YCBTDecoder {
    /** Plausibility gate for a live SpO2 sample — the one metric this decoder must self-gate. */
    private val spo2Range = 35..100

    /**
     * Decode one validated frame into the events it carries.
     *
     * @param startedMode the mode of the `03 2f` **start** still awaiting its reply, or null if the
     *   last live-measurement command sent was a stop (or none was). [YCBTDriver] supplies this,
     *   since the ring's reply carries a status but not a mode.
     */
    fun decode(frame: YCBTFrame, now: Instant = Instant.now(), startedMode: UByte? = null): List<RingDecodedEvent> =
        when {
            frame.type == YCBTGroup.REAL -> decodeRealStream(frame, now)
            // Auto-ACKed by YCBTDriver *before* this decode runs — the ring retransmits until it is.
            frame.type == YCBTGroup.DEV_CONTROL -> decodeDevControlPush(frame.cmd, frame.payload, now)
            frame.type == YCBTGroup.GET -> decodeGetReply(frame)
            frame.type == YCBTGroup.APP_CONTROL && frame.cmd == YCBTCommand.LIVE_MEASUREMENT ->
                decodeMeasurementStartReply(frame.payload, startedMode)
            frame.type == YCBTGroup.SETTING && frame.cmd == YCBTSettingKey.SET_TIME ->
                listOf(RingDecodedEvent.TimeSyncAck(now))
            else -> listOf(RingDecodedEvent.CommandAck(frame.cmd))
        }

    // MARK: - AppControl replies (group 0x03)

    /**
     * The ring's answer to `03 2f {enable, mode}` — one status byte. `0x00` is "started"; anything
     * else is the firmware declining. Surfaced as [RingDecodedEvent.MeasurementRejected] so the
     * in-flight spot measurement can fail immediately instead of polling a stream the ring already
     * told us it will never send.
     *
     * Two things keep a *stray* refusal from cancelling the wrong measurement:
     * 1. `startedMode` is null unless a start is actually outstanding — a rejected **stop** is just
     *    an ack, and a duplicate/late reply finds the mode already cleared.
     * 2. The mode travels with the event, so `RingSyncCoordinator` can check it against the
     *    measurement it is actually running before failing anything.
     */
    private fun decodeMeasurementStartReply(p: List<UByte>, startedMode: UByte?): List<RingDecodedEvent> {
        val ack = listOf(RingDecodedEvent.CommandAck(YCBTCommand.LIVE_MEASUREMENT))
        if (p.size != 1 || startedMode == null) return ack
        val status = p[0]
        if (YCBTMeasurementMode.isAccepted(status)) return ack
        return listOf(RingDecodedEvent.MeasurementRejected(startedMode))
    }

    // MARK: - Async live stream (be940003, group 0x06)

    private fun decodeRealStream(frame: YCBTFrame, now: Instant): List<RingDecodedEvent> {
        val p = frame.payload
        return when (frame.cmd) {
            YCBTCommand.LIVE_STATUS -> {
                // Cumulative day totals. steps verified against capture; distance/calories are the
                // adjacent u16s — UNVERIFIED (capture-inferred), but the app's activity update
                // uses max() so an over-read can't corrupt the day.
                if (p.size < 2) listOf(RingDecodedEvent.CommandAck(frame.cmd))
                else listOf(RingDecodedEvent.ActivityUpdate(now, YCBTBytes.u16(p, 0), YCBTBytes.u16(p, 2), YCBTBytes.u16(p, 4)))
            }

            YCBTCommand.LIVE_HEART_RATE -> {
                // 1-byte live bpm. Verified (climbed 82->86 across the capture).
                val bpm = p.firstOrNull()
                if (bpm == null) listOf(RingDecodedEvent.CommandAck(frame.cmd))
                else listOf(RingDecodedEvent.HeartRateSample(bpm.toInt(), now))
            }

            YCBTCommand.LIVE_SPO2 -> {
                // 1-byte live SpO2 % from the mode-0x02 (red-LED) stream. Gate to a plausible range
                // so a warm-up 0 isn't surfaced as a reading.
                val spo2 = p.firstOrNull()
                if (spo2 == null || spo2.toInt() !in spo2Range) listOf(RingDecodedEvent.CommandAck(frame.cmd))
                else listOf(RingDecodedEvent.Spo2Result(spo2.toInt(), now))
            }

            YCBTCommand.LIVE_VITALS -> decodeLiveVitals(p, frame.cmd, now)

            YCBTCommand.LIVE_BATTERY -> {
                // `06 15` battery push: `[chargingStatus][percent]`. Sent unprompted on
                // charge/level changes, so battery stays fresh without polling `02 00`.
                if (p.size < 2) listOf(RingDecodedEvent.CommandAck(frame.cmd))
                else listOf(RingDecodedEvent.Battery(p[1].toInt()))
            }

            YCBTCommand.LIVE_WEARING_STATUS -> {
                // `06 13`: `[ts:u32 2000-epoch][status]`. UNVERIFIED polarity — nonzero taken as worn.
                if (p.size < 5) listOf(RingDecodedEvent.CommandAck(frame.cmd))
                else listOf(RingDecodedEvent.WearingStatus(p[4].toUInt() != 0u, YCBTBytes.date(YCBTBytes.u32(p, 0))))
            }

            else -> listOf(RingDecodedEvent.CommandAck(frame.cmd))
        }
    }

    // MARK: - Command channel (be940001, group 0x02)

    private fun decodeGetReply(frame: YCBTFrame): List<RingDecodedEvent> = when (frame.cmd) {
        YCBTCommand.GET_DEVICE_INFO -> decodeDeviceInfo(frame.payload)
        YCBTCommand.GET_SUPPORT_FUNCTION -> decodeSupportFunction(frame.payload)
        YCBTCommand.GET_CHIP_SCHEME -> decodeChipScheme(frame.payload)
        else -> listOf(RingDecodedEvent.CommandAck(frame.cmd))
    }

    /**
     * `02 00` GetDeviceInfo reply: deviceId u16 @0, firmware sub-version @2 and main-version @3
     * (formatted "main.sub" by the vendor app, e.g. main 1 / sub 5 -> "1.05"), battery **state**
     * @4 and battery **percent** @5. Battery is in-band on this reply — the ring exposes no
     * standard battery service.
     *
     * The firmware string is deliberately not surfaced as a [RingDecodedEvent.FirmwareVersion]
     * here — that event is `Int?` on Android (jring's firmware is a bare numeric version), and
     * reformatting "main.sub" into an Int would misrepresent it. A YCBT ring that also exposes the
     * standard DIS `0x2A26`/`0x2A28` characteristics still gets its firmware string via
     * `RingBLEClient`'s existing generic read path.
     */
    private fun decodeDeviceInfo(p: List<UByte>): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>(RingDecodedEvent.Status(address = null))
        if (p.size >= 6) events.add(RingDecodedEvent.Battery(p[5].toInt()))
        return events
    }

    /** `02 01` GetSupportFunction reply — the firmware's own capability bitmap. */
    private fun decodeSupportFunction(p: List<UByte>): List<RingDecodedEvent> =
        listOf(RingDecodedEvent.SupportFunctions(YCBTSupportFunction.capabilities(p)))

    /** `02 1b` GetChipScheme reply — diagnostic only. */
    private fun decodeChipScheme(p: List<UByte>): List<RingDecodedEvent> =
        listOf(RingDecodedEvent.ChipScheme(YCBTChipScheme.value(p)))

    /**
     * `06 03` — the live feed for **both** the BP and the HRV spot measurements:
     *   `[SBP@0][DBP@1][hr@2]` then, if long enough, `[hrv@3][spo2@4][tempInt@5][tempFrac@6]`
     *
     * There are **not** two frame shapes here to disambiguate: the offsets are fixed, and the mode
     * just decides which of them the ring fills (BP mode fills @0/@1 and zeroes @3; HRV mode the
     * reverse). Each field is emitted iff it carries a value — which also recovers the HR that the
     * BP sweep measures.
     *
     * Emitted as [RingDecodedEvent.HistoryMeasurement] (upsert-by-timestamp), matching this
     * codebase's existing convention for jring's combined-sensor packet (`ColmiDecoder`/
     * `RingDecoder`'s `0x24`/BP handling) rather than introducing a separate append-only "live
     * sample" event class iOS has but Android's persistence layer doesn't distinguish.
     */
    private fun decodeLiveVitals(p: List<UByte>, cmd: UByte, now: Instant): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        if (p.size >= 2 && p[0] > 0u && p[1] > 0u) {
            events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, p[0].toDouble(), now))
            events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, p[1].toDouble(), now))
        }
        if (p.size >= 3 && p[2] > 0u) {
            events.add(RingDecodedEvent.HeartRateSample(p[2].toInt(), now))
        }
        if (p.size >= 4 && p[3] > 0u) {
            events.add(RingDecodedEvent.HrvSample(p[3].toInt(), now))
        }
        if (p.size >= 5 && p[4].toInt() in spo2Range) {
            events.add(RingDecodedEvent.Spo2Result(p[4].toInt(), now))
        }
        if (p.size >= 7 && p[5] > 0u) {
            events.add(RingDecodedEvent.TemperatureSample(YCBTHealthRecords.composite(p[5], p[6]), now))
        }
        return events.ifEmpty { listOf(RingDecodedEvent.CommandAck(cmd)) }
    }

    /** The ring's DevControl pushes. Only the measurement ones carry data PulseLoop has a home for. */
    private fun decodeDevControlPush(cmd: UByte, payload: List<UByte>, now: Instant): List<RingDecodedEvent> =
        when (cmd) {
            YCBTDevControl.MEASUREMENT_STATUS -> {
                val events = measurementStatusEvents(payload, now)
                events.ifEmpty { listOf(RingDecodedEvent.CommandAck(cmd)) }
            }
            YCBTDevControl.MEASUREMENT_RESULT -> {
                // `04 0e`: `[measureType][result]`, no value. SmartHealth reacts to a success by
                // re-reading history, which is where the reading actually lands — PulseLoop's
                // periodic re-sync already does that.
                listOf(RingDecodedEvent.CommandAck(cmd))
            }
            else -> listOf(RingDecodedEvent.CommandAck(cmd))
        }

    /**
     * `04 13` MeasurStatusAndResults — the ring's live "measurement in progress / done" push, the
     * counterpart of the `03 2f` start we sent: `[type@0][state@1]` then that type's value(s).
     */
    private fun measurementStatusEvents(p: List<UByte>, now: Instant): List<RingDecodedEvent> {
        if (p.size < 3) return emptyList()
        val value = p[2]
        val fraction = if (p.size >= 4) p[3] else 0u.toUByte()

        return when (p[0]) {
            YCBTMeasurementMode.HEART_RATE ->
                if (value > 0u) listOf(RingDecodedEvent.HeartRateSample(value.toInt(), now)) else emptyList()

            YCBTMeasurementMode.BLOOD_PRESSURE -> {
                if (value <= 0u || fraction <= 0u) emptyList()
                else listOf(
                    RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, value.toDouble(), now),
                    RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, fraction.toDouble(), now),
                )
            }

            YCBTMeasurementMode.SPO2 ->
                if (value.toInt() in spo2Range) listOf(RingDecodedEvent.Spo2Result(value.toInt(), now)) else emptyList()

            YCBTMeasurementMode.TEMPERATURE ->
                if (value > 0u) listOf(RingDecodedEvent.TemperatureSample(YCBTHealthRecords.composite(value, fraction), now)) else emptyList()

            YCBTMeasurementMode.BLOOD_SUGAR -> {
                // Tenths of mmol/L, as everywhere else in this SDK (int * 10 + frac).
                val tenths = value.toInt() * 10 + fraction.toInt()
                if (tenths <= 0) emptyList()
                else listOf(RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_SUGAR, YCBTHealthRecords.bloodSugarMgdl(tenths), now))
            }

            // Respiratory rate (3), uric acid (6), ketone (7), blood fat (9): no live event exists
            // for any of them, and PulseLoop can't start those measurements in the first place —
            // only a ring-initiated one could land here.
            else -> emptyList()
        }
    }
}
