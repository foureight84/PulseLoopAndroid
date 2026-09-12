package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SpO₂ settle (issue #59, RC-2 feedback): the reading is the last plausible sample, because
 * that is what the ring itself logs — see [Spo2SampleWindow] for the three sources behind that.
 * The five captures below are @Albabit's, each lined up against the value read back out of the
 * ring's own history for that run.
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
        assertTrue(w.collect(97))
        assertTrue(w.receivedReading)
        assertEquals(97, w.settled)
    }

    /** Captures 1–3: one tight burst. Ring stored 98. */
    @Test
    fun `a tight burst settles on its last sample`() {
        val w = Spo2SampleWindow()
        w.begin()
        listOf(99, 98, 98, 98).forEach { w.collect(it) }
        assertEquals(98, w.settled)
    }

    /**
     * Capture 4: the late burst collapses from 98 to 86–87. The ring stored **87** — that run was
     * simply bad, and the reading must say so rather than rescue it from the earlier burst.
     */
    @Test
    fun `a collapsing run settles on the collapse, as the ring does`() {
        val w = Spo2SampleWindow()
        w.begin()
        listOf(98, 98, 98, 86, 86, 86, 87, 87, 87).forEach { w.collect(it) }
        assertEquals(87, w.settled)
    }

    /** Capture 5: the one run where the median (98) disagreed with what the ring stored (99). */
    @Test
    fun `the run that split median from ring settles with the ring`() {
        val w = Spo2SampleWindow()
        w.begin()
        listOf(97, 97, 97, 99, 99, 98, 98, 98, 99).forEach { w.collect(it) }
        assertEquals(99, w.settled)
    }

    /** The original RC-1 capture: rises to 99 then declines to 94 before `04 0e`. Ring behaviour
     *  says the last sample is the reading; the old first-sample leg would have said 96. */
    @Test
    fun `the RC-1 capture settles on its tail, not its first sample`() {
        val w = Spo2SampleWindow()
        w.begin()
        listOf(96, 96, 96, 99, 98, 98, 98, 96, 96, 95, 94, 94).forEach { w.collect(it) }
        assertEquals(94, w.settled)
    }

    /** The vendor's plausibility band (70..100): a zero or a dropout is neither the reading nor
     *  evidence that the ring has read anything yet. */
    @Test
    fun `implausible samples are dropped and do not count as a reading`() {
        val w = Spo2SampleWindow()
        w.begin()
        assertFalse(w.collect(0))
        assertFalse(w.collect(69))
        assertFalse(w.collect(101))
        assertFalse(w.receivedReading)
        assertNull(w.settled)
        assertTrue(w.collect(97))
        assertFalse(w.collect(0))
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
