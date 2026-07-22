package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from YCBTHealthRecords.swift.
 * Pure buffer→events decoders for the YCBT health-history record types.
 */

object YCBTHealthRecords {
    private const val TEMPERATURE_FILLER: Int = 15
    private const val MAX_SLEEP_SESSION_MINUTES = 24 * 60

    fun decode(buffer: ByteArray, type: YCBTHistoryType): List<RingDecodedEvent> {
        return when (type) {
            YCBTHistoryType.SPORT -> sport(buffer)
            YCBTHistoryType.SLEEP -> sleep(buffer)
            YCBTHistoryType.HEART -> heartRate(buffer)
            YCBTHistoryType.BLOOD -> bloodPressure(buffer)
            YCBTHistoryType.ALL -> combinedVitals(buffer)
            YCBTHistoryType.SPO2 -> spo2(buffer)
            YCBTHistoryType.TEMPERATURE -> temperature(buffer)
            YCBTHistoryType.COMPREHENSIVE -> comprehensive(buffer)
            YCBTHistoryType.BODY_DATA -> bodyData(buffer)
            else -> emptyList()
        }
    }

    // MARK: Sport (query 0x02, 14-byte records)

    fun sport(buffer: ByteArray): List<RingDecodedEvent> {
        return records(buffer, 14).mapNotNull { r ->
            val steps = YCBTBytes.u16(r, 8)
            val distance = YCBTBytes.u16(r, 10)
            if (steps <= 0 && distance <= 0) return@mapNotNull null
            RingDecodedEvent.ActivityBucket(
                _timestamp = YCBTBytes.date(YCBTBytes.u32(r, 0)),
                steps = steps,
                distanceMeters = distance,
            )
        }
    }

    // MARK: Heart rate (query 0x06, 6-byte records)

