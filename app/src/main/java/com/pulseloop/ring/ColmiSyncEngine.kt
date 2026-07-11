package com.pulseloop.ring

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*

/**
 * Ported from [ColmiSyncEngine] in ColmiSyncEngine.swift.
 * Colmi R02 sync engine: response-driven history state machine + realtime-HR keepalive.
 *
 * Stage order: activity(0..7) → HR(0..7) → stress → spo2(bigdata) → sleep(bigdata)
 * → hrv(0..6) → temperature(bigdata) → done.
 */
class ColmiSyncEngine(
    private var writer: RingCommandWriter?,
    private val decoder: ColmiDecoder
) : RingSyncEngine {
    private val encoder = ColmiEncoder
    private val zone = ZoneId.systemDefault()

    /** User-chosen all-day measurement config, applied in the connect handshake and updatable
     *  live. `null` ⇒ the user has never saved one: the ring's own settings (possibly
     *  configured in the official app) are the source of truth, so the handshake reads them
     *  and seeds the app config from the replies instead of force-writing defaults. */
    private var measurementSettings: MeasurementSettings? = null

    /** Sink for the config seeded from the ring's pref-read replies (no persisted config). */
    private var onConfigSeeded: ((MeasurementSettings) -> Unit)? = null

    /** Invoked when the ring's device-support reply advertises `supportBlePair`; the BLE client
     *  creates the OS bond in response (mirrors the official QRing app). */
    private var onBondRequested: (() -> Unit)? = null

    // Read-reply seeding state: collected until both the auto-HR and temp prefs reported.
    private var seedingFromRing = false
    private var seededHrIntervalMinutes: Int? = null
    private var seededTempEnabled: Boolean? = null

    /** The user's profile for the ring's user-preferences command. `null` ⇒ send the encoder's
     *  neutral defaults (matches prior behaviour) until the coordinator pushes real values. */
    private var userProfile: UserProfileValues? = null

    // History state machine
    private enum class Stage { IDLE, ACTIVITY, HEART_RATE, STRESS, SPO2, SLEEP, HRV, BP, TEMPERATURE, BLOOD_SUGAR, DONE }

    private var stage = Stage.IDLE
    private var daysAgo = 0
    private var syncDay = LocalDate.now(zone)

    /** A standalone sleep-only request is outstanding (see [syncSleepNow]). Set true when we fire
     *  `bigDataSleep()` outside the staged history sync; its big-data completion clears it and
     *  must NOT advance the pipeline into HRV. Volatile: set on Main, read on the notify thread. */
    @Volatile private var sleepOnlyActive = false
    private var sleepOnlyWatchdogJob: Job? = null

    // Watchdog
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val watchdogTimeoutMs = 10_000L
    private val activityWatchdogTimeoutMs = 20_000L

    // Realtime HR keepalive
    private var realtimeHRActive = false
    private var realtimeHRPacketCount = 0
    private var manualHRActive = false
    private var manualSpO2Active = false

    companion object {
        fun isHistoryOpcode(op: UByte): Boolean =
            op == ColmiCommandID.SYNC_ACTIVITY ||
            op == ColmiCommandID.SYNC_HEART_RATE ||
            op == ColmiCommandID.SYNC_STRESS ||
            op == ColmiCommandID.SYNC_HRV ||
            op == ColmiCommandID.BP_READ
    }

    override fun runStartup() {
        writer?.enqueue(encoder.phoneName())
        writer?.enqueue(encoder.setDateTime())
        writer?.enqueue(userPreferencesCommand())
        // Read the ring's capability bitfield early: the reply's supportBlePair bit drives the
        // OS bond (see handleRawNotify → onBondRequested). Harmless on rings that don't answer.
        writer?.enqueue(encoder.deviceSupport())
        writer?.enqueue(encoder.battery())
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_HR_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_STRESS_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_SPO2_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_HRV_PREF))
        writer?.enqueue(encoder.readTempPref())
        writer?.enqueue(encoder.readGoals())
        val settings = measurementSettings
        if (settings != null) {
            // The user saved a config in PulseLoop — it is the source of truth; push it so
            // the ring accumulates the data the history sync returns.
            enqueueMeasurementCommands(settings)
        } else {
            // Nothing persisted (fresh install / first connect): don't clobber ring-side
            // settings the user may have configured elsewhere. The AUTO_HR_PREF / temp-pref
            // reads above seed the app config from the ring's replies (handleRawNotify).
            // SpO2/stress/HRV are still force-enabled — the history sync depends on them
            // and they have always been enabled on connect.
            seedingFromRing = true
            seededHrIntervalMinutes = null
            seededTempEnabled = null
            writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_SPO2_PREF, enabled = true))
            writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_STRESS_PREF, enabled = true))
            writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_HRV_PREF, enabled = true))
        }
        startHistorySync()
    }

    /**
     * On-demand sleep fetch (QRing parity): request just the sleep big-data record, off the
     * staged history pipeline, so it can't be lost to an earlier stage's watchdog skip. The
     * decoder emits sleep events from the reply exactly as it does during a full sync; the only
     * difference is [handleBigDataComplete] must not advance into HRV afterwards
     * ([sleepOnlyActive]). If a full history sync is already running it will fetch sleep itself,
     * so this becomes a no-op then.
     */
    override fun syncSleepNow() {
        if (sleepOnlyActive) return  // one already outstanding — don't double-request
        if (stage != Stage.IDLE && stage != Stage.DONE) return  // a full sync already covers sleep
        sleepOnlyActive = true
        requestSleep()
        sleepOnlyWatchdogJob?.cancel()
        sleepOnlyWatchdogJob = scope.launch {
            delay(watchdogTimeoutMs)
            sleepOnlyActive = false  // reply never completed — give up quietly, no pipeline advance
        }
    }

    // MARK: Measurement settings + user profile (iOS #19)

    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        measurementSettings = settings
    }

    override fun setOnMeasurementConfigSeeded(callback: (MeasurementSettings) -> Unit) {
        onConfigSeeded = callback
    }

    override fun setOnBondRequested(callback: () -> Unit) {
        onBondRequested = callback
    }

    /**
     * Consume the connect handshake's pref-read replies while seeding (no persisted config).
     * One exception to "the ring's settings win": all-day HR must be ON or the `0x15` HR
     * history sync returns nothing — so a disabled reply gets a re-enable write, keeping
     * the ring's reported interval.
     */
    override fun handleRawNotify(data: ByteArray) {
        // Device-support reply is independent of config seeding: if the ring wants a bond, ask
        // the client to create one. Return early — a 0x3C frame carries nothing else we consume.
        decoder.decodeDeviceSupport(data)?.let { supportsBlePair ->
            if (supportsBlePair) onBondRequested?.invoke()
            return
        }
        if (!seedingFromRing) return
        decoder.decodeAutoHRPrefRead(data)?.let { readout ->
            val interval = if (readout.intervalMinutes in 5..60) readout.intervalMinutes else 5
            if (!readout.enabled) {
                writer?.enqueue(encoder.autoHeartRate(enabled = true, intervalMinutes = interval))
            }
            seededHrIntervalMinutes = interval
            finishSeedingIfComplete()
        }
        decoder.decodeTempPrefRead(data)?.let { enabled ->
            seededTempEnabled = enabled
            finishSeedingIfComplete()
        }
    }

    /** Once both prefs reported, adopt the seeded config and surface it for persistence. */
    private fun finishSeedingIfComplete() {
        val interval = seededHrIntervalMinutes ?: return
        val tempEnabled = seededTempEnabled ?: return
        seedingFromRing = false
        val settings = MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = interval,   // HR forced on (see handleRawNotify)
            spo2Enabled = true, stressEnabled = true, hrvEnabled = true,  // force-enabled at startup
            temperatureEnabled = tempEnabled,
        )
        measurementSettings = settings
        onConfigSeeded?.invoke(settings)
    }

    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        enqueueMeasurementCommands(settings)
    }

    override fun setUserProfile(profile: UserProfileValues) {
        userProfile = profile
    }

    override fun applyUserProfile(profile: UserProfileValues) {
        userProfile = profile
        writer?.enqueue(userPreferencesCommand())
    }

    /** Translate the device-agnostic settings into Colmi pref commands. HR uses the dedicated
     *  `0x16` command (interval + on/off in one); SpO2/stress/HRV/temp are simple on/off prefs. */
    private fun enqueueMeasurementCommands(s: MeasurementSettings) {
        writer?.enqueue(encoder.autoHeartRate(enabled = s.hrEnabled, intervalMinutes = s.hrIntervalMinutes))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_SPO2_PREF, enabled = s.spo2Enabled))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_STRESS_PREF, enabled = s.stressEnabled))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_HRV_PREF, enabled = s.hrvEnabled))
        writer?.enqueue(encoder.writeTempPref(enabled = s.temperatureEnabled))
    }

    /** Build the user-preferences command from the stored profile, falling back to the encoder's
     *  neutral defaults when no profile has been pushed yet. */
    private fun userPreferencesCommand(): ByteArray {
        val p = userProfile ?: return encoder.userPreferences()
        return encoder.userPreferences(
            metric = p.metric, gender = p.gender, age = p.age,
            heightCm = p.heightCm, weightKg = p.weightKg,
        )
    }

    private fun startHistorySync() {
        // A full sync fetches sleep itself; cancel any standalone sleep-only request so its
        // big-data completion doesn't short-circuit the pipeline's own SLEEP stage.
        sleepOnlyActive = false
        sleepOnlyWatchdogJob?.cancel(); sleepOnlyWatchdogJob = null
        daysAgo = 0
        stage = Stage.ACTIVITY
        requestActivity()
        armWatchdog()
    }

    override fun handle(event: RingDecodedEvent) {}

    // MARK: Driver hooks

    fun handleHistoryFrame(data: ByteArray): List<RingDecodedEvent> {
        val events = decoder.decodeHistory(data, day = syncDay)
        advanceAfterPagedFrame(data)
        armWatchdog()
        return events
    }

    fun handleBigDataComplete(type: UByte) {
        when (type) {
            ColmiCommandID.BIG_DATA_SPO2 -> {
                stage = Stage.SLEEP; requestSleep(); armWatchdog()
            }
            ColmiCommandID.BIG_DATA_SLEEP -> {
                // A standalone sleep-only fetch just completed: the decoder already emitted the
                // sleep events; do NOT drive the full-sync pipeline into HRV.
                if (sleepOnlyActive) {
                    sleepOnlyActive = false
                    sleepOnlyWatchdogJob?.cancel(); sleepOnlyWatchdogJob = null
                    return
                }
                // Only the pipeline's own SLEEP stage should advance to HRV. Ignore a stray sleep
                // completion that arrives when we're not on SLEEP — e.g. a standalone reply that
                // landed after a full sync started (startHistorySync cleared sleepOnlyActive), or a
                // late reply after the watchdog already skipped SLEEP. Otherwise it jumps/duplicates
                // the pipeline (ACTIVITY→HRV, skipping HR/STRESS/SPO2, or a second HRV request).
                if (stage != Stage.SLEEP) return
                stage = Stage.HRV; daysAgo = 0; requestHRV(); armWatchdog()
            }
            ColmiCommandID.BIG_DATA_TEMPERATURE -> {
                stage = Stage.BLOOD_SUGAR; requestBloodSugar(); armWatchdog()
            }
            ColmiCommandID.BIG_DATA_BLOOD_SUGAR -> finishSync()
        }
    }

    fun observedRealtimeHeartRate() {
        if (!realtimeHRActive) return
        realtimeHRPacketCount = (realtimeHRPacketCount + 1) % 30
        if (realtimeHRPacketCount == 0) {
            writer?.enqueue(encoder.realtimeHeartRateContinue())
        }
    }

    // MARK: Stage requests

    private fun requestActivity() {
        syncDay = dayStart(daysAgo)
        writer?.enqueue(encoder.syncActivity(daysAgo))
    }

    private fun requestHeartRate() {
        syncDay = dayStart(daysAgo)
        val unix = syncDay.atStartOfDay(zone).toEpochSecond().toInt()
        writer?.enqueue(encoder.syncHeartRate(unix))
    }

    private fun requestStress() {
        syncDay = LocalDate.now(zone)
        writer?.enqueue(encoder.syncStress())
    }

    private fun requestHRV() {
        syncDay = dayStart(daysAgo)
        writer?.enqueue(encoder.syncHRV(daysAgo))
    }

    private fun requestBp() { writer?.enqueue(encoder.syncBp()) }

    private fun requestSpo2() { writer?.enqueue(encoder.bigDataSpo2()) }
    private fun requestSleep() { writer?.enqueue(encoder.bigDataSleep()) }
    private fun requestTemperature() { writer?.enqueue(encoder.bigDataTemperature()) }
    private fun requestBloodSugar() { writer?.enqueue(encoder.bigDataBloodSugar()) }

    private fun dayStart(daysAgo: Int): LocalDate = LocalDate.now(zone).minusDays(daysAgo.toLong())

    // MARK: Paged stage advancement

    private fun advanceAfterPagedFrame(data: ByteArray) {
        val packetNr = ColmiDecoder.historyPacketNumber(data)
        val isEmpty = packetNr == 0xFF
        val dayComplete = isEmpty || isTerminalPacket(data)
        if (!dayComplete) return

        when (stage) {
            Stage.ACTIVITY -> {
                if (daysAgo < 7) { daysAgo++; requestActivity() }
                else { daysAgo = 0; stage = Stage.HEART_RATE; requestHeartRate() }
            }
            Stage.HEART_RATE -> {
                if (daysAgo < 7) { daysAgo++; requestHeartRate() }
                else { stage = Stage.STRESS; requestStress() }
            }
            Stage.STRESS -> { stage = Stage.SPO2; requestSpo2() }
            Stage.HRV -> {
                if (daysAgo < 6) { daysAgo++; requestHRV() }
                else { stage = Stage.BP; requestBp() }
            }
            Stage.BP -> {
                // BP is a single bulk response, not paged
                stage = Stage.TEMPERATURE; requestTemperature()
            }
            else -> {}
        }
    }

    private fun isTerminalPacket(data: ByteArray): Boolean {
        val v = data.map { it.toUByte() }
        if (v.size < 7) return false
        return when (v[0]) {
            ColmiCommandID.SYNC_STRESS, ColmiCommandID.SYNC_HRV -> v[1].toInt() == 4
            ColmiCommandID.SYNC_ACTIVITY -> v[5].toInt() == v[6].toInt() - 1
            else -> false
        }
    }

    // MARK: Watchdog

    private fun armWatchdog() {
        watchdogJob?.cancel()
        val expected = stage
        val timeout = if (expected == Stage.ACTIVITY) activityWatchdogTimeoutMs else watchdogTimeoutMs
        watchdogJob = scope.launch {
            delay(timeout)
            if (isActive) forceAdvanceStage(expected)
        }
    }

    private fun forceAdvanceStage(stuck: Stage) {
        when (stuck) {
            Stage.ACTIVITY -> { daysAgo = 0; stage = Stage.HEART_RATE; requestHeartRate() }
            Stage.HEART_RATE -> { stage = Stage.STRESS; requestStress() }
            Stage.STRESS -> { stage = Stage.SPO2; requestSpo2() }
            Stage.SPO2 -> { stage = Stage.SLEEP; requestSleep() }
            Stage.SLEEP -> { daysAgo = 0; stage = Stage.HRV; requestHRV() }
            Stage.HRV -> { stage = Stage.BP; requestBp() }
            Stage.BP -> { stage = Stage.TEMPERATURE; requestTemperature() }
            Stage.TEMPERATURE -> { stage = Stage.BLOOD_SUGAR; requestBloodSugar() }
            Stage.BLOOD_SUGAR -> finishSync()
            else -> {}
        }
        if (stage != Stage.DONE) armWatchdog()
    }

    private fun finishSync() {
        stage = Stage.DONE
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // MARK: Measurement actions

    override fun startHeartRate() {
        realtimeHRActive = true
        realtimeHRPacketCount = 0
        writer?.enqueue(encoder.realtimeHeartRate(enable = true))
    }

    override fun stopHeartRate() {
        if (manualHRActive) {
            manualHRActive = false
            writer?.enqueue(encoder.manualHeartRate(enable = false))
        }
        if (!realtimeHRActive) return
        realtimeHRActive = false
        writer?.enqueue(encoder.realtimeHeartRate(enable = false))
    }

    override fun measureHeartRateSpot() {
        manualHRActive = true
        writer?.enqueue(encoder.manualHeartRate(enable = true))
    }

    override fun startSpO2() {
        // On-demand live SpO₂ via the real-time command (0x69/3). The ring streams
        // [0x69, 3, error, value] frames decoded to Spo2Result. (Historical SpO₂ is a
        // separate big-data path, requestSpo2(), used by the startup history sync.)
        manualSpO2Active = true
        writer?.enqueue(encoder.manualSpO2(enable = true))
    }

    override fun stopSpO2() {
        if (!manualSpO2Active) return
        manualSpO2Active = false
        writer?.enqueue(encoder.manualSpO2(enable = false))
    }

    override fun findDevice() {
        writer?.enqueue(encoder.findDevice())
    }

    override fun setGoal(steps: Int) {
        // Colmi goals write left minimal pending verification
    }

    override fun powerOff() {
        writer?.enqueue(encoder.powerOff())
    }

    override fun factoryReset() {
        writer?.enqueue(encoder.factoryReset())
    }

    fun destroy() {
        scope.cancel()
    }
}
