package com.pulseloop.ring

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Ring-reported all-day HR config from a `0x16` auto-HR pref read reply. */
data class ColmiAutoHRReadout(val enabled: Boolean, val intervalMinutes: Int)

/**
 * Ported from [ColmiDecoder] in ColmiDecoder.swift.
 * Decodes Colmi frames into shared [RingDecodedEvent]s. Two channels:
 * - Normal (V1): realtime HR, manual HR, battery, notifications, paged history
 * - Big-data (V2): reassembled 0xbc frames for sleep/SpO2/temperature
 *
 * The decoder is stateless; paging/day bookkeeping lives in [ColmiSyncEngine].
 */
object ColmiDecoder {

    /** Decode a normal-channel (V1) frame. */
    fun decodeNormal(data: ByteArray, now: Instant = Instant.now()): List<RingDecodedEvent> {
        val packet = ColmiPacket.validating(data) ?: return listOf(
            RingDecodedEvent.Unknown(commandId = if (data.isNotEmpty()) data[0].toUByte() else 0u, raw = data)
        )
        val v = packet.bytes.map { it.toUByte() }

        return when (v[0]) {
            ColmiCommandID.BATTERY -> listOf(RingDecodedEvent.Battery(percent = v[1].toInt()))
            ColmiCommandID.MANUAL_HEART_RATE -> {
                // Real-time stream: [0x69, reading_type, error, value, …].
                // reading_type selects the metric (HR=1, SpO2=3); error!=0 ends the run.
                val readingType = v[1]
                val errorCode = v[2].toInt()
                val value = v[3].toInt()
                if (readingType == ColmiCommandID.RT_SPO2) {
                    // error!=0 ends the run — surface it so a spot measurement fails fast
                    // instead of idling out its full window (mirrors the HR path below).
                    if (errorCode != 0) return listOf(RingDecodedEvent.Spo2Complete(_timestamp = now))
                    if (value !in 70..100) return emptyList()  // warm-up / noise
                    return listOf(RingDecodedEvent.Spo2Result(value = value, _timestamp = now))
                }
                // Default: heart rate (reading_type == RT_HEART_RATE, or legacy 2-byte request).
                if (errorCode != 0) return listOf(RingDecodedEvent.HeartRateComplete(_timestamp = now))
                if (value !in 30..220) return emptyList()  // warm-up (bpm 0) or noise
                listOf(RingDecodedEvent.HeartRateSample(bpm = value, _timestamp = now))
            }
            ColmiCommandID.REALTIME_HEART_RATE -> {
                val bpm = v[1].toInt()
                if (bpm !in 30..220) return emptyList()
                listOf(RingDecodedEvent.HeartRateSample(bpm = bpm, _timestamp = now))
            }
            ColmiCommandID.REALTIME_HEART_RATE_ERROR ->
                listOf(RingDecodedEvent.HeartRateComplete(_timestamp = now))
            ColmiCommandID.NOTIFICATION -> decodeNotification(v, now)
            ColmiCommandID.BP_READ -> decodeBpResponse(v, now)
            else -> listOf(RingDecodedEvent.CommandAck(commandId = v[0]))
        }
    }

