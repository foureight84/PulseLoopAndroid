package com.pulseloop.ring

import com.pulseloop.wearables.WearableModel

/**
 * Ported from ColmiSmartHealthCoordinator.swift / TK5Coordinator.swift.
 * Coordinator for YCBT rings (SmartHealth app) — R10M and siblings.
 */

object YCBTCoordinator : WearableCoordinator {
    override val deviceType = RingDeviceType.YCBT


    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        // Disqualify QRing service outright — those belong to ColmiDriver.
        val qringServices = listOf(ColmiUUIDs.SERVICE_V1, ColmiUUIDs.SERVICE_V2)
        if (advertisement.serviceUUIDs.any { it in qringServices }) return false

        val model = WearableModel.modelForAdvertisedName(name)
        if (model != null) {
            return model.family == deviceType && isSmartHealthName(name)
        }

        val hasYcbtService = advertisement.serviceUUIDs.contains(YCBTUUIDs.SERVICE)
        val hasKnownName = isSmartHealthName(name)
        // The 0x7810 manufacturer marker is common enough that it is corroborating
        // evidence only, never sufficient to route an unknown peripheral.
        return hasYcbtService || hasKnownName
    }

    private fun isSmartHealthName(name: String?): Boolean {
        if (name == null) return false
        val normalized = name.trim().uppercase()
        return Regex("^(?:R10M|TK5|T50|SR09|SR08|R08|R09)(?:[ _-][0-9A-Z]+)?$").matches(normalized)
    }

    override val capabilities = setOf(
        WearableCapability.HEART_RATE,
        WearableCapability.SPO2,
        WearableCapability.STEPS,
        WearableCapability.SLEEP,
        WearableCapability.REM_SLEEP,
        WearableCapability.BATTERY,
        WearableCapability.MANUAL_HEART_RATE,
        WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE,
        WearableCapability.REALTIME_STEPS,
        WearableCapability.MEASUREMENT_INTERVAL,
    )

    override val bitmapGatedCapabilities = setOf(
        WearableCapability.TEMPERATURE,
        WearableCapability.BLOOD_PRESSURE,
        WearableCapability.MANUAL_BLOOD_PRESSURE,
        WearableCapability.STRESS,
        WearableCapability.FATIGUE,
        WearableCapability.BLOOD_SUGAR,
        WearableCapability.HRV,
        WearableCapability.MANUAL_HRV,
        WearableCapability.FIND_DEVICE,
    )

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver {
        return YCBTDriver(writer)
    }
}

/** Hex extension matching iOS hexString. */
fun ByteArray.toHexString(): String = joinToString("") { String.format("%02x", it) }
