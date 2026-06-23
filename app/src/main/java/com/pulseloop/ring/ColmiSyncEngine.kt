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

    // History state machine
    private enum class Stage { IDLE, ACTIVITY, HEART_RATE, STRESS, SPO2, SLEEP, HRV, BP, TEMPERATURE, BLOOD_SUGAR, DONE }

    private var stage = Stage.IDLE
    private var daysAgo = 0
    private var syncDay = LocalDate.now(zone)

    // Watchdog
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val watchdogTimeoutMs = 10_000L
    private val activityWatchdogTimeoutMs = 20_000L

    // Realtime HR keepalive
    private var realtimeHRActive = false
    private var realtimeHRPacketCount = 0
    private var manualHRActive = false

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
        writer?.enqueue(encoder.userPreferences())
        writer?.enqueue(encoder.battery())
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_HR_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_STRESS_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_SPO2_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_HRV_PREF))
        writer?.enqueue(encoder.readTempPref())
        writer?.enqueue(encoder.readGoals())
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_SPO2_PREF, enabled = true))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_STRESS_PREF, enabled = true))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_HRV_PREF, enabled = true))
        startHistorySync()
    }

    private fun startHistorySync() {
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
        writer?.enqueue(encoder.bigDataSpo2())
    }

    override fun stopSpO2() {}

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
