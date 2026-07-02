package com.pulseloop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Shared semantic zone palette — reused across every metric so colors mean the
 * same thing everywhere.
 *
 * Status colors, not series colors: they always ship next to a text label
 * (labelFor / threshold captions), never as the only carrier of meaning.
 * Each value is contrast-checked ≥ 3:1 as a graphic on BOTH surfaces
 * (white and the dark surface 0xFF171A21) — see scratchpad contrast.py.
 */
object MetricColors {
    val ZoneGood        = Color(0xFF2E9E4F)   // optimal / healthy      (3.4 light / 5.1 dark)
    val ZoneNormal      = Color(0xFF189A8A)   // normal / acceptable    (3.5 / 5.0)
    val ZoneBorderline  = Color(0xFFC77E00)   // borderline / watch     (3.3 / 5.3)
    val ZoneConcern     = Color(0xFFE5484D)   // high / concerning      (3.9 / 4.5)
    val ZoneLow         = Color(0xFF3E8DE3)   // below normal range     (3.4 / 5.1)
    val ZoneElevated    = Color(0xFFD9730D)   // elevated / stage 1     (3.3 / 5.3)

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
