package com.pulseloop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from the ZoneLineSplitter assertions in iOS ChartSampleTests (iOS #35). */
class ZoneLineSplitterTest {

    @Test
    fun `pair within one zone returns a single piece with exact endpoints`() {
        val pieces = ZoneLineSplitter.split(0.0, 65.0, 10.0, 70.0, listOf(60.0, 100.0))
        assertEquals(1, pieces.size)
        assertEquals(ValuePoint(0.0, 65.0), pieces[0].first)
        assertEquals(ValuePoint(10.0, 70.0), pieces[0].second)
    }

    @Test
    fun `crossing one boundary splits into two pieces at the interpolated point`() {
        // 90 → 110 crosses 100 at t=0.5.
        val pieces = ZoneLineSplitter.split(0.0, 90.0, 10.0, 110.0, listOf(100.0))
        assertEquals(2, pieces.size)
        assertEquals(ValuePoint(5.0, 100.0), pieces[0].second)
        assertEquals(ValuePoint(5.0, 100.0), pieces[1].first)
        assertEquals(ValuePoint(10.0, 110.0), pieces[1].second)
    }

    @Test
    fun `descending segment splits boundaries in traversal order`() {
        // 125 → 55 crosses 120, 100, 60 in that order.
        val pieces = ZoneLineSplitter.split(0.0, 125.0, 14.0, 55.0, listOf(60.0, 100.0, 120.0))
        assertEquals(4, pieces.size)
        assertEquals(120.0, pieces[0].second.value, 1e-9)
        assertEquals(100.0, pieces[1].second.value, 1e-9)
        assertEquals(60.0, pieces[2].second.value, 1e-9)
        assertEquals(55.0, pieces[3].second.value, 1e-9)
    }

    @Test
    fun `flat segment never splits even on a boundary`() {
        val pieces = ZoneLineSplitter.split(0.0, 100.0, 10.0, 100.0, listOf(100.0))
        assertEquals(1, pieces.size)
    }

    @Test
    fun `gap segmentation breaks runs at the max gap`() {
        val ts = listOf(0L, 60_000L, 120_000L, 10_000_000L, 10_060_000L)
        val runs = ZoneLineSplitter.segmentsByGap(ts, maxGapMs = 5_400_000L)
        assertEquals(listOf(0..2, 3..4), runs.map { it.first..it.last })
    }

    @Test
    fun `max gap tracks the plotted span like iOS`() {
        assertEquals(90 * 60_000L, ZoneLineSplitter.maxGapMs(24 * 3600_000L))
        assertEquals(36 * 3600_000L, ZoneLineSplitter.maxGapMs(7 * 86_400_000L))
        assertEquals(4 * 86_400_000L, ZoneLineSplitter.maxGapMs(30 * 86_400_000L))
        assertEquals(45 * 86_400_000L, ZoneLineSplitter.maxGapMs(365 * 86_400_000L))
    }
}
