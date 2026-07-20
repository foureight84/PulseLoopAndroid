package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/** Tests for [YCBTHealthRecords]' pure buffer->events decoders (iOS #82). */
class YCBTHealthRecordsTest {

    private fun u32le(value: Int): List<UByte> = listOf(
        (value and 0xff).toUByte(), ((value shr 8) and 0xff).toUByte(),
        ((value shr 16) and 0xff).toUByte(), ((value shr 24) and 0xff).toUByte(),
    )

    // MARK: - composite / score

    @Test
    fun `composite string-concatenates integer and fraction`() {
        assertEquals(45.6, YCBTHealthRecords.composite(45u, 6u), 0.0001)
        assertEquals(36.5, YCBTHealthRecords.composite(36u, 5u), 0.0001)
        assertEquals(36.25, YCBTHealthRecords.composite(36u, 25u), 0.0001)
    }

    @Test
    fun `score digit-concatenates onto the app's 1 to 100 scale`() {
        // (5, 3) is the 53 the app shows, not 5.3.
        assertEquals(53.0, YCBTHealthRecords.score(5u, 3u), 0.0001)
    }

    @Test
    fun `blood sugar converts tenths of mmol to mg per dL`() {
        // 55 tenths = 5.5 mmol/L ~= 99 mg/dL.
        val mgdl = YCBTHealthRecords.bloodSugarMgdl(55)
        assertEquals(99.088, mgdl, 0.01)
    }

    // MARK: - Heart rate (6-byte records)

    @Test
    fun `heart rate drops zero readings`() {
        val buffer = u32le(1000) + listOf<UByte>(0u, 0u) +   // hr=0, unworn
            u32le(2000) + listOf<UByte>(0u, 70u)             // hr=70
        val events = YCBTHealthRecords.heartRate(buffer)
        assertEquals(1, events.size)
        val e = events[0] as RingDecodedEvent.HistoryMeasurement
        assertEquals(MeasurementKind.HEART_RATE, e.kind_field)
        assertEquals(70.0, e.value, 0.0)
    }

    // MARK: - Temperature filler

    @Test
    fun `temperature filler int0 frac15 is dropped`() {
        val buffer = u32le(1000) + listOf<UByte>(0u, 0u, 15u)
        assertTrue(YCBTHealthRecords.temperature(buffer).isEmpty())
    }

    @Test
    fun `temperature filler with stale nonzero integer is still dropped`() {
        // int=36, frac=15 is the same "never measured" marker, not a real 36.15C.
        val buffer = u32le(1000) + listOf<UByte>(0u, 36u, 15u)
        assertTrue(YCBTHealthRecords.temperature(buffer).isEmpty())
    }

    @Test
    fun `real temperature reading decodes as the composite value`() {
        val buffer = u32le(1000) + listOf<UByte>(0u, 36u, 5u)
        val events = YCBTHealthRecords.temperature(buffer)
        assertEquals(1, events.size)
        assertEquals(36.5, (events[0] as RingDecodedEvent.HistoryMeasurement).value, 0.0001)
    }

    // MARK: - Sleep (variable-length sessions)

    private fun sleepSegment(tag: Int, startSeconds: Int, durationSeconds: Int): List<UByte> =
        listOf(tag.toUByte()) + u32le(startSeconds) +
            listOf(
                (durationSeconds and 0xff).toUByte(),
                ((durationSeconds shr 8) and 0xff).toUByte(),
                ((durationSeconds shr 16) and 0xff).toUByte(),
            )

    private fun sleepSession(startSeconds: Int, segments: List<List<UByte>>): List<UByte> {
        val segmentBytes = segments.flatten()
        val recordLen = 20 + segmentBytes.size
        val header = listOf<UByte>(0u, 0u) + listOf((recordLen and 0xff).toUByte(), ((recordLen shr 8) and 0xff).toUByte()) +
            u32le(startSeconds) + u32le(0) + List(8) { 0u.toUByte() }   // start, end, 8 bytes of counts/totals
        return header + segmentBytes
    }

    @Test
    fun `sleep decodes stages by tag and repeats minutes for the segment duration`() {
        val session = sleepSession(1000, listOf(sleepSegment(tag = 1, startSeconds = 1000, durationSeconds = 120)))
        val events = YCBTHealthRecords.sleep(session)
        assertEquals(1, events.size)
        val timeline = events[0] as RingDecodedEvent.SleepTimeline
        assertEquals(2, timeline.stages.size)   // 120s -> 2 minutes
        assertTrue(timeline.stages.all { it == SleepStage.DEEP })
    }

