package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from YCBTSyncEngine.swift (iOS #82).
 * YCBT sync engine. Connect is a parameterized handshake (clock -> device interrogation -> locale
 * -> all-day monitors -> user profile -> live-status stream), followed by the history sync.
 *
 * History is **not** driven from here. It is a protocol state machine in [YCBTHistoryTransfer]
 * (owned by the driver, the only thing that sees frames): request -> header -> data frames ->
 * terminal block -> mandatory ACK -> next type. This engine only seeds the queue.
 *
 * `runStartup()` doubles as the periodic re-sync on Android — `RingSyncCoordinator.syncNow()` and
 * the 30-minute periodic pass both just call `engine.runStartup()` again (the same convention
 * jring/Colmi already use), rather than a dedicated lighter-weight "re-fetch history only" call.
 * `transfer.start` is a no-op while a transfer is already in flight, so this is safe to call
 * repeatedly.
 */
class YCBTSyncEngine(
    private val writer: RingCommandWriter?,
    private val transfer: YCBTHistoryTransfer,
) : RingSyncEngine {

    /**
     * Pushed in by `RingSyncCoordinator` before [runStartup], so the handshake carries the user's
     * real configuration. Defaults keep a freshly-paired ring logging until the store is read.
     */
    private var measurementSettings: MeasurementSettings = MeasurementSettings.ALL_ON_DEFAULT
    private var userProfile = UserProfileValues(metric = true, gender = 0x02u, age = 0u, heightCm = 0u, weightKg = 0u)

    // MARK: Startup

    override fun runStartup() {
        for (command in YCBTEncoder.startupSequence(measurement = measurementSettings, profile = userProfile)) {
            writer?.enqueue(command.toRawByteArray())
        }
        // The transfer machine writes the first `05 <type>` query itself and advances off the
        // ring's terminal blocks. History steps arrive as an activity update (a per-day max
        // ratchet) and history measurements upsert by (kind, timestamp), so a re-sync is already
        // idempotent.
        transfer.start(YCBTHistoryType.CATALOG)
    }

    /** History is protocol-driven — nothing here advances it. */
    override fun handle(event: RingDecodedEvent) {}

    // MARK: All-day measurement config (the five `01 xx {enable, interval}` monitors)

    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        if (settings != null) measurementSettings = settings
    }

    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        for (command in YCBTEncoder.monitorCommands(settings)) {
            writer?.enqueue(command.toRawByteArray())
        }
    }

    // MARK: User profile (`01 03`)

    override fun setUserProfile(profile: UserProfileValues) {
        userProfile = profile
    }

    override fun applyUserProfile(profile: UserProfileValues) {
        userProfile = profile
        writer?.enqueue(YCBTEncoder.userInfo(profile).toRawByteArray())
    }

    // MARK: Clock / battery

    /** The ring's stored records are stamped from its own RTC in local wall-clock, so a timezone
     *  change must be pushed or every subsequent record decodes to the wrong instant. */
    override fun resyncTime() {
        writer?.enqueue(YCBTEncoder.setTime(Instant.now()).toRawByteArray())
    }

    // MARK: Live actions (proprietary 06-stream on be940003, mode-selected by 03 2f)

    override fun startHeartRate() {
        writer?.enqueue(YCBTEncoder.heartRateStart().toRawByteArray())
    }

    override fun stopHeartRate() {
        writer?.enqueue(YCBTEncoder.heartRateStop().toRawByteArray())
    }

    override fun startSpO2() {
        writer?.enqueue(YCBTEncoder.spo2Start().toRawByteArray())
    }

    override fun stopSpO2() {
        writer?.enqueue(YCBTEncoder.spo2Stop().toRawByteArray())
    }

    /**
     * "Measure now" (combined) drives the blood-pressure mode: it surfaces BP plus the HR the same
     * sweep measures, on the shared `06 03` live-vitals frame. Unlike Colmi's single `0x24` combined
     * command — which returns HR/BP/SpO2/stress/fatigue/bloodSugar/HRV all in one packet — YCBT's
     * `03 2f` mode byte gates *which* fields the ring fills (BP mode fills SBP/DBP and zeroes HRV),
     * so a single YCBT sweep cannot recover every metric simultaneously the way Colmi's can.
     * Standalone HRV/temperature/stress/blood-sugar spot measurement rides the ring's own all-day
     * monitors and history sync instead, matching this app's product surface (no dedicated
     * "Measure HRV" screen exists on Android, unlike iOS's Vitals detail screens).
     */
    override fun startCombinedMeasurement() {
        writer?.enqueue(YCBTEncoder.bloodPressureStart().toRawByteArray())
    }

    override fun stopCombinedMeasurement() {
        writer?.enqueue(YCBTEncoder.bloodPressureStop().toRawByteArray())
    }

    override fun findDevice() {
        writer?.enqueue(YCBTEncoder.findDevice().toRawByteArray())
    }

    /** `SettingGoal 01 02` exists in the SDK but its payload shape is unverified for this ring;
     *  PulseLoop persists the goal app-side regardless. */
    override fun setGoal(steps: Int) {}

    /** No YCBT power-off/factory-reset opcode is implemented — neither TK5 nor SmartHealth-Colmi
     *  declare these capabilities, so the buttons stay hidden and these are never invoked. */
    override fun powerOff() {}
    override fun factoryReset() {}
}
