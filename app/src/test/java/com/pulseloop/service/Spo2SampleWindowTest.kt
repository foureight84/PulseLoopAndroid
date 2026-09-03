package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SpO₂ settle (issue #59, RC-1 feedback). The rule is deliberately non-committal about the
 * shape of the run — see [Spo2SampleWindow] — so these tests pin what it must *not* do as much as
 * what it returns.
 */
class Spo2SampleWindowTest {

    @Test
    fun `nothing collected settles to nothing`() {
        val w = Spo2SampleWindow()
        w.begin()
        assertNull(w.settled)
        assertFalse(w.receivedReading)
    }

    @Test
    fun `a single sample is its own reading`() {
        val w = Spo2SampleWindow()
        w.begin()
        w.collect(97)
        assertTrue(w.receivedReading)
        assertEquals(97, w.settled)
    }

    /**
     * Replayed from @Albabit's instrumented capture: a first burst at 96, a 24 s silence, a peak of
     * 99, then a decline to 94 before the ring ends the run itself at t+50 s.
     *
     * The old leg returned 96 — the first sample, handed back at t+13 s with nine more still to
     * come. The settle must not simply agree with it by accident of ordering, and must sit inside
     * the run rather than at either extreme, since which end is honest is still unknown.
     */
    @Test
    fun `the captured run settles between the peak and the declining tail`() {
        val w = Spo2SampleWindow()
        w.begin()
        listOf(96, 96, 96, 99, 98, 98, 98, 96, 96, 95, 94, 94).forEach { w.collect(it) }

        val settled = w.settled!!
        assertTrue("must not chase the 99 peak: got $settled", settled < 99)
        assertTrue("must not settle on the 94 tail: got $settled", settled > 94)
        assertEquals(96, settled)
    }

    /** Order must not matter: the same run collected backwards settles identically. A rule that
     *  depended on arrival order is exactly what the first-sample-wins behaviour was. */
    @Test
    fun `the settle is independent of arrival order`() {
        val run = listOf(96, 96, 96, 99, 98, 98, 98, 96, 96, 95, 94, 94)
        val forward = Spo2SampleWindow().apply { begin(); run.forEach { collect(it) } }
        val backward = Spo2SampleWindow().apply { begin(); run.reversed().forEach { collect(it) } }

        assertEquals(forward.settled, backward.settled)
    }

    /** A single outlier — one motion artifact in an otherwise steady run — must not move it. */
    @Test
    fun `an outlier does not drag the reading`() {
        val w = Spo2SampleWindow()
        w.begin()
        listOf(97, 97, 98, 97, 97, 70).forEach { w.collect(it) }
        assertEquals(97, w.settled)
    }

    @Test
    fun `begin clears a prior run`() {
        val w = Spo2SampleWindow()
        w.begin()
        listOf(90, 91, 92).forEach { w.collect(it) }
        w.begin()
        assertFalse(w.receivedReading)
        assertNull(w.settled)
    }
}
