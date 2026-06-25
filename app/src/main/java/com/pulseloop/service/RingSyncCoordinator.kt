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
    private var measurementReceivedReading = false

    val connectionState: RingConnectionState get() = client.state.value.connectionState
    val isConnected: Boolean get() = connectionState == RingConnectionState.CONNECTED

    private val hrMeasureSeconds = 30L
    private val hrSettleSeconds = 4
    private val spo2MeasureSeconds = 40L
    private val combinedMeasureSeconds = COMBINED_MEASURE_SECONDS.toLong()

    companion object {
        /** Duration of a combined spot measurement (0x23→0x24); also drives the UI countdown. */
        const val COMBINED_MEASURE_SECONDS = 45
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

    fun querySleep() {
        if (!isConnected) return
        engine?.runStartup()
    }

    fun findRing() {
        if (!isConnected) return
        engine?.findDevice()
    }

    /** Send ring-side unpair commands (power-off, factory reset if supported),
     *  then disconnect and forget. */
    fun forgetRing(onCleared: () -> Unit) {
        val caps = client.state.value.activeCapabilities
        if (caps.contains(WearableCapability.POWER_OFF)) {
            engine?.powerOff()
        }
        if (caps.contains(WearableCapability.FACTORY_RESET)) {
            engine?.factoryReset()
        }
        // Give the ring a moment to process, then disconnect + forget
        scope.launch {
            kotlinx.coroutines.delay(500)
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

    suspend fun measureHR(): Int? {
        if (hrState == MeasureState.MEASURING) return null
        if (!isConnected) { hrState = MeasureState.FAILED; return null }
        hrState = MeasureState.MEASURING
        hrNoReadingReported = false
        measurementReceivedReading = false

        engine?.measureHeartRateSpot()
        pollForValue(hrMeasureSeconds, { if (measurementReceivedReading) latestHRValue else null }, { hrNoReadingReported })

        var result = if (measurementReceivedReading) latestHRValue else null
        if (result != null) {
            repeat(hrSettleSeconds * 2) {   // 0.5s granularity
                delay(500)
                latestHRValue?.let { result = it }
            }
        }
        engine?.stopHeartRate()
        hrState = if (result != null) MeasureState.DONE else MeasureState.FAILED
        return result
    }

    suspend fun measureSpO2(): Int? {
        if (spo2State == MeasureState.MEASURING) return null
        if (!isConnected) { spo2State = MeasureState.FAILED; return null }
        spo2State = MeasureState.MEASURING
        latestSpO2Value = null
        engine?.startSpO2()
        val result = pollForValue(spo2MeasureSeconds, { latestSpO2Value }, { false })
        engine?.stopSpO2()
        spo2State = if (result != null) MeasureState.DONE else MeasureState.FAILED
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
        repeat(combinedMeasureSeconds.toInt()) { delay(1000) }
        engine?.stopCombinedMeasurement()
        combinedState = MeasureState.DONE
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
                if (hrState == MeasureState.MEASURING) measurementReceivedReading = true
            }
            is PulseEvent.HeartRateComplete -> {
                if (hrState == MeasureState.MEASURING && !measurementReceivedReading) {
                    hrNoReadingReported = true
                }
            }
            is PulseEvent.Spo2Result -> {
                latestSpO2Value = event.value
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
