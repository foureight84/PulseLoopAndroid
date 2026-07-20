package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.ring.*
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Ported from [RingSyncCoordinator] in RingSyncCoordinator.swift.
 * High-level orchestration of ring command flows. Subscribes to PulseEventBus to
 * track latest measurement values and expose app-facing measurement actions.
 */
class RingSyncCoordinator(
    private val client: RingBLEClient,
    private val db: PulseLoopDatabase,
    private val apiKeyStore: ApiKeyStore? = null,
) {
    enum class MeasureState { IDLE, MEASURING, DONE, FAILED }

    var hrState: MeasureState = MeasureState.IDLE
        private set
    var spo2State: MeasureState = MeasureState.IDLE
        private set
    var combinedState: MeasureState = MeasureState.IDLE
        private set
    var lastSyncAt: Long? = null
        private set

    /**
     * History-sync progress, 0–100 while records stream in after a connect, null when
     * idle/done. Computed the same way as the official app's "Sync data X%": the newest
     * received record's timestamp mapped onto the [now − N days, now] window, monotonic.
     */
    private val _syncProgress = MutableStateFlow<Int?>(null)
    val syncProgress: StateFlow<Int?> = _syncProgress.asStateFlow()
    private var syncWindowStart = 0L
    private var syncWindowEnd = 0L
    private var syncResetJob: Job? = null
    private var lastAdvanceAt = 0L
    /** Days of history requested on startup — must match makeHistoryQueryCommand's default. */
    private val syncWindowDays = 1
    /** How often the stall-watcher checks, and how long without progress before it gives up. */
    private val SYNC_STALL_CHECK_MS = 2_000L
    private val SYNC_STALL_MS = 12_000L

    /** Latest live HR bpm, mirrored for UI without a query. */
    var latestHRValue: Int? = null
        private set
    /** Latest live SpO2 %, mirrored for UI without a query. */
    var latestSpO2Value: Int? = null
        private set

    var workoutHRActive = false
        private set
    private var hrNoReadingReported = false
    private var spo2NoReadingReported = false
    /** The samples of the HR measurement in flight, and the rule for whether they settled — see
     *  [HRSampleWindow], which owns the warm-up echo and the consistency gate (iOS #66). */
    private val hrWindow = HRSampleWindow()
    /** The refusal fast-fail gate for spot measurements (iOS `c8969a4`) — the ring's `03 2f`
     *  verdict can only ever abort the measurement it names, while it is actually running. */
    private val spot = SpotMeasurementGate()
    /** True once the current measurement has produced a real (post-warm-up) bpm; keeps a stale
     *  [latestHRValue] from passing for a fresh reading. */
    val measurementReceivedReading: Boolean get() = hrWindow.receivedReading

    val connectionState: RingConnectionState get() = client.state.value.connectionState
    val isConnected: Boolean get() = connectionState == RingConnectionState.CONNECTED

    private val hrMeasureSeconds = HR_MEASURE_SECONDS.toLong()
    private val spo2MeasureSeconds = SPO2_MEASURE_SECONDS.toLong()
    private val combinedMeasureSeconds = COMBINED_MEASURE_SECONDS.toLong()

    companion object {
        /** Duration of a combined spot measurement (0x23→0x24); also drives the UI countdown. */
        const val COMBINED_MEASURE_SECONDS = 45
        /** Window for the live-HR leg of a spot measurement. */
        const val HR_MEASURE_SECONDS = 30
        /** Window for the live-SpO₂ leg of a spot measurement. iOS raised this 40 → 60
         *  (`c8969a4`): the R99's successful sweep took 38s while another attempt ran past 41s
         *  with no result — at 40s the outcome is a coin toss where the user watches the ring's
         *  red LED work and gets an error anyway. */
        const val SPO2_MEASURE_SECONDS = 60
        /** Upper-bound for a sequential HR+SpO₂ spot measurement; drives the UI countdown.
         *  Derived from the legs so the countdown can't desync when one is tuned. Post-#66 the
         *  HR leg samples its full window by design (no early exit), so this is a real bound,
         *  not slack. */
        const val SPOT_MEASURE_SECONDS = HR_MEASURE_SECONDS + SPO2_MEASURE_SECONDS + 1
        /** Max time to wait for the pre-factory-reset history sync before resetting anyway. */
        const val SYNC_BEFORE_RESET_TIMEOUT_MS = 30_000L
    }

    private val engine: RingSyncEngine? get() = client.syncEngine
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamJob: Job? = null

    fun start() {
        streamJob?.cancel()
        streamJob = null
        streamJob = scope.launch {
            PulseEventBus.events.collect { event -> handle(event) }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    // MARK: - Actions

    /** Canonical startup sequence run on connect. */
    fun runStartupSequence() {
        // Begin progress here (a real sync request), NOT on DeviceStateChanged(CONNECTED):
        // the ring re-emits CONNECTED on every 0x0C status packet, which would otherwise
        // keep resetting the bar to 0%.
        beginSyncProgress()
        scope.launch {
            // Push the persisted measurement config + profile into the engine BEFORE
            // runStartup so the connect handshake reflects them (the engine emits the
            // commands itself, so we don't double-send here). iOS #19 parity.
            // No persisted config (null) ⇒ the engine seeds one from the ring's own
            // reported settings; persist that as the device's initial config.
            val persisted = loadMeasurementSettings()
            engine?.setMeasurementSettings(persisted)
            if (persisted == null) {
                engine?.setOnMeasurementConfigSeeded { seeded ->
                    scope.launch { persistSeededMeasurementConfig(db, seeded) }
                }
            }
            loadUserProfileValues()?.let { engine?.setUserProfile(it) }
            // Claim the ring for this app FIRST (0x48). The ring binds to the connecting app's
            // id and otherwise can stay mute after another app (e.g. the official one) claimed it.
            apiKeyStore?.ringAppId?.let { engine?.setAppId(it) }
            engine?.runStartup()
            // Push the user's profile so the ring's blood-sugar (profile-derived) and
            // calorie algorithms run on real inputs. BP is a direct sensor reading and
            // does not depend on user info. Matches the official app, which calls
            // setUserInfo on every connect anyway.
            pushUserSettingsFromStore()
            lastSyncAt = System.currentTimeMillis()
        }
    }

    /** The persisted per-device measurement config, or null when the user never saved one. */
    private suspend fun loadMeasurementSettings(): MeasurementSettings? =
        loadPersistedMeasurementSettings(db)

    private suspend fun loadUserProfileValues(): UserProfileValues? =
        loadPersistedUserProfile(db, apiKeyStore)

    /**
     * Live "Save" from the Measurement settings section: persist nothing here (the view owns
     * the Room write), just push the latest config to the connected ring so it takes effect
     * immediately. No-op when disconnected — applied on the next connect handshake instead.
     */
    fun applyMeasurementSettings() {
        if (!isConnected) return
        scope.launch {
            loadMeasurementSettings()?.let { engine?.applyMeasurementSettings(it) }
        }
    }

    /** Live push of the profile-backed user-preferences command (Colmi 0x02-equivalent). */
    fun applyUserProfileToRing() {
        if (!isConnected) return
        scope.launch {
            loadUserProfileValues()?.let { engine?.applyUserProfile(it) }
        }
    }

    /** Read the stored profile + BP calibration and push them to the ring. */
    private fun pushUserSettingsFromStore() {
        scope.launch {
            val profile = try { db.userProfileDao().get() } catch (_: Exception) { null }
            applyUserSettings(
                profile,
                apiKeyStore?.bpAdjustSystolic ?: 0,
                apiKeyStore?.bpAdjustDiastolic ?: 0,
            )
        }
    }

    /**
     * Send user info (0x02) and BP calibration (0x33) to the ring. Called on
     * connect and immediately after the user edits their profile in Settings.
     * User info feeds the ring's blood-sugar (profile-derived estimate) and
     * calorie algorithms only; BP is a direct sensor reading. Values must be
     * metric (cm / kg); [makeUserInfoCommand] transmits them with the metric flag.
     */
    fun applyUserSettings(profile: UserProfileEntity?, bpSystolic: Int, bpDiastolic: Int) {
        if (!isConnected) return
        profile?.let { p ->
            val age = p.age
            val heightCm = p.heightCm?.toInt()
            val weightKg = p.weightKg?.toInt()
            if (age != null && heightCm != null && weightKg != null) {
                val isMale = p.sex?.equals("male", ignoreCase = true) == true
                engine?.setUserInfo(age, isMale, heightCm, weightKg)
            }
        }
        if (bpSystolic in 1..300 && bpDiastolic in 1..300) {
            engine?.setBloodPressureAdjust(bpSystolic, bpDiastolic)
        }
    }

    fun syncNow() {
        if (!isConnected) return
        runStartupSequence()
    }

    /**
     * On-demand, sleep-only sync (QRing-style): fetch just the sleep record without running the
     * whole history pipeline. Wired to the Sleep screen opening so a user who wants last night's
     * sleep gets a dedicated request instead of depending on the full sync's SLEEP stage
     * surviving four earlier stages. No-op when disconnected, or on rings that fetch sleep in
     * bulk (jring).
     */
    fun syncSleepNow() {
        if (!isConnected) return
        engine?.syncSleepNow()
    }

    /** Pull-to-refresh entry point. */
    suspend fun pullToRefresh() {
        if (isConnected) {
            runStartupSequence()
        } else if (client.state.value.activeDeviceType != null) {
            client.connectLastKnown()
        } else {
            client.startScanning()
        }
        delay(1200)
    }

    // MARK: - Workout HR streaming

    fun startWorkoutHeartRate() {
        if (!isConnected) return
        engine?.startHeartRate()
        workoutHRActive = true
    }

    fun stopWorkoutHeartRate() {
        if (!workoutHRActive) return
        engine?.stopHeartRate()
        workoutHRActive = false
    }

    /**
     * Ported from iOS's `restartWorkoutHeartRateIfActive()` (RingSyncCoordinator.swift:418).
     * A spot read's stop also tears down the realtime stream (Colmi stops both 0x69 and 0x1e),
     * so if a workout stream is supposed to be running, bring it straight back — the same shape
     * the SmartHealth vendor app uses (it re-issues the identical enable after every
     * interruption: reconnect, sync-end, resume — no delay, no special opcode). The engine's
     * `startHeartRate()` is idempotent (re-arms the keepalive), so this is safe to call
     * liberally; without it the keepalive only sends the *continue* frame, which can't revive a
     * mode the ring already dropped.
     */
    fun restartWorkoutHeartRateIfActive() {
        if (!workoutHRActive || !isConnected) return
        engine?.startHeartRate()
    }

    fun querySleep() {
        if (!isConnected) return
        engine?.runStartup()
    }

    fun findRing() {
        if (!isConnected) return
        engine?.findDevice()
    }

    /**
     * Non-destructive: unbind + disconnect + drop the ring from the app. The ring keeps all
     * of its on-device data, stays powered on, and can be re-paired immediately. Does NOT
     * power off or factory-reset — that would wipe a Colmi ring's unsynced history and leave
     * it dark until charged. For a true wipe use [factoryResetRing].
     *
     * [onCleared] runs on the coordinator's own long-lived scope, so callers can do their
     * cleanup (e.g. clearing the device row) without tying it to a screen's lifecycle.
     */
    fun forgetRing(onCleared: suspend () -> Unit) {
        // client.forget() already sends the protocol unbind, waits for the ack, removes any
        // OS bond, and clears the stored peripheral. That is the whole forget.
        scope.launch {
            client.forget()
            stop()
            onCleared()
        }
    }

    /**
     * Destructive: wipe the ring's on-device storage. Because a Colmi ring buffers days of
     * unsynced history, we sync the latest data into the app FIRST, then send the factory
     * reset, then forget. Gate this on the ring's FACTORY_RESET capability at the call site.
     * [onProgress] receives a short status for the UI; [onCleared] fires when fully done and
     * runs on the coordinator's own long-lived scope (see [forgetRing]).
     */
    fun factoryResetRing(onProgress: (String) -> Unit = {}, onCleared: suspend () -> Unit) {
        scope.launch {
            if (isConnected) {
                onProgress("Syncing latest data…")
                runStartupSequence()
                // Wait for the history sync to drain (progress reaches 100 or clears), capped
                // so a stale link can never hang the reset.
                kotlinx.coroutines.withTimeoutOrNull(SYNC_BEFORE_RESET_TIMEOUT_MS) {
                    syncProgress.first { it == null || it >= 100 }
                }
                kotlinx.coroutines.delay(800)  // let the final history writes flush
                onProgress("Resetting ring…")
                engine?.factoryReset()
                // The reset frame is only ENQUEUED on the GATT op queue; wait until it has
                // actually been written and acked before forget() tears the queue down —
                // otherwise a slow queue silently swallows the wipe the user confirmed.
                client.awaitOpsFlushed()
            }
            client.forget()
            stop()
            onCleared()
        }
    }

    fun setGoal(steps: Int) {
        if (isConnected) engine?.setGoal(steps)
        scope.launch {
            val goal = db.userGoalDao().get()
            if (goal != null) {
                db.userGoalDao().upsert(goal.copy(steps = steps, updatedAt = System.currentTimeMillis()))
            } else {
                db.userGoalDao().upsert(UserGoalEntity(steps = steps))
            }
        }
    }

    // MARK: - Spot measurements

    /**
     * Manual spot measurement for rings without the combined 0x23 packet (e.g. Colmi):
     * live HR then live SpO₂, each capability-gated, run sequentially through the same
     * paths the Today/Vitals views read. Each leg returns early once it gets a reading.
     *
     * Triggered only by the Vitals "Measure" button. Matching iOS, connecting does NOT
     * auto-measure — the ring does its own low-power periodic monitoring (pulled in via the
     * history sync), so we never pin the optical sensor on just for connecting.
     */
    suspend fun measureSpot() {
        if (!isConnected) return
        val caps = client.state.value.activeCapabilities
        if (caps.contains(WearableCapability.MANUAL_HEART_RATE)) measureHR()
        if (caps.contains(WearableCapability.MANUAL_SPO2)) measureSpO2()
    }

    suspend fun measureHR(): Int? {
        if (hrState == MeasureState.MEASURING) return null
        if (!isConnected) { hrState = MeasureState.FAILED; return null }
        hrState = MeasureState.MEASURING
        // Do NOT clear latestHRValue — it's the live value the workout UI shows, so a new
        // measurement keeps the last reading on screen until a fresh one replaces it.
        hrNoReadingReported = false
        hrWindow.begin()

        val spotToken = spot.begin(YCBTMeasurementMode.HEART_RATE)
        engine?.measureHeartRateSpot()
        var result: Int? = null
        try {
            // Sample the full window in 0.5s steps: handle() drops everything inside the 5s warm-up
            // (the ring's cached-echo bpm) and collects the rest. We break out early only where
            // continuing is pointless — and each of those is an abort, not a short-but-usable reading,
            // so none of them report a value (iOS #66).
            var aborted = false
            val steps = (hrMeasureSeconds * 2).toInt()   // 0.5s granularity
            for (i in 0 until steps) {
                // The ring reported "worn incorrectly", or refused the measurement outright.
                if (hrNoReadingReported || spot.isRejected(spotToken)) { aborted = true; break }
                // Ring removed / BLE dropped mid-measure → fail rather than settle a truncated window.
                if (!isConnected) { aborted = true; break }
                // Contact lost after readings began (ring slipped / hand moved).
                if (hrWindow.contactLost()) { aborted = true; break }
                delay(500)
            }
            result = if (aborted) null else hrWindow.stableValue
        } finally {
            spot.end(spotToken)
            // Always switch the optical sensor off — even if the caller's coroutine is
            // cancelled (e.g. the user navigates away mid-measurement) — or the ring keeps pulsing.
            engine?.stopHeartRate()
            // The stop also tears down the workout's realtime stream; bring it straight back.
            restartWorkoutHeartRateIfActive()
            hrState = if (result != null) MeasureState.DONE else MeasureState.FAILED
        }
        return result
    }

    suspend fun measureSpO2(): Int? {
        if (spo2State == MeasureState.MEASURING) return null
        if (!isConnected) { spo2State = MeasureState.FAILED; return null }
        spo2State = MeasureState.MEASURING
        latestSpO2Value = null
        spo2NoReadingReported = false
        val spotToken = spot.begin(YCBTMeasurementMode.SPO2)
        engine?.startSpO2()
        var result: Int? = null
        try {
            // Abort early when the ring reports the run ended with an error (finger off,
            // ring not worn) or refused the start, instead of idling out the full window.
            result = pollForValue(spo2MeasureSeconds, { latestSpO2Value }, { spo2NoReadingReported || spot.isRejected(spotToken) })
        } finally {
            spot.end(spotToken)
            engine?.stopSpO2()   // stop the sensor even on cancellation (see measureHR)
            restartWorkoutHeartRateIfActive()   // the stop preempts the workout's HR stream
            spo2State = if (result != null) MeasureState.DONE else MeasureState.FAILED
        }
        return result
    }

    /**
     * Trigger the combined spot measurement (0x23). The ring replies with 0x24 carrying
     * blood pressure, SpO₂, stress, fatigue and blood sugar in one packet; those decode
     * through the normal event pipeline into Room and onto the Vitals/Today views.
     * Runs for ~45s, matching the official app's combined measurement window.
     */
    suspend fun measureCombined() {
        if (combinedState == MeasureState.MEASURING) return
        if (!isConnected) { combinedState = MeasureState.FAILED; return }
        combinedState = MeasureState.MEASURING
        engine?.startCombinedMeasurement()
        try {
            repeat(combinedMeasureSeconds.toInt()) { delay(1000) }
        } finally {
            engine?.stopCombinedMeasurement()   // stop even on cancellation (see measureHR)
            restartWorkoutHeartRateIfActive()   // the stop preempts the workout's HR stream
            combinedState = MeasureState.DONE
        }
    }

    private suspend fun pollForValue(
        windowSec: Long,
        value: () -> Int?,
        abort: () -> Boolean,
    ): Int? {
        val steps = (windowSec * 2).toInt()
        repeat(steps) {
            value()?.let { return it }
            if (abort()) return null
            delay(500)
        }
        return value()
    }

    // MARK: - Event handling

    private fun handle(event: PulseEvent) {
        when (event) {
            is PulseEvent.HeartRateSample -> {
                latestHRValue = event.bpm
                if (hrState == MeasureState.MEASURING) hrWindow.collect(event.bpm)
            }
            is PulseEvent.HeartRateComplete -> {
                if (hrState == MeasureState.MEASURING && !measurementReceivedReading) {
                    hrNoReadingReported = true
                }
            }
            is PulseEvent.Spo2Result -> {
                latestSpO2Value = event.value
            }
            is PulseEvent.Spo2Complete -> {
                if (spo2State == MeasureState.MEASURING && latestSpO2Value == null) {
                    spo2NoReadingReported = true
                }
            }
            is PulseEvent.DeviceStateChanged -> {
                when (event.state) {
                    RingConnectionState.CONNECTED -> lastSyncAt = System.currentTimeMillis()
                    RingConnectionState.DISCONNECTED,
                    RingConnectionState.FAILED,
                    RingConnectionState.IDLE -> clearSyncProgress()
                    else -> {}
                }
            }
            // `MeasurementRejected` has no PulseEvent of its own — it is a verdict on a command,
            // not data — so the raw-packet feed (which carries every decoded frame) is where a
            // measurement hears the ring say no (iOS `c8969a4`).
            is PulseEvent.RawPacket -> {
                val decoded = event.decoded
                if (event.direction == PacketDirection.INCOMING && decoded is RingDecodedEvent.MeasurementRejected) {
                    spot.noteRejected(decoded.mode)
                }
            }
            // History records stream in oldest→newest; advance the progress bar by mapping
            // each record's timestamp onto the sync window.
            is PulseEvent.ActivityBucket -> advanceSyncProgress(event.timestamp.toEpochMilli())
            is PulseEvent.ActivityUpdate -> advanceSyncProgress(event.timestamp.toEpochMilli())
            is PulseEvent.SleepTimeline -> advanceSyncProgress(event.timestamp.toEpochMilli())
            is PulseEvent.HistoryMeasurement -> advanceSyncProgress(event.timestamp.toEpochMilli())
            is PulseEvent.SyncProgress -> if (event.stage == "done") finishSyncProgressSoon()
            else -> {}
        }
    }

    // MARK: - Sync progress (mirrors official "Sync data X%")

    private fun beginSyncProgress() {
        syncWindowEnd = System.currentTimeMillis()
        syncWindowStart = syncWindowEnd - syncWindowDays * 86_400_000L
        lastAdvanceAt = syncWindowEnd
        syncResetJob?.cancel()
        _syncProgress.value = 0
        // Never let the indicator stick: if no history record advances it for a while
        // (e.g. a stale link, or the ring has nothing to send), wrap it up / hide it.
        syncResetJob = scope.launch {
            while (isActive) {
                delay(SYNC_STALL_CHECK_MS)
                val v = _syncProgress.value ?: break
                if (v >= 100) break
                if (System.currentTimeMillis() - lastAdvanceAt > SYNC_STALL_MS) {
                    if (v > 0) finishSyncProgressSoon() else _syncProgress.value = null
                    break
                }
            }
        }
    }

    private fun advanceSyncProgress(recordEpochMs: Long) {
        // Only while a sync is active — ignore live measurements arriving after sync.
        val current = _syncProgress.value ?: return
        if (syncWindowEnd <= syncWindowStart) return
        val span = (syncWindowEnd - syncWindowStart).toDouble()
        val pct = (((recordEpochMs - syncWindowStart) * 100.0) / span).toInt().coerceIn(0, 100)
        lastAdvanceAt = System.currentTimeMillis()  // data is flowing — keep the bar alive
        if (pct > current) _syncProgress.value = pct
        if (pct >= 100) finishSyncProgressSoon()
    }

    private fun finishSyncProgressSoon() {
        if (_syncProgress.value == null) return
        syncResetJob?.cancel()
        syncResetJob = scope.launch {
            _syncProgress.value = 100
            delay(1500)
            _syncProgress.value = null
        }
    }

    private fun clearSyncProgress() {
        syncResetJob?.cancel()
        _syncProgress.value = null
    }
}

/**
 * The persisted per-device measurement config, or null when the user never saved one.
 * Shared by the foreground coordinator and the background [RingSyncWorker] so BOTH
 * connect handshakes push the user's saved settings. null tells the engine to seed
 * from the ring's own reported settings instead of force-writing all-on defaults —
 * which would silently override ring-side settings (e.g. a 60-min HR interval or
 * temperature off, configured in the official app) on every fresh install.
 */
internal suspend fun loadPersistedMeasurementSettings(db: PulseLoopDatabase): MeasurementSettings? {
    val device = try { db.deviceDao().current() } catch (_: Exception) { null }
        ?: return null
    val config = try { db.deviceMeasurementConfigDao().byDevice(device.id) } catch (_: Exception) { null }
        ?: return null
    return MeasurementSettings(
        hrEnabled = config.hrEnabled,
        hrIntervalMinutes = config.hrIntervalMinutes,
        spo2Enabled = config.spo2Enabled,
        stressEnabled = config.stressEnabled,
        hrvEnabled = config.hrvEnabled,
        temperatureEnabled = config.temperatureEnabled,
    )
}

/**
 * Persist the config the engine seeded from the ring's own reported settings as this
 * device's initial DeviceMeasurementConfig. Only fills the gap — a config the user saved
 * (or a concurrent seed) always wins, and no row is written without a current device.
 */
internal suspend fun persistSeededMeasurementConfig(
    db: PulseLoopDatabase,
    settings: MeasurementSettings,
) {
    val device = try { db.deviceDao().current() } catch (_: Exception) { null } ?: return
    val existing = try { db.deviceMeasurementConfigDao().byDevice(device.id) } catch (_: Exception) { null }
    if (existing != null) return
    try {
        db.deviceMeasurementConfigDao().upsert(
            com.pulseloop.data.entity.DeviceMeasurementConfigEntity(
                deviceId = device.id,
                hrIntervalMinutes = settings.hrIntervalMinutes,
                hrEnabled = settings.hrEnabled,
                spo2Enabled = settings.spo2Enabled,
                stressEnabled = settings.stressEnabled,
                hrvEnabled = settings.hrvEnabled,
                temperatureEnabled = settings.temperatureEnabled,
            )
        )
    } catch (_: Exception) {}
}

/** The persisted user profile as ring-protocol values, or null when no profile saved. */
internal suspend fun loadPersistedUserProfile(
    db: PulseLoopDatabase,
    apiKeyStore: ApiKeyStore?,
): UserProfileValues? {
    val profile = try { db.userProfileDao().get() } catch (_: Exception) { null } ?: return null
    return UserProfileValues.from(
        metric = apiKeyStore?.resolvedUnitSystem != com.pulseloop.settings.UnitSystem.IMPERIAL,
        sex = profile.sex, age = profile.age,
        heightCm = profile.heightCm, weightKg = profile.weightKg,
    )
}
