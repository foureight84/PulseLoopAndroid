package com.pulseloop.ring

/**
 * Ported from [RingCommandWriter] in WearableDriver.swift.
 * Thin write seam so a driver/sync engine can enqueue commands without holding RingBLEClient.
 */
fun interface RingCommandWriter {
    fun enqueue(command: ByteArray)
}

enum class SubscriptionMode { NOTIFICATION, INDICATION }

data class RequiredSubscription(
    val uuid: String,
    val mode: SubscriptionMode,
)

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

    /** Channels that must be subscribed successfully before this driver is usable. Empty keeps
     * legacy first-notify readiness for devices whose additional channels are optional. */
    val requiredSubscriptionsBeforeConnected: List<RequiredSubscription> get() = emptyList()

    /** Commands that must be placed directly after the final required CCCD write. */
    fun immediatePostSubscriptionCommands(): List<ByteArray> = emptyList()

    /** Apply outbound framing. jring: identity. Colmi: pad to 15 + checksum. */
    fun frame(command: ByteArray): ByteArray

    /** Whether an outbound frame must go to the commandUUID characteristic. */
    fun usesCommandChannel(frame: ByteArray): Boolean = false

    /** Decode one inbound notify frame → 0..n events. */
    fun ingest(data: ByteArray, from: String): List<RingDecodedEvent>

    /** Build the per-device sync engine. */
    fun makeSyncEngine(): RingSyncEngine

    /** Reset connection-scoped protocol state. */
    fun connectionDidStart() {}
    fun connectionDidEnd() {}

    /**
     * The connected GATT's full service table, delivered once after discovery.
     *
     * For families whose wire format isn't decidable from the advertisement: RWfit serves one data
     * service (`A00A`) over two incompatible framings, and the vendor app picks between them purely
     * from which *sibling* services the ring exposes (`r5/b.java onServicesDiscovered`). No-op for
     * everyone else.
     */
    fun servicesDiscovered(serviceUUIDs: Collection<String>) {}
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
    companion object {
        /**
         * Fallback bound on the live-HR leg of a spot measurement, for a family that gives no
         * other signal that it has finished. Long enough for a settled optical reading, short
         * enough that a ring which will never produce one doesn't hold the sensor on.
         */
        const val DEFAULT_SPOT_HEART_RATE_SECONDS = 30
        /** Default ceiling on the SpO₂ leg. iOS raised this 40 → 60 (`c8969a4`): the R99's
         *  successful sweep took 38 s while another ran past 41 s with no result. */
        const val DEFAULT_SPOT_SPO2_SECONDS = 60
    }

    /** True only for protocols with one native command that returns a combined vitals packet.
     * Capability bits such as manual BP/glucose do not imply this transport feature. */
    val supportsCombinedMeasurement: Boolean get() = false

    /**
     * How long the live-HR leg of a spot measurement may run on this family (issue #59).
     *
     * A ceiling, not a duration: the leg ends the moment the ring says it is done. Only a family
     * that never says so actually spends this long, which is why it can be raised for a family
     * that *does* — the ring in #59 needs ~26 s of warm-up before its PPG converges and ends the
     * measurement itself at ~35 s, so at the default it was cut off just as its readings became
     * real, while raising the default for everyone would make every other family's measurement
     * visibly slower for nothing.
     */
    val spotHeartRateSeconds: Int get() = DEFAULT_SPOT_HEART_RATE_SECONDS

    /**
     * Ceiling on the SpO₂ leg of a spot measurement, in seconds. The same reasoning as
     * [spotHeartRateSeconds]: a family whose ring ends the measurement itself may need a longer
     * fallback bound than the default, because the ring's own `04 0e` is what actually ends the
     * leg and the window must be long enough for it to arrive.
     */
    val spotSpo2Seconds: Int get() = DEFAULT_SPOT_SPO2_SECONDS

    /**
     * True when this family's ring tells us a spot measurement has ended (issue #59's `04 0e`).
     *
     * It gates whether a leg may keep collecting: a leg that waits for a completion signal no
     * family sends would simply idle out its whole window, which is why the SpO2 leg keeps its
     * "first plausible value wins" behaviour everywhere else. The CRP R11 in particular answers a
     * spot SpO2 with one value after ~48 s of silence and nothing further — waiting past it would
     * turn a working measurement into a minute-long stare at a progress bar for no gain.
     */
    val signalsMeasurementCompletion: Boolean get() = false

    fun runStartup()
    fun handle(event: RingDecodedEvent)

    /** User-requested refresh. Existing families historically replay their startup sync. */
    fun refresh() = runStartup()

    /** Legacy sleep query action. Existing families historically replay their startup sync. */
    fun querySleep() = runStartup()

    /** Refresh the recent vital series after a live workout without replaying all history. */
    fun syncVitalsHistory() {}

    /** On-screen, standalone sleep fetch — request just the sleep record without running the
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

    /** Re-push the device clock after the phone's timezone or wall clock changes. Only rings
     *  whose firmware keys behaviour off their own RTC need this (jring's sleep detection and
     *  day-indexed history do). Default no-op. */
    fun resyncTime() {}

    /** Store the user's profile *without* sending — the connect handshake sends it. No-op if unsupported. */
    fun setUserProfile(profile: UserProfileValues) {}

    /** Store *and* immediately push the profile — the live path when the profile saves. No-op if unsupported. */
    fun applyUserProfile(profile: UserProfileValues) {}
}

