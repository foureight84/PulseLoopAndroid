package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from LuckRingDecoder.swift (iOS #90).
 *
 * Decodes reassembled LuckRing frames into the shared [RingDecodedEvent]. Frames arrive whole from
 * [LuckRingFrameAssembler]; this decoder dispatches on `dataType` and slices each record type per its
 * `ProcessDATA_TYPE_*` parser.
 *
 * **Timestamps are true UTC Unix seconds**, so -- unlike the jring/YCBT clocks, which store local
 * wall-clock -- decoding is a plain `Instant.ofEpochSecond` with no offset to un-apply. Range gating
 * lives in `RingEventBridge`/`EventPersistenceSubscriber`, so a misdecoded byte is dropped rather than
 * persisted; the decoder only cuts records and drops the ring's "no sample" fillers.
 *
 * **Every metric record uses one envelope**: `[total u16 LE][items u8]` then `items` fixed-stride
 * records. Live blood pressure has no dedicated Android event type (mirrors [YCBTDecoder]'s existing
 * convention) -- both live and history BP fan out to two [RingDecodedEvent.HistoryMeasurement] rows
 * (systolic/diastolic), upserted by timestamp.
 */
object LuckRingDecoder {
    fun decode(frame: LuckRingFrame, now: Instant = Instant.now()): List<RingDecodedEvent> {
        // A device ACK is a verdict on a command, not data.
        if (frame.cmdType == LuckRingCmdType.ACK) {
            return listOf(RingDecodedEvent.CommandAck(frame.dataType))
        }

        val p = frame.payload
        return when (frame.dataType) {
            LuckRingDataType.DEV_INFO -> decodeDeviceInfo(p)
            LuckRingDataType.BATTERY -> {
                val percent = p.firstOrNull()
                if (percent == null) listOf(RingDecodedEvent.CommandAck(frame.dataType))
                else listOf(RingDecodedEvent.Battery(percent.toInt()))
            }

            LuckRingDataType.REAL_SPORT, LuckRingDataType.HISTORY_SPORT -> decodeSport(p, frame.dataType)
            LuckRingDataType.SLEEP -> decodeSleep(p)

            LuckRingDataType.REAL_HEART, LuckRingDataType.EXERCISE_HEART -> decodeLiveHeart(p, now)
            LuckRingDataType.HISTORY_HEART -> decodeHistory(p, MeasurementKind.HEART_RATE)

            LuckRingDataType.REAL_O2 -> decodeLiveSpO2(p, now)
            LuckRingDataType.HISTORY_O2 -> decodeHistory(p, MeasurementKind.SPO2)

            LuckRingDataType.REAL_BP -> decodeBP(p).ifEmpty { listOf(RingDecodedEvent.CommandAck(LuckRingDataType.REAL_BP)) }
            LuckRingDataType.HISTORY_BP -> decodeBP(p)

            LuckRingDataType.REAL_HRV ->
                decodeLive(p, now) { value, ts -> RingDecodedEvent.HrvSample(value.toInt(), ts) }
            LuckRingDataType.HISTORY_HRV -> decodeHistory(p, MeasurementKind.HRV)

            LuckRingDataType.REAL_TEMP -> decodeTemperature(p, history = false)
            LuckRingDataType.HISTORY_TEMP -> decodeTemperature(p, history = true)

            LuckRingDataType.STRESS ->
                decodeLive(p, now) { value, ts -> RingDecodedEvent.StressSample(value.toInt(), ts) }
            LuckRingDataType.STRESS_HISTORY -> decodeHistory(p, MeasurementKind.STRESS)

            LuckRingDataType.PAIR_FINISH, LuckRingDataType.FIND_DEVICE, LuckRingDataType.DEV_SYNC,
            LuckRingDataType.FUNCTION_CONTROL, LuckRingDataType.UNBIND ->
                // Handshake/echo frames with no metric to persist -- surfaced as acks (the raw-packet
                // feed still shows them). `devSync` (9) carries a MixInfo TLV of settings/function
                // bits, but the capability bitmap is obfuscated in the decompile, so nothing maps it yet.
                listOf(RingDecodedEvent.CommandAck(frame.dataType))

            else -> listOf(RingDecodedEvent.Unknown(frame.dataType, p.map { it.toByte() }.toByteArray()))
        }
    }

    // MARK: - Envelope helpers

    /**
     * `[total u16 LE][items u8]` header, then `items` records. Returns each record's bytes, cut at the
     * declared item count. When `stride` is null it is derived from the payload -- needed for
     * temperature, whose 5-byte and 8-byte record variants share the same opcode.
     */
    private fun records(payload: List<UByte>, stride: Int?): List<List<UByte>> {
        if (payload.size < 3) return emptyList()
        val items = payload[2].toInt()
        if (items <= 0) return emptyList()
        val body = payload.subList(3, payload.size)
        val step = stride ?: (body.size / items)
        if (step <= 0) return emptyList()
        val out = mutableListOf<List<UByte>>()
        var offset = 0
        for (n in 0 until items) {
            if (offset + step > body.size) break
            out.add(body.subList(offset, offset + step))
            offset += step
        }
        return out
    }

    private fun ringDate(record: List<UByte>): Instant = Instant.ofEpochSecond(LuckRingBytes.u32(record, 0))

    // MARK: - Records

    /**
     * `K6_DevInfoStruct.getSoftwareVer()`: bytes `[1..5]` (customer.hardware.code.picture.font) joined
     * by dots. Byte `[0]` is the item count and is not part of the version. Android has no `Int`-typed
     * firmware event that fits a dotted string (see [YCBTDecoder]'s identical note), so this rides
     * [RingDecodedEvent.Status]'s optional `firmware` field instead.
     */
    private fun decodeDeviceInfo(p: List<UByte>): List<RingDecodedEvent> {
        if (p.size < 6) return listOf(RingDecodedEvent.CommandAck(LuckRingDataType.DEV_INFO))
        val version = p.subList(1, 6).joinToString(".") { it.toInt().toString() }
        return listOf(RingDecodedEvent.Status(address = null, firmware = version))
    }

    /**
     * Sport records -- 20 bytes each (`K6_Sport`): `[start u32][steps u32][distance u24(+pad)]`
     * `[calories u24(+pad)][duration u24(+pad)]`. Emitted as `.activityBucket` (per-interval, summed
     * into the day and upserted by timestamp) for both the live (4) and history (5) streams, so a
     * re-sync is idempotent. Calories are intentionally dropped (`.activityBucket` carries none).
     */
    private fun decodeSport(p: List<UByte>, dataType: UByte): List<RingDecodedEvent> {
        val recs = records(p, 20)
        if (recs.isEmpty()) return listOf(RingDecodedEvent.CommandAck(dataType))
        return recs.map { r ->
            RingDecodedEvent.ActivityBucket(
                ringDate(r),
                steps = LuckRingBytes.u32(r, 4).toInt(),
                distanceMeters = LuckRingBytes.u24(r, 8),
            )
        }
    }

    /** Live HR -- 5-byte records `[time u32][bpm u8]`. An envelope with zero items is the ring
     *  signalling the measurement ended, surfaced as `.heartRateComplete`. */
    private fun decodeLiveHeart(p: List<UByte>, now: Instant): List<RingDecodedEvent> {
        val recs = records(p, 5)
        if (recs.isEmpty()) return listOf(RingDecodedEvent.HeartRateComplete(now))
        return recs.map { RingDecodedEvent.HeartRateSample(it[4].toInt(), ringDate(it)) }
    }

    /** Live SpO2 -- 5-byte records `[time u32][spo2 u8]` (`K6_DATA_TYPE_REAL_O2`). */
    private fun decodeLiveSpO2(p: List<UByte>, now: Instant): List<RingDecodedEvent> {
        val recs = records(p, 5)
        if (recs.isEmpty()) return listOf(RingDecodedEvent.Spo2Complete(now))
        return recs.map { RingDecodedEvent.Spo2Result(it[4].toInt(), ringDate(it)) }
    }

    /** Blood pressure -- 6-byte records `[time u32][sys u8][dia u8]`, shared by the live (18) and
     *  history (41) opcodes. Fans out to a systolic and a diastolic row (each trends independently),
     *  mirroring `EventPersistenceSubscriber`'s two-row storage and [YCBTDecoder]'s BP convention. */
    private fun decodeBP(p: List<UByte>): List<RingDecodedEvent> {
        val recs = records(p, 6)
        if (recs.isEmpty()) return emptyList()
        return recs.flatMap { r ->
            val date = ringDate(r)
            listOf(
                RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, r[4].toDouble(), date),
                RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, r[5].toDouble(), date),
            )
        }
    }

    /** A generic single-value live stream -- 5-byte records `[time u32][value u8]` -- mapped through a
     *  builder (HRV, stress). Empty envelope acks (nothing to complete). */
    private fun decodeLive(p: List<UByte>, now: Instant, make: (UByte, Instant) -> RingDecodedEvent): List<RingDecodedEvent> {
        val recs = records(p, 5)
        if (recs.isEmpty()) return listOf(RingDecodedEvent.CommandAck(0u))
        return recs.map { make(it[4], ringDate(it)) }
    }

    /** History for a 5-byte `[time u32][value u8]` type -> `.historyMeasurement`. */
    private fun decodeHistory(p: List<UByte>, kind: MeasurementKind): List<RingDecodedEvent> {
        val recs = records(p, 5)
        if (recs.isEmpty()) return emptyList()
        return recs.map { RingDecodedEvent.HistoryMeasurement(kind, it[4].toDouble(), ringDate(it)) }
    }

    /**
     * Temperature -- `K6_TempStruct`: `[time u32][value u16 LE]/10`. The stride is derived from the
     * envelope so both the 5-byte (`parse`) and 8-byte (`parseFloat`) record variants decode; the
     * value is read as a u16 when the record is wide enough, else a single byte, and scaled by 10.
     */
    private fun decodeTemperature(p: List<UByte>, history: Boolean): List<RingDecodedEvent> {
        val recs = records(p, null)
        if (recs.isEmpty()) {
            return if (history) emptyList() else listOf(RingDecodedEvent.CommandAck(LuckRingDataType.REAL_TEMP))
        }
        return recs.map { r ->
            val raw = if (r.size >= 6) LuckRingBytes.u16(r, 4) else if (r.size > 4) r[4].toInt() else 0
            val celsius = raw / 10.0
            val date = ringDate(r)
            if (history) RingDecodedEvent.HistoryMeasurement(MeasurementKind.TEMPERATURE, celsius, date)
            else RingDecodedEvent.TemperatureSample(celsius, date)
        }
    }

    // MARK: - Sleep

    private data class SleepEntry(val type: UByte, val time: Long)

    /**
     * Sleep timeline (`ProcessDATA_TYPE_SLEEP`): `[total u16][pageCount u8]`, then `pageCount` pages of
     * `[validCount u8]` + 15 x `[type u8][time u32 LE]` (76 B/page, only `validCount` entries valid).
     *
     * Types (`CEBC.SLEEPSTATUS`): 1 start, 2 deep, 3 light, 4 wake (ends a session), 5 movement. Each
     * entry's duration is the gap to the next entry; the segment's stage is the *earlier* entry's
     * type, mapped 1/3/5->light, 2->deep. A session runs from a start (or the first entry) to a wake,
     * and is emitted as a per-minute stage array stamped at the session start.
     */
    private fun decodeSleep(p: List<UByte>): List<RingDecodedEvent> {
        if (p.size < 3) return listOf(RingDecodedEvent.CommandAck(LuckRingDataType.SLEEP))
        val pageCount = p[2].toInt()

        val entries = mutableListOf<SleepEntry>()
        var offset = 3
        for (page in 0 until pageCount) {
            if (offset >= p.size) break
            val valid = p[offset].toInt()
            offset += 1
            for (slot in 0 until 15) {
                if (offset + 5 > p.size) break
                if (slot < valid) {
                    entries.add(SleepEntry(p[offset], LuckRingBytes.u32(p, offset + 1)))
                }
                offset += 5
            }
        }

        return sleepSessions(entries)
    }

    private fun sleepSessions(entries: List<SleepEntry>): List<RingDecodedEvent> {
        val sessions = mutableListOf<RingDecodedEvent>()
        var sessionStart: Long? = null
        val stages = mutableListOf<SleepStage>()

        fun flush() {
            val start = sessionStart
            if (start != null && stages.isNotEmpty()) {
                sessions.add(RingDecodedEvent.SleepTimeline(Instant.ofEpochSecond(start), stages.toList()))
            }
            sessionStart = null
            stages.clear()
        }

        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.type == 1u.toUByte()) flush()                       // explicit session start
            if (sessionStart == null) sessionStart = entry.time           // implicit start
            if (entry.type == 4u.toUByte()) { flush(); continue }         // wake ends the session

            if (i + 1 >= entries.size) continue                          // last entry has no duration
            val delta = entries[i + 1].time - entry.time
            val minutes = maxOf(0L, delta / 60).toInt()
            val stage = sleepStage(entry.type)
            repeat(minutes) { stages.add(stage) }
        }
        flush()
        return sessions
    }

    private fun sleepStage(type: UByte): SleepStage = when (type) {
        2u.toUByte() -> SleepStage.DEEP
        4u.toUByte() -> SleepStage.AWAKE
        else -> SleepStage.LIGHT   // 1 start, 3 light, 5 movement all render as light sleep
    }
}
