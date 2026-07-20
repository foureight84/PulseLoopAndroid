package com.pulseloop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsBaseball
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.pulseloop.settings.UnitSystem

/**
 * Activity-type metadata, ported from ActivityMeta (PulseModels.swift). The type order matches
 * iOS (dance sits between yoga and hike — iOS #11). SF Symbols map to Material equivalents.
 */
object ActivityMeta {
    /** Selection order in the record picker (iOS RecordSelectView). */
    val ORDER = listOf("walk", "run", "cycle", "gym", "squash", "sport", "yoga", "dance", "hike", "other")

    fun label(type: String): String = when (type) {
        "walk" -> "Walking"
        "run" -> "Running"
        "cycle" -> "Cycling"
        "gym" -> "Gym"
        "squash" -> "Squash"
        "sport" -> "Sport"
        "yoga" -> "Yoga"
        "dance" -> "Dance"
        "hike" -> "Hiking"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    fun helper(type: String): String = when (type) {
        "walk" -> "Casual or brisk walking"
        "run" -> "Outdoor or treadmill runs"
        "cycle" -> "Road, trail, or stationary"
        "gym" -> "Strength or circuit training"
        "squash" -> "Court sessions"
        "sport" -> "Team or racket sports"
        "yoga" -> "Flow, stretch, or breathwork"
        "dance" -> "Studio, cardio, or freestyle"
        "hike" -> "Trails and elevation"
        else -> "Anything else"
    }

    fun icon(type: String): ImageVector = when (type) {
        "walk" -> Icons.AutoMirrored.Filled.DirectionsWalk
        "run" -> Icons.AutoMirrored.Filled.DirectionsRun
        "cycle" -> Icons.AutoMirrored.Filled.DirectionsBike
        "gym" -> Icons.Filled.FitnessCenter
        "squash" -> Icons.Filled.SportsBaseball
        "sport" -> Icons.Filled.SportsSoccer
        "yoga" -> Icons.Filled.SelfImprovement
        "dance" -> Icons.Filled.MusicNote
        "hike" -> Icons.Filled.Hiking
        else -> Icons.Filled.Star
    }

    /** Whether the type records a GPS route (dance/yoga/gym are indoor — iOS #11; sport is
     *  GPS-capable on iOS — Components.swift `"sport": ActivityKind(..., gpsCapable: true)`). */
    fun gpsCapable(type: String): Boolean = type in setOf("walk", "run", "cycle", "hike", "sport")

    /**
     * Ported from ActivityMetricSet (WorkoutMetricsEngine.swift): which stats a workout of a
     * given type surfaces, live and in the summary — pace for foot activities, speed for
     * cycling ("min/km reads oddly on a bike"), neither for court/studio workouts.
     */
    data class ActivityMetricSet(
        val showsDistance: Boolean,
        val showsPace: Boolean,
        val showsSpeed: Boolean,
        val showsElevation: Boolean,
        val showsSplits: Boolean,
    )

    fun metricSet(type: String): ActivityMetricSet = when (type) {
        "walk" -> ActivityMetricSet(showsDistance = true, showsPace = true, showsSpeed = false, showsElevation = false, showsSplits = true)
        "run", "hike" -> ActivityMetricSet(showsDistance = true, showsPace = true, showsSpeed = false, showsElevation = true, showsSplits = true)
        "cycle" -> ActivityMetricSet(showsDistance = true, showsPace = false, showsSpeed = true, showsElevation = true, showsSplits = true)
        "sport" -> ActivityMetricSet(showsDistance = true, showsPace = false, showsSpeed = false, showsElevation = false, showsSplits = false)
        else -> ActivityMetricSet(showsDistance = false, showsPace = false, showsSpeed = false, showsElevation = false, showsSplits = false)
    }

    /** "38:00" or "1:02:10" from seconds. */
    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * "6:14" pace (min:sec per display unit) or null when distance/duration are missing.
     * Rounds seconds *before* the min/sec split so 299.85 s shows 5:00, not 4:00 (iOS #43).
     */
    fun pace(distanceMeters: Double?, durationSeconds: Int?, units: UnitSystem): String? {
        if (distanceMeters == null || durationSeconds == null) return null
        val perUnit = if (units == UnitSystem.IMPERIAL) 1609.344 else 1000.0
        val unitsCovered = distanceMeters / perUnit
        if (unitsCovered <= 0.01) return null
        val total = (durationSeconds / unitsCovered).let { Math.round(it).toInt() }
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun paceUnit(units: UnitSystem): String = if (units == UnitSystem.IMPERIAL) "/mi" else "/km"

    /**
     * Average speed for cycling (iOS LivePaceTile / RecordSummaryComponents hero), or null when
     * there's not enough data — iOS requires distance ≥ 50m and a positive elapsed, same floor
     * as its pace/distance display.
     */
    fun speed(distanceMeters: Double?, durationSeconds: Int?, units: UnitSystem): String? =
        speedParts(distanceMeters, durationSeconds, units)?.let { "${it.first} ${it.second.lowercase()}" }

    /** [speed] split into value and label parts ("8.1" to "MPH") for hero-style display. */
    fun speedParts(distanceMeters: Double?, durationSeconds: Int?, units: UnitSystem): Pair<String, String>? {
        if (distanceMeters == null || durationSeconds == null || durationSeconds <= 0 || distanceMeters < 50) return null
        val mps = distanceMeters / durationSeconds
        return if (units == UnitSystem.IMPERIAL) "%.1f".format(mps * 2.23694) to "MPH"
        else "%.1f".format(mps * 3.6) to "KM/H"
    }
}
