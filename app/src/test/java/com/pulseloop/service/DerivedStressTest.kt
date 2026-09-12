package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #67: a stress figure derived from HRV for rings that never report one. */
class DerivedStressTest {

    /** 40 ms median, spread of 5. */
    private val baseline = listOf(30.0, 35.0, 35.0, 40.0, 40.0, 40.0, 40.0, 45.0, 45.0, 50.0, 40.0, 38.0)

    @Test
    fun `a reading at the user's own median scores mid-scale`() {
        val reading = DerivedStress.score(40.0, baseline)
        assertEquals(50, reading!!.score)
    }

    @Test
    fun `lower HRV than usual reads as more stress and higher as less`() {
        val stressed = DerivedStress.score(30.0, baseline)!!.score
        val calm = DerivedStress.score(50.0, baseline)!!.score

        assertTrue("below the median is above mid-scale", stressed > 50)
        assertTrue("above the median is below mid-scale", calm < 50)
        assertEquals("and symmetric about it", 100, stressed + calm)
    }

    /**
     * The score is relative to the person: HRV varies several-fold between individuals, so the same
     * absolute reading must not label one person calm and another stressed.
     */
    @Test
    fun `the same HRV scores differently against different people`() {
        val lowHrvPerson = List(12) { 20.0 + (it % 3) }
        val highHrvPerson = List(12) { 90.0 + (it % 3) }

        val againstLow = DerivedStress.score(40.0, lowHrvPerson)!!.score
        val againstHigh = DerivedStress.score(40.0, highHrvPerson)!!.score

        assertTrue("well above their normal reads calm", againstLow < 50)
        assertTrue("well below their normal reads stressed", againstHigh > 50)
    }

    @Test
    fun `no score without enough baseline`() {
        assertNull(DerivedStress.score(40.0, baseline.take(DerivedStress.MIN_BASELINE_SAMPLES - 1)))
        assertNull(DerivedStress.score(40.0, emptyList()))
        assertNull("a missing reading is not a zero", DerivedStress.score(0.0, baseline))
    }

    /** Outliers are what ring HRV history is full of; one must not redefine the scale. */
    @Test
    fun `an extreme outlier does not swamp the baseline`() {
        val withOutlier = baseline + 400.0

        val normal = DerivedStress.score(40.0, baseline)!!.score
        val withJunk = DerivedStress.score(40.0, withOutlier)!!.score

        assertTrue("the median-based scale barely moves", kotlin.math.abs(normal - withJunk) <= 5)
    }

    @Test
    fun `a flat baseline still responds rather than snapping to the ends`() {
        val flat = List(12) { 50.0 }

        val slightlyLow = DerivedStress.score(48.0, flat)!!.score

        assertTrue(slightlyLow in 51..99)
    }

    @Test
    fun `the score is always bounded and always marked derived`() {
        assertEquals(100, DerivedStress.score(1.0, baseline)!!.score)
        assertEquals(0, DerivedStress.score(500.0, baseline)!!.score)
        assertTrue(DerivedStress.score(40.0, baseline)!!.derived)
    }

    /** The series scores each reading against only what came before it — no hindsight. */
    @Test
    fun `the series skips readings with no baseline behind them`() {
        val hrv = List(20) { 40.0 }
        val series = DerivedStress.series(hrv)

        assertEquals(20 - DerivedStress.MIN_BASELINE_SAMPLES, series.size)
    }

    /**
     * Because the first readings are skipped, a caller cannot zip the scores back onto the HRV
     * series positionally — the index has to travel with the score or every point on the chart
     * lands at the wrong time.
     */
    @Test
    fun `scored names the reading each score came from`() {
        val hrv = List(20) { 40.0 }

        val scored = DerivedStress.scored(hrv)

        assertEquals(DerivedStress.MIN_BASELINE_SAMPLES, scored.first().first)
        assertEquals(hrv.lastIndex, scored.last().first)
        assertEquals(DerivedStress.series(hrv), scored.map { it.second })
    }
}
