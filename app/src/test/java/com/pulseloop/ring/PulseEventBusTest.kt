package com.pulseloop.ring

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
}
