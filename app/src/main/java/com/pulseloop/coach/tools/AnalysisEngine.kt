package com.pulseloop.coach.tools

import kotlin.math.*

/**
 * Ported from AnalysisEngine.swift.
 * Deterministic analysis over numeric series — trend, correlation, outliers, distribution,
 * and period comparison. Pure logic — zero Android dependencies.
 */
object AnalysisEngine {
    data class TrendResult(
        val count: Int,
        val direction: String,          // "rising" | "falling" | "flat"
        val slopePerDay: Double,
        val first: Double?,
        val last: Double?,
        val changeAbsolute: Double?,
        val changePercent: Double?,
        val average: Double?,
    )

    data class ComparisonResult(
        val aAverage: Double?,
        val bAverage: Double?,
        val aCount: Int,
        val bCount: Int,
        val deltaAbsolute: Double?,
        val deltaPercent: Double?,
        val direction: String,          // "up" | "down" | "flat"
    )

    data class CorrelationResult(
        val pairs: Int,
        val pearson: Double?,
        val strength: String,           // "strong" | "moderate" | "weak" | "none"
        val note: String,
    )

    data class Outlier(
        val value: Double,
        val zScore: Double,
    )

    data class DistributionResult(
        val count: Int,
        val mean: Double?,
        val median: Double?,
        val min: Double?,
        val max: Double?,
        val stddev: Double?,
        val p25: Double?,
        val p75: Double?,
    )

    fun trend(values: List<Double>): TrendResult {
        if (values.size < 2) {
            return TrendResult(values.size, "flat", 0.0,
                values.firstOrNull(), values.lastOrNull(), 0.0, null, values.firstOrNull())
        }
        val n = values.size.toDouble()
        val xs = (0 until values.size).map { it.toDouble() }
        val meanX = xs.average()
        val meanY = values.average()
        val cov = xs.zip(values).sumOf { (x, y) -> (x - meanX) * (y - meanY) }
        val varX = xs.sumOf { (it - meanX).pow(2) }
        val slope = if (varX == 0.0) 0.0 else cov / varX
        val first = values.first()
        val last = values.last()
        val change = last - first
        val pct = if (first == 0.0) null else (change / abs(first) * 100).round(1)
        val direction = when {
            abs(slope) < 0.0001 -> "flat"
            slope > 0 -> "rising"
            else -> "falling"
        }
        return TrendResult(values.size, direction, slope.round(3),
            first, last, change.round(2), pct, meanY.round(2))
    }

    fun correlation(pairs: List<Pair<Double, Double>>): CorrelationResult {
        if (pairs.size < 3) {
            return CorrelationResult(pairs.size, null, "none",
                "Need at least 3 overlapping days to correlate.")
        }
        val xs = pairs.map { it.first }; val ys = pairs.map { it.second }
        val n = pairs.size.toDouble()
        val mx = xs.average(); val my = ys.average()
        val cov = xs.zip(ys).sumOf { (x, y) -> (x - mx) * (y - my) }
        val sx = sqrt(xs.sumOf { (it - mx).pow(2) })
        val sy = sqrt(ys.sumOf { (it - my).pow(2) })
        if (sx == 0.0 || sy == 0.0) {
            return CorrelationResult(pairs.size, null, "none", "One series has no variation.")
        }
        val r = (cov / (sx * sy)).round(3)
        val strength = when {
            abs(r) >= 0.6 -> "strong"
            abs(r) >= 0.3 -> "moderate"
            abs(r) >= 0.1 -> "weak"
            else -> "none"
        }
        return CorrelationResult(pairs.size, r, strength, "Correlation is not causation; small sample.")
    }

    fun outliers(values: List<Double>, threshold: Double = 2.0): List<Outlier> {
        if (values.size < 4) return emptyList()
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
        if (sd == 0.0) return emptyList()
        return values.mapNotNull { v ->
            val z = (v - mean) / sd
            if (abs(z) >= threshold) Outlier(v, z.round(2)) else null
        }
    }

    fun distribution(values: List<Double>): DistributionResult {
        if (values.isEmpty()) {
            return DistributionResult(0, null, null, null, null, null, null, null)
        }
        val sorted = values.sorted()
        val n = values.size.toDouble()
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean).pow(2) } / n)
        return DistributionResult(
            count = values.size,
            mean = mean.round(2),
            median = percentile(sorted, 0.5).round(2),
            min = sorted.first(), max = sorted.last(),
            stddev = sd.round(2),
            p25 = percentile(sorted, 0.25).round(2),
            p75 = percentile(sorted, 0.75).round(2),
        )
    }

    fun comparePeriods(a: List<Double>, b: List<Double>): ComparisonResult {
        val aAvg = if (a.isEmpty()) null else a.average().round(2)
        val bAvg = if (b.isEmpty()) null else b.average().round(2)
        var delta: Double? = null; var pct: Double? = null; var direction = "flat"
        if (aAvg != null && bAvg != null) {
            delta = (bAvg - aAvg).round(2)
            pct = if (aAvg == 0.0) null else ((bAvg - aAvg) / abs(aAvg) * 100).round(1)
            direction = when {
                abs(bAvg - aAvg) < 0.0001 -> "flat"
                bAvg > aAvg -> "up"
                else -> "down"
            }
        }
        return ComparisonResult(aAvg, bAvg, a.size, b.size, delta, pct, direction)
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val rank = p * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = (rank + 0.5).toInt().coerceAtMost(sorted.size - 1)
        val frac = rank - lo.toDouble()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    private fun Double.round(places: Int): Double {
        val p = 10.0.pow(places)
        return round(this * p) / p
    }
}
