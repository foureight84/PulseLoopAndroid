package com.pulseloop.ring

object RWfitCoordinator : WearableCoordinator {
    override val deviceType: RingDeviceType = RingDeviceType.RWFIT

    override val capabilities: Set<WearableCapability> = setOf(
        WearableCapability.HEART_RATE, WearableCapability.SPO2, WearableCapability.STEPS,
        WearableCapability.SLEEP, WearableCapability.BATTERY,
        WearableCapability.BLOOD_PRESSURE, WearableCapability.BLOOD_SUGAR,
        WearableCapability.HRV, WearableCapability.STRESS, WearableCapability.TEMPERATURE,
        WearableCapability.MANUAL_HEART_RATE, WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE,
    )

    override val iconSystemName: String = "circle.fill"

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        if (advertisement.serviceUUIDs.contains(RWfitProtocol.SERVICE_UUID)) return true
        return name != null && (name.contains("RWfit", ignoreCase = true) || name.startsWith("RW"))
    }

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = RWfitDriver(writer)
}
