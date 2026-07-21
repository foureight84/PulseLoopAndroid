package com.pulseloop.ring

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from LuckRingDecoderTests.swift (iOS #90).
 * The decoder's record cutting, per `ProcessDATA_TYPE_*`: battery, firmware, the metric history
 * fan-outs, the sport u24 fields, the paged sleep timeline (sessions -> per-minute stages), the
 * empty-envelope "ended" markers, and the /10 temperature scaling.
 */
class LuckRingDecoderTest {
    private val base = 1_700_000_000L
    private val baseDate: Instant = Instant.ofEpochSecond(base)

    // Envelope: [total u16 LE][items u8] + records.
    private fun envelope(items: Int, records: List<List<UByte>>): List<UByte> =
        LuckRingBytes.le16(items) + listOf(items.toUByte()) + records.flatten()

    private fun frame(dataType: UByte, payload: List<UByte>, cmd: LuckRingCmdType = LuckRingCmdType.SEND): LuckRingFrame =
        LuckRingFrame(cmd, dataType, payload)

    private fun le24(v: Int): List<UByte> = listOf((v and 0xff).toUByte(), ((v shr 8) and 0xff).toUByte(), ((v shr 16) and 0xff).toUByte())

    // MARK: Simple frames

    @Test
    fun `battery`() {
        val events = LuckRingDecoder.decode(frame(3u, listOf<UByte>(85u, 1u)))
        val battery = events.first() as? RingDecodedEvent.Battery ?: return fail("expected battery")
        assertEquals(85, battery.percent)
    }

    @Test
    fun `firmware string joins bytes one to five`() {
        // [items, customer, hardware, code, picture, font] -> "customer.hardware.code.picture.font".
        val events = LuckRingDecoder.decode(frame(2u, listOf<UByte>(6u, 1u, 2u, 3u, 4u, 5u)))
        val status = events.first() as? RingDecodedEvent.Status ?: return fail("expected status/firmware")
        assertEquals("1.2.3.4.5", status.firmware)
    }

    @Test
    fun `device ack decodes to command ack`() {
        val events = LuckRingDecoder.decode(frame(111u, listOf<UByte>(1u), cmd = LuckRingCmdType.ACK))
        val ack = events.first() as? RingDecodedEvent.CommandAck ?: return fail("expected commandAck")
        assertEquals(111u.toUByte(), ack.commandId)
    }

    // MARK: History fan-outs

    @Test
    fun `heart rate history fans out per record`() {
        val payload = envelope(2, listOf(
            LuckRingBytes.le32(base) + listOf<UByte>(72u),
            LuckRingBytes.le32(base + 60) + listOf<UByte>(75u),
        ))
        val events = LuckRingDecoder.decode(frame(8u, payload))
        val readings = events.mapNotNull { it as? RingDecodedEvent.HistoryMeasurement }
            .filter { it.kind_field == MeasurementKind.HEART_RATE }
        assertEquals(listOf(72.0, 75.0), readings.map { it.value })
        assertEquals(baseDate, readings.first()._timestamp)
    }

    @Test
    fun `spo2 history decodes`() {
        val payload = envelope(1, listOf(LuckRingBytes.le32(base) + listOf<UByte>(97u)))
        val events = LuckRingDecoder.decode(frame(40u, payload))
        val m = events.first() as? RingDecodedEvent.HistoryMeasurement ?: return fail("expected spo2 history")
        assertEquals(MeasurementKind.SPO2, m.kind_field)
        assertEquals(97.0, m.value, 0.0)
    }

    @Test
    fun `blood pressure history fans out systolic and diastolic`() {
        val payload = envelope(1, listOf(LuckRingBytes.le32(base) + listOf<UByte>(120u, 80u)))
        val events = LuckRingDecoder.decode(frame(41u, payload))
        val kinds = events.mapNotNull { (it as? RingDecodedEvent.HistoryMeasurement)?.let { m -> m.kind_field to m.value } }
        assertEquals(2, kinds.size)
        assertTrue(kinds.contains(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC to 120.0))
        assertTrue(kinds.contains(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC to 80.0))
    }

    @Test
    fun `hrv history decodes`() {
        val payload = envelope(1, listOf(LuckRingBytes.le32(base) + listOf<UByte>(45u)))
        val events = LuckRingDecoder.decode(frame(42u, payload))
        val m = events.first() as? RingDecodedEvent.HistoryMeasurement ?: return fail("expected hrv history")
        assertEquals(MeasurementKind.HRV, m.kind_field)
        assertEquals(45.0, m.value, 0.0)
    }

    @Test
    fun `temperature scales by ten from the wide record`() {
        // 8-byte record (`parseFloat`): [time u32][value u16 LE]/10 (+2 pad). 365 -> 36.5C.
        val payload = envelope(1, listOf(LuckRingBytes.le32(base) + LuckRingBytes.le16(365) + listOf<UByte>(0u, 0u)))
        val events = LuckRingDecoder.decode(frame(47u, payload))
        val m = events.first() as? RingDecodedEvent.HistoryMeasurement ?: return fail("expected temperature history")
        assertEquals(MeasurementKind.TEMPERATURE, m.kind_field)
        assertEquals(36.5, m.value, 0.001)
    }

    // MARK: Live streams

    @Test
    fun `live heart rate samples and ended marker`() {
        val stream = envelope(1, listOf(LuckRingBytes.le32(base) + listOf<UByte>(66u)))
        val hr = LuckRingDecoder.decode(frame(7u, stream)).first() as? RingDecodedEvent.HeartRateSample
            ?: return fail("expected live HR sample")
        assertEquals(66, hr.bpm)

        // Empty envelope (items 0) = the measurement ended.
        val ended = LuckRingDecoder.decode(frame(7u, envelope(0, emptyList())))
        assertTrue("expected heartRateComplete for an empty envelope, got $ended", ended.first() is RingDecodedEvent.HeartRateComplete)
    }

    @Test
    fun `live blood pressure decodes`() {
        val payload = envelope(1, listOf(LuckRingBytes.le32(base) + listOf<UByte>(118u, 76u)))
        val events = LuckRingDecoder.decode(frame(18u, payload))
        val kinds = events.mapNotNull { (it as? RingDecodedEvent.HistoryMeasurement)?.let { m -> m.kind_field to m.value } }
        assertTrue(kinds.contains(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC to 118.0))
        assertTrue(kinds.contains(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC to 76.0))
    }

    // MARK: Sport

    @Test
    fun `sport record decodes u24 fields`() {
        // [start u32][steps u32][distance u24+pad][calories u24+pad][duration u24+pad].
        val record = LuckRingBytes.le32(base) + LuckRingBytes.le32(1234) +
            le24(5000) + listOf<UByte>(0u) + le24(300) + listOf<UByte>(0u) + le24(600) + listOf<UByte>(0u)
        val events = LuckRingDecoder.decode(frame(5u, envelope(1, listOf(record))))
        val bucket = events.first() as? RingDecodedEvent.ActivityBucket ?: return fail("expected activityBucket, got $events")
        assertEquals(baseDate, bucket._timestamp)
        assertEquals(1234, bucket.steps)
        assertEquals(5000, bucket.distanceMeters)
    }

    // MARK: Sleep

    /** One session: start -> 5 min light, deep -> 10 min deep, wake ends it. The 15-slot page is padded. */
    @Test
    fun `sleep single session expands to per minute stages`() {
        val entries = listOf(
            Pair<UByte, Long>(1u, base),           // session start
            Pair<UByte, Long>(2u, base + 300),      // deep, 5 min after start
            Pair<UByte, Long>(4u, base + 900),      // wake, 10 min after the deep entry
        )
        val events = LuckRingDecoder.decode(frame(6u, sleepPayload(listOf(Pair(3, entries)))))
        val timeline = events.first() as? RingDecodedEvent.SleepTimeline ?: return fail("expected sleepTimeline, got $events")
        assertEquals(baseDate, timeline._timestamp)
        assertEquals(List(5) { SleepStage.LIGHT }, timeline.stages.subList(0, 5))
        assertEquals(15, timeline.stages.size)
        assertEquals(List(10) { SleepStage.DEEP }, timeline.stages.subList(5, 15))
    }

    /** Type 5 (movement) maps to light; two sessions separated by a wake both surface. */
    @Test
    fun `sleep multi session and movement mapping`() {
        val first = listOf(Pair<UByte, Long>(1u, base), Pair<UByte, Long>(5u, base + 120), Pair<UByte, Long>(4u, base + 300))
        val second = listOf(Pair<UByte, Long>(1u, base + 3600), Pair<UByte, Long>(2u, base + 3720), Pair<UByte, Long>(4u, base + 3900))
        val events = LuckRingDecoder.decode(frame(6u, sleepPayload(listOf(Pair(3, first), Pair(3, second)))))
        val sessions = events.mapNotNull { it as? RingDecodedEvent.SleepTimeline }
        assertEquals(2, sessions.size)
        assertTrue("start + movement both render as light", sessions[0].stages.all { it == SleepStage.LIGHT })
        assertEquals(Instant.ofEpochSecond(base + 3600), sessions[1]._timestamp)
    }

    // Build a sleep payload: [total u16][pageCount], then each page = [validCount] + 15 x [type, time u32].
    private fun sleepPayload(pages: List<Pair<Int, List<Pair<UByte, Long>>>>): List<UByte> {
        val out = mutableListOf<UByte>()
        out.addAll(LuckRingBytes.le16(0))
        out.add(pages.size.toUByte())
        for ((valid, entries) in pages) {
            out.add(valid.toUByte())
            for (slot in 0 until 15) {
                if (slot < entries.size) {
                    out.add(entries[slot].first)
                    out.addAll(LuckRingBytes.le32(entries[slot].second))
                } else {
                    out.addAll(listOf<UByte>(0u, 0u, 0u, 0u, 0u))
                }
            }
        }
        return out
    }
}
