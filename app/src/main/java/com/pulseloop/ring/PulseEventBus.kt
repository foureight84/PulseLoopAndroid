package com.pulseloop.ring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Ported from [PulseEvent] in PulseEventBus.swift.
 * Typed events published on the bus for subscribers to consume.
 */
sealed class PulseEvent {
    data class DeviceStateChanged(
        val state: RingConnectionState,
        val address: String?,
        val firmware: String? = null,
        val name: String? = null,
        val deviceType: RingDeviceType? = null,
    ) : PulseEvent()
    /** Emitted on connect once the active wearable's type + capabilities are known (iOS #49 adds
     *  the exact catalog model + advertised name so persistence can stamp them on the device). */
    data class DeviceIdentified(
        val deviceType: RingDeviceType,
        val wearableModelID: String? = null,
        val advertisedName: String? = null,
        val capabilities: Set<WearableCapability> = emptySet(),
    ) : PulseEvent()
    /** Emitted when the user forgets the ring, so persistence clears the stored model identity. */
    data object DeviceForgotten : PulseEvent()
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
    /**
     * [spot] marks the one settled reading a spot measurement publishes for itself (issue #60);
     * false for the ring's live stream. [ringWillLogIt] additionally says this ring writes that
     * measurement into its **own** history, so a later sync will hand the same reading back and
     * the two copies have to be reconciled — true only for a family whose ring reports its own
     * completion (`RingSyncEngine.signalsMeasurementCompletion`), which is the same property:
     * the ring decides the number and the vendor app re-reads it. Meaningless unless [spot].
     */
    data class HeartRateSample(
        val bpm: Int,
        val timestamp: java.time.Instant,
        val spot: Boolean = false,
        val ringWillLogIt: Boolean = false,
    ) : PulseEvent()
    data class HeartRateComplete(val timestamp: java.time.Instant) : PulseEvent()
    /** [spot] and [ringWillLogIt] as on [HeartRateSample]. */
    data class Spo2Result(
        val value: Int,
        val timestamp: java.time.Instant,
        val spot: Boolean = false,
        val ringWillLogIt: Boolean = false,
    ) : PulseEvent()
    /** The ring ended a live-SpO₂ run (error or natural finish) — no more results coming. */
    data class Spo2Complete(val timestamp: java.time.Instant) : PulseEvent()
    /** A live measurement command was refused (not worn, sensor busy, unsupported). */
    data class MeasurementRejected(val mode: Int) : PulseEvent()
    /** The ring ended a spot measurement on its own and reported the verdict (issue #59).
     *  [mode] names which measurement finished; [success] is the ring's own success byte. */
    data class MeasurementComplete(val mode: Int, val success: Boolean, val timestamp: java.time.Instant) : PulseEvent()
    /**
     * The coordinator opening or closing the gate on storing [kind]'s live samples (issue #60).
     * Travels through the bus rather than a shared flag so it is ordered against the samples it
     * governs: everything the ring streamed before the gate reopened is dropped, whatever the
     * persistence collector's lag, and the settled reading published after it is stored.
     */
    data class LiveSampleGate(val kind: MeasurementKind, val closed: Boolean) : PulseEvent()
    data class BloodPressureSample(
        val systolic: Int,
        val diastolic: Int,
        val timestamp: java.time.Instant,
        val isHistory: Boolean = false,
    ) : PulseEvent()
    data class BloodSugarSample(val mgdl: Double, val timestamp: java.time.Instant) : PulseEvent()
    data class HistoryMeasurement(val kind: MeasurementKind, val value: Double, val timestamp: java.time.Instant) : PulseEvent()
    data class StressSample(val value: Int, val timestamp: java.time.Instant, val isHistory: Boolean = false) : PulseEvent()
    data class HrvSample(val value: Int, val timestamp: java.time.Instant) : PulseEvent()
    data class TemperatureSample(val celsius: Double, val timestamp: java.time.Instant, val isHistory: Boolean = false) : PulseEvent()
    data class SleepTimeline(
        val timestamp: java.time.Instant,
        val stages: List<SleepStage>,
        val completeSession: Boolean = false,
    ) : PulseEvent()
    data class SyncProgress(val stage: String) : PulseEvent()
    data class FirmwareVersion(val version: Int?) : PulseEvent()
    /** The ring's firmware version as a display string (CRP `3/3` → `MOY-R1K3-2.1.6`). Distinct
     *  from [FirmwareVersion], which carries the jring `0xF6` numeric build, and deliberately not
     *  folded into [DeviceStateChanged]: that event means the *connection state* changed, and
     *  persistence rebuilds the sleep tables on every CONNECTED it sees. */
    data class FirmwareRevision(val version: String) : PulseEvent()
    /** The ring's on-finger / skin-contact state changed. `worn == false` ⇒ an optical spot
     *  measurement can't produce a reading, so the coordinator fast-fails it (issue #29). */
    data class WearState(val worn: Boolean) : PulseEvent()
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
    private val pending = Channel<PulseEvent>(capacity = Channel.UNLIMITED)
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        dispatchScope.launch {
            // The bus is process-long and single-drained: an uncaught throw here would kill the
            // dispatcher and silently stop every subscriber for the rest of the process (the
            // SupervisorJob does not restart it). Isolate each emit so one bad event can't do that.
            for (event in pending) {
                try {
                    _events.emit(event)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.e("PulseEventBus", "Dropped ${event.javaClass.simpleName} on emit failure", t)
                }
            }
        }
    }

    suspend fun publish(event: PulseEvent) {
        pending.send(event)
    }

    /** Non-suspending publish for use from non-coroutine contexts (callbacks). */
    fun publishBlocking(event: PulseEvent) {
        val result = pending.trySend(event)
        if (result.isFailure) {
            android.util.Log.e("PulseEventBus", "Pulse event queue rejected ${event.javaClass.simpleName}", result.exceptionOrNull())
        }
    }
}
