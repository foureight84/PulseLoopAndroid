package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from YCBTSyncEngine.swift.
 * YCBT sync engine. Connect is a parameterized handshake, followed by the history sync.
 */

class YCBTSyncEngine(
    private var writer: RingCommandWriter?,
    private val transfer: YCBTHistoryTransfer,
) : RingSyncEngine {
    private val encoder = YCBTEncoder()

    private var measurementSettings = MeasurementSettings.ALL_ON_DEFAULT
    private var userProfile = UserProfileValues(metric = true, gender = 0x02u, age = 25u, heightCm = 175u, weightKg = 70u)

    companion object {
        private val HISTORY_TYPES: List<YCBTHistoryType> = listOf(
            YCBTHistoryType.SPORT, YCBTHistoryType.SLEEP, YCBTHistoryType.HEART,
            YCBTHistoryType.BLOOD, YCBTHistoryType.ALL, YCBTHistoryType.SPO2,
            YCBTHistoryType.TEMPERATURE, YCBTHistoryType.COMPREHENSIVE, YCBTHistoryType.BODY_DATA,
        )
        private val VITALS_TYPES: List<YCBTHistoryType> = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL, YCBTHistoryType.SPO2)
    }

    override fun runStartup() {
        for (command in encoder.startupSequence(measurement = measurementSettings, profile = userProfile)) {
            writer?.enqueue(command)
        }
        transfer.start(types = HISTORY_TYPES)
    }

    override fun handle(event: RingDecodedEvent) {
        // History is protocol-driven now — nothing here advances it.
    }

    override fun syncHistory() {
        transfer.start(types = HISTORY_TYPES)
    }

    fun syncVitalsHistory() {
        transfer.start(types = VITALS_TYPES)
    }

    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        if (settings != null) measurementSettings = settings
    }

    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        for (command in encoder.monitorCommands(settings)) {
            writer?.enqueue(command)
        }
    }

    override fun setUserProfile(profile: UserProfileValues) {
        userProfile = profile
    }

    override fun applyUserProfile(profile: UserProfileValues) {
        userProfile = profile
        writer?.enqueue(encoder.userInfo(profile))
    }

    fun resyncTime() {
        writer?.enqueue(encoder.setTime())
    }

    fun requestBattery() {
        writer?.enqueue(encoder.deviceInfoRequest())
    }

    override fun startHeartRate() {
        writer?.enqueue(encoder.heartRateStart())
    }

    override fun stopHeartRate() {
        writer?.enqueue(encoder.heartRateStop())
    }

    override fun startSpO2() {
        writer?.enqueue(encoder.spo2Start())
    }

    override fun stopSpO2() {
        writer?.enqueue(encoder.spo2Stop())
    }

    override fun startHRV() {
        writer?.enqueue(encoder.hrvStart())
    }

    override fun stopHRV() {
        writer?.enqueue(encoder.hrvStop())
    }

    override fun startBloodPressure() {
        writer?.enqueue(encoder.bloodPressureStart())
    }

    override fun stopBloodPressure() {
        writer?.enqueue(encoder.bloodPressureStop())
    }

    override fun findDevice() {
        writer?.enqueue(encoder.findDevice())
    }

    override fun setGoal(steps: Int) {
        // Unverified for this ring; goal is persisted app-side.
    }

    override fun powerOff() {}
    override fun factoryReset() {}
    override fun startCombinedMeasurement() {}
    override fun stopCombinedMeasurement() {}
    override fun setUserInfo(ageYears: Int, isMale: Boolean, heightCm: Int, weightKg: Int) {}
    override fun setBloodPressureAdjust(systolic: Int, diastolic: Int) {}
    override fun setAppId(appId: String) {}
    override fun setOnMeasurementConfigSeeded(callback: (MeasurementSettings) -> Unit) {}
    override fun setOnBondRequested(callback: () -> Unit) {}
    override fun syncSleepNow() {}
    override fun handleRawNotify(data: ByteArray) {}
}
