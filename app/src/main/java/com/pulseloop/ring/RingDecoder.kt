package com.pulseloop.ring

import java.time.Instant

/**
 * Decodes 20-byte raw BLE notification data into typed [RingDecodedEvent]s.
 *
 * All byte offsets and decoding logic match the official app's SXR KeepFit SDK
 * as documented in Gadgetbridge's [KeepFitDeviceSupport.java].
 * Multi-byte integers are little-endian.
 */
object RingDecoder {

    fun decode(data: ByteArray): List<RingDecodedEvent> {
        val packet = RingPacket.fromData(data).getOrElse {
            return listOf(RingDecodedEvent.Unknown(
                commandId = if (data.isNotEmpty()) data[0].toUByte() else 0u,
                raw = data
            ))
        }

        val bytes = packet.raw
        val now = Instant.now()

        return when (packet.commandId.toInt()) {
            0x01 -> listOf(decodeTimeSync(bytes))
            0x02 -> listOf(RingDecodedEvent.CommandAck(commandId = packet.commandId))
            0x03 -> listOf(decodeActivity(bytes))
            0x0B -> listOf(decodeBattery(bytes))
            0x0C -> listOf(decodeStatus(bytes))
            0x10 -> decodeActivityHistory(bytes)  // 15× 1-min buckets
            0x11 -> listOf(decodeSleepTimeline(bytes))
            0x14 -> listOf(decodeLiveHeartRate(bytes, now))
            0x16 -> decodeHeartRateHistory(bytes)  // multi-packet protocol
            0x24 -> decodeCombinedSensor(bytes, now)  // 5 metrics in one packet
            0x27 -> listOf(RingDecodedEvent.HeartRateComplete(_timestamp = now))
            0x28 -> listOf(RingDecodedEvent.Spo2Complete(_timestamp = now))
            0x3F -> listOf(decodeSpo2Result(bytes, now))
            0x4B -> listOf(decodeBindNotify(bytes))  // ring-side bind/unbind handshake
            0xF6 -> listOf(decodeFirmwareVersion(bytes))
            else -> listOf(RingDecodedEvent.Unknown(commandId = packet.commandId, raw = bytes))
        }
    }

    // ── 0x03: Current Activity ────────────────────────────────────────────

