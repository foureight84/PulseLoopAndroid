package com.pulseloop.ring

import com.pulseloop.wearables.WearableModel

/**
 * Ported from TK5Coordinator.swift (iOS #82).
 * Coordinator for the TK5 ring (SmartHealth app). Declares the capabilities we can actually decode
 * and recognizes the device from its advertisement.
 *
 * The *protocol* is not TK5-specific — the ring speaks YCBT, so the driver, encoder, decoder and
 * sync engine it builds are the shared `YCBT*` types. This file is the whole of what makes a TK5
 * a TK5: its advertised identity and its capability set.
 *
 * Recognition is name-first: the TK5's proprietary `be940000` service is **not advertised** (only
 * standard Heart Rate + a generic `FEE7` service are), so the reliable signal is the `TK5 ...`
 * local name, backed up by the manufacturer-data prefix observed in the nRF capture.
 */
@OptIn(ExperimentalStdlibApi::class)
object TK5Coordinator : WearableCoordinator {
    override val deviceType: RingDeviceType = RingDeviceType.TK5

    /** Manufacturer-data prefix from the capture (`10786501...`, company 0x7810). The trailing
     *  bytes are device-specific, so only the prefix is matched. */
    private const val MANUFACTURER_HEX_PREFIX = "10786501"

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        if (name != null && name.uppercase().startsWith("TK5")) return true
        if (WearableModel.modelForAdvertisedName(name)?.family == RingDeviceType.TK5) return true
        advertisement.manufacturerData?.let { mfg ->
            if (mfg.toHexString().startsWith(MANUFACTURER_HEX_PREFIX)) return true
        }
        return false
    }

    /**
     * The floor: what the TK5 has been *seen* doing, plus what every YCBT ring does regardless of
     * which sensors its SKU carries. A baseline entry is an unconditional promise — the refinement
     * ([bitmapGatedCapabilities]) is additive-only, so the ring's own bitmap can never take one back.
     *
     * `.hrv`/`.manualHrv` stay here (not gated): TK5's HRV was *observed on this ring* (48/79 ms,
     * cross-checked against the vendor app) — the strongest evidence class available, and the
     * opposite of the SmartHealth-Colmi R99, whose HRV was denied four independent ways (see
     * [ColmiSmartHealthCoordinator]). We have never captured the TK5's own `02 01` reply, so gating
     * it could only ever *lose* a demonstrably working feature.
     *
     * `.spo2History`, `.remSleep` and `.findDevice` are also deliberately ungated: the first two are
     * protocol facts (a sub-source/sub-stage of a query `.spo2`/`.sleep` already grants, not a
     * separate sensor), and find-device has a bit that could gate it but nobody has pressed it on a
     * TK5, so there's no evidence either way — gating would only risk removing a working button.
     */
    override val capabilities: Set<WearableCapability> = setOf(
        WearableCapability.HEART_RATE, WearableCapability.SPO2, WearableCapability.SPO2_HISTORY,
        WearableCapability.STEPS, WearableCapability.BATTERY,
        WearableCapability.HRV, WearableCapability.MANUAL_HRV,
        WearableCapability.SLEEP, WearableCapability.REM_SLEEP,
        WearableCapability.MANUAL_HEART_RATE, WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE, WearableCapability.REALTIME_STEPS,
        WearableCapability.FIND_DEVICE, WearableCapability.MEASUREMENT_INTERVAL,
    )

    /**
     * The per-SKU sensors: offered only if this unit's `02 01` bitmap claims them. These used to be
     * baseline (the *SDK* defines these record types, which is not the same claim as "a TK5 has this
     * sensor") until the sibling SmartHealth-Colmi family's R99 denied HRV four independent ways
     * while unconditionally claiming it — the same reasoning now applies here defensively, even
     * though no TK5 bitmap reply has been captured yet.
     */
    override val bitmapGatedCapabilities: Set<WearableCapability> = setOf(
        WearableCapability.TEMPERATURE, WearableCapability.BLOOD_PRESSURE, WearableCapability.MANUAL_BLOOD_PRESSURE,
        WearableCapability.STRESS, WearableCapability.FATIGUE, WearableCapability.BLOOD_SUGAR,
    )

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = YCBTDriver(writer)
}

/**
 * Ported from ColmiSmartHealthCoordinator.swift (iOS #82).
 * Coordinator for Colmi rings that ship with the **SmartHealth** app rather than QRing.
 *
 * Same product line as [ColmiCoordinator], a completely different firmware: these speak YCBT — the
 * byte-identical protocol the TK5 speaks — so the entire stack they build ([YCBTDriver]) is the
 * shared one, and this file is the whole of what makes them their own family. Colmi rings that
 * ship with QRing keep the GadgetBridge-derived [ColmiDriver].
 *
 * Both confirmed YCBT rings (`TK5 24AA`, `R99 54DC`) name themselves `<MODEL><SPACE><4 hex>`, while
 * every QRing-Colmi in the catalog uses an underscore (`R02_A1B2`, `COLMI R10_9C3F`). That
 * space-versus-underscore split is the primary signal — not the manufacturer data, which is
 * unconfirmed for this family (the only capture in it, the TK5, isn't even this coordinator).
 */
