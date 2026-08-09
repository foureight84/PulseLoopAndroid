package com.pulseloop.ring

/**
 * Coordinator for the RWfit family (vendor app `com.rw.revivalfit`) — rings sold under assorted
 * brands, including "Colmi"-badged units that share nothing with the Colmi protocol.
 *
 * Recognition uses **only** the strong, family-exclusive signals the vendor's own scanner keys on
 * (`r5/d.java c()`, lines 76-120): the advertised `A00A` service, or manufacturer data opening with
 * company `0x05D6` / `0x06D6`.
 *
 * **No name matching, deliberately.** The vendor requires a non-empty name but never looks at its
 * content, because the name is the one field every rebrander changes — the unit iOS was built
 * against was sold as a "Colmi". The first Android attempt matched `name.startsWith("RW")`, which
 * both misses genuinely-rebranded rings and would hijack any unrelated device whose name happens to
 * begin with those two letters.
 */
object RWfitCoordinator : WearableCoordinator {

    override val deviceType: RingDeviceType = RingDeviceType.RWFIT

    /**
     * The floor: what every RWfit ring's firmware serves regardless of framing — the history
     * streams both wire protocols define unconditionally, plus in-band battery. REM is in: both
     * sleep formats carry a REM stage (legacy type 3).
     */
    override val capabilities: Set<WearableCapability> = setOf(
        WearableCapability.HEART_RATE,
        WearableCapability.SPO2,
        WearableCapability.STEPS,
        WearableCapability.SLEEP,
        WearableCapability.REM_SLEEP,
        WearableCapability.BATTERY,
    )

    /**
     * Per-unit extras, granted only when the connected ring claims them.
     *
     * The manual/realtime set is here rather than in [capabilities] because the vendor app has **no
     * legacy on-demand measurement command at all** — on a `0x7E` link a Measure button could only
     * ever time out. The sensor streams (temperature, BP, HRV, stress, blood sugar) are per-SKU and
     * come from the legacy `0x03` feature bitmap / the JieLi bind reply.
     *
     * Note: the bitmap's own layout (`x5/b.java i()` → `SupportMenuBean`) has not been extracted
     * yet, so nothing currently *grants* these — the set is declared so the gating exists the
     * moment that decode lands, and so they are never granted unconditionally in the meantime.
     */
    override val bitmapGatedCapabilities: Set<WearableCapability> = setOf(
        WearableCapability.TEMPERATURE,
        WearableCapability.BLOOD_PRESSURE,
        WearableCapability.MANUAL_BLOOD_PRESSURE,
        WearableCapability.HRV,
        WearableCapability.MANUAL_HRV,
        WearableCapability.STRESS,
        WearableCapability.BLOOD_SUGAR,
        WearableCapability.REALTIME_HEART_RATE,
        WearableCapability.MANUAL_HEART_RATE,
        WearableCapability.MANUAL_SPO2,
    )

    override val iconSystemName: String = "circle.fill"

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        if (advertisesService(advertisement)) return true
        val mfg = advertisement.manufacturerData ?: return false
        val hex = mfg.joinToString("") { "%02x".format(it) }
        return RWfitProtocol.MANUFACTURER_HEX_PREFIXES.any { hex.startsWith(it) }
    }

    /** True when the advertisement carries `A00A`, in either the 16-bit or 128-bit form. */
    private fun advertisesService(advertisement: AdvertisementInfo): Boolean =
        advertisement.serviceUUIDs.any {
            val uuid = it.lowercase()
            uuid == "a00a" || uuid == "0000a00a" || uuid == RWfitProtocol.SERVICE_UUID
        }

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = RWfitDriver(writer)
}
