package com.pulseloop.ring

import com.pulseloop.util.TimeUtil
import java.time.Instant
import java.time.Duration
import java.time.ZoneId

/**
 * Ported from YCBTHealthRecords.swift.
 * Pure buffer→events decoders for the YCBT health-history record types.
 */

object YCBTHealthRecords {
    private const val TEMPERATURE_FILLER: Int = 15
    private const val MAX_SLEEP_SESSION_MINUTES = 24 * 60
    // Pixel 7 + R10M FCF4 emitted one proven night as three complete records 1h52m and 42m apart.
    private val MAX_OVERNIGHT_FRAGMENT_GAP = Duration.ofHours(3)
    private val MAX_STITCHED_SLEEP_SPAN = Duration.ofHours(16)

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
            val remaining = buffer.size - cursor
            val segmentsStart = cursor + headerLength
            val declared = maxOf(0, recordLength - headerLength) / segmentLength
            val available = (buffer.size - segmentsStart) / segmentLength
            val segmentCount = minOf(declared, available)

            val headerStart = YCBTBytes.date(YCBTBytes.u32(buffer, cursor + 4))
            val headerEnd = YCBTBytes.date(YCBTBytes.u32(buffer, cursor + 8))
            val headerDuration = Duration.between(headerStart, headerEnd)
            val validHeader = !headerDuration.isNegative && !headerDuration.isZero &&
                headerDuration <= Duration.ofMinutes(MAX_SLEEP_SESSION_MINUTES.toLong())
            val validDeclaredWidth = recordLength >= headerLength &&
                recordLength <= remaining &&
                (recordLength - headerLength) % segmentLength == 0
            val rawSegments = mutableListOf<SleepStageSegment>()
            val seenStarts = mutableSetOf<Int>()
            var allDeclaredSegmentsValid = true
            var nextRecordBoundary: Int? = null
            for (index in 0 until segmentCount) {
                val offset = segmentsStart + index * segmentLength
                if (buffer[offset] == 0xaf.toByte() && buffer[offset + 1] == 0xfa.toByte()) {
                    nextRecordBoundary = offset
                    allDeclaredSegmentsValid = false
                    break
                }
                val stage = sleepStage(buffer[offset].toInt() and 0xFF)
                val segmentStartRaw = YCBTBytes.u32(buffer, offset + 1)
                val segmentSeconds = YCBTBytes.u24(buffer, offset + 5)
                val segmentStart = YCBTBytes.date(segmentStartRaw)
                val segmentEnd = segmentStart.plusSeconds(segmentSeconds.toLong())
                val validFields = stage != null && segmentSeconds > 0
                val uniqueStart = seenStarts.add(segmentStartRaw)
                val intersectsHeader = validHeader && segmentStart < headerEnd && segmentEnd > headerStart
                if (!validFields || !intersectsHeader) {
                    allDeclaredSegmentsValid = false
                }
                if (validFields && uniqueStart && (!validHeader || intersectsHeader)) {
                    rawSegments += SleepStageSegment(stage ?: continue, segmentStart, segmentEnd)
                }
            }

