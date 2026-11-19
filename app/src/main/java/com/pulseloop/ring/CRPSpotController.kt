package com.pulseloop.ring

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Dedicated spot measurement controller for CRP ("crrepa" / COLMI R11) rings.
 *
 * ## R11 Wire Protocol Characteristics (Issue #88)
 * - The ring answers on-demand spot HR (cmd 9) and SpO₂ (cmd 11) with a SINGLE terminal
 *   result frame ~15-17s (HR) or ~48s (SpO₂) after start, and then sends nothing more.
 * - Every group 1 cmd 9/11 frame is terminal:
 *   - Valid reading (HR 40..200, SpO₂ 70..100) completes measurement immediately.
 *   - Empty payload, 0, 0xFF, or out-of-range value ends measurement as "no reading" / failure.
 * - Autonomous wear-state push (group 3, cmd 7 [00]) fast-fails as not-worn.
 * - Link loss (disconnect) aborts measurement immediately without issuing any stop write.
 * - Countdown starts only when the start command is actually sent to the ring (outgoing raw packet),
 *   not when queued. A start that is not seen leaving the queue within [dispatchTimeoutSeconds]
 *   fails the measurement, so nothing waits on a dispatch that never happens.
 * - Cancelling while queued purges the command from opQueue without sending stop.
 *   Cancelling after dispatch sends stop (if connected).
 * - History traffic (group 2) never completes a spot measurement.
 * - Stored with `sourceRaw = "spot_result"`, keeping it out of the `"spot"` history-adoption policy.
 */
