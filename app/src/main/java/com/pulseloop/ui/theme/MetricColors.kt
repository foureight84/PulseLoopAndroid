package com.pulseloop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Shared semantic zone palette — reused across every metric so colors mean the
 * same thing everywhere.
 */
object MetricColors {
    val ZoneGood        = Color(0xFF43A047)   // optimal / healthy
    val ZoneNormal      = Color(0xFF00897B)   // normal / acceptable
    val ZoneBorderline  = Color(0xFFFFB300)   // borderline / watch
    val ZoneConcern     = Color(0xFFE53935)   // high / concerning
    val ZoneLow         = Color(0xFF1E88E5)   // below normal range (low)
    val ZoneElevated    = Color(0xFFFB8C00)   // elevated / stage 1

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