/**
 * Ported from [AdvertisementInfo] in WearableCoordinator.swift.
 *
 * [manufacturerData] is one manufacturer-specific data block in its **on-air layout**: the
 * little-endian company ID first, then the vendor bytes. That is what CoreBluetooth hands iOS,
 * and it is what every coordinator's prefix matches (`1078…`, `64ff…`, `d605…`). Android's
 * `ScanRecord` splits the same field the other way, so build this through
 * [AdvertisementMatcher.manufacturerBlocks] rather than from `valueAt()` directly.
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
 * Turns Android's parsed advertisement fields into the [AdvertisementInfo]s the coordinators
 * expect, and walks the registry against them.
 *
 * Exists because of a platform mismatch that made every manufacturer-data fallback dead code on
 * Android (issue #56). `ScanRecord.getManufacturerSpecificData()` returns a `SparseArray` **keyed
 * by company ID with the ID stripped from the value**; the coordinators were ported from Swift,
 * where CoreBluetooth includes the company ID in the bytes, so they all match a little-endian
 * company-ID prefix (`TK5Coordinator` `10786501`, `ColmiSmartHealthCoordinator` `1078`,
 * `LuckRingCoordinator` `64ff`, `RWfitProtocol.MANUFACTURER_HEX_PREFIXES` `d605…`/`d606…`). Those
 * prefixes can never appear in a `valueAt()` payload, so the fallback never fired for any YCBT,
 * LuckRing or RWfit ring — a ring whose name the catalog doesn't recognise was simply invisible.
 */
object AdvertisementMatcher {

    /**
     * Re-attach the company ID to each entry, little-endian, restoring the on-air layout.
     *
     * Returns one block per entry — **all** of them, not just index 0: a device may advertise
     * several company blocks and nothing guarantees the family marker is the first. A device with
     * no manufacturer data yields a single `null` block, so the service/name matches still run.
     */
    fun manufacturerBlocks(entries: List<Pair<Int, ByteArray>>): List<ByteArray?> {
        if (entries.isEmpty()) return listOf(null)
        return entries.map { (companyId, value) ->
            byteArrayOf(
                (companyId and 0xFF).toByte(),
                ((companyId shr 8) and 0xFF).toByte(),
            ) + value
        }
    }

    /**
     * First coordinator in registry order that claims the advertisement, or `null`.
     *
     * Registry order stays the outer loop — it is load-bearing (see `RingBLEClient.coordinators`) —
     * so a coordinator listed earlier still wins even when a later one matches a different
     * manufacturer block of the same device.
     */
    fun match(
        coordinators: List<WearableCoordinator>,
        name: String?,
        serviceUUIDs: List<String>,
        manufacturerEntries: List<Pair<Int, ByteArray>>,
    ): RingDeviceType? {
        val blocks = manufacturerBlocks(manufacturerEntries)
        for (coordinator in coordinators) {
            for (block in blocks) {
                if (coordinator.matches(name, AdvertisementInfo(serviceUUIDs, block))) {
                    return coordinator.deviceType
                }
            }
        }
        return null
    }
}

/**
 * Ported from [WearableCoordinator] in WearableCoordinator.swift.
 * Capability + metadata descriptor for a wearable family.
 */
interface WearableCoordinator {
    val deviceType: RingDeviceType
    /** The floor: what every unit of this family does. An unconditional promise — see
     *  [bitmapGatedCapabilities] for capabilities a *specific* unit may or may not have. */
    val capabilities: Set<WearableCapability>
    /**
     * Per-SKU capabilities offered only if the connected unit's own reported bitmap claims them
     * (YCBT `02 01` SupportFunction; see `YCBTSupportFunction`). Empty for families with no such
     * bitmap (jring, QRing-Colmi) — their [capabilities] set is the whole story.
     *
     * Refinement is **additive-only**: [RingBLEClient] unions this (intersected with what the
     * device actually reports) into [capabilities] once connected. The device's own reply can
     * only ever *add* a capability the family already offers as gate-able, never take one away
     * from [capabilities].
     */
    val bitmapGatedCapabilities: Set<WearableCapability> get() = emptySet()
    val iconSystemName: String
    val displayName: String get() = deviceType.displayName

    fun matches(name: String?, advertisement: AdvertisementInfo): Boolean
    fun makeDriver(writer: RingCommandWriter): WearableDriver
}
