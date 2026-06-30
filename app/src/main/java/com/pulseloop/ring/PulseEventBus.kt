package com.pulseloop.ring

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Ported from [PulseEvent] in PulseEventBus.swift.
 * Typed events published on the bus for subscribers to consume.
 */
sealed class PulseEvent {
    data class DeviceStateChanged(val state: RingConnectionState, val address: String?, val firmware: String? = null, val name: String? = null) : PulseEvent()
    data class DeviceIdentified(val deviceType: RingDeviceType, val capabilities: Set<WearableCapability>) : PulseEvent()
    data class BatteryLevel(val percent: Int) : PulseEvent()
    data class RawPacket(val direction: PacketDirection, val data: ByteArray, val decoded: RingDecodedEvent) : PulseEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawPacket) return false
            return direction == other.direction && data.contentEquals(other.data) && decoded == other.decoded
        }
        override fun hashCode(): Int = 31 * (31 * direction.hashCode() + data.contentHashCode()) + decoded.hashCode()
    }
    data class ActivityUpdate(val timestamp: java.time.Instant, val steps: Int, val distanceMeters: Double, val calories: Double) : PulseEvent()
    data class ActivityBucket(val timestamp: java.time.Instant, val steps: Int, val distanceMeters: Double) : PulseEvent()
    data object ActivitySyncReset : PulseEvent()
    data class HeartRateSample(val bpm: Int, val timestamp: java.time.Instant) : PulseEvent()
    data class HeartRateComplete(val timestamp: java.time.Instant) : PulseEvent()
    data class Spo2Result(val value: Int, val timestamp: java.time.Instant) : PulseEvent()
    data class HistoryMeasurement(val kind: MeasurementKind, val value: Double, val timestamp: java.time.Instant) : PulseEvent()
    data class StressSample(val value: Int, val timestamp: java.time.Instant) : PulseEvent()
    data class HrvSample(val value: Int, val timestamp: java.time.Instant) : PulseEvent()
    data class TemperatureSample(val celsius: Double, val timestamp: java.time.Instant) : PulseEvent()
    data class SleepTimeline(val timestamp: java.time.Instant, val stages: List<SleepStage>) : PulseEvent()
    data class SyncProgress(val stage: String) : PulseEvent()
    data class FirmwareVersion(val version: Int?) : PulseEvent()
}

enum class RingConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, FAILED }

enum class PacketDirection { INCOMING, OUTGOING }

/**
 * Ported from [PulseEventBus] in PulseEventBus.swift.
 * SharedFlow-based event bus for fanning typed events to subscribers.
 */
object PulseEventBus {
    private val _events = MutableSharedFlow<PulseEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<PulseEvent> = _events.asSharedFlow()

    suspend fun publish(event: PulseEvent) {
        _events.emit(event)
    }

    /** Non-suspending publish for use from non-coroutine contexts (callbacks). */
    fun publishBlocking(event: PulseEvent) {
        _events.tryEmit(event)
    }
}
