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
     * True only for models with demonstrated GATT-only fragility — the Colmi R09 (connects once
     * then can't re-sync unless bonded) and the R11/Yawell R11 (same silicon, stuck on
     * "Connecting" GATT-only — issue #29). Every other model works GATT-only exactly like iOS
     * (which bonds nothing), so we leave them unbonded to avoid the pairing prompt — most
     * notably the R10, which showed the OS pairing dialog when this gate was briefly dropped in
     * favor of matching QRing's blanket `supportBlePair` bond. See docs/qring-ble-adoption.md
     * §5a. Expand this only when a model is shown to need it on real hardware.
     */
    val requiresOsBond: Boolean = false,
) {
    companion object {
        /**
         * The SmartHealth naming convention: model, one space, four hex digits — the
         * space-versus-underscore split that separates a SmartHealth-flavoured Colmi (`R99 54DC`)
         * from a QRing one (`R02_A1B2`). Anchored end to end.
         *
         * The model half allows `-` because resellers badge these rings under their own hyphenated
         * names: the unit in issue #56 advertises as `Ale-Hop2211 E1C7`, a textbook SmartHealth
         * name that the original `[A-Za-z0-9]`-only class rejected on the hyphen alone. Widening it
         * cannot pull in a QRing-Colmi — those have no space before the hex — and this is the
         * broadest card in [CATALOG], scanned last, so every narrower model still gets first shot.
         *
         * Shared with `ColmiSmartHealthCoordinator`, which used to keep its own copy of the same
         * literal. Two copies of one convention is exactly how issue #56 slipped through; keep it
         * at one.
         */
        const val SMARTHEALTH_NAME_PATTERN = "^[A-Za-z0-9-]+( [A-Za-z0-9-]+)* [0-9A-Fa-f]{4}$"

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
        // R09 is one of two models that need an OS bond to hold a stable Android link (see
        // WearableModel.requiresOsBond).
        val COLMI_R09 = colmi("colmi-r09", "Colmi R09", "Colmi", "^R09_.*", R.drawable.ring_colmi_r09,
            requiresOsBond = true)
        val COLMI_R10 = colmi("colmi-r10", "Colmi R10", "Colmi", "^COLMI R10_.*", R.drawable.ring_colmi_r10)
        // The R11 shares its product art with the Yawell R11 (same hardware, same look) and the
        // same OS-bond requirement (issue #29 — stuck on "Connecting" GATT-only).
        val COLMI_R11 = colmi("colmi-r11", "Colmi R11", "Colmi", "^R11C_[0-9A-F]{4}$", R.drawable.ring_yawell_r11,
            requiresOsBond = true)
        val COLMI_R12 = colmi("colmi-r12", "Colmi R12", "Colmi", "^COLMI R12_.*", R.drawable.ring_colmi_r12)

        /**
         * YCBT / SmartHealth family — a distinct protocol from the QRing Colmi rings above, so this
         * runs [RingDeviceType.YCBT]'s driver, not the Colmi one, despite the R10-ish model number.
         *
         * "R10M" is a white-label ODM model sold under several reseller brands (LittleMeatball,
         * Anarow, JTLlink, MOMOTECH). LittleMeatball is the one hardware-validated unit and the name
         * a buyer is most likely to recognise, so it leads the display name. The pattern accepts
         * both separators: `R10M FCF4` (the tested unit) is also caught by the deliberately broad
         * [COLMI_SMARTHEALTH] entry — which this precedes in [CATALOG], so it wins — while
         * `R10M_FCF4` matches nothing else in the catalog at all.
         *
         * No dedicated product art: it is not a Colmi ring, so it must not borrow Colmi art. Falls
         * back to the generic ring silhouette, same as [TK5] and [LUCK_RING_TK18].
         */
        val R10M = ycbt(
            "r10m", "R10M (LittleMeatball)", "LittleMeatball",
            "^R10M[ _][0-9A-F]{4}$", imageRes = null,
        )

        /**
         * The **CRP-firmware** R11 — same physical ring as [COLMI_R11], but its official app is
         * Moyoung "Da Rings" and it speaks the proprietary `fdda` CRP protocol, not the Colmi/QRing
         * UART (see `CRPCoordinator`). "R11 / SMART_RING" is sold under both firmwares; a unit is
         * routed here when discovery reveals the `fdda` service (issue #29, zaggash's ring) or when
         * the user explicitly picks this card. No usable name pattern — the ring advertises the same
         * generic `SMART_RING` as jring, so identity comes from the `fdda` service post-connect, not
         * the name. Connects GATT-only, so `requiresOsBond = false` (no pairing dialog).
         */
        val COLMI_R11_CRP = WearableModel(
            id = "colmi-r11-crp", displayName = "Colmi R11 (Da Rings app)", brand = "Colmi",
            family = RingDeviceType.CRP,
            tint = PulseColors.hrv, blurb = "HR · Steps",
            advertisedNamePatterns = emptyList(),
            imageRes = R.drawable.ring_yawell_r11,
        )

        // Yawell-branded variants
        val YAWELL_R05 = colmi("yawell-r05", "Yawell R05", "Yawell", "^R05_[0-9A-F]{4}$", R.drawable.ring_yawell_r05)
        val YAWELL_R10 = colmi("yawell-r10", "Yawell R10", "Yawell", "^R10_[0-9A-F]{4}$", R.drawable.ring_yawell_r10)
        val YAWELL_R11 = colmi("yawell-r11", "Yawell R11", "Yawell", "^R11_[0-9A-F]{4}$", R.drawable.ring_yawell_r11,
            requiresOsBond = true)
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
            advertisedNamePatterns = listOf(SMARTHEALTH_NAME_PATTERN),
        )

        // TK18 -- the LuckRing app / "K6" protocol (company ID 0xFF64). The only hardware-tested unit
        // of the whole 0xFF64 family, so it stays limited-support. No dedicated product art yet --
        // falls back to the generic ring silhouette, same as TK5.
        val LUCK_RING_TK18 = WearableModel(
            id = "luckring-tk18", displayName = "TK18", brand = "LuckRing", family = RingDeviceType.LUCK_RING,
            tint = PulseColors.hrv, blurb = "HR · SpO₂ · HRV · Temp · BP · Sleep · Steps",
            advertisedNamePatterns = listOf("^TK18([ _-].*)?$"),
        )

        /**
         * The catalog card for the family. `advertisedNamePatterns` is deliberately **empty**: the
         * vendor's own scanner never looks at the name, and these rings ship under whatever badge
         * the reseller picked (the reference unit was sold as a "Colmi"). Recognition lives
         * entirely in [com.pulseloop.ring.RWfitCoordinator]'s advertisement match.
         */
        val RWFIT = WearableModel(
            id = "rwfit", displayName = "RWfit Ring", brand = "RWfit", family = RingDeviceType.RWFIT,
            tint = PulseColors.bloodPressure,
            blurb = "HR · SpO₂ · Sleep · Steps",
            advertisedNamePatterns = emptyList(),
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

        private fun ycbt(
            id: String,
            name: String,
            brand: String,
            pattern: String,
            @DrawableRes imageRes: Int?,
        ) = WearableModel(
            id = id, displayName = name, brand = brand, family = RingDeviceType.YCBT,
            tint = PulseColors.hrv, blurb = "HR · SpO₂ · BP · Sleep",
            advertisedNamePatterns = listOf(pattern),
            imageRes = imageRes,
        )

        /** Every supported model. The pairing screen groups by brand and sorts each tab alphabetically. */
        val CATALOG: List<WearableModel> = listOf(
            COLMI_R02, COLMI_R06, COLMI_R10, YAWELL_R11, JRING,
            COLMI_R03, COLMI_R07, COLMI_R08, COLMI_R09, COLMI_R11, COLMI_R12,
            YAWELL_R05, YAWELL_R10, H59, R10M, TK5, LUCK_RING_TK18, COLMI_R11_CRP, RWFIT,
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
