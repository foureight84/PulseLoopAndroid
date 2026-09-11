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

    /** One `af fa` record's stage segment, kept with its own start (issue #63). */
    private data class SleepSegment(val stage: SleepStage, val startSeconds: Int, val seconds: Int)

    fun sleep(buffer: ByteArray): List<RingDecodedEvent> {
        val headerLength = 20
        val segmentLength = 8
        val events = mutableListOf<RingDecodedEvent>()
        var cursor = 0
        while (cursor + headerLength <= buffer.size) {
            // Every session opens with `af fa` (DataUnpack case 4 reads and discards both bytes).
            // A record whose declared length disagrees with its real one would otherwise leave
            // the cursor mid-record and silently mis-parse every later session of the night
            // (issue #63 is exactly a multi-session night), so resynchronise on the magic.
            if (!isSleepSessionHeader(buffer, cursor)) {
                val next = nextSleepSessionHeader(buffer, cursor + 1) ?: break
                cursor = next
                continue
            }
            val recordLength = YCBTBytes.u16(buffer, cursor + 2)
            // The vendor reads the session's own bounds out of the header (DataUnpack's
            // `startTime` at +4, `endTime` at +8) rather than inferring them from the segments.
            val headerStart = YCBTBytes.u32(buffer, cursor + 4)
            val headerEnd = YCBTBytes.u32(buffer, cursor + 8)
            val segmentsStart = cursor + headerLength
            val declared = maxOf(0, recordLength - headerLength) / segmentLength
            val available = (buffer.size - segmentsStart) / segmentLength
            val segmentCount = minOf(declared, available)

            val segments = mutableListOf<SleepSegment>()
            val seenStarts = mutableSetOf<Int>()
            for (index in 0 until segmentCount) {
                val offset = segmentsStart + index * segmentLength
                val stage = sleepStage(buffer[offset].toInt() and 0xFF) ?: continue
                val segmentStart = YCBTBytes.u32(buffer, offset + 1)
                val segmentSeconds = YCBTBytes.u24(buffer, offset + 5)
                // A zero-length segment claims no minute of the timeline, so it must not take the
                // start time's place in the de-duplication and shadow a real segment sharing it.
                // The ring emits both: in the night dump on issue #63, all four duplicate starts
                // across thirteen records are a zero-length LIGHT ahead of a real 76–105 s
                // segment, and dropping the real one leaves those minutes unclaimed — which
                // `placeStages` then reads as wake. The vendor drops them too
                // (`DataUnpack` case 4 keeps the first `sleepStartTime` it sees) and gets away
                // with it because its headline comes from the header's own totals rather than
                // from the segment array; ours is counted off the timeline, so it cannot.
                if (segmentSeconds <= 0) continue
                // The vendor de-duplicates on the segment's start time (`sleepStartTime`).
                if (!seenStarts.add(segmentStart)) continue
                segments.add(SleepSegment(stage, segmentStart, segmentSeconds))
            }
            val stages = placeStages(segments, headerStart, headerEnd)
            if (segments.isNotEmpty() && stages.isNotEmpty()) {
                val start = if (usableHeaderBounds(headerStart, headerEnd)) headerStart
                            else segments.first().startSeconds
                events.add(
                    RingDecodedEvent.SleepTimeline(
                        _timestamp = YCBTBytes.date(start),
                        stages = stages,
                        completeSession = true,
                    )
                )
            }
            cursor = segmentsStart + segmentCount * segmentLength
        }
        return events
    }

    private fun usableHeaderBounds(startSeconds: Int, endSeconds: Int): Boolean =
        startSeconds > 0 && endSeconds > startSeconds &&
            (endSeconds - startSeconds) / 60 <= MAX_SLEEP_SESSION_MINUTES

    /**
     * A record's minute-by-minute stage timeline (issue #63).
     *
     * The stored run has to end where the ring says the session ended, because
     * `completeSessionSurvivors` grows its retirement run across blocks that abut end-to-start.
     * Concatenating `round(seconds / 60)` per segment from the first segment's start does not:
     * segments carry a one-second gap between each pair and each rounds independently, so a
     * night's derived end drifts from its real one — 470 minutes against a declared 474 on the
     * captured night in `YCBTHealthRecordsTest`. A single minute of drift in the other direction
     * is enough to make one record of a split night abut the next, and the whole of the earlier
     * session is then retired in favour of the later one: 5 h 22 of a two-record night vanished
     * that way.
     *
     * So each segment is placed at its own `sleepStartTime` for its own `sleepLen`, and the run
     * spans exactly the header's `startTime`..`endTime`. The one-second gaps round away against
     * the minute grid; a gap the ring really left reads as wake, which is what it is.
     *
     * A record with unusable header bounds (a synthetic or truncated one) keeps the old
     * concatenation, since there is nothing better to place against.
     */
    private fun placeStages(
        segments: List<SleepSegment>,
        headerStart: Int,
        headerEnd: Int,
    ): List<SleepStage> {
        if (segments.isEmpty()) return emptyList()
        if (!usableHeaderBounds(headerStart, headerEnd)) {
            val stages = mutableListOf<SleepStage>()
            for (segment in segments) {
                val remaining = MAX_SLEEP_SESSION_MINUTES - stages.size
                if (remaining <= 0) break
                val minutes = kotlin.math.round(segment.seconds / 60.0).toInt().coerceIn(1, remaining)
                repeat(minutes) { stages.add(segment.stage) }
            }
            return stages
        }
        val total = kotlin.math.round((headerEnd - headerStart) / 60.0).toInt()
            .coerceIn(1, MAX_SLEEP_SESSION_MINUTES)
        // AWAKE is the honest filler: the ring reports wake as its own segment type (0xf4), so a
        // minute no segment claims is one the ring did not call sleep.
        val timeline = MutableList(total) { SleepStage.AWAKE }
        for (segment in segments) {
            val from = kotlin.math.round((segment.startSeconds - headerStart) / 60.0).toInt()
            val until = kotlin.math.round(
                (segment.startSeconds.toLong() + segment.seconds - headerStart) / 60.0
            ).toInt()
            for (minute in maxOf(0, from) until minOf(total, until)) timeline[minute] = segment.stage
        }
        return timeline
    }

    private fun isSleepSessionHeader(buffer: ByteArray, at: Int): Boolean =
        at + 1 < buffer.size && buffer[at] == 0xAF.toByte() && buffer[at + 1] == 0xFA.toByte()

    private fun nextSleepSessionHeader(buffer: ByteArray, from: Int): Int? {
        var i = from
        while (i + 1 < buffer.size) {
            if (isSleepSessionHeader(buffer, i)) return i
            i++
        }
        return null
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
