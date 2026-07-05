package com.pulseloop.ring

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Ported from [RingEventBridge] in RingEventBridge.swift.
 * Pure mapping from decoded ring packets to typed [PulseEvent]s with sanity gates.
 */
object RingEventBridge {
    private val hrRange = 30..220
    private val stressRange = 1..100
    private val hrvRange = 1..300
    private val temperatureRange = 30.0..45.0
    private const val maxBucketSteps = 5000
    private const val maxBucketDistance = 6000

    fun eventsFor(decoded: RingDecodedEvent, now: Instant = Instant.now()): List<PulseEvent> = when (decoded) {
        is RingDecodedEvent.ActivityUpdate ->
            listOf(PulseEvent.ActivityUpdate(decoded._timestamp, decoded.steps, decoded.distanceMeters.toDouble(), decoded.calories.toDouble()))

        is RingDecodedEvent.ActivityBucket -> {
            if (decoded.steps !in 0..maxBucketSteps || decoded.distanceMeters !in 0..maxBucketDistance) emptyList()
            else listOf(PulseEvent.ActivityBucket(decoded._timestamp, decoded.steps, decoded.distanceMeters.toDouble()))
        }

        is RingDecodedEvent.HeartRateSample -> {
            if (decoded.bpm !in hrRange) emptyList()
            else listOf(PulseEvent.HeartRateSample(decoded.bpm, decoded._timestamp))
        }

        is RingDecodedEvent.HeartRateComplete ->
            listOf(PulseEvent.HeartRateComplete(decoded._timestamp))

        is RingDecodedEvent.Spo2Result ->
            listOf(PulseEvent.Spo2Result(decoded.value, decoded._timestamp))

        is RingDecodedEvent.HistoryMeasurement -> {
            if (decoded.kind_field == MeasurementKind.HEART_RATE && decoded.value.toInt() !in hrRange) emptyList()
            else listOf(PulseEvent.HistoryMeasurement(decoded.kind_field, decoded.value, decoded._timestamp))
        }

        is RingDecodedEvent.StressSample -> {
            if (decoded.value !in stressRange) emptyList()
            else listOf(PulseEvent.StressSample(decoded.value, decoded._timestamp))
        }

        is RingDecodedEvent.HrvSample -> {
            if (decoded.value !in hrvRange) emptyList()
            else listOf(PulseEvent.HrvSample(decoded.value, decoded._timestamp))
        }

        is RingDecodedEvent.TemperatureSample -> {
            if (decoded.celsius !in temperatureRange) emptyList()
            else listOf(PulseEvent.TemperatureSample(decoded.celsius, decoded._timestamp))
        }

        is RingDecodedEvent.HistorySyncProgress ->
            listOf(PulseEvent.SyncProgress(decoded.stage))

        is RingDecodedEvent.HistorySyncFinished ->
            listOf(PulseEvent.SyncProgress("done"))

        is RingDecodedEvent.SleepTimeline -> {
            if (!isPlausibleSleepStart(decoded._timestamp, now) || decoded.stages.isEmpty()) emptyList()
            else listOf(PulseEvent.SleepTimeline(decoded._timestamp, decoded.stages))
        }

        is RingDecodedEvent.Battery -> {
            if (decoded.percent !in 0..100) emptyList()
            else listOf(PulseEvent.BatteryLevel(decoded.percent))
        }

        is RingDecodedEvent.Status ->
            listOf(PulseEvent.DeviceStateChanged(RingConnectionState.CONNECTED, decoded.address, decoded.firmware))

        is RingDecodedEvent.TimeSyncAck, is RingDecodedEvent.CommandAck, is RingDecodedEvent.Unknown ->
            emptyList()

        is RingDecodedEvent.FirmwareVersion ->
            listOf(PulseEvent.FirmwareVersion(decoded.version))

        is RingDecodedEvent.Spo2Complete ->
            listOf(PulseEvent.Spo2Complete(decoded._timestamp))

        is RingDecodedEvent.Spo2Progress ->
            emptyList() // Phase 1 does not fan these out

        is RingDecodedEvent.BindNotify ->
            emptyList() // Bind/unbind handshake is driven in the sync engine / BLE client
    }

    private fun isPlausibleSleepStart(start: Instant, now: Instant): Boolean {
        val lower = now.minus(8, ChronoUnit.DAYS)
        val upper = now.plus(1, ChronoUnit.HOURS)
        return !start.isBefore(lower) && !start.isAfter(upper)
    }
}