class CRPSpotController(
    private val sendWrite: (ByteArray) -> Unit,
    private val cancelQueuedWrite: ((ByteArray) -> Boolean) -> Boolean,
    private val isConnected: () -> Boolean,
    private val events: SharedFlow<PulseEvent> = PulseEventBus.events,
    private val publishEvent: (PulseEvent) -> Unit = { PulseEventBus.publishBlocking(it) },
    private val dispatchTimeoutSeconds: Int = DISPATCH_TIMEOUT_SECONDS,
) {
    companion object {
        /** How long the start command may wait in the write queue before the measurement fails. */
        const val DISPATCH_TIMEOUT_SECONDS = 15
    }

    constructor(client: RingBLEClient) : this(
        sendWrite = { client.enqueueWrite(it) },
        cancelQueuedWrite = { client.cancelQueuedCommand(it) },
        isConnected = { client.isConnected },
    )

    private val _countdownRemaining = MutableStateFlow<Int?>(null)
    val countdownRemaining: StateFlow<Int?> = _countdownRemaining.asStateFlow()

    var measureNotWorn: Boolean = false
        private set
    var measureDisconnected: Boolean = false
        private set
    var measureNoReading: Boolean = false
        private set
    var inFlightKind: MeasurementKind? = null
        private set

    val isMeasuring: Boolean get() = inFlightKind != null

    suspend fun measureHeartRate(timeoutSeconds: Int = 30): Int? =
        measureVital(
            kind = MeasurementKind.HEART_RATE,
            startFrame = CRPProtocol.measureHeartRate(true),
            stopFrame = CRPProtocol.measureHeartRate(false),
            resultCmd = CRPCommands.CMD_RESULT_HR,
            validRange = 40..200,
            timeoutSeconds = timeoutSeconds,
            createSuccessEvent = { bpm, now ->
                PulseEvent.HeartRateSample(
                    bpm = bpm,
                    timestamp = now,
                    spot = true,
                    ringWillLogIt = false,
                    sourceRaw = "spot_result",
                )
            },
        )

    suspend fun measureSpO2(timeoutSeconds: Int = 60): Int? =
        measureVital(
            kind = MeasurementKind.SPO2,
            startFrame = CRPProtocol.measureSpO2(true),
            stopFrame = CRPProtocol.measureSpO2(false),
            resultCmd = CRPCommands.CMD_RESULT_SPO2,
            validRange = 70..100,
            timeoutSeconds = timeoutSeconds,
            createSuccessEvent = { spo2, now ->
                PulseEvent.Spo2Result(
                    value = spo2,
                    timestamp = now,
                    spot = true,
                    ringWillLogIt = false,
                    sourceRaw = "spot_result",
                )
            },
        )

    private suspend fun measureVital(
        kind: MeasurementKind,
        startFrame: ByteArray,
        stopFrame: ByteArray,
        resultCmd: Int,
        validRange: IntRange,
        timeoutSeconds: Int,
        createSuccessEvent: (Int, Instant) -> PulseEvent,
    ): Int? {
        if (inFlightKind != null) return null
        if (!isConnected()) {
            measureDisconnected = true
            return null
        }

        measureNotWorn = false
        measureDisconnected = false
        measureNoReading = false
        inFlightKind = kind
        _countdownRemaining.value = null

        publishEvent(PulseEvent.LiveSampleGate(kind, closed = true))

        val completion = CompletableDeferred<Int?>()

        return coroutineScope {
            var countdownJob: Job? = null
            // Bounds the wait for the start command to leave the queue; cancelled once it does.
            // Without it, a dispatch that is never observed leaves no deadline at all.
            val dispatchTimeoutJob = launch {
                delay(dispatchTimeoutSeconds * 1000L)
                completion.complete(null)
            }
            // UNDISPATCHED so the bus subscription exists before [sendWrite] below: the bus does
            // not replay, and on an idle queue the start's outgoing packet can be published before
            // a normally dispatched listener has subscribed — the countdown then never started.
            val listenerJob = launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { event ->
                    when (event) {
                        is PulseEvent.RawPacket -> {
                            if (event.direction == PacketDirection.OUTGOING) {
                                if (event.data.contentEquals(startFrame)) {
                                    dispatchTimeoutJob.cancel()
                                    countdownJob?.cancel()
                                    countdownJob = launch {
                                        for (s in timeoutSeconds downTo 0) {
                                            _countdownRemaining.value = s
                                            if (s > 0) delay(1000)
                                        }
                                        completion.complete(null)
                                    }
                                }
                            } else if (event.direction == PacketDirection.INCOMING) {
                                val data = event.data
                                if (CRPProtocol.isFrameStart(data) && data.size >= 6) {
                                    val group = data[4].toInt() and 0xFF
                                    val cmd = data[5].toInt() and 0xFF

                                    // Wear state: group 3 cmd 7 [00]
                                    if (group == CRPCommands.GROUP_POWER && cmd == CRPCommands.CMD_WEAR_STATE) {
                                        val payloadByte = if (data.size > CRPProtocol.HEADER_SIZE) data[6].toInt() and 0xFF else 0
                                        if (payloadByte == 0) {
                                            measureNotWorn = true
                                            completion.complete(null)
                                        }
                                    }

                                    // Result frame: group 1, resultCmd
                                    if (group == CRPCommands.GROUP_DEVICE && cmd == resultCmd) {
                                        val payload = if (data.size > CRPProtocol.HEADER_SIZE) {
                                            data.copyOfRange(CRPProtocol.HEADER_SIZE, data.size)
                                        } else ByteArray(0)

                                        if (payload.isEmpty()) {
                                            measureNoReading = true
                                            completion.complete(null)
                                        } else {
                                            val value = payload[0].toInt() and 0xFF
                                            if (value == 0 || value == 0xFF) {
                                                measureNoReading = true
                                                completion.complete(null)
                                            } else if (value !in validRange) {
                                                measureNoReading = true
                                                completion.complete(null)
                                            } else {
                                                completion.complete(value)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is PulseEvent.WearState -> {
                            if (!event.worn) {
                                measureNotWorn = true
                                completion.complete(null)
                            }
                        }
                        is PulseEvent.DeviceStateChanged -> {
                            if (event.state != RingConnectionState.CONNECTED) {
                                measureDisconnected = true
                                completion.complete(null)
                            }
                        }
                        else -> {}
                    }
                }
            }

            sendWrite(startFrame)

            var result: Int? = null
            try {
                result = completion.await()
            } finally {
                withContext(NonCancellable) {
                    dispatchTimeoutJob.cancel()
                    countdownJob?.cancel()
                    listenerJob.cancel()
                    _countdownRemaining.value = null
                    inFlightKind = null

                    publishEvent(PulseEvent.LiveSampleGate(kind, closed = false))

                    val removedFromQueue = cancelQueuedWrite { it.contentEquals(startFrame) }
                    if (!removedFromQueue) {
                        if (isConnected()) {
                            sendWrite(stopFrame)
                        }
                    }
                }
            }

            if (result != null) {
                publishEvent(createSuccessEvent(result, Instant.now()))
            }
            result
        }
    }
}
