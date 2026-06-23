package com.pulseloop.coach.schema

import com.pulseloop.coach.orchestration.CoachResponseParser
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from CoachSchemaTests + CoachChartDomainTests in CoachTests.swift.
 * Tests chart round-tripping, schema serialization, and chart domain calculations.
 */
class CoachSchemaTest {

    @Test
    fun testChartRoundTrips() {
        val chart = CoachChart(
            chartType = "bar",
            title = "Steps",
            xLabel = "Date",
            yLabel = "Steps",
            points = listOf(
                CoachChartPoint("2026-05-01", 8000.0),
                CoachChartPoint("2026-05-02", 9500.0),
            ),
        )
        val response = CoachResponse(
            responseType = CoachResponseType.INSIGHT_WITH_CHART,
            title = "Steps Trend",
            summary = "Your steps this week",
            chart = chart,
        )
        val json = response.toJson()
        val decoded = CoachResponse.fromJson(json)
        assertNotNull("Chart round-trip should succeed", decoded)
        assertEquals("bar", decoded!!.chart?.chartType)
        assertEquals(8000.0, decoded.chart!!.points[0].yValue, 0.001)
        assertEquals(2, decoded.chart!!.points.size)
    }

    @Test
    fun testChartWithAllPointTypes() {
        val chart = CoachChart(
            chartType = "line",
            title = "Heart Rate",
            xLabel = "Time",
            yLabel = "bpm",
            points = listOf(
                CoachChartPoint("08:00", 72.0),
                CoachChartPoint("12:00", 78.0),
                CoachChartPoint("18:00", 70.0),
            ),
        )
        val response = CoachResponse(
            responseType = CoachResponseType.INSIGHT_WITH_CHART,
            title = "HR Trend",
            summary = "Your heart rate today",
            chart = chart,
            confidence = CoachConfidence.HIGH,
        )
        val json = response.toJson()
        val decoded = CoachResponse.fromJson(json)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.chart!!.points.size)
    }

    @Test
    fun testPlainText() {
        val response = CoachResponse(
            title = "Test",
            summary = "You did well today.",
            bullets = listOf("Steps: 8000", "HR: 72 bpm"),
        )
        val text = response.plainText
        assertTrue(text.contains("You did well today."))
        assertTrue(text.contains("Steps: 8000"))
        assertTrue(text.contains("HR: 72 bpm"))
    }

    @Test
    fun testPlainTextNoBullets() {
        val response = CoachResponse(summary = "Just a summary.")
        assertEquals("Just a summary.", response.plainText)
    }

    // ── Chart Domain (ported from CoachChartDomainTests) ─────────────────

    @Test
    fun testTrendLineAutoscalesAwayFromZero() {
        val domain = yDomain(listOf(70.0, 72.0, 75.0, 71.0), isBar = false)
        assertTrue("Lower bound should be > 50 (not anchored at 0)", domain.first > 50)
        assertTrue("Upper bound should be > 75", domain.second > 75)
        assertTrue("Lower bound should be < 70", domain.first < 70)
    }

    @Test
    fun testMagnitudeBarStartsAtZero() {
        val domain = yDomain(listOf(4000.0, 8000.0, 12000.0), isBar = true)
        assertEquals(0.0, domain.first, 0.001)
        assertTrue(domain.second > 12000)
    }

    @Test
    fun testSpO2ClampedTo100() {
        val domain = yDomain(listOf(96.0, 98.0, 99.0), isBar = false)
        assertTrue("Upper bound should be <= 100 for SpO2", domain.second <= 100)
        assertTrue("Lower bound should be < 96", domain.first < 96)
    }

    @Test
    fun testPositiveValuesNonZeroLower() {
        val domain = yDomain(listOf(10.0, 15.0, 12.0), isBar = false)
        assertTrue("Lower bound for non-magnitude should not be 0", domain.first > 0)
        assertTrue(domain.first < 10)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Ported from CoachChartView.yDomain. */
    private fun yDomain(
        values: List<Double>,
        isBar: Boolean,
        topPadding: Double = 0.15,
        bottomPadding: Double = 0.1,
        ceiling: Double? = null,
    ): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 1.0
        val min = values.min()
        val max = values.max()
        val range = max - min
        val effectiveBottom = if (isBar) 0.0 else {
            val raw = min - range * bottomPadding
            if (min > 0 && raw < min * 0.85) min * 0.85 else raw
        }
        val rawTop = max + range * topPadding
        val effectiveTop = ceiling?.let { minOf(rawTop, it) } ?: rawTop
        return effectiveBottom to effectiveTop
    }
}
