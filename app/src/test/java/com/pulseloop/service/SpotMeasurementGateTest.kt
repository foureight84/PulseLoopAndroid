package com.pulseloop.service

import com.pulseloop.ring.YCBTMeasurementMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SpotMeasurementGate], ported case-for-case from iOS's SpotMeasurementGateTests.swift
 * (the fast-fail rule for a refused spot measurement — the R99 refuses HRV `0x0a` with status
 * `0x01` because it has no HRV sensor, and the poll must give up at once rather than spin its
 * whole window).
 */
class SpotMeasurementGateTest {

    /** The R99's case: the ring refuses the measurement we are running, and the poll gives up at once. */
    @Test
    fun `a refusal of the measurement in flight aborts it`() {
        val gate = SpotMeasurementGate()
        val hrv = gate.begin(YCBTMeasurementMode.HRV)
        assertFalse(gate.isRejected(hrv))

        gate.noteRejected(YCBTMeasurementMode.HRV)

        assertTrue(gate.isRejected(hrv))
    }

    /** It must not cancel a measurement it doesn't name — a late reply from a previous sweep, or
     *  a refusal for another mode, leaves the running measurement alone. */
    @Test
    fun `a refusal of a different mode cannot abort the one in flight`() {
        val gate = SpotMeasurementGate()
        val spo2 = gate.begin(YCBTMeasurementMode.SPO2)

        gate.noteRejected(YCBTMeasurementMode.HRV)
        gate.noteRejected(YCBTMeasurementMode.HEART_RATE)

        assertFalse("only the measurement the ring actually named may be aborted", gate.isRejected(spo2))
    }

    /** Nothing in flight ⇒ nothing to abort. This keeps a stray refusal off a workout HR stream:
     *  streaming is not a spot measurement, it never arms the gate, and it must survive anything
     *  that happens to a spot reading. */
    @Test
    fun `a refusal with no measurement in flight is ignored`() {
        val gate = SpotMeasurementGate()
        assertTrue(gate.modesInFlight.isEmpty())

        gate.noteRejected(YCBTMeasurementMode.HEART_RATE)

        assertTrue(gate.modesInFlight.isEmpty())
    }

    /** A refusal that lands after the window already closed cannot fail the *next* measurement. */
    @Test
    fun `a refusal cannot leak into the next measurement`() {
        val gate = SpotMeasurementGate()
        val hrv = gate.begin(YCBTMeasurementMode.HRV)
        gate.noteRejected(YCBTMeasurementMode.HRV)
        assertTrue(gate.isRejected(hrv))

        gate.end(hrv)
        gate.noteRejected(YCBTMeasurementMode.HRV)   // a late duplicate, after we gave up
        assertFalse("a retired measurement has no window left to cut short", gate.isRejected(hrv))

        val spo2 = gate.begin(YCBTMeasurementMode.SPO2)
        assertFalse("a fresh measurement starts clean", gate.isRejected(spo2))
    }

    /** The ring accepting a start decodes to a plain ack, so nothing ever calls noteRejected. */
    @Test
    fun `an accepted measurement is never aborted`() {
        val gate = SpotMeasurementGate()
        val spo2 = gate.begin(YCBTMeasurementMode.SPO2)

        assertFalse(gate.isRejected(spo2))
        assertEquals(setOf(YCBTMeasurementMode.SPO2), gate.modesInFlight)
    }

    /** The wrong-cancel: a workout HR poll in flight when the user's BP start is refused must not
     *  trip — a refusal aborts exactly the measurement it names, and nothing else. */
    @Test
    fun `a refusal aborts only the measurement it names when two are in flight`() {
        val gate = SpotMeasurementGate()
        val hr = gate.begin(YCBTMeasurementMode.HEART_RATE)        // the workout's timer poll
        val bp = gate.begin(YCBTMeasurementMode.BLOOD_PRESSURE)    // the user's BP reading

        gate.noteRejected(YCBTMeasurementMode.BLOOD_PRESSURE)

        assertTrue(gate.isRejected(bp))
        assertFalse("the ring refused BP — the workout's HR poll was never named", gate.isRejected(hr))
    }

    /** The missed abort: the second measurement to start must not displace the first's claim on
     *  its mode. */
    @Test
    fun `a second measurement does not displace the firsts claim on its mode`() {
        val gate = SpotMeasurementGate()
        val hr = gate.begin(YCBTMeasurementMode.HEART_RATE)
        val bp = gate.begin(YCBTMeasurementMode.BLOOD_PRESSURE)

        gate.noteRejected(YCBTMeasurementMode.HEART_RATE)

        assertTrue("HR is still in flight — its refusal must still reach it", gate.isRejected(hr))
        assertFalse(gate.isRejected(bp))
    }

    /** The premature disarm: whichever measurement finishes first may only retire its own token. */
    @Test
    fun `ending one measurement leaves the other armed`() {
        val gate = SpotMeasurementGate()
        val hr = gate.begin(YCBTMeasurementMode.HEART_RATE)
        val bp = gate.begin(YCBTMeasurementMode.BLOOD_PRESSURE)

        gate.end(bp)   // the BP reading returns first
        assertEquals(setOf(YCBTMeasurementMode.HEART_RATE), gate.modesInFlight)

        gate.noteRejected(YCBTMeasurementMode.HEART_RATE)
        assertTrue("HR was still mid-poll when the ring refused it", gate.isRejected(hr))
    }
}