@OptIn(ExperimentalStdlibApi::class)
object ColmiSmartHealthCoordinator : WearableCoordinator {
    override val deviceType: RingDeviceType = RingDeviceType.COLMI_SMART_HEALTH

    /** The SmartHealth naming convention: model, one space, four hex digits. Anchored end to end. */
    private val namePattern = Regex("^[A-Za-z0-9]+( [A-Za-z0-9]+)* [0-9A-Fa-f]{4}$")

    /** The Yucheng SDK's company ID (0x7810, little-endian => `1078`), matched as a manufacturer-
     *  data prefix. Demoted to corroborating evidence only — never overrides a name match, since a
     *  QRing-Colmi may carry the same company ID. */
    private const val MANUFACTURER_HEX_MARKER = "1078"

    private fun isSmartHealthName(name: String?): Boolean = name != null && namePattern.matches(name)

    /**
     * Name-first, with the catalog as the arbiter of *whose* name it is.
     * 1. A QRing service disqualifies outright — that ring answers to [ColmiDriver].
     * 2. If a catalog card claims the name, the card decides: it must be able to be this family
     *    **and** the name must follow the SmartHealth convention — the space-versus-underscore
     *    split that separates `R99 54DC` from `R02_A1B2`. A `TK5 24AA` resolves to `.tk5` only, so
     *    it is rejected here, which is what keeps this coordinator (checked ahead of
     *    [TK5Coordinator]) off the TK5.
     * 3. Otherwise nobody names it: fall back to the manufacturer marker, standing aside for the
     *    TK5's fuller `10786501...` prefix (checked after us).
     */
    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        val qringServiceUUIDs = setOf(ColmiUUIDs.SERVICE_V1, ColmiUUIDs.SERVICE_V2)
        if (advertisement.serviceUUIDs.any { it in qringServiceUUIDs }) return false

        val model = WearableModel.modelForAdvertisedName(name)
        if (model != null) {
            return model.family == RingDeviceType.COLMI_SMART_HEALTH && isSmartHealthName(name)
        }
        val manufacturer = advertisement.manufacturerData ?: return false
        if (!manufacturer.toHexString().startsWith(MANUFACTURER_HEX_MARKER)) return false
        return !TK5Coordinator.matches(name, advertisement)
    }

    /**
     * The floor: what every YCBT ring does regardless of which sensors its SKU carries. Anything
     * sensor-dependent is deferred to [bitmapGatedCapabilities] and only claimed if the ring itself
     * claims it — two Colmi rings speaking this identical protocol can differ on whether they have a
     * temperature or blood-pressure sensor at all.
     *
     * `.findDevice` stays baseline on no evidence either way (the R99's bitmap doesn't claim it, but
     * nobody has pressed Find Ring on one either). `.fatigue` is deliberately absent from *both*
     * lists: the R99 answered its history query with "unsupported key", confirming it absent on the
     * one unit tested — a baseline promise would leave its gauge permanently empty.
     */
    override val capabilities: Set<WearableCapability> = setOf(
        WearableCapability.HEART_RATE, WearableCapability.SPO2, WearableCapability.SPO2_HISTORY,
        WearableCapability.STEPS, WearableCapability.SLEEP, WearableCapability.REM_SLEEP, WearableCapability.BATTERY,
        WearableCapability.MANUAL_HEART_RATE, WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE, WearableCapability.REALTIME_STEPS,
        WearableCapability.FIND_DEVICE, WearableCapability.MEASUREMENT_INTERVAL,
    )

    /**
     * The per-SKU sensors, added only if this unit's `02 01` bitmap claims them.
     *
     * **HRV moved here because of the R99.** The owner's `R99 54DC` (firmware 2.32) denies HRV four
     * independent ways in one session: its bitmap leaves the HRV bit clear, the all-day HRV monitor
     * write is rejected, the body-data history query is rejected, and a live HRV start is refused
     * outright. The same session *confirmed* blood pressure worked (claimed + a real 100/68 spot
     * reading), and correctly did not claim temperature/stress/blood sugar. [TK5Coordinator] keeps
     * HRV baseline instead, because on that ring HRV was observed *working* — same discipline,
     * opposite evidence, opposite conclusion.
     */
    override val bitmapGatedCapabilities: Set<WearableCapability> = setOf(
        WearableCapability.TEMPERATURE, WearableCapability.BLOOD_PRESSURE, WearableCapability.STRESS,
        WearableCapability.BLOOD_SUGAR, WearableCapability.MANUAL_BLOOD_PRESSURE,
        WearableCapability.HRV, WearableCapability.MANUAL_HRV,
    )

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = YCBTDriver(writer)
}
