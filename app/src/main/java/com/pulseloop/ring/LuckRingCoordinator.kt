package com.pulseloop.ring

import com.pulseloop.wearables.WearableModel

/**
 * Ported from LuckRingCoordinator.swift (iOS #90).
 *
 * Coordinator for the LuckRing / TK18 family (the "K6" vendor SDK). Declares what the driver can
 * decode and recognizes the device from its advertisement. The *protocol* is not TK18-specific --
 * the whole `0xFF64` LuckRing family speaks it -- so the driver / encoder / decoder / sync engine it
 * builds are the shared `LuckRing*` types. This file is the whole of what makes a LuckRing a LuckRing:
 * its advertised identity and its capability set.
 *
 * Recognition is by **strong, family-exclusive signals**: the advertised `F618` service, or the
 * `0xFF64` manufacturer company ID (little-endian `64 FF` prefix), or a catalog name pattern (TK18).
 * The vendor Android SDK matches on the company ID alone with no name whitelist, so a TK18 sibling
 * that renames itself is still claimed.
 */
@OptIn(ExperimentalStdlibApi::class)
object LuckRingCoordinator : WearableCoordinator {
    override val deviceType: RingDeviceType = RingDeviceType.LUCK_RING

    /** The manufacturer-data prefix: company ID `0xFF64` in the little-endian slot => `64ff`. This is
     *  the single signal the vendor app itself matches on, so it is authoritative. */
    private const val MANUFACTURER_HEX_PREFIX = "64ff"

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        if (advertisement.serviceUUIDs.contains(LuckRingUUIDs.SERVICE)) return true
        advertisement.manufacturerData?.let { mfg ->
            if (mfg.toHexString().startsWith(MANUFACTURER_HEX_PREFIX)) return true
        }
        if (WearableModel.modelForAdvertisedName(name)?.family == RingDeviceType.LUCK_RING) return true
        return false
    }

    /**
     * The baseline the LuckRing driver can decode: live + history HR, live + history SpO2 (with the
     * all-day log), day steps, sleep, HRV, temperature, blood pressure, stress, and the in-band
     * battery, plus the find-device buzz. All are unconditional promises -- every metric maps onto a
     * `LuckRing*` decoder path.
     *
     * `bitmapGatedCapabilities` is empty on purpose. The K6 `FUNCTION_CONTROL` (dataType 22) bitmap is
     * obfuscated in the decompile, so no capability can yet be deferred to the connected unit; the
     * whole baseline stands as a family promise. TK18 is the only hardware-tested unit of this
     * family, so capabilities a real ring refuses should be pruned here once on-device testing
     * confirms them (Android tracks no separate per-family support-level flag, unlike iOS's
     * `WearableSupportLevel` -- this doc comment is the only record of that caveat here).
     */
    override val capabilities: Set<WearableCapability> = setOf(
        WearableCapability.HEART_RATE, WearableCapability.REALTIME_HEART_RATE, WearableCapability.MANUAL_HEART_RATE,
        WearableCapability.SPO2, WearableCapability.MANUAL_SPO2, WearableCapability.SPO2_HISTORY,
        WearableCapability.STEPS, WearableCapability.REALTIME_STEPS,
        WearableCapability.SLEEP, WearableCapability.BATTERY,
        WearableCapability.HRV, WearableCapability.MANUAL_HRV,
        WearableCapability.TEMPERATURE,
        WearableCapability.BLOOD_PRESSURE, WearableCapability.MANUAL_BLOOD_PRESSURE,
        WearableCapability.STRESS,
        WearableCapability.FIND_DEVICE,
        // The K6 auto-monitoring config (opcode 128: auto-HR on/off, interval, auto-SpO2) is a real
        // device knob -- the firmware ships with it *off*, so exposing the interval UI is what lets
        // the ring log history at all.
        WearableCapability.MEASUREMENT_INTERVAL,
    )

    override val bitmapGatedCapabilities: Set<WearableCapability> = emptySet()

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = LuckRingDriver(writer)
}
