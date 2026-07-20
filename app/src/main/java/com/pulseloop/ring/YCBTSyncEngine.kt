package com.pulseloop.ring

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
    private var requestActivityAfterStartupHistory = false
    private var historyCapabilities = YCBTCoordinator.capabilities

    companion object {
        private val HISTORY_TYPES: List<YCBTHistoryType> = listOf(
            YCBTHistoryType.SPORT, YCBTHistoryType.SLEEP, YCBTHistoryType.HEART,
            YCBTHistoryType.BLOOD, YCBTHistoryType.ALL,
            YCBTHistoryType.TEMPERATURE, YCBTHistoryType.COMPREHENSIVE, YCBTHistoryType.BODY_DATA,
        )
        private val VITALS_TYPES: List<YCBTHistoryType> = listOf(YCBTHistoryType.HEART, YCBTHistoryType.ALL)
    }

    @Synchronized
    override fun runStartup() {
        requestActivityAfterStartupHistory = true
        for (command in encoder.startupSequence(
            measurement = measurementSettings,
            profile = userProfile,
            capabilities = historyCapabilities,
        )) {
            writer?.enqueue(command)
        }
        transfer.start(types = supportedHistoryTypes(HISTORY_TYPES))
    }

    @Synchronized
    override fun handle(event: RingDecodedEvent) {
        if (event is RingDecodedEvent.SupportFunctions) {
            val previousTypes = supportedHistoryTypes(HISTORY_TYPES).toSet()
            val previousCapabilities = historyCapabilities
            historyCapabilities = YCBTCoordinator.capabilities +
                event.capabilities.intersect(YCBTCoordinator.bitmapGatedCapabilities)
            for (command in encoder.monitorCommands(measurementSettings, historyCapabilities - previousCapabilities)) {
                writer?.enqueue(command)
            }
            val addedCapabilities = historyCapabilities - previousCapabilities
            val newlySupported = supportedHistoryTypes(HISTORY_TYPES)
                .filterNot(previousTypes::contains)
                .toMutableList()
            // ALL carries optional fields as well as baseline SpO2. If capability discovery lands
            // after its first pass, fetch it once more so newly accepted values are not lost.
            if (addedCapabilities.any {
                    it == WearableCapability.BLOOD_PRESSURE ||
                        it == WearableCapability.HRV ||
                        it == WearableCapability.TEMPERATURE ||
                        it == WearableCapability.BLOOD_SUGAR
                }) {
                newlySupported.add(YCBTHistoryType.ALL)
            }
            transfer.append(newlySupported)
        }
        // The early startup command enables live status while the ring is still processing its
        // connect handshake. Some R10M firmware acknowledges it without immediately publishing
        // the current cumulative activity. Ask once more after the startup history walk, when the
        // connection is settled, so reconnect updates steps without requiring pull-to-refresh.
        if (event is RingDecodedEvent.HistorySyncFinished && requestActivityAfterStartupHistory) {
            requestActivityAfterStartupHistory = false
            writer?.enqueue(encoder.enableLiveStatus())
        }
    }

    @Synchronized
    override fun refresh() {
        // Ask for current cumulative activity before the slower multi-type history walk. Without
        // this, pull-to-refresh can leave steps stale until an unsolicited live push arrives.
        writer?.enqueue(encoder.enableLiveStatus())
        transfer.start(types = supportedHistoryTypes(HISTORY_TYPES))
    }

    @Synchronized
    override fun querySleep() {
        transfer.start(types = supportedHistoryTypes(listOf(YCBTHistoryType.SLEEP)))
    }

    @Synchronized
    override fun syncVitalsHistory() {
        transfer.start(types = supportedHistoryTypes(VITALS_TYPES))
    }

    @Synchronized
    override fun syncSleepNow() {
        transfer.start(types = supportedHistoryTypes(listOf(YCBTHistoryType.SLEEP)))
    }

    private fun supportedHistoryTypes(types: List<YCBTHistoryType>): List<YCBTHistoryType> =
        types.filter { type ->
            when (type) {
                YCBTHistoryType.SPORT -> WearableCapability.STEPS in historyCapabilities
                YCBTHistoryType.SLEEP -> WearableCapability.SLEEP in historyCapabilities
                YCBTHistoryType.HEART -> WearableCapability.HEART_RATE in historyCapabilities
                YCBTHistoryType.BLOOD -> WearableCapability.BLOOD_PRESSURE in historyCapabilities
                YCBTHistoryType.ALL -> true
                YCBTHistoryType.SPO2 -> WearableCapability.SPO2_HISTORY in historyCapabilities
                YCBTHistoryType.TEMPERATURE -> WearableCapability.TEMPERATURE in historyCapabilities
                YCBTHistoryType.COMPREHENSIVE -> WearableCapability.BLOOD_SUGAR in historyCapabilities
                YCBTHistoryType.BODY_DATA ->
                    WearableCapability.STRESS in historyCapabilities ||
                        WearableCapability.FATIGUE in historyCapabilities
                else -> false
            }
        }

    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        if (settings != null) measurementSettings = settings
    }

    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        for (command in encoder.monitorCommands(settings, historyCapabilities)) {
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

    override fun handleRawNotify(data: ByteArray) {}
}
