package com.pulseloop.ring

@OptIn(ExperimentalStdlibApi::class)

/**
 * Ported from [JringCoordinator] in JringCoordinator.swift.
 * Coordinator for the original "jring" (SMART_RING, service 000056ff…).
 */
object JringCoordinator : WearableCoordinator {
    override val deviceType = RingDeviceType.JRING
    private const val ADVERTISED_NAME = "SMART_RING"
    private const val MANUFACTURER_HEX_NEEDLE = "41422ec75b6a"

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        if (name == ADVERTISED_NAME) return true
        if (advertisement.serviceUUIDs.contains(RingUUIDs.SERVICE)) return true
        advertisement.manufacturerData?.let { mfg ->
            if (mfg.toHexString().contains(MANUFACTURER_HEX_NEEDLE)) return true
        }
        return false
    }

    override val capabilities = setOf(
        WearableCapability.HEART_RATE,
        WearableCapability.SPO2,
        WearableCapability.STEPS,
        WearableCapability.SLEEP,
        WearableCapability.BATTERY,
        WearableCapability.BLOOD_PRESSURE,   // 0x23/0x24 combined measurement
        WearableCapability.BLOOD_SUGAR,      // combined measurement byte[7] (mmol/L ×10)
        WearableCapability.STRESS,           // combined measurement byte[6]
        WearableCapability.FATIGUE,          // combined measurement byte[5]
        WearableCapability.HRV,              // combined measurement byte[8] — spot only;
                                             // the jring protocol has no HRV history sync
                                             // (Colmi 0x39 equivalent doesn't exist)
        WearableCapability.MANUAL_HEART_RATE,
        WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE,
        WearableCapability.FIND_DEVICE,
        // NOTE: No TEMPERATURE — the Jring (SR08-class PPG ring) has no skin-temperature
        // sensor. In the official app temperature is gated by the 0x20 capability bit
        // FUNCTION_TEMPERATURE (zArr[10]), which these rings do not set; it exists in the
        // shared SDK only for smartwatch variants.
    )

    override val iconSystemName = "circle.hexagongrid.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = JringDriver(writer)
}

/**
 * Ported from [JringDriver] in JringDriver.swift.
 * Thin wrapper over RingDecoder/RingEncoder for jring devices.
 */
class JringDriver(private val writer: RingCommandWriter) : WearableDriver {
    private val decoder = RingDecoder

    override val serviceUUIDs = listOf(RingUUIDs.SERVICE)
    override val writeUUID = RingUUIDs.WRITE
    override val notifyUUIDs = listOf(RingUUIDs.NOTIFY)
    override val batteryServiceUUID = "0000180f-0000-1000-8000-00805f9b34fb"
    override val batteryCharUUID = RingUUIDs.BATTERY

    override fun frame(command: ByteArray) = command  // jring: already 20 bytes, no checksum

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> =
        decoder.decode(data)

    override fun makeSyncEngine(): RingSyncEngine = JringSyncEngine(writer)
}

/**
 * Ported from [JringSyncEngine] in JringSyncEngine.swift.
 * Fire-and-forget sync engine for jring devices.
 */
class JringSyncEngine(private val writer: RingCommandWriter?) : RingSyncEngine {
    private val encoder = RingEncoder

    override fun runStartup() {
        writer?.enqueue(encoder.makeStatusCommand())
        writer?.enqueue(encoder.makeTimeSyncCommand())
        writer?.enqueue(encoder.makeLocaleCommand())
        writer?.enqueue(encoder.makeDefaultUserInfoCommand())
        // 3 days: a days=1 window can clip the pre-midnight half of last night's
        // sleep (firmware indexes by calendar day), and 0x11 sleep packets have
        // never been observed with days=1 — field diagnosis 2026-07-04.
        writer?.enqueue(encoder.makeHistoryQueryCommand(days = 3))
        writer?.enqueue(encoder.makeHistoryMeasurementQueryCommand())
        // Arm the automatic HR schedule on every connect. It was previously only
        // sent after a manual HR measurement ended — a ring that never got one
        // does no periodic night sampling, and without night samples the
        // firmware has nothing to stage sleep from.
        writer?.enqueue(encoder.makeAutomaticHeartRateCommand(enabled = true, cadenceMinutes = 5))
    }

    override fun handle(event: RingDecodedEvent) {
        // Ring-side bind handshake (0x4B), mirroring the official app's
        // onNotifyBindedInfo: the ring drives binding on connect so it stays paired
        // to us and keeps streaming. Unbind (on forget) is handled in RingBLEClient.
        if (event is RingDecodedEvent.BindNotify) {
            when (event.action) {
                0 -> if (event.state == 0) writer?.enqueue(encoder.makeBindAppStartCommand()) // INIT → APP_START
                2 -> writer?.enqueue(encoder.makeBindSuccessCommand())                         // ACK → SUCCESS
            }
        }
    }

    override fun startHeartRate() {
        writer?.enqueue(encoder.makeHeartRateStartCommand())
    }

    override fun stopHeartRate() {
        writer?.enqueue(encoder.makeHeartRateStopCommand())
        writer?.enqueue(encoder.makeAutomaticHeartRateCommand(enabled = true, cadenceMinutes = 5))
    }

    /** Spot HR using live HR streaming (0x14), same as workout HR. */
    override fun measureHeartRateSpot() {
        writer?.enqueue(encoder.makeHeartRateStartCommand())
    }

    /** SpO₂-only measurement using 0x3E (not 0x23 — that's combined BP measurement). */
    override fun startSpO2() {
        writer?.enqueue(encoder.makeSpO2StartCommand())
    }

    override fun stopSpO2() {
        writer?.enqueue(encoder.makeSpO2StopCommand())
    }

    /** Combined measurement (0x23 → 0x24): HR + systolic + diastolic + SpO₂ + fatigue + stress + blood sugar + HRV in one 20-byte notification. BP values are direct sensor readings. Blood sugar is a profile-derived estimate. */
    override fun startCombinedMeasurement() {
        writer?.enqueue(encoder.makeCombinedMeasurementStart())
    }

    override fun stopCombinedMeasurement() {
        writer?.enqueue(encoder.makeCombinedMeasurementStop())
    }

    override fun findDevice() {
        writer?.enqueue(encoder.makeFindRingCommand())
    }

    override fun setGoal(steps: Int) {
        writer?.enqueue(encoder.makeGoalCommand(steps))
    }

    override fun setUserInfo(ageYears: Int, isMale: Boolean, heightCm: Int, weightKg: Int) {
        writer?.enqueue(encoder.makeUserInfoCommand(ageYears, isMale, heightCm, weightKg))
    }

    override fun setBloodPressureAdjust(systolic: Int, diastolic: Int) {
        writer?.enqueue(encoder.makeBPAdjustCommand(systolic, diastolic))
    }

    override fun setAppId(appId: String) {
        writer?.enqueue(encoder.makeAppIdCommand(appId))
    }

    // Keepalive ping (0x3A) — prevents ring's ~20s idle disconnect
    fun sendKeepalive() {
        val cmd = ByteArray(20)
        cmd[0] = 0x3A.toByte()
        writer?.enqueue(cmd)
    }

    // Jring has no power-off or factory-reset capabilities — no-ops
    override fun powerOff() {}
    override fun factoryReset() {}
}
