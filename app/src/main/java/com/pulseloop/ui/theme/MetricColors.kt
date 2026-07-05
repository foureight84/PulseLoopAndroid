package com.pulseloop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Shared semantic zone palette — reused across every metric so colors mean the
 * same thing everywhere.
 */
object MetricColors {
    // Values match the iOS #35 zone palette (AppTheme.swift / ZonePalette.kt) so both
    // apps read the same color language: mint=optimal, cyan=normal, amber=watch,
    // red=concern, blue=low-side, orange=elevated.
    val ZoneGood        = Color(0xFF35E0A1)   // optimal / healthy (zoneMint)
    val ZoneNormal      = Color(0xFF4DDCFF)   // normal / acceptable (zoneCyan)
    val ZoneBorderline  = Color(0xFFFFB86B)   // borderline / watch (zoneAmber)
    val ZoneConcern     = Color(0xFFFF4D6D)   // high / concerning (zoneRed)
    val ZoneLow         = Color(0xFF4DA3FF)   // below normal range (zoneBlue)
    val ZoneElevated    = Color(0xFFFF8A4C)   // elevated / stage 1 (zoneOrange)

    /** For rendering — maps a zone color to a description for the trend read. */
    fun labelFor(color: Color): String = when (color) {
        ZoneGood       -> "good"
        ZoneNormal     -> "normal"
        ZoneBorderline -> "borderline"
        ZoneConcern    -> "concerning"
        ZoneLow        -> "low"
        ZoneElevated   -> "elevated"
        else           -> "unknown"
    }
}
