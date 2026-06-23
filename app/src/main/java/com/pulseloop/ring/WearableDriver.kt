package com.pulseloop.ring

/**
 * Ported from [RingCommandWriter] in WearableDriver.swift.
 * Thin write seam so a driver/sync engine can enqueue commands without holding RingBLEClient.
 */
fun interface RingCommandWriter {
    fun enqueue(command: ByteArray)
}

/**
 * Ported from [WearableDriver] in WearableDriver.swift.
 * Connection + protocol handler for one wearable family.
 */
interface WearableDriver {
    val serviceUUIDs: List<String>
    val writeUUID: String
    val notifyUUIDs: List<String>
    val commandUUID: String? get() = null
    val batteryServiceUUID: String? get() = null
    val batteryCharUUID: String? get() = null

    /** Apply outbound framing. jring: identity. Colmi: pad to 15 + checksum. */
    fun frame(command: ByteArray): ByteArray

    /** Whether an outbound frame must go to the commandUUID characteristic. */
    fun usesCommandChannel(frame: ByteArray): Boolean = false

    /** Decode one inbound notify frame → 0..n events. */
    fun ingest(data: ByteArray, from: String): List<RingDecodedEvent>

    /** Build the per-device sync engine. */
    fun makeSyncEngine(): RingSyncEngine
}

/**
 * Ported from [RingSyncEngine] in WearableDriver.swift.
 * Per-device orchestration of command flows.
 */
interface RingSyncEngine {
    fun runStartup()
    fun handle(event: RingDecodedEvent)
    fun startHeartRate()
    fun stopHeartRate()
    fun measureHeartRateSpot() { startHeartRate() }
    fun startSpO2()
    fun stopSpO2()
    /** Combined spot measurement (BP + SpO₂ + stress + fatigue + blood sugar). No-op if unsupported. */
    fun startCombinedMeasurement() {}
    fun stopCombinedMeasurement() {}
    fun findDevice()
    fun setGoal(steps: Int)
    fun powerOff()
    fun factoryReset()
    /** Push user anthropometrics (age/sex/height/weight) so on-device BP/sugar/calorie
     *  algorithms have real inputs. No-op if unsupported. */
    fun setUserInfo(ageYears: Int, isMale: Boolean, heightCm: Int, weightKg: Int) {}
    /** Calibrate blood pressure against a reference systolic/diastolic. No-op if unsupported. */
    fun setBloodPressureAdjust(systolic: Int, diastolic: Int) {}
    /** Claim the ring for this app's id so it streams data to us. No-op if unsupported. */
    fun setAppId(appId: String) {}
}

/**
 * Ported from [AdvertisementInfo] in WearableCoordinator.swift.
 */
data class AdvertisementInfo(
    val serviceUUIDs: List<String>,
    val manufacturerData: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertisementInfo) return false
        return serviceUUIDs == other.serviceUUIDs &&
            manufacturerData.contentEquals(other.manufacturerData)
    }

    override fun hashCode(): Int =
        31 * serviceUUIDs.hashCode() + manufacturerData.contentHashCode()
}

/**
 * Ported from [WearableCoordinator] in WearableCoordinator.swift.
 * Capability + metadata descriptor for a wearable family.
 */
interface WearableCoordinator {
    val deviceType: RingDeviceType
    val capabilities: Set<WearableCapability>
    val iconSystemName: String
    val displayName: String get() = deviceType.displayName

    fun matches(name: String?, advertisement: AdvertisementInfo): Boolean
    fun makeDriver(writer: RingCommandWriter): WearableDriver
}
