package com.pulseloop.wearables

import androidx.compose.ui.graphics.Color
import com.pulseloop.ring.RingDeviceType

/**
 * Ported from WearableModel.swift.
 * A selectable ring model for the pairing carousel. Several models map to the same
 * family/driver; this catalog gives each a name, tint, and one-line capability blurb.
 */
data class WearableModel(
    val id: String,
    val displayName: String,
    val family: RingDeviceType,
    val tint: Color,
    val blurb: String,
) {
    companion object {
        val JRING = WearableModel(
            id = "jring", displayName = "jring", family = RingDeviceType.JRING,
            tint = Color(0xFF4FC3F7), blurb = "Heart rate · SpO₂ · Sleep",
        )

        // Colmi line — all share the Colmi protocol/driver
        val COLMI_R02 = colmi("colmi-r02", "Colmi R02")
        val COLMI_R03 = colmi("colmi-r03", "Colmi R03")
        val COLMI_R06 = colmi("colmi-r06", "Colmi R06")
        val COLMI_R07 = colmi("colmi-r07", "Colmi R07")
        val COLMI_R09 = colmi("colmi-r09", "Colmi R09")
        val COLMI_R10 = colmi("colmi-r10", "Colmi R10")
        val COLMI_R12 = colmi("colmi-r12", "Colmi R12")

        // Yawell-branded variants
        val YAWELL_R05 = colmi("yawell-r05", "Yawell R05")
        val YAWELL_R10 = colmi("yawell-r10", "Yawell R10")
        val YAWELL_R11 = colmi("yawell-r11", "Yawell R11")
        val H59 = colmi("h59", "H59 Ring")

        private fun colmi(id: String, name: String) = WearableModel(
            id = id, displayName = name, family = RingDeviceType.COLMI_R02,
            tint = Color(0xFF43A047), blurb = "HR · SpO₂ · HRV · Stress · Temp · Sleep",
        )

        /** Carousel order: the most common models first. */
        val CATALOG: List<WearableModel> = listOf(
            COLMI_R02, COLMI_R06, COLMI_R10, YAWELL_R11, JRING,
            COLMI_R03, COLMI_R07, COLMI_R09, COLMI_R12,
            YAWELL_R05, YAWELL_R10, H59,
        )
    }
}
