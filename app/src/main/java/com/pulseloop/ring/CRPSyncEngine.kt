package com.pulseloop.ring

/**
 * Per-connection orchestration for a CRP ("crrepa") ring. Ported in spirit from the Moyoung
 * "Da Rings" connect flow (`d1/b.java` + `b1` package builders): after the link is up the app sets the
 * clock and pushes user anthropometrics, then the ring streams current steps (`fdd1`) on its own
 * and answers measurement commands. The bulk-history state machine stays deferred for now.
 *
 * Scope: clock + user-info handshake, spot HR + SpO2 (Measure button), all-day vital timing
 * enable/disable driven by [MeasurementSettings], find-device, factory reset. Steps and battery
 * arrive as autonomous pushes/reads (see [CRPDriver]). HRV / stress / temperature are all-day
 * metrics — their timing is enabled here and live results decode via [CRPDecoder]. Of the stored
 * day timelines, sleep (group-2/cmd-14) is decoded ([CRPDecoder.decodeSleep], confirmed against a
 * hardware capture); the group-7 vital histories still land as acks pending a non-empty capture.
 */
class CRPSyncEngine(private val writer: RingCommandWriter?) : RingSyncEngine {

    private var profile: UserProfileValues? = null

    /** User-chosen all-day measurement config. Applied in the connect handshake and updatable
     *  live via [applyMeasurementSettings]. `null` ⇒ the user has never saved one; unlike QRing/YCBT
     *  the CRP ring exposes no way to read back its own config, so a fresh R11 ships with every
     *  all-day monitor OFF and never records anything to sync. We therefore fall back to
     *  [MeasurementSettings.ALL_ON_DEFAULT] (matching how [ColmiSyncEngine] force-enables on connect)
     *  so the day timeline actually accumulates. */
    private var measurementSettings: MeasurementSettings? = null

    override fun runStartup() {
        // Set the device clock first (matches the vendor's connect handshake), then user info so
        // the ring's step/calorie algorithm has real inputs.
        send(CRPProtocol.setTime())
        // Query firmware version so the UI doesn't show "Firmware: reading" (zaggash's report).
        send(CRPProtocol.queryFirmwareVersion())
        profile?.let { send(userInfoFrame(it)) }
        // Enable all-day vital monitoring. A fresh ring has these OFF, so without this the ring
        // stores no HR/SpO2/HRV/stress/temperature history and every history query below returns an
        // empty reply (issue #29, zaggash's full-day capture). Default to ALL_ON until the user
        // saves their own config, then honour it exactly.
        applyTimingSettings(measurementSettings ?: MeasurementSettings.ALL_ON_DEFAULT)
        // Pull the day's stored all-day timeline. runStartup() IS the poll pass (RingSyncWorker's
        // ~30-min background sync and the foreground syncNow() both re-invoke it), so this runs at
        // the app's configured polling cadence; the ring samples at hrIntervalMinutes (above).
        // The ring only emits history replies once asked — so this also produces the group-7/2
        // reply frames in a diagnostic capture, which is what we need to finish decoding them.
        // NOTE: the replies are currently acknowledged, not yet parsed into samples
        // (CRPDecoder.decodeHistoryOrDeviceInfoResponse is a TODO pending that capture).
        queryAllHistory()
    }

    /** Request the stored all-day timelines the ring has accumulated (group 7 for HR/stress/HRV/
     *  SpO2, group 2 for sleep/temp). Vendor `u3/g1.java` fires the same set on its sync pass. */
    private fun queryAllHistory() {
        send(CRPProtocol.queryHistoryHeartRate())
        send(CRPProtocol.queryHistorySpO2())
        send(CRPProtocol.queryHistoryHRV())
        send(CRPProtocol.queryHistoryStress())
        send(CRPProtocol.queryHistoryTemp())
        send(CRPProtocol.queryHistorySleep())
    }

    override fun handle(event: RingDecodedEvent) {
        // Steps/HR/battery are persisted by RingBLEClient via RingEventBridge; v1 keeps no
        // engine-side state (no staged history pipeline to advance).
    }

    // ---- Spot (manual) measurements. Trigger via the fdda command channel; the ring replies on
    // the same group-1/cmd, decoded by CRPDecoder.decodeVitalResult and persisted via the bridge.
    // HR and SpO2 are the app's spot-measurable vitals (MANUAL_* capabilities); HRV/stress/temp are
    // all-day metrics pulled through timing + history, not the Measure button. ----
    override fun startHeartRate() { send(CRPProtocol.measureHeartRate(true)) }
    override fun stopHeartRate() { send(CRPProtocol.measureHeartRate(false)) }

    override fun startSpO2() { send(CRPProtocol.measureSpO2(true)) }
    override fun stopSpO2() { send(CRPProtocol.measureSpO2(false)) }

    override fun findDevice() { send(CRPProtocol.findDevice(true)) }

    override fun factoryReset() { send(CRPProtocol.factoryReset()) }

    override fun powerOff() {
        // No confirmed shut-down opcode in the v1 subset (the vendor uses a callback-gated
        // notification write). Left unsupported rather than sending a guessed command.
    }

    override fun setGoal(steps: Int) {
        // Step-goal command layout not yet confirmed from the decompile; no-op for now.
    }

    override fun setUserProfile(profile: UserProfileValues) { this.profile = profile }

    override fun applyUserProfile(profile: UserProfileValues) {
        this.profile = profile
        send(userInfoFrame(profile))
    }

    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        measurementSettings = settings
    }

    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        applyTimingSettings(settings)
    }

    /** Send the all-day enable/disable command for every vital. The CRP protocol takes a single
     *  interval byte per enable, and [MeasurementSettings] carries only [MeasurementSettings.hrIntervalMinutes]
     *  (no per-vital cadence), so the HR interval is shared across the board. Disabled vitals are
     *  explicitly turned off so a reconnect can't leave a previously-enabled monitor running. */
    private fun applyTimingSettings(settings: MeasurementSettings) {
        if (settings.hrEnabled) send(CRPProtocol.enableTimingHeartRate(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingHeartRate())
        if (settings.hrvEnabled) send(CRPProtocol.enableTimingHRV(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingHRV())
        if (settings.stressEnabled) send(CRPProtocol.enableTimingStress(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingStress())
        if (settings.spo2Enabled) send(CRPProtocol.enableTimingSpO2(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingSpO2())
        if (settings.temperatureEnabled) send(CRPProtocol.enableTimingTemp())
        else send(CRPProtocol.disableTimingTemp())
    }

    override fun resyncTime() { send(CRPProtocol.setTime()) }

    /** Map the app's [UserProfileValues] onto the CRP user-info payload. Stride length isn't
     *  carried by the profile, so estimate it from height (~0.43·height, a common default). */
    private fun userInfoFrame(p: UserProfileValues): ByteArray {
        val heightCm = p.heightCm.toInt()
        val strideCm = (heightCm * 0.43).toInt().coerceIn(0, 255)
        return CRPProtocol.setUserInfo(
            heightCm = heightCm,
            weightKg = p.weightKg.toInt(),
            ageYears = p.age.toInt(),
            gender = p.gender.toInt(),
            strideCm = strideCm,
        )
    }

    private fun send(frame: ByteArray?) {
        if (frame != null) writer?.enqueue(frame)
    }
}
