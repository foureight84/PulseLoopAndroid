package com.pulseloop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Issue #113: the app's one-shot startup runs for the process's first composition only. */
class StartupGateTest {

    @Test
    fun `only the first claim opens the gate`() {
        val gate = StartupGate()
        assertTrue(gate.claim())
        // A recreated Activity composes again and must not re-run startup.
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }

    @Test
    fun `concurrent claims open it exactly once`() {
        val gate = StartupGate()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(64) { Callable { gate.claim() } }).map { it.get() }
            assertEquals(1, results.count { it })
        } finally {
            pool.shutdown()
        }
    }
}
