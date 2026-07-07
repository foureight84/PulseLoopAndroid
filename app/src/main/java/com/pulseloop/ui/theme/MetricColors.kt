package com.pulseloop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Shared semantic zone palette — reused across every metric so colors mean the
 * same thing everywhere.
 */
object MetricColors {
    // Aliases the iOS #35 zone tokens in [PulseColors] (AppTheme.swift zone*) so both
    // apps read the same color language: mint=optimal, cyan=normal, amber=watch,
    // red=concern, blue=low-side, orange=elevated.
    val ZoneGood        = PulseColors.zoneMint     // optimal / healthy
    val ZoneNormal      = PulseColors.zoneCyan     // normal / acceptable
    val ZoneBorderline  = PulseColors.zoneAmber    // borderline / watch
    val ZoneConcern     = PulseColors.zoneRed      // high / concerning
    val ZoneLow         = PulseColors.zoneBlue     // below normal range
    val ZoneElevated    = PulseColors.zoneOrange   // elevated / stage 1

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
