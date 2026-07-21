package com.pulseloop.ring

import com.pulseloop.wearables.WearableModel

/**
 * Coordinator for the CRP ("crrepa"/CRPsmart) `fdda`-profile family — official app Moyoung
 * "Da Rings" (`com.moyoung.ring`). Declares what [CRPDriver] can decode and how the ring is
 * recognised. See [CRPProtocol] and `decompiled-moyoung-official/`.
 *
 * **Recognition.** The family's authoritative signal is the advertised `fdda` service. In practice
 * the CRP Colmi R11 advertises the generic name `SMART_RING` with **no** service UUID pre-connect,
 * so no coordinator matches at scan and it falls back to JRING — [RingBLEClient] then re-routes to
 * this coordinator once discovery reveals the `fdda` service post-connect (mirrors the JRING→Colmi
 * re-route). A user who explicitly picks the CRP model in the pairing carousel is honored the same
 * way [ColmiCoordinator]'s carousel pick is.
 *
 * **Bonding.** Unlike the Colmi-UART R11, the CRP ring connects GATT-only — the vendor app performs
 * no OS bond in its connect path (bonding there is a separate opt-in HID/camera feature). So the
 * CRP carousel model sets `requiresOsBond = false`; there is no pairing dialog.
 */
object CRPCoordinator : WearableCoordinator {
    override val deviceType = RingDeviceType.CRP

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        if (WearableModel.modelForAdvertisedName(name)?.family == RingDeviceType.CRP) return true
        return advertisement.serviceUUIDs.any { it.equals(CRPUUIDs.SERVICE, ignoreCase = true) }
    }

    /**
     * v1 baseline — only capabilities backed by a decode path confirmed from the decompile:
     * current-steps push (`fdd1`), the standard HR stream (`2a37`) with its start/stop command,
     * the standard battery read, plus find-device and factory-reset commands. Sleep / SpO2 / HRV /
     * stress / temperature and history sync are deferred until their CRP reply layouts are
     * confirmed against hardware — deliberately not promised here so the product UI hides them.
     */
    override val capabilities = setOf(
        WearableCapability.STEPS, WearableCapability.REALTIME_STEPS,
        WearableCapability.HEART_RATE, WearableCapability.REALTIME_HEART_RATE,
        WearableCapability.MANUAL_HEART_RATE,
        WearableCapability.BATTERY,
        WearableCapability.FIND_DEVICE, WearableCapability.FACTORY_RESET,
    )

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = CRPDriver(writer)
}
