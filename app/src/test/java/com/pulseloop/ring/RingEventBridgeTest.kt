package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Tests for RingEventBridge range gating and event mapping.
 * Pure logic — no Room/hardware dependency.
 */
class RingEventBridgeTest {

    private val now = Instant.now()

    // ── Activity Bucket Gating ──────────────────────────────────────────

    @Test
    fun `activity bucket with valid steps passes`() {
        val bucket = RingDecodedEvent.ActivityBucket(
            _timestamp = now,
            steps = 4500,
            distanceMeters = 3000,
        )
        val events = RingEventBridge.eventsFor(bucket, now)
        assertEquals(1, events.size)
        assertTrue(events[0] is PulseEvent.ActivityBucket)
    }

    @Test
    fun `activity bucket exceeding max steps is dropped`() {
        val bucket = RingDecodedEvent.ActivityBucket(
            _timestamp = now,
            steps = 6000, // > 5000
            distanceMeters = 3000,
        )
        val events = RingEventBridge.eventsFor(bucket, now)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `activity bucket exceeding max distance is dropped`() {
        val bucket = RingDecodedEvent.ActivityBucket(
            _timestamp = now,
            steps = 1000,
            distanceMeters = 7000, // > 6000
        )
        val events = RingEventBridge.eventsFor(bucket, now)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `activity bucket with exactly at max passes`() {
        val bucket = RingDecodedEvent.ActivityBucket(
            _timestamp = now,
            steps = 5000,
            distanceMeters = 6000,
        )
        val events = RingEventBridge.eventsFor(bucket, now)
        assertEquals(1, events.size)
    }

    // ── Heart Rate Gating ───────────────────────────────────────────────

    @Test
    fun `valid heart rate passes`() {
        val hr = RingDecodedEvent.HeartRateSample(bpm = 72, _timestamp = now)
        val events = RingEventBridge.eventsFor(hr, now)
        assertEquals(1, events.size)
        val event = events[0] as PulseEvent.HeartRateSample
        assertEquals(72, event.bpm)
    }

    @Test
    fun `heart rate at lower bound passes`() {
        val hr = RingDecodedEvent.HeartRateSample(bpm = 30, _timestamp = now)
        assertEquals(1, RingEventBridge.eventsFor(hr, now).size)
    }

    @Test
    fun `heart rate at upper bound passes`() {
        val hr = RingDecodedEvent.HeartRateSample(bpm = 220, _timestamp = now)
        assertEquals(1, RingEventBridge.eventsFor(hr, now).size)
    }

    @Test
    fun `heart rate below range is dropped`() {
        val hr = RingDecodedEvent.HeartRateSample(bpm = 29, _timestamp = now)
        assertTrue(RingEventBridge.eventsFor(hr, now).isEmpty())
    }

    @Test
    fun `heart rate above range is dropped`() {
        val hr = RingDecodedEvent.HeartRateSample(bpm = 221, _timestamp = now)
        assertTrue(RingEventBridge.eventsFor(hr, now).isEmpty())
    }

    // ── History Measurement Gating ──────────────────────────────────────

    @Test
    fun `valid history HR passes`() {
        val hm = RingDecodedEvent.HistoryMeasurement(
            kind_field = MeasurementKind.HEART_RATE,
            value = 72.0,
            _timestamp = now,
        )
        val events = RingEventBridge.eventsFor(hm, now)
        assertEquals(1, events.size)
        val event = events[0] as PulseEvent.HistoryMeasurement
        assertEquals(MeasurementKind.HEART_RATE, event.kind)
        assertEquals(72.0, event.value, 0.01)
    }

    @Test
    fun `history HR out of range is dropped`() {
        val hm = RingDecodedEvent.HistoryMeasurement(
            kind_field = MeasurementKind.HEART_RATE,
            value = 250.0,
            _timestamp = now,
        )
        assertTrue(RingEventBridge.eventsFor(hm, now).isEmpty())
    }

    @Test
    fun `history SpO2 passes without HR range check`() {
        val hm = RingDecodedEvent.HistoryMeasurement(
            kind_field = MeasurementKind.SPO2,
            value = 97.0,
            _timestamp = now,
        )
        assertEquals(1, RingEventBridge.eventsFor(hm, now).size)
    }

    @Test
    fun `history measurement older than 8 days is dropped`() {
        // A ring that logged under a stale clock (e.g. a pre-JringClock UTC RTC) can decode
        // history hours or days into the future or past a plausible window.
        val hm = RingDecodedEvent.HistoryMeasurement(
            kind_field = MeasurementKind.SPO2,
            value = 97.0,
            _timestamp = now.minus(10, ChronoUnit.DAYS),
        )
        assertTrue(RingEventBridge.eventsFor(hm, now).isEmpty())
    }

    @Test
    fun `history measurement in the future is dropped`() {
        val hm = RingDecodedEvent.HistoryMeasurement(
            kind_field = MeasurementKind.FATIGUE,
            value = 40.0,
            _timestamp = now.plus(2, ChronoUnit.HOURS),
        )
        assertTrue(RingEventBridge.eventsFor(hm, now).isEmpty())
    }

    // ── Stress Gating ───────────────────────────────────────────────────

    @Test
    fun `valid stress passes`() {
        val s = RingDecodedEvent.StressSample(value = 50, _timestamp = now)
        assertEquals(1, RingEventBridge.eventsFor(s, now).size)
    }

    @Test
    fun `stress out of range is dropped`() {
        val s = RingDecodedEvent.StressSample(value = 150, _timestamp = now)
        assertTrue(RingEventBridge.eventsFor(s, now).isEmpty())
    }

    // ── HRV Gating ──────────────────────────────────────────────────────

    @Test
    fun `valid HRV passes`() {
        val h = RingDecodedEvent.HrvSample(value = 42, _timestamp = now)
        assertEquals(1, RingEventBridge.eventsFor(h, now).size)
    }

    @Test
    fun `HRV out of range is dropped`() {
        val h = RingDecodedEvent.HrvSample(value = 999, _timestamp = now)
        assertTrue(RingEventBridge.eventsFor(h, now).isEmpty())
    }

    // ── Temperature Gating ──────────────────────────────────────────────

    @Test
    fun `valid temperature passes`() {
        val t = RingDecodedEvent.TemperatureSample(celsius = 36.5, _timestamp = now)
        assertEquals(1, RingEventBridge.eventsFor(t, now).size)
    }

    @Test
    fun `extreme temperature is dropped`() {
        val t = RingDecodedEvent.TemperatureSample(celsius = 50.0, _timestamp = now)
        assertTrue(RingEventBridge.eventsFor(t, now).isEmpty())
    }

    // ── Sleep Timeline Gating ───────────────────────────────────────────

    @Test
    fun `valid sleep timeline passes`() {
        val sleep = RingDecodedEvent.SleepTimeline(
            _timestamp = now.minus(4, ChronoUnit.HOURS),
            stages = listOf(SleepStage.LIGHT, SleepStage.DEEP),
        )
        assertEquals(1, RingEventBridge.eventsFor(sleep, now).size)
    }

    @Test
    fun `sleep timeline with empty stages is dropped`() {
        val sleep = RingDecodedEvent.SleepTimeline(
            _timestamp = now.minus(4, ChronoUnit.HOURS),
            stages = emptyList(),
        )
        assertTrue(RingEventBridge.eventsFor(sleep, now).isEmpty())
    }

    @Test
    fun `sleep timeline older than 8 days is dropped`() {
        val sleep = RingDecodedEvent.SleepTimeline(
            _timestamp = now.minus(10, ChronoUnit.DAYS),
            stages = listOf(SleepStage.LIGHT),
        )
        assertTrue(RingEventBridge.eventsFor(sleep, now).isEmpty())
    }

    @Test
    fun `sleep timeline in the future is dropped`() {
        val sleep = RingDecodedEvent.SleepTimeline(
            _timestamp = now.plus(2, ChronoUnit.HOURS),
            stages = listOf(SleepStage.LIGHT),
        )
        assertTrue(RingEventBridge.eventsFor(sleep, now).isEmpty())
    }

    // ── Battery Gating ──────────────────────────────────────────────────

    @Test
    fun `valid battery passes`() {
        val b = RingDecodedEvent.Battery(percent = 85)
        assertEquals(1, RingEventBridge.eventsFor(b, now).size)
    }

    @Test
    fun `battery out of range is dropped`() {
        val b = RingDecodedEvent.Battery(percent = 150)
        assertTrue(RingEventBridge.eventsFor(b, now).isEmpty())
    }

    // ── No-Op Events ────────────────────────────────────────────────────

    @Test
    fun `sync progress events are fanned out to PulseEvent`() {
        val events1 = RingEventBridge.eventsFor(
            RingDecodedEvent.HistorySyncProgress(stage = "connecting"), now
        )
        assertEquals(1, events1.size)
        assertTrue(events1[0] is PulseEvent.SyncProgress)

        val events2 = RingEventBridge.eventsFor(
            RingDecodedEvent.HistorySyncFinished, now
        )
        assertEquals(1, events2.size)
        assertTrue(events2[0] is PulseEvent.SyncProgress)
    }

    @Test
    fun `command ack and unknown events produce no output`() {
        assertTrue(RingEventBridge.eventsFor(
            RingDecodedEvent.CommandAck(commandId = 0x01u.toUByte()), now
        ).isEmpty())
        assertTrue(RingEventBridge.eventsFor(
            RingDecodedEvent.Unknown(commandId = 0xFFu.toUByte(), raw = byteArrayOf()), now
        ).isEmpty())
    }

    @Test
    fun `time sync ack produces no output`() {
        val ts = RingDecodedEvent.TimeSyncAck(_timestamp = now)
        assertTrue(RingEventBridge.eventsFor(ts, now).isEmpty())
    }

    @Test
    fun `measurement rejection reaches product orchestration`() {
        val events = RingEventBridge.eventsFor(
            RingDecodedEvent.MeasurementRejected(YCBTMeasurementMode.SPO2), now
        )
        assertEquals(listOf(PulseEvent.MeasurementRejected(YCBTMeasurementMode.SPO2)), events)
    }

    @Test
    fun `live blood pressure preserves live identity`() {
        val events = RingEventBridge.eventsFor(
            RingDecodedEvent.BloodPressureSample(118, 79, now), now
        )

        assertEquals(listOf(PulseEvent.BloodPressureSample(118, 79, now)), events)
        assertFalse(events.any { it is PulseEvent.HistoryMeasurement })
    }

    @Test
    fun `historical blood pressure and HRV retain history identity`() {
        val systolic = RingEventBridge.eventsFor(
            RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, 130.0, now), now
        ).single()
        val hrv = RingEventBridge.eventsFor(
            RingDecodedEvent.HistoryMeasurement(MeasurementKind.HRV, 55.0, now), now
        ).single()

        assertTrue(systolic is PulseEvent.HistoryMeasurement)
        assertTrue(hrv is PulseEvent.HistoryMeasurement)
        assertFalse(systolic is PulseEvent.BloodPressureSample)
        assertFalse(hrv is PulseEvent.HrvSample)
    }

    @Test
    fun `implausible historical measurements are dropped`() {
        assertTrue(
            RingEventBridge.eventsFor(
                RingDecodedEvent.HistoryMeasurement(MeasurementKind.SPO2, 255.0, now), now
            ).isEmpty()
        )
        assertTrue(
            RingEventBridge.eventsFor(
                RingDecodedEvent.HistoryMeasurement(MeasurementKind.TEMPERATURE, 255.0, now), now
            ).isEmpty()
        )
        assertTrue(
            RingEventBridge.eventsFor(
                RingDecodedEvent.BloodPressureSample(255, 255, now, isHistory = true), now
            ).isEmpty()
        )
    }

    @Test
    fun `live blood sugar keeps live event identity`() {
        val events = RingEventBridge.eventsFor(RingDecodedEvent.BloodSugarSample(99.0, now), now)

        assertEquals(listOf(PulseEvent.BloodSugarSample(99.0, now)), events)
    }

    @Test
    fun `band function reply produces no output`() {
        val bf = RingDecodedEvent.BandFunction(JringBandCapabilities(byteArrayOf(0, 0, 0)))
        assertTrue(RingEventBridge.eventsFor(bf, now).isEmpty())
    }
}
