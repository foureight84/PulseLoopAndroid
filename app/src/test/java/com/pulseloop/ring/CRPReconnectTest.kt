package com.pulseloop.ring

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CRPReconnectTest {
    @Test
    fun `five second connection failure retries three seconds later instead of next watchdog tick`() = runTest {
        val events = mutableListOf<Pair<Long, String>>()
        val recovery = CRPReconnect(
            scope = this,
            canRetry = { true },
            onPending = { events += testScheduler.currentTime to "reconnecting" },
            onRetry = { events += testScheduler.currentTime to "scan" },
            onAbandoned = { fail("retry is eligible") },
        )
        // Replay the failed first attempt in the October 17 capture.
        advanceTimeBy(5_000)
        assertTrue(recovery.schedule())
        runCurrent()
        assertTrue(recovery.isPending)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(listOf(5_000L to "reconnecting"), events)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(5_000L to "reconnecting", 8_000L to "scan"), events)
        assertFalse(recovery.isPending)
        assertTrue("scan remains part of recovery", recovery.isRecovering)
        recovery.cancel()
    }

    @Test
    fun `duplicate scheduling and watchdog checks cannot dispatch a second attempt`() = runTest {
        var attempts = 0
        val recovery = CRPReconnect(this, { true }, {}, { attempts++ }, {})
        assertTrue(recovery.schedule())
        runCurrent()
        advanceTimeBy(1_000)
        assertFalse(recovery.schedule())
        assertTrue("watchdog must yield to pending retry", recovery.isPending)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals("timer is one-shot", 1, attempts)
        recovery.cancel()
    }

    @Test
    fun `cancellation invalidates a timer already ready on the dispatcher`() = runTest {
        var attempts = 0
        val recovery = CRPReconnect(this, { true }, {}, { attempts++ }, {})
        recovery.schedule()
        runCurrent()
        advanceTimeBy(3_000) // Deadline reached, continuation has not run yet.
        recovery.cancel()
        runCurrent()
        assertEquals(0, attempts)
        assertFalse(recovery.isPending)
        assertFalse(recovery.isRecovering)
    }

    @Test
    fun `a superseding attempt invalidates the old timer without ending recovery`() = runTest {
        var attempts = 0
        val recovery = CRPReconnect(this, { true }, {}, { attempts++ }, {})
        recovery.schedule()
        runCurrent()
        advanceTimeBy(3_000)
        recovery.cancelPending()
        runCurrent()
        assertEquals(0, attempts)
        assertTrue(recovery.isRecovering)
        // If the superseding attempt fails, its new deadline must win over the old one.
        assertTrue(recovery.schedule())
        runCurrent()
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(0, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, attempts)
        recovery.cancel()
    }

    @Test
    fun `ineligible recovery never schedules or changes presentation`() = runTest {
        val recovery = CRPReconnect(this, { false }, { fail("must stay disconnected") },
            { fail("must not connect") }, { fail("nothing was scheduled") })
        assertFalse(recovery.schedule())
        advanceTimeBy(15_000)
        runCurrent()
        assertFalse(recovery.isPending)
        assertFalse(recovery.isRecovering)
    }

    @Test
    fun `eligibility revoked before dispatch abandons recovery without a connection attempt`() = runTest {
        var eligible = true
        var abandoned = 0
        val recovery = CRPReconnect(this, { eligible }, {},
            { fail("eligibility was revoked") }, { abandoned++ })
        recovery.schedule()
        runCurrent()
        eligible = false
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, abandoned)
        assertFalse(recovery.isPending)
        assertFalse(recovery.isRecovering)
    }

    @Test
    fun `repeated cancellation permits only a fresh recovery`() = runTest {
        var attempts = 0
        val recovery = CRPReconnect(this, { true }, {}, { attempts++ }, {})
        repeat(4) {
            recovery.schedule()
            runCurrent()
            advanceTimeBy(1_000)
            recovery.cancel()
            advanceTimeBy(3_000)
            runCurrent()
        }
        assertEquals(0, attempts)
        recovery.schedule()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, attempts)
        recovery.cancel()
    }
}
