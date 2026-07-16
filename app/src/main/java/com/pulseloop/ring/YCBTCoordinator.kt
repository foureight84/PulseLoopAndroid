package com.pulseloop.ring

import com.pulseloop.wearables.WearableModel

/**
 * Ported from ColmiSmartHealthCoordinator.swift / TK5Coordinator.swift.
 * Coordinator for YCBT rings (SmartHealth app) — R10M and siblings.
 */

object YCBTCoordinator : WearableCoordinator {
    override val deviceType = RingDeviceType.YCBT

    private val manufacturerHexMarker = "1078"

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        // Disqualify QRing service outright — those belong to ColmiDriver.
        val qringServices = listOf(ColmiUUIDs.SERVICE_V1, ColmiUUIDs.SERVICE_V2)
        if (advertisement.serviceUUIDs.any { it in qringServices }) return false

        val model = WearableModel.modelForAdvertisedName(name)
        if (model != null) {
            return model.family == deviceType && isSmartHealthName(name)
        }

        val manufacturer = advertisement.manufacturerData
        if (manufacturer != null && manufacturer.toHexString().startsWith(manufacturerHexMarker)) {
            // Manufacturer marker is corroborating evidence only; do not override a name match.
            return true
        }
        return false
    }

    private fun isSmartHealthName(name: String?): Boolean {
        if (name == null) return false
        return Regex("^[A-Za-z0-9]+( [A-Za-z0-9]+)* [0-9A-Fa-f]{4}$").matches(name)
    }

    override val capabilities = setOf(
        WearableCapability.HEART_RATE,
        WearableCapability.SPO2,
        WearableCapability.SPO2_HISTORY,
        WearableCapability.STEPS,
        WearableCapability.SLEEP,
        WearableCapability.REM_SLEEP,
        WearableCapability.BATTERY,
        WearableCapability.MANUAL_HEART_RATE,
        WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE,
        WearableCapability.REALTIME_STEPS,
        WearableCapability.FIND_DEVICE,
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
    )

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver {
        return YCBTDriver(writer)
    }
}

/** Hex extension matching iOS hexString. */
fun ByteArray.toHexString(): String = joinToString("") { String.format("%02x", it) }
