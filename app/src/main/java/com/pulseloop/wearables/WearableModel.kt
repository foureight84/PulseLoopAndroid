package com.pulseloop.wearables

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.pulseloop.R
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ui.theme.PulseColors

/**
 * Ported from WearableModel.swift (iOS #48).
 * A selectable ring model for the pairing carousel. Several models map to the same
 * family/driver; this catalog gives each a name, brand, tint, product image, and
 * one-line capability blurb so the user can swipe and say "this is my ring."
 */
data class WearableModel(
    val id: String,
    val displayName: String,
    /** Marketing brand, used to group models under the pairing screen's brand tabs. */
    val brand: String,
    val family: RingDeviceType,
    val tint: Color,
    val blurb: String,
    /** Product image for this ring; when null, [com.pulseloop.ui.components.RingArtView] falls back to a generic ring. */
    @DrawableRes val imageRes: Int? = null,
) {
    companion object {
        // "jring" is intentionally lowercase — that's how the brand styles its name.
        val JRING = WearableModel(
            id = "jring", displayName = "jring", brand = "jring", family = RingDeviceType.JRING,
            tint = PulseColors.accent, blurb = "Heart rate · SpO₂ · Sleep",
            imageRes = R.drawable.ring_jring,
        )

        // Colmi line — all share the Colmi protocol/driver
        val COLMI_R02 = colmi("colmi-r02", "Colmi R02", "Colmi", R.drawable.ring_colmi_r02)
        val COLMI_R03 = colmi("colmi-r03", "Colmi R03", "Colmi", R.drawable.ring_colmi_r03)
        val COLMI_R06 = colmi("colmi-r06", "Colmi R06", "Colmi", R.drawable.ring_colmi_r06)
        val COLMI_R07 = colmi("colmi-r07", "Colmi R07", "Colmi", R.drawable.ring_colmi_r07)
        val COLMI_R09 = colmi("colmi-r09", "Colmi R09", "Colmi", R.drawable.ring_colmi_r09)
        val COLMI_R10 = colmi("colmi-r10", "Colmi R10", "Colmi", R.drawable.ring_colmi_r10)
        // The R11 shares its product art with the Yawell R11 (same hardware, same look).
        val COLMI_R11 = colmi("colmi-r11", "Colmi R11", "Colmi", R.drawable.ring_yawell_r11)
        val COLMI_R12 = colmi("colmi-r12", "Colmi R12", "Colmi", R.drawable.ring_colmi_r12)

        // Yawell-branded variants
        val YAWELL_R05 = colmi("yawell-r05", "Yawell R05", "Yawell", R.drawable.ring_yawell_r05)
        val YAWELL_R10 = colmi("yawell-r10", "Yawell R10", "Yawell", R.drawable.ring_yawell_r10)
        val YAWELL_R11 = colmi("yawell-r11", "Yawell R11", "Yawell", R.drawable.ring_yawell_r11)
        val H59 = colmi("h59", "H59 Ring", "H59", R.drawable.ring_h59)

        private fun colmi(id: String, name: String, brand: String, @DrawableRes imageRes: Int?) = WearableModel(
            id = id, displayName = name, brand = brand, family = RingDeviceType.COLMI_R02,
            tint = PulseColors.hrv, blurb = "HR · SpO₂ · HRV · Stress · Temp · Sleep",
            imageRes = imageRes,
        )

        /** Every supported model. The pairing screen groups by brand and sorts each tab alphabetically. */
        val CATALOG: List<WearableModel> = listOf(
            COLMI_R02, COLMI_R06, COLMI_R10, YAWELL_R11, JRING,
            COLMI_R03, COLMI_R07, COLMI_R09, COLMI_R11, COLMI_R12,
            YAWELL_R05, YAWELL_R10, H59,
        )
    }
}