    @Test
    fun `sleep classifies every documented stage tag`() {
        val segments = listOf(
            sleepSegment(1, 1000, 60), sleepSegment(2, 1060, 60),
            sleepSegment(3, 1120, 60), sleepSegment(4, 1180, 60),
        )
        val session = sleepSession(1000, segments)
        val timeline = YCBTHealthRecords.sleep(session)[0] as RingDecodedEvent.SleepTimeline
        assertEquals(listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE), timeline.stages)
    }

    @Test
    fun `unknown tag is skipped, not terminal`() {
        // A stray unrecognized tag (0) between two real segments must not truncate the session.
        val segments = listOf(
            sleepSegment(1, 1000, 60),
            sleepSegment(0, 1060, 60),   // unrecognized — high nibble only, low nibble 0
            sleepSegment(2, 1120, 60),
        )
        val session = sleepSession(1000, segments)
        val timeline = YCBTHealthRecords.sleep(session)[0] as RingDecodedEvent.SleepTimeline
        assertEquals(listOf(SleepStage.DEEP, SleepStage.LIGHT), timeline.stages)
    }

    @Test
    fun `duplicate segment start times within a session are deduplicated`() {
        val segments = listOf(
            sleepSegment(1, 1000, 60),
            sleepSegment(1, 1000, 60),   // firmware repeat — same start time
            sleepSegment(2, 1060, 60),
        )
        val session = sleepSession(1000, segments)
        val timeline = YCBTHealthRecords.sleep(session)[0] as RingDecodedEvent.SleepTimeline
        // Only one DEEP minute counted, not two.
        assertEquals(listOf(SleepStage.DEEP, SleepStage.LIGHT), timeline.stages)
    }

    @Test
    fun `multiple back-to-back sessions each decode independently`() {
        val session1 = sleepSession(1000, listOf(sleepSegment(1, 1000, 60)))
        val session2 = sleepSession(5000, listOf(sleepSegment(2, 5000, 60)))
        val events = YCBTHealthRecords.sleep(session1 + session2)
        assertEquals(2, events.size)
    }

    // MARK: - Blood pressure (8-byte records)

    @Test
    fun `blood pressure zero bytes produce no events`() {
        val buffer = u32le(1000) + listOf<UByte>(0u, 0u, 0u, 0u)
        assertTrue(YCBTHealthRecords.bloodPressure(buffer).isEmpty())
    }

    @Test
    fun `blood pressure emits systolic diastolic and incidental heart rate`() {
        val buffer = u32le(1000) + listOf<UByte>(0u, 120u, 80u, 65u)
        val events = YCBTHealthRecords.bloodPressure(buffer)
        assertEquals(3, events.size)
        val kinds = events.map { (it as RingDecodedEvent.HistoryMeasurement).kind_field }
        assertTrue(kinds.containsAll(listOf(
            MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, MeasurementKind.HEART_RATE,
        )))
    }

    // MARK: - Body data (28-byte records)

    @Test
    fun `body data reads stress as score and hrv as composite from the same shape`() {
        val buffer = u32le(1000) +
            listOf<UByte>(0u, 0u) +      // loadIdx (unused)
            listOf<UByte>(45u, 6u) +     // hrv -> composite 45.6
            listOf<UByte>(5u, 3u) +      // stress (pressure) -> score 53
            listOf<UByte>(2u, 0u) +      // fatigue (body) -> score 20
            listOf<UByte>(0u, 0u) +      // sympathetic (unused)
            List(2) { 0u.toUByte() } +   // sdnn u16
            listOf<UByte>(48u) +         // vo2max @16
            List(11) { 0u.toUByte() }    // pnn50, rmssd, lf, hf, lfHf padding to 28 bytes
        assertEquals(28, buffer.size)
        val events = YCBTHealthRecords.bodyData(buffer)
        val byKind = events.associateBy { (it as RingDecodedEvent.HistoryMeasurement).kind_field }
        assertEquals(45.6, (byKind[MeasurementKind.HRV] as RingDecodedEvent.HistoryMeasurement).value, 0.0001)
        assertEquals(53.0, (byKind[MeasurementKind.STRESS] as RingDecodedEvent.HistoryMeasurement).value, 0.0001)
        assertEquals(20.0, (byKind[MeasurementKind.FATIGUE] as RingDecodedEvent.HistoryMeasurement).value, 0.0001)
        assertEquals(48.0, (byKind[MeasurementKind.VO2MAX] as RingDecodedEvent.HistoryMeasurement).value, 0.0001)
    }

    // MARK: - decode() dispatch

    @Test
    fun `decode dispatches by history type`() {
        val buffer = u32le(1000) + listOf<UByte>(0u, 70u)
        assertEquals(YCBTHealthRecords.heartRate(buffer), YCBTHealthRecords.decode(buffer, YCBTHistoryType.HEART))
    }
}