            if (segmentCount < declared) allDeclaredSegmentsValid = false
            val structurallyComplete = validDeclaredWidth && allDeclaredSegmentsValid &&
                nextRecordBoundary == null
            val normalized = if (validHeader && structurallyComplete) {
                when {
                    declared == 0 -> listOf(SleepStageSegment(SleepStage.UNKNOWN, headerStart, headerEnd))
                    rawSegments.isEmpty() -> emptyList()
                    else -> normalizeSleepSegments(headerStart, headerEnd, rawSegments)
                }
            } else {
                fallbackSleepSegments(rawSegments)
            }
            if (normalized.isNotEmpty()) {
                val useHeaderBounds = validHeader && structurallyComplete
                val sessionStart = if (useHeaderBounds) headerStart else normalized.first().start
                val sessionEnd = if (useHeaderBounds) headerEnd else normalized.last().end
                events.add(
                    RingDecodedEvent.SleepTimeline(
                        sessionStart = sessionStart,
                        sessionEnd = sessionEnd,
                        segments = normalized,
                        completeSession = useHeaderBounds,
                    )
                )
            }
            cursor = when {
                nextRecordBoundary != null -> nextRecordBoundary
                recordLength in headerLength..remaining -> cursor + recordLength
                recordLength > remaining -> maxOf(cursor + 1, segmentsStart + segmentCount * segmentLength)
                else -> cursor + 1
            }
        }
        return stitchCompleteOvernightFragments(events)
    }

    private fun stitchCompleteOvernightFragments(
        events: List<RingDecodedEvent>,
    ): List<RingDecodedEvent> {
        val timelines = events.filterIsInstance<RingDecodedEvent.SleepTimeline>()
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<RingDecodedEvent.SleepTimeline>> { it.value.sessionStart }
                    .thenBy { it.value.sessionEnd }
                    .thenBy { it.index },
            )
            .map { it.value }
        if (timelines.size < 2) return timelines

        val out = mutableListOf<RingDecodedEvent.SleepTimeline>()
        var cluster = timelines.first()
        for (next in timelines.drop(1)) {
            if (canStitch(cluster, next)) {
                cluster = RingDecodedEvent.SleepTimeline(
                    sessionStart = cluster.sessionStart,
                    sessionEnd = next.sessionEnd,
                    segments = normalizeSleepSegments(
                        cluster.sessionStart,
                        next.sessionEnd,
                        cluster.segments + next.segments,
                    ),
                    completeSession = true,
                )
            } else {
                out += cluster
                cluster = next
            }
        }
        out += cluster
        return out
    }

    private fun canStitch(
        current: RingDecodedEvent.SleepTimeline,
        next: RingDecodedEvent.SleepTimeline,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!current.completeSession || !next.completeSession) return false
        if (!isOvernightStart(current.sessionStart, zone) || !isOvernightStart(next.sessionStart, zone)) return false
        if (TimeUtil.wakingDayLocal(current.sessionStart.toEpochMilli(), zone) !=
            TimeUtil.wakingDayLocal(next.sessionStart.toEpochMilli(), zone)) return false
        if (next.sessionStart < current.sessionEnd) return false
        if (Duration.between(current.sessionEnd, next.sessionStart) > MAX_OVERNIGHT_FRAGMENT_GAP) return false
        return Duration.between(current.sessionStart, next.sessionEnd) <= MAX_STITCHED_SLEEP_SPAN
    }

    private fun isOvernightStart(start: Instant, zone: ZoneId): Boolean {
        val hour = start.atZone(zone).hour
        return hour >= TimeUtil.SLEEP_EVENING_BOUNDARY_HOUR || hour < 12
    }

    private fun normalizeSleepSegments(
        sessionStart: Instant,
        sessionEnd: Instant,
        raw: List<SleepStageSegment>,
    ): List<SleepStageSegment> {
        if (raw.isEmpty()) return emptyList()
        val clipped = raw.mapNotNull { segment ->
            val start = maxOf(segment.start, sessionStart)
            val end = minOf(segment.end, sessionEnd)
            if (end <= start) null else segment.copy(start = start, end = end)
        }.sortedWith(compareBy<SleepStageSegment> { it.start }.thenBy { it.end })
        if (clipped.isEmpty()) return emptyList()

        val out = mutableListOf<SleepStageSegment>()
        var cursor = sessionStart
        for (segment in clipped) {
            val start = maxOf(segment.start, cursor)
            if (start >= segment.end) continue
            if (start > cursor) appendSleepSegment(out, SleepStageSegment(SleepStage.UNKNOWN, cursor, start))
            appendSleepSegment(out, segment.copy(start = start))
            cursor = segment.end
        }
        if (cursor < sessionEnd) {
            appendSleepSegment(out, SleepStageSegment(SleepStage.UNKNOWN, cursor, sessionEnd))
        }
        return out
    }

    private fun fallbackSleepSegments(raw: List<SleepStageSegment>): List<SleepStageSegment> {
        val first = raw.firstOrNull() ?: return emptyList()
        val limit = first.start.plusSeconds(MAX_SLEEP_SESSION_MINUTES * 60L)
        val out = mutableListOf<SleepStageSegment>()
        var cursor = first.start
        for (segment in raw) {
            if (cursor >= limit) break
            val seconds = Duration.between(segment.start, segment.end).seconds
            if (seconds <= 0) continue
            val end = minOf(cursor.plusSeconds(seconds), limit)
            appendSleepSegment(out, SleepStageSegment(segment.stage, cursor, end))
            cursor = end
        }
        return out
    }

    private fun appendSleepSegment(out: MutableList<SleepStageSegment>, segment: SleepStageSegment) {
        val previous = out.lastOrNull()
        if (previous != null && previous.stage == segment.stage && previous.end == segment.start) {
            out[out.lastIndex] = previous.copy(end = segment.end)
        } else {
            out += segment
        }
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
