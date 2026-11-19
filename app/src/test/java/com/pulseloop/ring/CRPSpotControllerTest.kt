package com.pulseloop.ring

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for [CRPSpotController] covering all requirements in Issue #88:
 * - Single terminal result handling for HR and SpO₂
 * - Fast-fail on not-worn, no-reading (0, 0xFF, empty), and out-of-range
 * - Immediate failure on disconnect without sending stop command
 * - Cancellation while queued removes start without sending stop
 * - Cancellation while in-flight sends stop when connected
 * - Isolation from history traffic
 * - Preservation of sourceRaw = "spot_result"
 * - A start dispatched before the listener would have run still starts the countdown
 * - A start that never leaves the queue fails after the dispatch timeout
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CRPSpotControllerTest {

    private val startHR = CRPProtocol.measureHeartRate(true)
    private val stopHR = CRPProtocol.measureHeartRate(false)
    private val startSpO2 = CRPProtocol.measureSpO2(true)
    private val stopSpO2 = CRPProtocol.measureSpO2(false)

    private fun outgoing(data: ByteArray) =
        PulseEvent.RawPacket(PacketDirection.OUTGOING, data, RingDecodedEvent.Unknown(0u, data))

    private fun incoming(data: ByteArray, decoded: RingDecodedEvent = RingDecodedEvent.Unknown(0u, data)) =
        PulseEvent.RawPacket(PacketDirection.INCOMING, data, decoded)

    @Test
    fun `valid CRP HR result completes the measurement immediately`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val published = mutableListOf<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
            publishEvent = { published.add(it) },
        )

        val deferred = async { controller.measureHeartRate() }
        runCurrent()

        assertEquals(1, writes.size)
        assertArrayEquals(startHR, writes[0])
        assertNull(controller.countdownRemaining.value)

        // Command dispatched over the wire
        events.emit(outgoing(startHR))
        runCurrent()
        assertEquals(30, controller.countdownRemaining.value)

        // Valid 72 bpm result frame: FD DA 10 07 01 09 48
        val hrResult = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_HR, byteArrayOf(72))
        events.emit(incoming(hrResult))
        runCurrent()

        val bpm = deferred.await()
        assertEquals(72, bpm)
        assertFalse(controller.measureNoReading)
        assertFalse(controller.measureNotWorn)
        assertFalse(controller.measureDisconnected)

        // Verify spot_result published
        val hrEvent = published.filterIsInstance<PulseEvent.HeartRateSample>().single()
        assertEquals(72, hrEvent.bpm)
        assertTrue(hrEvent.spot)
        assertFalse(hrEvent.ringWillLogIt)
        assertEquals("spot_result", hrEvent.sourceRaw)

        // Verify stop command sent on completion
        assertEquals(2, writes.size)
        assertArrayEquals(stopHR, writes[1])
    }

    @Test
    fun `valid CRP SpO2 result completes the measurement immediately`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val published = mutableListOf<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
            publishEvent = { published.add(it) },
        )

        val deferred = async { controller.measureSpO2() }
        runCurrent()

        assertEquals(1, writes.size)
        assertArrayEquals(startSpO2, writes[0])

        events.emit(outgoing(startSpO2))
        runCurrent()
        assertEquals(60, controller.countdownRemaining.value)

        // Valid 97% SpO2 result frame: FD DA 10 07 01 0B 61
        val spo2Result = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_SPO2, byteArrayOf(97))
        events.emit(incoming(spo2Result))
        runCurrent()

        val spo2 = deferred.await()
        assertEquals(97, spo2)

        val spo2Event = published.filterIsInstance<PulseEvent.Spo2Result>().single()
        assertEquals(97, spo2Event.value)
        assertTrue(spo2Event.spot)
        assertFalse(spo2Event.ringWillLogIt)
        assertEquals("spot_result", spo2Event.sourceRaw)

        assertEquals(2, writes.size)
        assertArrayEquals(stopSpO2, writes[1])
    }

    @Test
    fun `not-worn push ends the measurement immediately`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val deferred = async { controller.measureHeartRate() }
        runCurrent()

        events.emit(outgoing(startHR))
        runCurrent()

        // Ring pushes wear state not-worn: group 3 cmd 7 [00]
        val notWorn = CRPProtocol.frame(CRPCommands.GROUP_POWER, CRPCommands.CMD_WEAR_STATE, byteArrayOf(0))
        events.emit(incoming(notWorn))
        runCurrent()

        val result = deferred.await()
        assertNull(result)
        assertTrue(controller.measureNotWorn)

        assertEquals(2, writes.size)
        assertArrayEquals(stopHR, writes[1])
    }

    @Test
    fun `empty payload ends the measurement immediately as failure`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val deferred = async { controller.measureHeartRate() }
        runCurrent()

        events.emit(outgoing(startHR))
        runCurrent()

        // Empty group 1 cmd 9 payload
        val emptyFrame = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_HR, ByteArray(0))
        events.emit(incoming(emptyFrame))
        runCurrent()

        val result = deferred.await()
        assertNull(result)
        assertTrue(controller.measureNoReading)

        assertEquals(2, writes.size)
        assertArrayEquals(stopHR, writes[1])
    }

    @Test
    fun `zero reading ends the measurement immediately as failure`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val deferred = async { controller.measureHeartRate() }
        runCurrent()

        events.emit(outgoing(startHR))
        runCurrent()

        val zeroFrame = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_HR, byteArrayOf(0))
        events.emit(incoming(zeroFrame))
        runCurrent()

        val result = deferred.await()
        assertNull(result)
        assertTrue(controller.measureNoReading)

        assertEquals(2, writes.size)
        assertArrayEquals(stopHR, writes[1])
    }

    @Test
    fun `0xFF sentinel ends the measurement immediately as failure`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val deferred = async { controller.measureSpO2() }
        runCurrent()

        events.emit(outgoing(startSpO2))
        runCurrent()

        // Group 1 cmd 11 [FF] no-reading sentinel
        val sentinelFrame = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_SPO2, byteArrayOf(0xFF.toByte()))
        events.emit(incoming(sentinelFrame))
        runCurrent()

        val result = deferred.await()
        assertNull(result)
        assertTrue(controller.measureNoReading)

        assertEquals(2, writes.size)
        assertArrayEquals(stopSpO2, writes[1])
    }

    @Test
    fun `out-of-range HR bpm ends the measurement immediately as failure`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val deferred = async { controller.measureHeartRate() }
        runCurrent()

        events.emit(outgoing(startHR))
        runCurrent()

        // 35 bpm is outside 40..200
        val outOfRange = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_HR, byteArrayOf(35))
        events.emit(incoming(outOfRange))
        runCurrent()

        val result = deferred.await()
        assertNull(result)
        assertTrue(controller.measureNoReading)

        assertEquals(2, writes.size)
        assertArrayEquals(stopHR, writes[1])
    }

    @Test
    fun `out-of-range SpO2 percentage ends the measurement immediately as failure`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val deferred = async { controller.measureSpO2() }
        runCurrent()

        events.emit(outgoing(startSpO2))
        runCurrent()

        // 65% is outside 70..100
        val outOfRange = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_SPO2, byteArrayOf(65))
        events.emit(incoming(outOfRange))
        runCurrent()

        val result = deferred.await()
        assertNull(result)
        assertTrue(controller.measureNoReading)

        assertEquals(2, writes.size)
        assertArrayEquals(stopSpO2, writes[1])
    }

    @Test
    fun `disconnect fails the measurement immediately without sending stop command`() = runTest {
        var connected = true
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { connected },
            events = events,
        )

        val deferred = async { controller.measureSpO2() }
        runCurrent()

        events.emit(outgoing(startSpO2))
        runCurrent()

        // Ring link drops mid-measurement
        connected = false
        events.emit(PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, address = null))
        runCurrent()

        val result = deferred.await()
        assertNull(result)
        assertTrue(controller.measureDisconnected)

        // Only startSpO2 was sent; no stop command because ring is disconnected
        assertEquals(1, writes.size)
        assertArrayEquals(startSpO2, writes[0])
    }

    @Test
    fun `cancellation while connected sends stop command`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val job = launch { controller.measureHeartRate() }
        runCurrent()

        events.emit(outgoing(startHR))
        runCurrent()

        // User navigates away / cancels
        job.cancel()
        runCurrent()

        assertEquals(2, writes.size)
        assertArrayEquals(startHR, writes[0])
        assertArrayEquals(stopHR, writes[1])
    }

    @Test
    fun `cancellation while start command is queued removes it from queue without sending stop`() = runTest {
        var queuedOp: ByteArray? = null
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = {
                queuedOp = it
                writes.add(it)
            },
            cancelQueuedWrite = { predicate ->
                if (queuedOp != null && predicate(queuedOp!!)) {
                    queuedOp = null
                    true
                } else false
            },
            isConnected = { true },
            events = events,
        )

        val job = launch { controller.measureHeartRate() }
        runCurrent()

        // Start command is still queued, not yet dispatched over the wire
        assertEquals(1, writes.size)
        assertArrayEquals(startHR, writes[0])

        job.cancel()
        runCurrent()

        // Was removed from queue, so no stop command was issued
        assertNull(queuedOp)
        assertEquals(1, writes.size)
    }

    @Test
    fun `history traffic does not complete spot measurement`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
        )

        val deferred = async { controller.measureHeartRate() }
        runCurrent()

        events.emit(outgoing(startHR))
        runCurrent()

        // Stored timeline history frame arrives (group 2 cmd 15)
        val historyFrame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_HR, byteArrayOf(0, 0, 72))
        events.emit(incoming(historyFrame))
        events.emit(PulseEvent.HistoryMeasurement(MeasurementKind.HEART_RATE, 72.0, Instant.now()))
        runCurrent()

        // Measurement must still be in flight!
        assertTrue(deferred.isActive)
        assertTrue(controller.isMeasuring)

        // Now the actual spot result arrives
        val spotResult = CRPProtocol.frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_RESULT_HR, byteArrayOf(72))
        events.emit(incoming(spotResult))
        runCurrent()

        val result = deferred.await()
        assertEquals(72, result)
        assertFalse(controller.isMeasuring)
    }

    /**
     * On an idle queue the start can be dispatched, and its outgoing packet published, before a
     * normally dispatched listener has subscribed. The bus does not replay, so the countdown never
     * started and, with a ring that stays silent, the measurement never ended.
     */
    @Test
    fun `start dispatched before the listener runs still starts the countdown`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>(extraBufferCapacity = 16)
        val controller = CRPSpotController(
            sendWrite = { data ->
                writes.add(data)
                events.tryEmit(outgoing(data))  // dispatched synchronously, as on an idle queue
            },
            cancelQueuedWrite = { false },
            isConnected = { true },
            events = events,
            publishEvent = {},
        )

        val deferred = async { controller.measureHeartRate(timeoutSeconds = 30) }
        runCurrent()
        assertEquals(30, controller.countdownRemaining.value)

        // The ring never answers: the measurement must still end at its deadline.
        advanceTimeBy(31_000)
        runCurrent()
        assertNull(deferred.await())
        assertArrayEquals(stopHR, writes.last())
    }

    @Test
    fun `start that never leaves the queue fails after the dispatch timeout without a stop`() = runTest {
        val writes = mutableListOf<ByteArray>()
        val events = MutableSharedFlow<PulseEvent>()
        val controller = CRPSpotController(
            sendWrite = { writes.add(it) },
            cancelQueuedWrite = { predicate -> writes.any(predicate) },  // still queued: removed
            isConnected = { true },
            events = events,
            publishEvent = {},
        )

        val deferred = async { controller.measureSpO2() }
        runCurrent()
        advanceTimeBy(CRPSpotController.DISPATCH_TIMEOUT_SECONDS * 1000L - 1)
        runCurrent()
        assertTrue(deferred.isActive)

        advanceTimeBy(2)
        runCurrent()
        assertNull(deferred.await())
        assertFalse(controller.isMeasuring)
        assertNull(controller.countdownRemaining.value)
        assertEquals(1, writes.size)  // the start only; it was purged from the queue, so no stop
        assertArrayEquals(startSpO2, writes.single())
    }
}