    private fun decodeActivity(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 17) return unknown(0x03, bytes)
        return RingDecodedEvent.ActivityUpdate(
            _timestamp = Instant.ofEpochSecond(u32le(bytes, 1).toLong()),
            steps = u32le(bytes, 5).toInt(),
            distanceMeters = u32le(bytes, 9).toInt(),
            calories = u32le(bytes, 13).toInt(),
        )
    }

    // ── 0x0B: Battery ─────────────────────────────────────────────────────

    private fun decodeBattery(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 3) return unknown(0x0B, bytes)
        return RingDecodedEvent.Battery(
            percent = bytes[1].toInt() and 0xFF,
            charging = (bytes[2].toInt() and 0xFF) == 1,
        )
    }

    // ── 0x0C: Device Info + Firmware ──────────────────────────────────────

    private fun decodeStatus(bytes: ByteArray): RingDecodedEvent {
        val address = if (bytes.size >= 9) {
            bytes.slice(3..8).joinToString(":") { String.format("%02X", it) }
        } else null
        // Full firmware string, exactly as the official app builds it in onGetDeviceInfo:
        //   CID(bytes[9-10]) + DID(bytes[11-12]) + "V" + version(bytes[1-2], LE u16)
        // For 0c 8a 00 .. 3a 00 2a 00 → "003A" + "002A" + "V" + 138 = "003A002AV138".
        // The version is bytes[1-2], NOT the 0xF6 packet (which carries a different value).
        val fw = if (bytes.size >= 13) {
            val version = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            val cid = ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
            val did = ((bytes[12].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
            String.format("%04X%04XV%d", cid, did, version)
        } else null
        return RingDecodedEvent.Status(address = address, firmware = fw)
    }

    // ── 0x10: Activity History (15× 1-min buckets per packet) ─────────────

    /**
     * Each 0x10 packet carries 15 consecutive 1-minute step samples.
     * The ring sends multiple packets covering the requested day range.
     * End condition: timestamp hits 23:45 local time.
     */
    private fun decodeActivityHistory(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x10, bytes))
        val baseTimestamp = Instant.ofEpochSecond(u32le(bytes, 1).toLong())
        return (5..19).map { i ->
            RingDecodedEvent.ActivityBucket(
                _timestamp = baseTimestamp.plusSeconds((i - 5) * 60L),
                steps = bytes[i].toInt() and 0xFF,
                distanceMeters = 0,  // 0x10 doesn't carry distance
            )
        }
    }

    // ── 0x11: Sleep Timeline ──────────────────────────────────────────────

    private fun decodeSleepTimeline(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 20) return unknown(0x11, bytes)
        return RingDecodedEvent.SleepTimeline(
            _timestamp = Instant.ofEpochSecond(u32le(bytes, 1).toLong()),
            stages = bytes.slice(5..19).map { SleepStage.fromByte(it.toUByte()) }
        )
    }

    // ── 0x14: Live Heart Rate ─────────────────────────────────────────────

    /**
     * Live HR streaming notification (~1/sec during active measurement).
     * timestamp==0 means measurement error — discard the reading.
     */
    private fun decodeLiveHeartRate(bytes: ByteArray, fallbackNow: Instant): RingDecodedEvent {
        if (bytes.size < 7) return unknown(0x14, bytes)
        val tsRaw = u32le(bytes, 1).toLong()
        // timestamp == 0 is a measurement error from the ring
        if (tsRaw == 0L) return RingDecodedEvent.HeartRateSample(
            bpm = 0, _timestamp = fallbackNow, isError = true
        )
        return RingDecodedEvent.HeartRateSample(
            bpm = bytes[5].toInt() and 0xFF,
            _timestamp = Instant.ofEpochSecond(tsRaw),
            sleepStatus = bytes[6].toInt() and 0xFF,
            isError = false,
        )
    }

    // ── 0x16: Heart Rate History (multi-packet protocol) ──────────────────

    /**
     * 0x16 multi-packet protocol:
     * - byte[1] = 0xF0: header → total packet count at bytes[6-7]
     * - byte[1] = 0xAA: index block → current index + last block count
     * - byte[1] = 0xA0: data block → 2 averaged HR values (6 samples each)
     * - byte[1] = 0xFF: sync finished
     */
    private fun decodeHeartRateHistory(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 2) return listOf(unknown(0x16, bytes))
        val subType = bytes[1].toInt() and 0xFF
        return when (subType) {
            0xF0 -> listOf(RingDecodedEvent.HistorySyncProgress(stage = "hr_header"))
            0xAA -> listOf(RingDecodedEvent.HistorySyncProgress(stage = "hr_index"))
            0xFF -> listOf(RingDecodedEvent.HistorySyncFinished)
            0xA0 -> decodeHrDataBlock(bytes)
            else -> {
                // Legacy fallback: try to extract a single HR value
                if (bytes.size >= 9) {
                    val ts = Instant.ofEpochSecond(u32le(bytes, 2).toLong())
                    val v = bytes.drop(8).firstOrNull { it != 0.toByte() }
                    if (v != null) listOf(RingDecodedEvent.HistoryMeasurement(
                        kind_field = MeasurementKind.HEART_RATE,
                        value = (v.toInt() and 0xFF).toDouble(),
                        _timestamp = ts,
                    )) else listOf(RingDecodedEvent.CommandAck(commandId = 0x16u))
                } else listOf(unknown(0x16, bytes))
            }
        }
    }

    /**
     * 0xA0 data block: 12 HR samples (bytes[8-19]) → 2 averaged readings.
     * Each reading = average of 6 consecutive 1-min samples, rounded.
     */
    private fun decodeHrDataBlock(bytes: ByteArray): List<RingDecodedEvent> {
        if (bytes.size < 20) return listOf(unknown(0x16, bytes))
        val baseTimestamp = Instant.ofEpochSecond(u32le(bytes, 2).toLong())

        return buildList {
            // First 6 samples → 1 averaged HR at baseTimestamp
            val avg1 = (8..13).map { bytes[it].toInt() and 0xFF }.average().let {
                Math.round(it).toInt()
            }
            if (avg1 > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.HEART_RATE,
                value = avg1.toDouble(),
                _timestamp = baseTimestamp,
            ))

            // Next 6 samples → 1 averaged HR at baseTimestamp + 1 min
            val avg2 = (14..19).map { bytes[it].toInt() and 0xFF }.average().let {
                Math.round(it).toInt()
            }
            if (avg2 > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.HEART_RATE,
                value = avg2.toDouble(),
                _timestamp = baseTimestamp.plusSeconds(60),
            ))
        }
    }

    // ── 0x24: Combined Sensor Data ────────────────────────────────────────

    /**
     * Response to CMD_TOGGLE_BLOOD_PRESSURE (0x23) — the combined spot measurement.
     * Matches the official app's onReceiveSensorData(i, i2, i3, i4, i5, i6, i7, i8):
     *
     * Offset:  0    1    2         3          4       5        6       7            8
     * Value:  0x24 HR   systolic  diastolic  SpO2%   fatigue  stress  bloodSugar   HRV
     *
     * Blood sugar arrives as mmol/L × 10 (byte[7]); the official UI shows mg/dL via
     * `mmol × 18.016`. e.g. 51 → 5.1 mmol/L → 91.88 mg/dL. Only emit values > 0.
     */
    private fun decodeCombinedSensor(bytes: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (bytes.size < 6) return listOf(unknown(0x24, bytes))

        return buildList {
            val hr = bytes[1].toInt() and 0xFF
            val systolic = bytes[2].toInt() and 0xFF
            val diastolic = bytes[3].toInt() and 0xFF
            val spo2 = bytes[4].toInt() and 0xFF
            val fatigue = bytes[5].toInt() and 0xFF
            val stress = if (bytes.size > 6) bytes[6].toInt() and 0xFF else 0
            val bloodSugarRaw = if (bytes.size > 7) bytes[7].toInt() and 0xFF else 0

            if (hr > 0) add(RingDecodedEvent.HeartRateSample(bpm = hr, _timestamp = now))
            if (systolic > 0 || diastolic > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_PRESSURE_SYSTOLIC,
                value = systolic.toDouble(),
                _timestamp = now,
            ))
            if (diastolic > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_PRESSURE_DIASTOLIC,
                value = diastolic.toDouble(),
                _timestamp = now,
            ))
            if (spo2 in 80..100) add(RingDecodedEvent.Spo2Result(value = spo2, _timestamp = now))
            // Fatigue (byte[5], i5) and stress (byte[6], i6) are distinct metrics in the
            // official app (TYPE_FATIGUE=13, TYPE_STRESS=17), both 0–100.
            if (fatigue > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.FATIGUE,
                value = fatigue.toDouble(),
                _timestamp = now,
            ))
            if (stress > 0) add(RingDecodedEvent.StressSample(value = stress, _timestamp = now))
            // Blood sugar: byte[7] = mmol/L × 10 → store mg/dL = (raw / 10) × 18.016
            if (bloodSugarRaw > 0) add(RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_SUGAR,
                value = (bloodSugarRaw / 10.0) * 18.016,
                _timestamp = now,
            ))
        }
    }

    // ── 0x3F: SpO₂ Result ─────────────────────────────────────────────────

    private fun decodeSpo2Result(bytes: ByteArray, now: Instant): RingDecodedEvent {
        if (bytes.size < 2) return unknown(0x3F, bytes)
        val spo2 = bytes[1].toInt() and 0xFF
        return if (spo2 in 80..100) {
            RingDecodedEvent.Spo2Result(value = spo2, _timestamp = now)
        } else {
            RingDecodedEvent.Spo2Progress(percent = null, _timestamp = now)
        }
    }

    // ── 0x4B: Bind / Unbind handshake ─────────────────────────────────────

    /**
     * Ring-side bind notification. Mirrors the official SDK's onNotifyBindedInfo:
     * byte[1] = action, byte[2] = state. The ring sends this on connect (to drive
     * binding) and to acknowledge an app-initiated unbind on forget.
     */
    private fun decodeBindNotify(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size < 2) return unknown(0x4B, bytes)
        val action = bytes[1].toInt() and 0xFF
        val state = if (bytes.size > 2) bytes[2].toInt() and 0xFF else 0
        return RingDecodedEvent.BindNotify(action = action, state = state)
    }

    // ── 0xF6: Firmware Version Number ─────────────────────────────────────

    private fun decodeFirmwareVersion(bytes: ByteArray): RingDecodedEvent {
        val version = if (bytes.size >= 6) {
            ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        } else null
        return RingDecodedEvent.FirmwareVersion(version = version)
    }

    // ── 0x01: Time Sync Ack ───────────────────────────────────────────────

    private fun decodeTimeSync(bytes: ByteArray): RingDecodedEvent {
        if (bytes.size >= 6) {
            return RingDecodedEvent.TimeSyncAck(
                _timestamp = Instant.ofEpochSecond(u32le(bytes, 1).toLong())
            )
        }
        return unknown(0x01, bytes)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun u32le(bytes: ByteArray, offset: Int): UInt {
        if (bytes.size < offset + 4) return 0u
        return (bytes[offset].toUByte().toUInt() or
                (bytes[offset + 1].toUByte().toUInt() shl 8) or
                (bytes[offset + 2].toUByte().toUInt() shl 16) or
                (bytes[offset + 3].toUByte().toUInt() shl 24))
    }

    private fun unknown(cmd: Int, bytes: ByteArray) =
        RingDecodedEvent.Unknown(commandId = cmd.toUByte(), raw = bytes)
}