    fun heartRate(buffer: ByteArray): List<RingDecodedEvent> {
        return records(buffer, 6).mapNotNull { r ->
            val hr = r[5].toInt() and 0xFF
            if (hr == 0) return@mapNotNull null
            RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.HEART_RATE,
                value = hr.toDouble(),
                _timestamp = YCBTBytes.date(YCBTBytes.u32(r, 0)),
            )
        }
    }

    // MARK: Blood pressure (query 0x08, 8-byte records)

    fun bloodPressure(buffer: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (r in records(buffer, 8)) {
            val ts = YCBTBytes.date(YCBTBytes.u32(r, 0))
            events.addAll(bloodPressureEvents(systolic = r[5].toInt() and 0xFF, diastolic = r[6].toInt() and 0xFF, timestamp = ts))
            if (r[7].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(
                    kind_field = MeasurementKind.HEART_RATE,
                    value = (r[7].toInt() and 0xFF).toDouble(),
                    _timestamp = ts,
                ))
            }
        }
        return events
    }

    // MARK: Combined vitals (query 0x09, 20-byte records)

    fun combinedVitals(buffer: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (r in records(buffer, 20)) {
            val ts = YCBTBytes.date(YCBTBytes.u32(r, 0))
            // 0x09 is vitals history, not the activity source of truth. Its adjacent step field
            // can lag/reset differently and arrives late in the refresh pipeline; routing it as
            // a live cumulative ActivityUpdate made today's steps jump to stale values. Activity
            // comes from 0x02 sport buckets plus 0x06/00 live status instead.
            events.addAll(bloodPressureEvents(systolic = r[7].toInt() and 0xFF, diastolic = r[8].toInt() and 0xFF, timestamp = ts))
            if (r[9].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.SPO2, value = (r[9].toInt() and 0xFF).toDouble(), _timestamp = ts))
            }
            if (r[10].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.RESPIRATORY_RATE, value = (r[10].toInt() and 0xFF).toDouble(), _timestamp = ts))
            }
            if (r[11].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.HRV, value = (r[11].toInt() and 0xFF).toDouble(), _timestamp = ts))
            }
            events.addAll(temperatureEvents(integer = r[13].toInt() and 0xFF, fraction = r[14].toInt() and 0xFF, timestamp = ts))
            if (r[17].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(
                    kind_field = MeasurementKind.BLOOD_SUGAR,
                    value = bloodSugarMgdl(r[17].toInt() and 0xFF),
                    _timestamp = ts,
                ))
            }
        }
        return events
    }

    // MARK: SpO₂ (query 0x1A, 6-byte records)

    fun spo2(buffer: ByteArray): List<RingDecodedEvent> {
        return records(buffer, 6).mapNotNull { r ->
            if (r[5].toInt() and 0xFF == 0) return@mapNotNull null
            RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.SPO2,
                value = (r[5].toInt() and 0xFF).toDouble(),
                _timestamp = YCBTBytes.date(YCBTBytes.u32(r, 0)),
            )
        }
    }

    // MARK: Temperature (query 0x1E, 7-byte records)

    fun temperature(buffer: ByteArray): List<RingDecodedEvent> {
        return records(buffer, 7).flatMap { r ->
            temperatureEvents(integer = r[5].toInt() and 0xFF, fraction = r[6].toInt() and 0xFF, timestamp = YCBTBytes.date(YCBTBytes.u32(r, 0)))
        }
    }

    // MARK: Comprehensive (query 0x2F, 44-byte records)

    fun comprehensive(buffer: ByteArray): List<RingDecodedEvent> {
        return records(buffer, 44).mapNotNull { r ->
            val tenths = (r[5].toInt() and 0xFF) * 10 + (r[6].toInt() and 0xFF)
            if (tenths <= 0) return@mapNotNull null
            RingDecodedEvent.HistoryMeasurement(
                kind_field = MeasurementKind.BLOOD_SUGAR,
                value = bloodSugarMgdl(tenths),
                _timestamp = YCBTBytes.date(YCBTBytes.u32(r, 0)),
            )
        }
    }

    // MARK: Body data (query 0x33, 28-byte records)

    fun bodyData(buffer: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (r in records(buffer, 28)) {
            val ts = YCBTBytes.date(YCBTBytes.u32(r, 0))
            if (r[6].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.HRV, value = composite(r[6].toInt() and 0xFF, r[7].toInt() and 0xFF), _timestamp = ts))
            }
            if (r[8].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.STRESS, value = score(r[8].toInt() and 0xFF, r[9].toInt() and 0xFF), _timestamp = ts))
            }
            if (r[10].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.FATIGUE, value = score(r[10].toInt() and 0xFF, r[11].toInt() and 0xFF), _timestamp = ts))
            }
            if (r.size > 16 && r[16].toInt() and 0xFF > 0) {
                events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.VO2MAX, value = (r[16].toInt() and 0xFF).toDouble(), _timestamp = ts))
            }
        }
        return events
    }

    // MARK: Sleep (variable-length sessions)

    fun sleep(buffer: ByteArray): List<RingDecodedEvent> {
        val headerLength = 20
        val segmentLength = 8
        val events = mutableListOf<RingDecodedEvent>()
        var cursor = 0
        while (cursor + headerLength <= buffer.size) {
            val recordLength = YCBTBytes.u16(buffer, cursor + 2)
            val segmentsStart = cursor + headerLength
            val declared = maxOf(0, recordLength - headerLength) / segmentLength
            val available = (buffer.size - segmentsStart) / segmentLength
            val segmentCount = minOf(declared, available)

            val stages = mutableListOf<SleepStage>()
            var sessionStart: Instant? = null
            val seenStarts = mutableSetOf<Int>()
            for (index in 0 until segmentCount) {
                val offset = segmentsStart + index * segmentLength
                val stage = sleepStage(buffer[offset].toInt() and 0xFF) ?: continue
                val segmentStart = YCBTBytes.u32(buffer, offset + 1)
                if (!seenStarts.add(segmentStart)) continue
                val segmentSeconds = YCBTBytes.u24(buffer, offset + 5)
                if (sessionStart == null) sessionStart = YCBTBytes.date(segmentStart)
                val remaining = MAX_SLEEP_SESSION_MINUTES - stages.size
                if (remaining <= 0) break
                val minutes = kotlin.math.round(segmentSeconds / 60.0).toInt().coerceIn(1, remaining)
                repeat(minutes) { stages.add(stage) }
            }
            if (sessionStart != null && stages.isNotEmpty()) {
                events.add(
                    RingDecodedEvent.SleepTimeline(
                        _timestamp = sessionStart,
                        stages = stages,
                        completeSession = true,
                    )
                )
            }
            cursor = segmentsStart + segmentCount * segmentLength
        }
        return events
    }

    private fun sleepStage(tag: Int): SleepStage? {
        return when (tag and 0x0f) {
            1 -> SleepStage.DEEP
            2 -> SleepStage.LIGHT
            3 -> SleepStage.REM
            4 -> SleepStage.AWAKE
            5 -> SleepStage.UNKNOWN
            else -> null
        }
    }

    // MARK: Shared field decoding

    private fun bloodPressureEvents(systolic: Int, diastolic: Int, timestamp: Instant): List<RingDecodedEvent> {
        if (systolic <= 0 || diastolic <= 0) return emptyList()
        return listOf(
            RingDecodedEvent.BloodPressureSample(
                systolic = systolic,
                diastolic = diastolic,
                _timestamp = timestamp,
                isHistory = true,
            ),
        )
    }

    private fun temperatureEvents(integer: Int, fraction: Int, timestamp: Instant): List<RingDecodedEvent> {
        if (integer <= 0 || fraction == TEMPERATURE_FILLER) return emptyList()
        return listOf(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.TEMPERATURE, value = composite(integer, fraction), _timestamp = timestamp))
    }

    /** String-concatenated composite: integer and fraction digits concatenated with a decimal point. */
    fun composite(integer: Int, fraction: Int): Double {
        return "$integer.$fraction".toDoubleOrNull() ?: integer.toDouble()
    }

    /** UNVERIFIED: digit-concatenated score inferred for stress/fatigue on a 1…100 scale. */
    fun score(integer: Int, fraction: Int): Double {
        return "$integer$fraction".toDoubleOrNull() ?: integer.toDouble()
    }

    // UNVERIFIED: hardware payloads look like tenths of mmol/L; no vendor ground truth yet.
    const val MGDL_PER_MMOL = 18.016

    fun bloodSugarMgdl(tenthsOfMmol: Int): Double {
        return tenthsOfMmol / 10.0 * MGDL_PER_MMOL
    }

    // MARK: Helpers

    private fun records(buffer: ByteArray, size: Int): List<ByteArray> {
        if (size <= 0) return emptyList()
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + size <= buffer.size) {
            out.add(buffer.copyOfRange(i, i + size))
            i += size
        }
        return out
    }
}
