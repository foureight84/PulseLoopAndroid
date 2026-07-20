package com.pulseloop.ring

/**
 * Ported from LuckRingSyncEngine.swift (iOS #90).
 *
 * LuckRing sync engine. Connect is the MixInfo binding bundle followed by device-info / battery /
 * settings-sync requests and the history catalog pass; the ring's real-time streams are toggled by
 * the per-metric `K6_DATA_TYPE_REAL_*` sends.
 *
 * History is **not** driven from [handle]. The pager ([LuckRingHistorySync], owned by the driver --
 * the only thing that sees frames) advances itself off the ring's data frames, so `handle` is a no-op.
 * `runStartup()` doubles as the periodic re-sync (the jring/YCBT convention already used on Android):
 * `historySync.start` is a no-op while a pass is already in flight, so re-calling this is safe.
 *
 * Every logical frame is split into 20-byte packets here (the driver's [LuckRingDriver.frame] is
 * identity) and each packet is enqueued individually onto `RingBLEClient`'s serialized write queue.
 */
class LuckRingSyncEngine(
    private val writer: RingCommandWriter?,
    private val historySync: LuckRingHistorySync,
) : RingSyncEngine {
    private val encoder = LuckRingEncoder()

    /** Pushed in by `RingSyncCoordinator` before [runStartup], so the binding bundle carries the
     *  user's real profile / goal. Defaults keep a freshly-paired ring sane until the store is read. */
    private var userProfile = UserProfileValues(metric = true, gender = 0x02u, age = 0u, heightCm = 0u, weightKg = 0u)
    private var goalSteps = 10_000

    /** Auto-monitoring config pushed as opcode 128 on startup. Defaults to a 30-minute cadence
     *  *enabled* -- the K6 firmware default is monitoring **off**, which would leave every history
     *  stream permanently empty on a ring the vendor app never configured. Only `hrEnabled`/
     *  `hrIntervalMinutes`/`spo2Enabled` feed this opcode; the other [MeasurementSettings] fields are
     *  inert here. */
    private var measurementSettings = MeasurementSettings(
        hrEnabled = true, hrIntervalMinutes = 30,
        spo2Enabled = true, stressEnabled = false, hrvEnabled = false, temperatureEnabled = false,
    )

    /** Split a logical frame into 20-byte packets and enqueue each (the driver's `frame` is identity). */
    private fun send(frame: LuckRingFrame) {
        for (packet in LuckRingPacketizer.packets(frame)) {
            writer?.enqueue(packet)
        }
    }

    // MARK: Startup

    override fun runStartup() {
        send(encoder.startupBundle(profile = userProfile, goalSteps = goalSteps))
        send(encoder.autoMonitoring(measurementSettings))

        send(encoder.request(LuckRingDataType.DEV_INFO))
        send(encoder.request(LuckRingDataType.BATTERY))
        send(encoder.request(LuckRingDataType.DEV_SYNC))

        historySync.start(LuckRingHistorySync.catalog)
    }

    /** History is pager-driven -- nothing here advances it. */
    override fun handle(event: RingDecodedEvent) {}

    // MARK: Live actions (per-metric `K6_DATA_TYPE_REAL_*` toggles)

    override fun startHeartRate() = send(encoder.realHeartRate(on = true))
    override fun stopHeartRate() = send(encoder.realHeartRate(on = false))
    // `measureHeartRateSpot` falls back to `startHeartRate` (the interface default) -- the ring has
    // no separate manual-HR command; a spot reading is the first sample off the same live stream.

    override fun startSpO2() = send(encoder.realSpO2(on = true))
    override fun stopSpO2() = send(encoder.realSpO2(on = false))

    /**
     * "Measure now" (combined) drives the blood-pressure toggle: it surfaces BP plus the HR the
     * ring streams alongside it. LuckRing has no single combined-sensor opcode (unlike Colmi's
     * `0x24`), so this picks one primary live metric — the same choice [YCBTSyncEngine] makes for
     * the TK5/SmartHealth family and for an identical reason: standalone HRV/temperature/stress spot
     * measurement rides the ring's own all-day monitors and history sync instead, matching this
     * app's product surface (no dedicated "Measure HRV" screen exists on Android).
     */
    override fun startCombinedMeasurement() = send(encoder.realBloodPressure(on = true))
    override fun stopCombinedMeasurement() = send(encoder.realBloodPressure(on = false))

    override fun findDevice() = send(encoder.findDevice())

    override fun setGoal(steps: Int) {
        goalSteps = steps
        send(encoder.setGoal(steps))
    }

    /** No LuckRing power-off/factory-reset opcode is implemented in this pass -- the buttons stay
     *  hidden (not declared in [LuckRingCoordinator.capabilities]), so these are never invoked. */
    override fun powerOff() {}
    override fun factoryReset() {}

    // MARK: Clock / profile

    /** The ring stamps records from its own RTC in true UTC, but the *offset* field in the clock
     *  payload still needs a re-push after a timezone change. */
    override fun resyncTime() = send(encoder.setTime())

    override fun setUserProfile(profile: UserProfileValues) {
        userProfile = profile
    }

    override fun applyUserProfile(profile: UserProfileValues) {
        userProfile = profile
        send(encoder.userInfo(profile))
    }

    // MARK: Measurement settings (opcode 128 -- the ring's own background-logging switch)

    /** Seed before [runStartup] (the coordinator pushes the stored per-device config here first). */
    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        if (settings != null) measurementSettings = settings
    }

    /** A live settings change from the UI -- push it to the ring immediately. */
    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        send(encoder.autoMonitoring(settings))
    }
}
