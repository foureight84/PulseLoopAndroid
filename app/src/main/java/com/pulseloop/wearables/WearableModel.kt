package com.pulseloop.wearables

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.pulseloop.R
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ui.theme.PulseColors

/**
 * Ported from WearableModel.swift (iOS #48, exact-model identification iOS #49).
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
    /**
     * Bluetooth local-name patterns that identify this exact product model. Protocol-family
     * matching remains the coordinator's job; these patterns are only for user-facing identity.
     */
    val advertisedNamePatterns: List<String>,
    /** Product image for this ring; when null, [com.pulseloop.ui.components.RingArtView] falls back to a generic ring. */
    @DrawableRes val imageRes: Int? = null,
    /**
     * Whether this model needs an OS-level bond (`createBond`) to hold a stable Android link.
     * True only for models with a demonstrated GATT-only fragility — currently just the Colmi
     * R09, which connects once then can't re-sync unless bonded (see docs/qring-ble-adoption.md
     * §5a). Every other model works GATT-only exactly like iOS (which bonds nothing), so we
     * leave them unbonded to avoid the pairing prompt and the "Disconnect doesn't release it"
     * problem. Deliberately narrower than QRing, which bonds every `supportBlePair` ring.
     * Expand this only when a model is shown to need it on real hardware.
     */
    val requiresOsBond: Boolean = false,
) {
    companion object {
        // "jring" is intentionally lowercase — that's how the brand styles its name.
        val JRING = WearableModel(
            id = "jring", displayName = "jring", brand = "jring", family = RingDeviceType.JRING,
            tint = PulseColors.accent, blurb = "HR · SpO₂ · Sleep",
            advertisedNamePatterns = listOf("^SMART_RING$"),
            imageRes = R.drawable.ring_jring,
        )

        // Colmi line — all share the Colmi protocol/driver
        val COLMI_R02 = colmi("colmi-r02", "Colmi R02", "Colmi", "^R02_.*", R.drawable.ring_colmi_r02)
        val COLMI_R03 = colmi("colmi-r03", "Colmi R03", "Colmi", "^R03_.*", R.drawable.ring_colmi_r03)
        val COLMI_R06 = colmi("colmi-r06", "Colmi R06", "Colmi", "^R06_.*", R.drawable.ring_colmi_r06)
        val COLMI_R07 = colmi("colmi-r07", "Colmi R07", "Colmi", "^COLMI R07_.*", R.drawable.ring_colmi_r07)
        val COLMI_R08 = colmi("colmi-r08", "Colmi R08", "Colmi", "^R08_.*", R.drawable.ring_colmi_r08)
        // R09 is the one model that needs an OS bond to hold a stable Android link (see
        // WearableModel.requiresOsBond).
        val COLMI_R09 = colmi("colmi-r09", "Colmi R09", "Colmi", "^R09_.*", R.drawable.ring_colmi_r09,
            requiresOsBond = true)
        val COLMI_R10 = colmi("colmi-r10", "Colmi R10", "Colmi", "^COLMI R10_.*", R.drawable.ring_colmi_r10)
        // The R11 shares its product art with the Yawell R11 (same hardware, same look).
        val COLMI_R11 = colmi("colmi-r11", "Colmi R11", "Colmi", "^R11C_[0-9A-F]{4}$", R.drawable.ring_yawell_r11)
        val COLMI_R12 = colmi("colmi-r12", "Colmi R12", "Colmi", "^COLMI R12_.*", R.drawable.ring_colmi_r12)

        // Yawell-branded variants
        val YAWELL_R05 = colmi("yawell-r05", "Yawell R05", "Yawell", "^R05_[0-9A-F]{4}$", R.drawable.ring_yawell_r05)
        val YAWELL_R10 = colmi("yawell-r10", "Yawell R10", "Yawell", "^R10_[0-9A-F]{4}$", R.drawable.ring_yawell_r10)
        val YAWELL_R11 = colmi("yawell-r11", "Yawell R11", "Yawell", "^R11_[0-9A-F]{4}$", R.drawable.ring_yawell_r11)
        val H59 = colmi("h59", "H59 Ring", "H59", "^H59_.*", R.drawable.ring_h59)

        // YCBT protocol family (iOS #82): TK5 + Colmi/Yawell rings that ship with the
        // SmartHealth app instead of QRing. No dedicated product art yet — falls back to the
        // generic ring silhouette.
        val TK5 = WearableModel(
            id = "tk5", displayName = "TK5", brand = "TK5", family = RingDeviceType.TK5,
            tint = PulseColors.hrv, blurb = "HR · SpO₂ · HRV · Sleep",
            advertisedNamePatterns = listOf("^TK5 [0-9A-Fa-f]{4}$"),
        )

        /**
         * Same physical Colmi/Yawell line as [COLMI_R02] etc., but the SmartHealth-flavoured
         * firmware speaks YCBT, not QRing — a different protocol family entirely (see
         * `ColmiSmartHealthCoordinator`). Distinguished from every QRing-Colmi pattern above by the
         * SmartHealth naming convention (space before the trailing hex, not underscore): `R99 54DC`
         * vs `R02_A1B2`. Deliberately generic (one card for the whole naming convention, not one per
         * model) since only one SmartHealth-Colmi unit has ever been seen — a false-positive match
         * costs one extra pairing-list row, not a broken connection, since the coordinator itself
         * (not this catalog entry) is what a real device ultimately connects through.
         */
        val COLMI_SMARTHEALTH = WearableModel(
            id = "colmi-smarthealth", displayName = "Colmi / Yawell (SmartHealth app)", brand = "Colmi",
            family = RingDeviceType.COLMI_SMART_HEALTH,
            tint = PulseColors.hrv, blurb = "HR · SpO₂ · Sleep",
            advertisedNamePatterns = listOf("^[A-Za-z0-9]+( [A-Za-z0-9]+)* [0-9A-Fa-f]{4}$"),
        )

        // TK18 -- the LuckRing app / "K6" protocol (company ID 0xFF64). The only hardware-tested unit
        // of the whole 0xFF64 family, so it stays limited-support. No dedicated product art yet --
        // falls back to the generic ring silhouette, same as TK5.
        val LUCK_RING_TK18 = WearableModel(
            id = "luckring-tk18", displayName = "TK18", brand = "LuckRing", family = RingDeviceType.LUCK_RING,
            tint = PulseColors.hrv, blurb = "HR · SpO₂ · HRV · Temp · BP · Sleep · Steps",
            advertisedNamePatterns = listOf("^TK18([ _-].*)?$"),
        )

        private fun colmi(
            id: String,
            name: String,
            brand: String,
            pattern: String,
            @DrawableRes imageRes: Int?,
            requiresOsBond: Boolean = false,
        ) = WearableModel(
            id = id, displayName = name, brand = brand, family = RingDeviceType.COLMI_R02,
            tint = PulseColors.hrv, blurb = "HR · SpO₂ · HRV · Stress · Temp · Sleep",
            advertisedNamePatterns = listOf(pattern),
            imageRes = imageRes,
            requiresOsBond = requiresOsBond,
        )

        /** Every supported model. The pairing screen groups by brand and sorts each tab alphabetically. */
        val CATALOG: List<WearableModel> = listOf(
            COLMI_R02, COLMI_R06, COLMI_R10, YAWELL_R11, JRING,
            COLMI_R03, COLMI_R07, COLMI_R08, COLMI_R09, COLMI_R11, COLMI_R12,
            YAWELL_R05, YAWELL_R10, H59, TK5, LUCK_RING_TK18,
            // Broadest pattern last: every narrower QRing-Colmi/TK5 entry above gets first shot
            // in modelForAdvertisedName's scan, so this can only match a name nothing else claims.
            COLMI_SMARTHEALTH,
        )

        fun model(id: String?): WearableModel? {
            if (id == null) return null
            return CATALOG.firstOrNull { it.id == id }
        }

        /**
         * First catalog model whose advertised-name pattern matches — iOS `model(advertisedName:)`
         * (named differently because Kotlin can't overload on the argument label alone). Patterns
         * anchor themselves (`^…$`/`^…`), so `containsMatchIn` mirrors iOS's
         * `NSRegularExpression.firstMatch`.
         */
        fun modelForAdvertisedName(advertisedName: String?): WearableModel? {
            if (advertisedName == null) return null
            return CATALOG.firstOrNull { model ->
                model.advertisedNamePatterns.any { pattern ->
                    try {
                        Regex(pattern).containsMatchIn(advertisedName)
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }

        /**
         * Bluetooth identity wins when available; the user's carousel choice is the fallback for
         * service-only or otherwise generic advertisements. Ported from WearableModel.resolve.
         */
        fun resolve(
            advertisedName: String?,
            selectedModelID: String?,
            family: RingDeviceType,
        ): WearableModel? {
            modelForAdvertisedName(advertisedName)?.let { detected ->
                if (detected.family == family) return detected
            }
            model(selectedModelID)?.let { selected ->
                if (selected.family == family) return selected
            }
            return if (family == RingDeviceType.JRING) JRING else null
        }
    }
}
