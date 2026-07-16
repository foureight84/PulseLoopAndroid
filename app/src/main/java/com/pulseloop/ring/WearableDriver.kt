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
 * Ported from [MeasurementSettings] in WearableDriver.swift (iOS #19).
 * User-chosen all-day measurement configuration, passed as a plain value from the app layer into
 * a sync engine (the engine never reads Room itself). Devices that support MEASUREMENT_INTERVAL
 * (Colmi) translate this into the relevant ring commands; others ignore it.
 */
data class MeasurementSettings(
    val hrEnabled: Boolean,
    /** All-day HR sampling interval in minutes (Colmi clamps to 5..60 in 5-min steps). */
    val hrIntervalMinutes: Int,
    val spo2Enabled: Boolean,
    val stressEnabled: Boolean,
    val hrvEnabled: Boolean,
    val temperatureEnabled: Boolean,
) {
    companion object {
        /** The firmware default (matches the previous hard-coded Colmi startup behaviour). */
        val ALL_ON_DEFAULT = MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = 5,
            spo2Enabled = true, stressEnabled = true, hrvEnabled = true, temperatureEnabled = true,
        )
    }
}

/**
 * Ported from [UserProfileValues] in WearableDriver.swift (iOS #19).
 * The user's profile projected to the byte-ish shape a ring's user-preferences command expects.
 * Devices that don't take a profile ignore it.
 */
data class UserProfileValues(
    val metric: Boolean,
    /** Ring gender byte: 0x00 female, 0x01 male, 0x02 unspecified/other (Colmi convention). */
    val gender: UByte,
    val age: UByte,
    val heightCm: UByte,
    val weightKg: UByte,
) {
    companion object {
        /** Build from stored profile fields, clamping to byte ranges with neutral fallbacks. */
        fun from(metric: Boolean, sex: String?, age: Int?, heightCm: Double?, weightKg: Double?) =
            UserProfileValues(
                metric = metric,
                gender = when (sex?.lowercase()) {
                    "female" -> 0x00u
                    "male" -> 0x01u
                    else -> 0x02u
                },
                age = (age ?: 25).coerceIn(0, 255).toUByte(),
                heightCm = (heightCm ?: 175.0).toInt().coerceIn(0, 255).toUByte(),
                weightKg = (weightKg ?: 70.0).toInt().coerceIn(0, 255).toUByte(),
            )
    }
}

/**
 * Ported from [RingSyncEngine] in WearableDriver.swift.
 * Per-device orchestration of command flows.
 */
interface RingSyncEngine {
    fun runStartup()
    fun handle(event: RingDecodedEvent)

    /** Re-run the full history sync without re-sending the connect handshake. No-op on devices
     *  that don't support staged history sync. */
    fun syncHistory() {}

    /** On-demand, standalone sleep fetch — request just the sleep record without running the
     *  whole history pipeline (which buries sleep behind activity/HR/stress/SpO₂ and can lose it
     *  to a watchdog stage-skip). Mirrors the official QRing app, which fires a dedicated sleep
     *  request when its sleep screen opens. No-op on devices whose history sync isn't staged this
     *  way (jring fetches sleep in one bulk history request, so it has nothing to decouple). */
    fun syncSleepNow() {}
    fun startHeartRate()
    fun stopHeartRate()
    fun measureHeartRateSpot() { startHeartRate() }
    fun startSpO2()
    fun stopSpO2()
    fun startHRV() {}
    fun stopHRV() {}
    fun startBloodPressure() {}
    fun stopBloodPressure() {}
    /** Combined measurement: HR + systolic + diastolic + SpO₂ + fatigue + stress + blood sugar + HRV. No-op if unsupported. */
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

    /** Store the all-day measurement config *without* sending — used just before [runStartup],
     *  which emits the relevant commands in the connect handshake (so we don't double-send).
     *  `null` ⇒ the user has never saved a config: engines that can should seed one from the
     *  device's own reported settings (see [setOnMeasurementConfigSeeded]) instead of
     *  force-writing defaults over ring-side settings from another app.
     *  Devices without MEASUREMENT_INTERVAL ignore it. */
    fun setMeasurementSettings(settings: MeasurementSettings?) {}

    /** Store *and* immediately push the config — the live "Save" path while connected, so
     *  changes take effect without waiting for a reconnect. No-op if unsupported. */
    fun applyMeasurementSettings(settings: MeasurementSettings) {}

    /** Register a sink for the measurement config seeded from the device's own reported
     *  settings when none was pushed via [setMeasurementSettings] — the app layer persists
     *  it as the device's initial config. No-op if unsupported. */
    fun setOnMeasurementConfigSeeded(callback: (MeasurementSettings) -> Unit) {}

    /** Inspect a raw inbound notify frame. Default no-op; engines that need reply payloads
     *  the decoded-event stream doesn't carry (e.g. Colmi pref-read replies) hook in here. */
    fun handleRawNotify(data: ByteArray) {}

    /** Register a callback the engine invokes when the connected ring reports that it wants an
     *  OS-level Bluetooth bond (Colmi `supportBlePair`). The client owns the actual
     *  [android.bluetooth.BluetoothDevice.createBond] call. No-op if unsupported. */
    fun setOnBondRequested(callback: () -> Unit) {}

    /** Store the user's profile *without* sending — the connect handshake sends it. No-op if unsupported. */
    fun setUserProfile(profile: UserProfileValues) {}

    /** Store *and* immediately push the profile — the live path when the profile saves. No-op if unsupported. */
    fun applyUserProfile(profile: UserProfileValues) {}
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
