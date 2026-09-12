package com.pulseloop.ring

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class PulseEventBusTest {
    @Test
    fun `non-suspending publisher does not drop a history-sized burst`() = runBlocking {
        val eventCount = 1_000
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            PulseEventBus.events
                .filterIsInstance<PulseEvent.FirmwareVersion>()
                .take(eventCount)
                .toList()
        }

        repeat(eventCount) { PulseEventBus.publishBlocking(PulseEvent.FirmwareVersion(it)) }

        assertEquals(eventCount, withTimeout(2_000) { collected.await() }.size)
    }

    /**
     * Issue #60: the spot-measurement gate rides the bus *because* delivery keeps publish order.
     * The coordinator sends close → (ring samples) → open → settled reading, and the persistence
     * collector must see them in exactly that order however far behind the ring it runs. If this
     * ever fails, the gate silently lets queued samples through again.
     */
    @Test
    fun `non-suspending publishes are delivered in publish order`() = runBlocking {
        val now = java.time.Instant.now()
        val sent = listOf(
            PulseEvent.LiveSampleGate(MeasurementKind.HEART_RATE, closed = true),
            PulseEvent.HeartRateSample(47, now),
            PulseEvent.HeartRateSample(46, now),
            PulseEvent.HeartRateSample(81, now),
            PulseEvent.LiveSampleGate(MeasurementKind.HEART_RATE, closed = false),
            PulseEvent.HeartRateSample(81, now, spot = true),
        )
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            PulseEventBus.events
                .filter { it is PulseEvent.LiveSampleGate || it is PulseEvent.HeartRateSample }
                .take(sent.size)
                .toList()
        }

        sent.forEach { PulseEventBus.publishBlocking(it) }

        assertEquals(sent, withTimeout(2_000) { collected.await() })
    }
}
