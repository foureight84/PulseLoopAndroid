package com.pulseloop.service

import com.pulseloop.ring.SpotVital
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotVitalWindowTest {

    @Test
    fun `settles on the last value once the ring has been quiet for a second`() {
        val w = SpotVitalWindow()
        w.begin(SpotVital.HRV)
        // The HRV run from the Ring 2 Pro capture: values every ~0.5 s, ending on 37.
        listOf(44.0, 33.0, 46.0, 41.0, 49.0, 33.0, 34.0, 37.0).forEachIndexed { i, v ->
            assertTrue(w.collect(SpotVital.HRV, v, atMs = 1_000L + i * 500))
        }
        val lastAt = 1_000L + 7 * 500
        assertFalse("still streaming", w.isSettled(lastAt + 500))
        assertTrue(w.isSettled(lastAt + SpotVitalWindow.QUIET_MS))
        assertEquals(37.0, w.settled!!, 0.0)
    }

    @Test
    fun `never settles without a value`() {
        val w = SpotVitalWindow()
        w.begin(SpotVital.STRESS)
        assertFalse(w.isSettled(60_000))
        assertNull(w.settled)
        assertFalse(w.receivedReading)
    }

    @Test
    fun `drops implausible values and values from another vital`() {
        val w = SpotVitalWindow()
        w.begin(SpotVital.TEMPERATURE)
        assertFalse(w.collect(SpotVital.TEMPERATURE, 20.0, 0))   // raw 0 would decode to 20 °C
        assertFalse(w.collect(SpotVital.HRV, 36.8, 0))
        assertFalse(w.receivedReading)
        assertTrue(w.collect(SpotVital.TEMPERATURE, 36.8, 0))
        assertEquals(36.8, w.settled!!, 0.0)
    }

    @Test
    fun `begin clears the previous run`() {
        val w = SpotVitalWindow()
        w.begin(SpotVital.STRESS)
        w.collect(SpotVital.STRESS, 43.0, 0)
        w.begin(SpotVital.STRESS)
        assertNull(w.settled)
    }
}