    private fun decodeBpResponse(v: List<UByte>, now: Instant): List<RingDecodedEvent> {
        // Sentinel: ffffffff means no more data
        if (v.size >= 5 && v[1] == 0xFFu.toUByte() && v[2] == 0xFFu.toUByte() &&
            v[3] == 0xFFu.toUByte() && v[4] == 0xFFu.toUByte()) {
            return emptyList()
        }
        // Each frame: [cmd=0x14, ts(4 bytes, LE), dia(1), sys(1)]
        if (v.size < 7) return emptyList()
        val ts = (v[1].toLong() or (v[2].toLong() shl 8) or (v[3].toLong() shl 16) or (v[4].toLong() shl 24)) * 1000L
        val dia = v[5].toInt()
        val sys = v[6].toInt()
        if (sys == 0 && dia == 0) return emptyList()
        val instant = Instant.ofEpochMilli(ts)
        return listOf(
            RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_PRESSURE_SYSTOLIC,
                value = sys.toDouble(), _timestamp = instant
            ),
            RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_PRESSURE_DIASTOLIC,
                value = dia.toDouble(), _timestamp = instant
            ),
        )
    }

    private fun decodeNotification(v: List<UByte>, now: Instant): List<RingDecodedEvent> = when (v[1]) {
        ColmiCommandID.NOTIF_BATTERY -> listOf(RingDecodedEvent.Battery(percent = v[2].toInt()))
        ColmiCommandID.NOTIF_LIVE_ACTIVITY -> {
            // Live activity (0x73 0x12) packs steps / calories / distance as BIG-endian u24
            // (verified against on-ring frames). Reading them little-endian inflated steps to
            // millions and, via the daily max-merge, locked in a garbage Today total.
            val steps = ColmiBytes.u24be(v[2], v[3], v[4])
            val calories = ColmiBytes.u24be(v[5], v[6], v[7]) / 1000   // field is in calories → kcal
            val distance = ColmiBytes.u24be(v[8], v[9], v[10])          // meters
            // Plausibility guard: drop any frame with an impossible step count so a single
            // bad packet can never poison the day's total again.
            if (steps !in 0..200_000) listOf(RingDecodedEvent.CommandAck(commandId = v[0]))
            else listOf(RingDecodedEvent.ActivityUpdate(
                _timestamp = now, steps = steps, distanceMeters = distance, calories = calories
            ))
        }
        else -> listOf(RingDecodedEvent.CommandAck(commandId = v[0]))
    }

    // MARK: Paged history (normal channel)

    fun historyPacketNumber(data: ByteArray): Int? {
        if (data.size < 2) return null
        return data[1].toInt() and 0xFF
    }

    fun decodeHistory(
        data: ByteArray, day: LocalDate, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()
    ): List<RingDecodedEvent> {
        val packet = ColmiPacket.validating(data) ?: return emptyList()
        val v = packet.bytes.map { it.toUByte() }
        return when (v[0]) {
            ColmiCommandID.SYNC_HEART_RATE -> decodeHRHistory(v, day, zone)
            ColmiCommandID.SYNC_STRESS -> decodeStressHistory(v, day, zone)
            ColmiCommandID.SYNC_HRV -> decodeHRVHistory(v, day, zone)
            ColmiCommandID.SYNC_ACTIVITY -> decodeActivityHistory(v, zone, now)
            else -> emptyList()
        }
    }

    private fun decodeHRHistory(v: List<UByte>, day: LocalDate, zone: ZoneId): List<RingDecodedEvent> {
        val packetNr = v[1].toInt()
        if (packetNr == 0xFF || packetNr == 0) return emptyList()
        val startIndex = if (packetNr == 1) 6 else 2
        val base = day.atStartOfDay(zone)
        val minutesInPrevious = if (packetNr > 1) 9 * 5 + (packetNr - 2) * 13 * 5 else 0
        return (startIndex until v.size - 1).mapNotNull { i ->
            val bpm = v[i].toInt()
            if (bpm == 0) return@mapNotNull null
            val ts = base.plusMinutes((minutesInPrevious + (i - startIndex) * 5).toLong()).toInstant()
            RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.HEART_RATE, value = bpm.toDouble(), _timestamp = ts
            )
        }
    }

    private fun decodeStressHistory(v: List<UByte>, day: LocalDate, zone: ZoneId): List<RingDecodedEvent> {
        val packetNr = v[1].toInt()
        if (packetNr == 0xFF || packetNr == 0) return emptyList()
        val startIndex = if (packetNr == 1) 3 else 2
        val base = day.atStartOfDay(zone)
        val minutesInPrevious = if (packetNr > 1) 12 * 30 + (packetNr - 2) * 13 * 30 else 0
        return (startIndex until v.size - 1).mapNotNull { i ->
            val stress = v[i].toInt()
            if (stress == 0) return@mapNotNull null
            val ts = base.plusMinutes((minutesInPrevious + (i - startIndex) * 30).toLong()).toInstant()
            RingDecodedEvent.StressSample(value = stress, _timestamp = ts)
        }
    }

    private fun decodeHRVHistory(v: List<UByte>, day: LocalDate, zone: ZoneId): List<RingDecodedEvent> {
        val packetNr = v[1].toInt()
        if (packetNr == 0xFF || packetNr == 0) return emptyList()
        val startIndex = if (packetNr == 1) 3 else 2
        val base = day.atStartOfDay(zone)
        val minutesInPrevious = if (packetNr > 1) 12 * 30 + (packetNr - 2) * 13 * 30 else 0
        return (startIndex until v.size - 1).mapNotNull { i ->
            val hrv = v[i].toInt()
            if (hrv == 0) return@mapNotNull null
            val ts = base.plusMinutes((minutesInPrevious + (i - startIndex) * 30).toLong()).toInstant()
            RingDecodedEvent.HrvSample(value = hrv, _timestamp = ts)
        }
    }

    private fun decodeActivityHistory(
        v: List<UByte>, zone: ZoneId, now: Instant
    ): List<RingDecodedEvent> {
        val marker = v[1].toInt()
        if (marker == 0xFF || marker == 0xF0 || v.size < 13) return emptyList()

        fun hexLit(b: UByte): Int = String.format("%02x", b.toInt()).toInt()
        val year = 2000 + hexLit(v[1])
        val month = hexLit(v[2])
        val day = hexLit(v[3])
        // v[4] is a quarter-of-day slot index (0..95) — each 0x43 sample covers one
        // 15-minute slice. Keep the full slice start: activity_buckets upserts by
        // startEpoch, so collapsing to the hour would make same-hour slices overwrite
        // each other and undercount the day's sum-of-buckets total.
        val slot = v[4].toInt().coerceIn(0, 95)
        val ts = try {
            LocalDate.of(year, month, day).atTime(slot / 4, (slot % 4) * 15).atZone(zone).toInstant()
        } catch (_: Exception) {
            return emptyList()
        }
        val lower = now.minus(8, ChronoUnit.DAYS)
        val upper = now.plus(1, ChronoUnit.HOURS)
        if (ts < lower || ts > upper) return emptyList()

        val steps = ColmiBytes.u16(v[9], v[10])
        val distance = ColmiBytes.u16(v[11], v[12]).toInt()
        return listOf(RingDecodedEvent.ActivityBucket(
            _timestamp = ts, steps = steps, distanceMeters = distance
        ))
    }

    // MARK: Pref read replies (connect handshake)

    /**
     * Decode a `0x16` auto-HR pref **read** reply: `[0x16, action=READ(0x01), flag, interval]`.
     * Mirrors the write shape (see [ColmiEncoder.autoHeartRate], per GadgetBridge): flag
     * `0x01`=on / `0x02`=off, then the all-day sampling interval in minutes. Returns null for
     * anything that isn't a read reply — write acks echo action `0x02`.
     */
    fun decodeAutoHRPrefRead(data: ByteArray): ColmiAutoHRReadout? {
        val packet = ColmiPacket.validating(data) ?: return null
        val v = packet.bytes.map { it.toUByte() }
        if (v[0] != ColmiCommandID.AUTO_HR_PREF || v[1] != ColmiCommandID.PREF_READ) return null
        return ColmiAutoHRReadout(enabled = v[2].toInt() == 0x01, intervalMinutes = v[3].toInt())
    }

    /**
     * Decode a temperature-pref **read** reply: `[0x3A, 0x03, action=READ(0x01), enabled]`.
     * Mirrors [ColmiEncoder.writeTempPref]'s extra `0x03` framing byte; enabled is
     * `0x01`/`0x00`. Returns null for write acks (action `0x02`) and other 0x3A frames.
     */
    fun decodeTempPrefRead(data: ByteArray): Boolean? {
        val packet = ColmiPacket.validating(data) ?: return null
        val v = packet.bytes.map { it.toUByte() }
        if (v[0] != ColmiCommandID.AUTO_TEMP_PREF || v[1].toInt() != 0x03 ||
            v[2] != ColmiCommandID.PREF_READ) return null
        return v[3].toInt() == 0x01
    }

    /**
     * Decode a `0x3C` device-support reply and return whether the ring wants an OS-level bond.
     * Frame layout mirrors QRing's `DeviceSupportFunctionRsp.acceptData`: opcode at `[0]`, the
     * first feature byte at `[1]`, where bit 3 (`0x08`) is `supportBlePair`. Returns null for any
     * frame that isn't a device-support reply — callers treat null as "not a bond signal", so a
     * wrong guess degrades to today's no-bond behaviour rather than misbehaving.
     */
    fun decodeDeviceSupport(data: ByteArray): Boolean? {
        val packet = ColmiPacket.validating(data) ?: return null
        val v = packet.bytes.map { it.toUByte() }
        if (v[0] != ColmiCommandID.DEVICE_SUPPORT) return null
        return (v[1].toInt() and 0x08) != 0
    }

    // MARK: Big-data (V2)

    fun decodeBigData(
        data: ByteArray, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()
    ): List<RingDecodedEvent> {
        val v = data.map { it.toUByte() }
        if (v.size < 6 || v[0] != ColmiCommandID.BIG_DATA_V2) {
            return listOf(RingDecodedEvent.Unknown(commandId = v.firstOrNull() ?: 0u, raw = data))
        }
        return when (v[1]) {
            ColmiCommandID.BIG_DATA_SPO2 -> decodeSpo2(data, zone)
            ColmiCommandID.BIG_DATA_SLEEP -> decodeSleep(data, zone)
            ColmiCommandID.BIG_DATA_TEMPERATURE -> decodeTemperature(data, zone)
            ColmiCommandID.BIG_DATA_BLOOD_SUGAR -> decodeBloodSugar(data, zone)
            else -> listOf(RingDecodedEvent.Unknown(commandId = v[1], raw = data))
        }
    }

    private fun decodeSpo2(data: ByteArray, zone: ZoneId): List<RingDecodedEvent> {
        val v = data.map { it.toUByte() }
        val length = ColmiBytes.u16(v[2], v[3])
        var index = 6
        val events = mutableListOf<RingDecodedEvent>()
        var daysAgo = -1
        val today = LocalDate.now(zone)
        while (daysAgo != 0 && index - 6 < length && index < v.size) {
            daysAgo = v[index].toInt(); index++
            val dayStart = today.minusDays(daysAgo.toLong())
            for (hour in 0..23) {
                if (index + 1 >= v.size) break
                val lo = v[index].toInt(); index++
                val hi = v[index].toInt(); index++
                if (lo > 0 && hi > 0) {
                    val value = ((lo + hi).toDouble() / 2.0)
                    val ts = dayStart.atTime(hour, 0).atZone(zone).toInstant()
                    events.add(RingDecodedEvent.HistoryMeasurement(
                        kind_field = MeasurementKind.SPO2, value = value, _timestamp = ts
                    ))
                }
                if (index - 6 >= length) break
            }
        }
        return events
    }

    private fun decodeTemperature(data: ByteArray, zone: ZoneId): List<RingDecodedEvent> {
        val v = data.map { it.toUByte() }
        val length = ColmiBytes.u16(v[2], v[3])
        if (length < 50) return emptyList()
        var index = 6
        val events = mutableListOf<RingDecodedEvent>()
        var daysAgo = -1
        val today = LocalDate.now(zone)
        while (daysAgo != 0 && index - 6 < length && index < v.size) {
            daysAgo = v[index].toInt(); index++
            index++ // skip one unknown byte
            val dayStart = today.minusDays(daysAgo.toLong())
            for (hour in 0..23) {
                if (index + 1 >= v.size) break
                val t00 = v[index].toInt(); index++
                val t30 = v[index].toInt(); index++
                if (t00 > 0) {
                    events.add(RingDecodedEvent.TemperatureSample(
                        celsius = t00.toDouble() / 10.0 + 20.0,
                        _timestamp = dayStart.atTime(hour, 0).atZone(zone).toInstant()
                    ))
                }
                if (t30 > 0) {
                    events.add(RingDecodedEvent.TemperatureSample(
                        celsius = t30.toDouble() / 10.0 + 20.0,
                        _timestamp = dayStart.atTime(hour, 30).atZone(zone).toInstant()
                    ))
                }
                if (index - 6 >= length) break
            }
        }
        return events
    }

    private fun decodeSleep(data: ByteArray, zone: ZoneId): List<RingDecodedEvent> {
        val v = data.map { it.toUByte() }
        val packetLength = ColmiBytes.u16(v[2], v[3])
        if (packetLength < 2 || v.size <= 7) return emptyList()
        val daysInPacket = v[6].toInt()
        var index = 7
        val events = mutableListOf<RingDecodedEvent>()
        val today = LocalDate.now(zone)
        for (_d in 0 until daysInPacket) {
            if (index + 5 >= v.size) break
            val daysAgo = v[index].toInt(); index++
            val dayBytes = v[index].toInt(); index++
            val sleepStart = ColmiBytes.u16(v[index], v[index + 1]); index += 2
            val sleepEnd = ColmiBytes.u16(v[index], v[index + 1]); index += 2
            val dayStart = today.minusDays(daysAgo.toLong())
            val startOffset = if (sleepStart > sleepEnd) sleepStart - 1440 else sleepStart
            val sessionStart = dayStart.atStartOfDay(zone).plusMinutes(startOffset.toLong())

            val stages = mutableListOf<SleepStage>()
            var j = 4
            while (j < dayBytes && index + 1 < v.size) {
                val stageType = v[index]
                val minutes = v[index + 1].toInt()
                index += 2; j += 2
                if (minutes <= 0) continue
                val stage = sleepStage(stageType)
                repeat(minutes) { stages.add(stage) }
            }
            if (stages.isNotEmpty()) {
                events.add(RingDecodedEvent.SleepTimeline(
                    _timestamp = sessionStart.toInstant(), stages = stages
                ))
            }
        }
        return events
    }

    fun sleepStage(type: UByte): SleepStage = when (type) {
        ColmiCommandID.SLEEP_LIGHT -> SleepStage.LIGHT
        ColmiCommandID.SLEEP_DEEP -> SleepStage.DEEP
        ColmiCommandID.SLEEP_REM -> SleepStage.REM
        ColmiCommandID.SLEEP_AWAKE -> SleepStage.AWAKE
        else -> SleepStage.UNKNOWN
    }

    /**
     * Decode blood sugar big-data response.
     * Format (best-guess, validate with real ring): same structure as temperature —
     * per-day entries with day index + hourly readings. Each reading is mg/dL × 10.
     */
    private fun decodeBloodSugar(data: ByteArray, zone: ZoneId): List<RingDecodedEvent> {
        val v = data.map { it.toUByte() }
        val length = ColmiBytes.u16(v[2], v[3])
        if (length < 2) return emptyList()
        var index = 6
        val events = mutableListOf<RingDecodedEvent>()
        var daysAgo = -1
        val today = LocalDate.now(zone)
        while (daysAgo != 0 && index - 6 < length && index < v.size) {
            daysAgo = v[index].toInt(); index++
            index++ // skip unknown byte
            val dayStart = today.minusDays(daysAgo.toLong())
            for (hour in 0..23) {
                if (index + 1 >= v.size) break
                val hi = v[index].toInt(); index++
                val lo = v[index].toInt(); index++
                val raw = ((hi shl 8) or lo)
                if (raw > 0) {
                    events.add(RingDecodedEvent.HistoryMeasurement(
                        kind_field = MeasurementKind.BLOOD_SUGAR,
                        value = raw.toDouble() / 10.0,
                        _timestamp = dayStart.atTime(hour, 0).atZone(zone).toInstant()
                    ))
                }
                if (index - 6 >= length) break
            }
        }
        return events
    }
}
