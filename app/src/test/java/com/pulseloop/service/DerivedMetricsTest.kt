package com.pulseloop.service

import org.junit.Assert.*
import org.junit.Test

class DerivedMetricsTest {
    @Test
    fun `sdnn of constant HR is zero, needs 60 samples`() {
        assertNull(sdnnProxy(List(59) { 60 }))
        assertEquals(0.0, sdnnProxy(List(60) { 60 })!!, 0.001)
    }

    @Test
    fun `sdnn grows with variability`() {
        val calm = sdnnProxy(List(120) { if (it % 2 == 0) 60 else 61 })!!
        val jumpy = sdnnProxy(List(120) { if (it % 2 == 0) 55 else 70 })!!
        assertTrue(jumpy > calm)
    }

    @Test
    fun `stress zero at baseline, rises with HR elevation`() {
        assertEquals(0, stressScore(60.0, 60.0, null, null))
        val elevated = stressScore(75.0, 60.0, null, null)!!   // +25% HR
        assertTrue(elevated in 50..70)
        assertNull(stressScore(null, 60.0, null, null))
    }

    @Test
    fun `fatigue needs sleep signal and scales with deficit`() {
        assertNull(fatigueScore(null, 62.0, 60.0, null, null))
        val rested = fatigueScore(480, 60.0, 60.0, null, null)!!
        val wrecked = fatigueScore(240, 66.0, 60.0, null, null)!!
        assertTrue(rested < 10)
        assertTrue(wrecked > rested + 20)
    }
}
