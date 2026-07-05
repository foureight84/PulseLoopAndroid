package com.pulseloop.ui.components

/**
 * Ported from [ZoneLineSplitter] in ChartSample.swift (iOS #35).
 * Pure value-space math for the zone-colored chart line: splitting sample-to-sample
 * segments at zone boundaries (so each drawn piece lies entirely within one zone and
 * takes that zone's color) and breaking the line across data gaps.
 */

/** A point in pure value space: `x` is any linear coordinate (epoch millis, px…). */
data class ValuePoint(val x: Double, val value: Double)

object ZoneLineSplitter {
    /**
     * Split the segment `(x0,v0)→(x1,v1)` at every threshold strictly between the values,
     * so each returned piece lies entirely within one zone. Crossing points are linearly
     * interpolated in both coordinates. A pair within a single zone returns one piece.
     * Endpoints are preserved exactly. [thresholds] should be sorted ascending.
     */
    fun split(x0: Double, v0: Double, x1: Double, v1: Double, thresholds: List<Double>): List<Pair<ValuePoint, ValuePoint>> {
        val a = ValuePoint(x0, v0)
        val b = ValuePoint(x1, v1)
        val lo = minOf(v0, v1)
        val hi = maxOf(v0, v1)
        val crossings = thresholds
            .filter { it > lo && it < hi }
            .sortedWith(if (v0 <= v1) naturalOrder() else reverseOrder())

        if (crossings.isEmpty() || v0 == v1) return listOf(a to b)

        val pieces = mutableListOf<Pair<ValuePoint, ValuePoint>>()
        var current = a
        for (threshold in crossings) {
            val point = interpolate(a, b, threshold)
            pieces.add(current to point)
            current = point
        }
        pieces.add(current to b)
        return pieces
    }

    /** The point on segment a→b where the value equals [target] (assumed between the values). */
    private fun interpolate(a: ValuePoint, b: ValuePoint, target: Double): ValuePoint {
        val span = b.value - a.value
        if (span == 0.0) return a
        val t = (target - a.value) / span
        return ValuePoint(a.x + (b.x - a.x) * t, target)
    }

    /**
     * Group sample indices into contiguous runs, breaking wherever consecutive timestamps
     * are more than [maxGapMs] apart — a broken line reads as "no data here" instead of a
     * misleading straight bridge across hours of nothing. Mirrors ChartSampleBuilder.segments.
     */
    fun segmentsByGap(timestamps: List<Long>, maxGapMs: Long): List<IntRange> {
        if (timestamps.isEmpty()) return emptyList()
        val runs = mutableListOf<IntRange>()
        var start = 0
        for (i in 1 until timestamps.size) {
            if (timestamps[i] - timestamps[i - 1] > maxGapMs) {
                runs.add(start until i)
                start = i
            }
        }
        runs.add(start until timestamps.size)
        return runs
    }

    /**
     * The maximum allowed gap before the line breaks, tuned to the plotted span so denser
     * windows break sooner. Mirrors ChartSampleBuilder.maxGap: 24h→90 min, 7d→36 h,
     * 30d→4 days, longer→45 days.
     */
    fun maxGapMs(spanMs: Long): Long = when {
        spanMs <= 26 * 3600_000L -> 90 * 60_000L
        spanMs <= 8 * 86_400_000L -> 36 * 3600_000L
        spanMs <= 32 * 86_400_000L -> 4 * 86_400_000L
        else -> 45 * 86_400_000L
    }
}
