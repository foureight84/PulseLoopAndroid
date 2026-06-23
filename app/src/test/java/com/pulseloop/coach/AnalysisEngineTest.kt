package com.pulseloop.coach.tools

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Ported from AnalysisEngineTests in CoachTests.swift.
 * Tests trend detection, correlation, outliers, and distribution.
 */
class AnalysisEngineTest {

    @Test
    fun testTrendRising() {
        val values = listOf(0.0, 100.0, 200.0, 300.0, 400.0)
        val result = AnalysisEngine.trend(values)
        assertEquals("rising", result.direction)
        assertEquals(400.0, result.changeAbsolute ?: 0.0, 0.01)
        assertEquals(5, result.count)
    }

    @Test
    fun testTrendFalling() {
        val values = listOf(400.0, 300.0, 200.0, 100.0, 0.0)
        val result = AnalysisEngine.trend(values)
        assertEquals("falling", result.direction)
        assertEquals(-400.0, result.changeAbsolute ?: 0.0, 0.01)
    }

    @Test
    fun testTrendFlat() {
        val values = listOf(100.0, 100.0, 100.0, 100.0)
        val result = AnalysisEngine.trend(values)
        assertEquals("flat", result.direction)
        assertEquals(0.0, result.slopePerDay, 0.001)
    }

    @Test
    fun testTrendSingleValue() {
        val values = listOf(50.0)
        val result = AnalysisEngine.trend(values)
        assertEquals("flat", result.direction)
        assertEquals(1, result.count)
    }

    @Test
    fun testTrendEmpty() {
        val result = AnalysisEngine.trend(emptyList())
        assertEquals("flat", result.direction)
        assertEquals(0, result.count)
    }

    @Test
    fun testCorrelationPerfectPositive() {
        val r = AnalysisEngine.correlation(listOf(1.0 to 2.0, 2.0 to 4.0, 3.0 to 6.0, 4.0 to 8.0))
        assertNotNull("Pearson should not be null", r.pearson)
        assertEquals(1.0, r.pearson!!, 0.001)
        assertEquals("strong", r.strength)
    }

    @Test
    fun testCorrelationPerfectNegative() {
        val r = AnalysisEngine.correlation(listOf(1.0 to 8.0, 2.0 to 6.0, 3.0 to 4.0, 4.0 to 2.0))
        assertNotNull(r.pearson)
        assertEquals(-1.0, r.pearson!!, 0.001)
    }

    @Test
    fun testCorrelationInsufficientData() {
        val r = AnalysisEngine.correlation(listOf(1.0 to 2.0, 2.0 to 4.0))
        assertNull("Need >= 3 pairs", r.pearson)
        assertEquals("none", r.strength)
    }

    @Test
    fun testDistribution() {
        val d = AnalysisEngine.distribution(listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(3.0, d.median ?: 0.0, 0.01)
        assertEquals(1.0, d.min ?: 0.0, 0.01)
        assertEquals(5.0, d.max ?: 0.0, 0.01)
        assertEquals(5, d.count)
    }

    @Test
    fun testDistributionEvenCount() {
        val d = AnalysisEngine.distribution(listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(2.5, d.median ?: 0.0, 0.01)
    }

    @Test
    fun testDistributionEmpty() {
        val d = AnalysisEngine.distribution(emptyList())
        assertNull(d.mean)
        assertEquals(0, d.count)
    }

    @Test
    fun testOutlierDetected() {
        val values = List(8) { 100.0 } + 1000.0
        val result = AnalysisEngine.outliers(values)
        assertTrue("Should detect the 1000.0 outlier", result.isNotEmpty())
        assertEquals(1000.0, result[0].value, 0.01)
        assertTrue(result[0].zScore > 2.0)
    }

    @Test
    fun testOutlierNotDetectedInUniformData() {
        val values = List(10) { 100.0 }
        val result = AnalysisEngine.outliers(values)
        assertTrue("Uniform data has no outliers", result.isEmpty())
    }

    @Test
    fun testOutlierInsufficientData() {
        val result = AnalysisEngine.outliers(listOf(1.0, 2.0))
        assertTrue("Need >= 4 points", result.isEmpty())
    }

    @Test
    fun testComparePeriods() {
        val a = listOf(100.0, 110.0, 120.0)
        val b = listOf(130.0, 140.0, 150.0)
        val result = AnalysisEngine.comparePeriods(a, b)
        assertEquals(110.0, result.aAverage ?: 0.0, 0.01)
        assertEquals(140.0, result.bAverage ?: 0.0, 0.01)
        assertEquals("up", result.direction)
    }
}

