package com.pulseloop.ring

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Ported from [RingEventBridge] in RingEventBridge.swift.
 * Pure mapping from decoded ring packets to typed [PulseEvent]s with sanity gates.
 */
object RingEventBridge {
    private val hrRange = 30..220
    val spo2Range = 70..100
    private val stressRange = 1..100
    private val hrvRange = 1..300
    private val temperatureRange = 30.0..45.0
    private val systolicRange = 60..250
    private val diastolicRange = 30..150
    private val bloodSugarRange = 20.0..600.0
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
            if (!isPlausibleHistoryMeasurement(decoded.kind_field, decoded.value)) emptyList()
            // A ring's on-device log can still hold records stamped under a previous clock — e.g.
            // a jring that logged against a UTC RTC before the app started setting it to local
            // time. Those decode hours into the future. Drop anything outside the history horizon
            // rather than persisting a sample that poisons "today", peak HR and the 24h trends.
            else if (!isWithinHistoryWindow(decoded._timestamp, now)) emptyList()
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
            if (!isWithinHistoryWindow(decoded._timestamp, now) || decoded.stages.isEmpty()) emptyList()
            else listOf(PulseEvent.SleepTimeline(decoded._timestamp, decoded.stages, decoded.completeSession))
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

        is RingDecodedEvent.MeasurementRejected ->
            listOf(PulseEvent.MeasurementRejected(decoded.mode))

        is RingDecodedEvent.BandFunction,
        is RingDecodedEvent.WearingStatus,
        is RingDecodedEvent.SupportFunctions,
        is RingDecodedEvent.ChipScheme ->
            emptyList() // Debug-feed only; no product surface yet

        is RingDecodedEvent.BloodPressureSample -> {
            if (decoded.systolic in systolicRange && decoded.diastolic in diastolicRange) {
                listOf(
                    PulseEvent.BloodPressureSample(
                        decoded.systolic,
                        decoded.diastolic,
                        decoded._timestamp,
                        decoded.isHistory,
                    )
                )
            } else emptyList()
        }

        is RingDecodedEvent.BloodSugarSample -> {
            if (decoded.mgdl in bloodSugarRange) listOf(PulseEvent.BloodSugarSample(decoded.mgdl, decoded._timestamp))
            else emptyList()
        }
    }

    private fun isPlausibleHistoryMeasurement(kind: MeasurementKind, value: Double): Boolean {
        if (!value.isFinite()) return false
        return when (kind) {
            MeasurementKind.HEART_RATE -> value.toInt() in hrRange
            MeasurementKind.SPO2 -> value.toInt() in spo2Range
            MeasurementKind.STRESS, MeasurementKind.FATIGUE -> value.toInt() in stressRange
            MeasurementKind.HRV -> value.toInt() in hrvRange
            MeasurementKind.TEMPERATURE -> value in temperatureRange
            MeasurementKind.BLOOD_PRESSURE_SYSTOLIC -> value.toInt() in systolicRange
            MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> value.toInt() in diastolicRange
            MeasurementKind.BLOOD_SUGAR -> value in bloodSugarRange
            MeasurementKind.RESPIRATORY_RATE -> value.toInt() in 5..60
            MeasurementKind.VO2MAX -> value.toInt() in 1..100
        }
    }

    /** Shared plausibility window: within the last ~8 days (the history horizon) and no more
     *  than an hour into the future. A timestamp outside it indicates a misdecoded frame. */
    private fun isWithinHistoryWindow(date: Instant, now: Instant): Boolean {
        val lower = now.minus(8, ChronoUnit.DAYS)
        val upper = now.plus(1, ChronoUnit.HOURS)
        return !date.isBefore(lower) && !date.isAfter(upper)
    }
}
