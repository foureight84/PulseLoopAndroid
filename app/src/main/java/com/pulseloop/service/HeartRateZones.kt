package com.pulseloop.service

/**
 * Ported from the HR zone calculation logic in the iOS app.
 * Max HR estimated via 220 - age formula; zones are percentage-based.
 */
object HeartRateZones {
    fun maxHR(age: Int = 30): Int = 220 - age

    enum class Zone(val label: String, val range: IntRange) {
        REST("Rest", 0..59),
        FAT_BURN("Fat Burn", 60..69),
        CARDIO("Cardio", 70..79),
        PEAK("Peak", 80..89),
        MAX("Max", 90..220),
    }

    fun zoneFor(bpm: Int, age: Int = 30): Zone {
        val max = maxHR(age)
        val pct = (bpm * 100) / max
        return when {
            pct < 60 -> Zone.REST
            pct < 70 -> Zone.FAT_BURN
            pct < 80 -> Zone.CARDIO
            pct < 90 -> Zone.PEAK
            else -> Zone.MAX
        }
    }

    /**
     * Resting heart rate estimate from a set of HR samples. Uses a low percentile of
     * the readings (default 10th) to approximate the lowest *sustained* at-rest rate —
     * more robust than a single minimum (which can be a momentary dip) and lower than a
     * daytime average. Returns null when there's no data.
     *
     * Shared by the Today panel, the Vitals HR card, and the HR detail screen so they
     * all report the same number.
     */
    fun restingHeartRate(values: List<Double>, percentile: Double = 0.10): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}

/**
 * Ported from haversine distance in LiveWorkoutManager.swift.
 */
object DistanceUtils {
    private const val R = 6_371_000.0  // Earth radius in meters

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * Math.PI / 180; val p2 = lat2 * Math.PI / 180
        val dPhi = (lat2 - lat1) * Math.PI / 180
        val dLambda = (lon2 - lon1) * Math.PI / 180
        val h = Math.sin(dPhi / 2).let { it * it } +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dLambda / 2).let { it * it }
        return 2 * R * Math.asin(minOf(1.0, Math.sqrt(h)))
    }

    fun paceSecondsPerKm(distanceMeters: Double, durationSeconds: Int): Double? {
        if (distanceMeters < 50 || durationSeconds <= 0) return null
        return durationSeconds.toDouble() / (distanceMeters / 1000.0)
    }

    /** Cumulative distance from a list of (lat, lon) points. */
    fun totalDistance(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        return points.zipWithNext().sumOf { (a, b) -> haversine(a.first, a.second, b.first, b.second) }
    }
}
