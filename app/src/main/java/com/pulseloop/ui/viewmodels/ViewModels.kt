package com.pulseloop.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.dao.Bucket
import com.pulseloop.data.entity.*
import com.pulseloop.ring.*
import com.pulseloop.service.HeartRateZones
import com.pulseloop.service.SleepCoach
import com.pulseloop.service.SleepInsights
import com.pulseloop.service.SleepScore
import com.pulseloop.service.SleepScoreResult
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.MetricThresholds
import com.pulseloop.ui.components.MetricThresholdTable
import com.pulseloop.util.TimeUtil
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * TodayViewModel — reads Room data for the Today dashboard.
 * Ported from MetricsService.buildTodaySummary in PulseServices.swift.
 * Uses reactive Flow queries so live ring data appears immediately.
 */
class TodayViewModel(db: PulseLoopDatabase, private val apiKeyStore: ApiKeyStore? = null) : ViewModel() {
    // Local midnight, not UTC — the Today dashboard rolls over at the device's local
    // midnight so daily stats line up with how the rest of the app keys per-day rows.
    private val todayStart = com.pulseloop.util.TimeUtil.startOfTodayLocal()

    data class TodayState(
        val steps: Int? = null,
        val calories: Double? = null,
        val distanceMeters: Double? = null,
        val activeMinutes: Int? = null,
        val heartRate: Int? = null,
        val spo2: Int? = null,
        val restingHR: Double? = null,
        val bloodPressureSystolic: Int? = null,
        val bloodPressureDiastolic: Int? = null,
        val bloodSugar: Double? = null,
        val supportsBP: Boolean = false,
        val supportsGlucose: Boolean = false,
        val batteryPercent: Int = 0,
        val deviceState: String = "idle",
        val isConnected: Boolean = false,
        val sleepMinutes: Int? = null,
        val sleepScore: Int? = null,
        val lastUpdated: Long = 0L,
    )

    private val _state = MutableStateFlow(TodayState())
    val state: StateFlow<TodayState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            db.activityDailyDao().byDayFlow(todayStart).collect { activity ->
                _state.update { it.copy(
                    steps = activity?.steps,
                    calories = activity?.calories,
                    distanceMeters = activity?.distanceMeters,
                    activeMinutes = activity?.activeMinutes,
                ) }
            }
        }
        viewModelScope.launch {
            db.deviceDao().currentFlow().collect { device ->
                _state.update { it.copy(
                    batteryPercent = device?.batteryPercent ?: 0,
                    deviceState = device?.stateRaw ?: "idle",
                    isConnected = device?.stateRaw == "CONNECTED",
                    supportsBP = device?.capabilities?.contains(WearableCapability.BLOOD_PRESSURE) ?: false,
                    supportsGlucose = device?.capabilities?.contains(WearableCapability.BLOOD_SUGAR) ?: false,
                ) }
            }
        }
        // Reactive HR — poll latest every 2s, resilient to DB errors.
        // Also derive resting HR from the last 24h of samples (low-percentile estimate).
        viewModelScope.launch {
            while (true) {
                try {
                    val nowMs = System.currentTimeMillis()
                    val hr = db.measurementDao().latest(MeasurementKind.HEART_RATE.name)
                    val samples = db.measurementDao()
                        .range(MeasurementKind.HEART_RATE.name, nowMs - 24 * 3600_000L, nowMs)
                        .map { it.value }
                    val resting = com.pulseloop.service.HeartRateZones.restingHeartRate(samples)
                    _state.update { it.copy(heartRate = hr?.toInt(), restingHR = resting, lastUpdated = nowMs) }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(2000)
            }
        }
        // Reactive SpO2 — poll latest every 2s
        viewModelScope.launch {
            while (true) {
                try {
                    val spo2 = db.measurementDao().latest(MeasurementKind.SPO2.name)
                    _state.update { it.copy(spo2 = spo2?.toInt(), lastUpdated = System.currentTimeMillis()) }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(2000)
            }
        }
        // Reactive BP — poll latest every 5s
        viewModelScope.launch {
            while (true) {
                try {
                    val sys = db.measurementDao().latest(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name)
                    val dia = db.measurementDao().latest(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC.name)
                    _state.update { it.copy(
                        bloodPressureSystolic = sys?.toInt()?.plus(apiKeyStore?.bpAdjustSystolic ?: 0),
                        bloodPressureDiastolic = dia?.toInt()?.plus(apiKeyStore?.bpAdjustDiastolic ?: 0),
                        lastUpdated = System.currentTimeMillis()
                    ) }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(5000)
            }
        }
        // Reactive Glucose — poll latest every 5s
        viewModelScope.launch {
            while (true) {
                try {
                    // Raw ring glucose + app-side calibration offset (see ApiKeyStore.glucoseOffsetMgdl).
                    val glucose = db.measurementDao().latest(MeasurementKind.BLOOD_SUGAR.name)
                        ?.plus(apiKeyStore?.glucoseOffsetMgdl ?: 0.0)
                    _state.update { it.copy(bloodSugar = glucose, lastUpdated = System.currentTimeMillis()) }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(5000)
            }
        }
    }
}

/**
 * SleepViewModel — reads Room data for the Sleep screen.
 */
class SleepViewModel(db: PulseLoopDatabase) : ViewModel() {
    data class SleepState(
        val lastNight: SleepSessionEntity? = null,
        val lastNightBlocks: List<SleepStageBlockEntity> = emptyList(),
        val score: SleepScoreResult? = null,
        val coach: SleepCoach? = null,
        val recentSessions: List<SleepSessionEntity> = emptyList(),
    )

    private val _state = MutableStateFlow(SleepState())
    val state: StateFlow<SleepState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            db.sleepSessionDao().recentFlow(7).collect { sessions ->
                val last = sessions.firstOrNull()
                val blocks = if (last != null) db.sleepStageBlockDao().forSession(last.id) else emptyList()
                val scoreResult = if (last != null) SleepScore.calculate(last, blocks) else null
                val coachText = if (last != null && scoreResult != null) {
                    SleepInsights.dayCoach(last, blocks, null)
                } else if (last == null) {
                    SleepInsights.dayNoDataCoach
                } else null
                _state.update { it.copy(
                    lastNight = last,
                    lastNightBlocks = blocks,
                    score = scoreResult,
                    coach = coachText,
                    recentSessions = sessions,
                ) }
            }
        }
    }
}

/**
 * ActivityViewModel — reads Room data for the Activity screen.
 */
class ActivityViewModel(db: PulseLoopDatabase) : ViewModel() {
    data class ActivityState(
        val recentDays: List<ActivityDailyEntity> = emptyList(),
        val recentWorkouts: List<ActivitySessionEntity> = emptyList(),
    )

    private val _state = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            db.activityDailyDao().recentFlow(7).collect { days ->
                _state.update { it.copy(recentDays = days) }
            }
        }
        viewModelScope.launch {
            db.activitySessionDao().recentFlow(5).collect { sessions ->
                _state.update { it.copy(recentWorkouts = sessions) }
            }
        }
    }
}

/**
 * VitalsViewModel — reads Room data for the Vitals screen.
 * Ported from MetricsService.metricRange in PulseServices.swift.
 * Uses reactive polling so data appears as soon as the ring syncs.
 */
class VitalsViewModel(private val db: PulseLoopDatabase, private val apiKeyStore: ApiKeyStore? = null) : ViewModel() {
    data class VitalsState(
        val hrSamples: List<Double> = emptyList(),
        val spo2Samples: List<Double> = emptyList(),
        val hrvSamples: List<Double> = emptyList(),
        val stressSamples: List<Double> = emptyList(),
        val fatigueSamples: List<Double> = emptyList(),
        val tempSamples: List<Double> = emptyList(),
        val latestHr: Int? = null,
        val restingHr: Double? = null,
        val latestSpo2: Int? = null,
        val latestHrv: Double? = null,
        val latestStress: Double? = null,
        val latestFatigue: Double? = null,
        val latestTemp: Double? = null,
        val bpSystolic: Int? = null,
        val bpDiastolic: Int? = null,
        val bloodSugar: Double? = null,
        val bpSysSamples: List<Double> = emptyList(),
        val bpDiaSamples: List<Double> = emptyList(),
        val glucoseSamples: List<Double> = emptyList(),
        val supportsHrv: Boolean = false,
        val supportsStress: Boolean = false,
        val supportsFatigue: Boolean = false,
        val supportsTemp: Boolean = false,
        val supportsBP: Boolean = false,
        val supportsGlucose: Boolean = false,
    )

    private val _state = MutableStateFlow(VitalsState())
    val state: StateFlow<VitalsState> = _state.asStateFlow()

    init {
        // Poll every 5 seconds so data appears as the ring syncs history
        viewModelScope.launch {
            while (true) {
                try { refresh(db) } catch (_: Exception) {}
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    /** Force an immediate refresh — call right after a spot measurement completes so the
     *  new value appears at once instead of waiting for the next poll tick. */
    fun refreshNow() {
        viewModelScope.launch { try { refresh(db) } catch (_: Exception) {} }
    }

    private suspend fun refresh(db: PulseLoopDatabase) {
        val now = System.currentTimeMillis()
        val twentyFourHoursAgo = now - 24 * 3600_000L
        val device = db.deviceDao().current()
        val caps = device?.capabilities ?: setOf(
            com.pulseloop.ring.WearableCapability.HEART_RATE,
            com.pulseloop.ring.WearableCapability.SPO2,
            com.pulseloop.ring.WearableCapability.STEPS,
            com.pulseloop.ring.WearableCapability.SLEEP,
            com.pulseloop.ring.WearableCapability.BATTERY,
        )

        val hr = db.measurementDao().range(MeasurementKind.HEART_RATE.name, twentyFourHoursAgo, now)
        val spo2 = db.measurementDao().range(MeasurementKind.SPO2.name, twentyFourHoursAgo, now)
        val hrv = if (caps.contains(WearableCapability.HRV)) db.measurementDao().range(MeasurementKind.HRV.name, twentyFourHoursAgo, now) else emptyList()
        val stress = if (caps.contains(WearableCapability.STRESS)) db.measurementDao().range(MeasurementKind.STRESS.name, twentyFourHoursAgo, now) else emptyList()
        val fatigue = if (caps.contains(WearableCapability.FATIGUE)) db.measurementDao().range(MeasurementKind.FATIGUE.name, twentyFourHoursAgo, now) else emptyList()
        val temp = if (caps.contains(WearableCapability.TEMPERATURE)) db.measurementDao().range(MeasurementKind.TEMPERATURE.name, twentyFourHoursAgo, now) else emptyList()
        val bpSys = if (caps.contains(WearableCapability.BLOOD_PRESSURE)) db.measurementDao().range(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name, twentyFourHoursAgo, now) else emptyList()
        val bpDia = if (caps.contains(WearableCapability.BLOOD_PRESSURE)) db.measurementDao().range(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC.name, twentyFourHoursAgo, now) else emptyList()
        val glucoseOffset = apiKeyStore?.glucoseOffsetMgdl ?: 0.0
        val gluc = if (caps.contains(WearableCapability.BLOOD_SUGAR)) db.measurementDao().range(MeasurementKind.BLOOD_SUGAR.name, twentyFourHoursAgo, now) else emptyList()

        _state.value = VitalsState(
            hrSamples = hr.map { it.value },
            spo2Samples = spo2.map { it.value },
            hrvSamples = hrv.map { it.value },
            stressSamples = stress.map { it.value },
            fatigueSamples = fatigue.map { it.value },
            tempSamples = temp.map { it.value },
            latestHr = hr.lastOrNull()?.value?.toInt(),
            restingHr = HeartRateZones.restingHeartRate(hr.map { it.value }),
            latestSpo2 = spo2.lastOrNull()?.value?.toInt(),
            latestHrv = hrv.lastOrNull()?.value,
            latestStress = stress.lastOrNull()?.value,
            latestFatigue = fatigue.lastOrNull()?.value,
            latestTemp = temp.lastOrNull()?.value,
            bpSystolic = db.measurementDao().latest(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name)?.toInt()
                ?.plus(apiKeyStore?.bpAdjustSystolic ?: 0),
            bpDiastolic = db.measurementDao().latest(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC.name)?.toInt()
                ?.plus(apiKeyStore?.bpAdjustDiastolic ?: 0),
            bloodSugar = db.measurementDao().latest(MeasurementKind.BLOOD_SUGAR.name)
                ?.plus(glucoseOffset),
            bpSysSamples = bpSys.map { it.value },
            bpDiaSamples = bpDia.map { it.value },
            glucoseSamples = gluc.map { it.value + glucoseOffset },
            supportsHrv = caps.contains(WearableCapability.HRV),
            supportsStress = caps.contains(WearableCapability.STRESS),
            supportsFatigue = caps.contains(WearableCapability.FATIGUE),
            supportsTemp = caps.contains(WearableCapability.TEMPERATURE),
            supportsBP = caps.contains(WearableCapability.BLOOD_PRESSURE),
            supportsGlucose = caps.contains(WearableCapability.BLOOD_SUGAR),
        )
    }
}

/**
 * CoachViewModel — wires the coach orchestrator to the UI.
 */
class CoachViewModel(
    private val db: PulseLoopDatabase,
    private val orchestrator: com.pulseloop.coach.orchestration.CoachOrchestrator,
) : ViewModel() {
    data class CoachState(
        val messages: List<ChatMessage> = emptyList(),
        val isThinking: Boolean = false,
        val error: String? = null,
    )

    data class ChatMessage(val role: String, val text: String)

    private val _state = MutableStateFlow(CoachState(
        messages = listOf(ChatMessage("assistant",
            "Hi! I'm your PulseLoop coach. I can answer questions about your sleep, heart rate, activity, and recovery. What would you like to know?"))
    ))
    val state: StateFlow<CoachState> = _state.asStateFlow()

    fun sendMessage(userText: String) {
        _state.update { it.copy(
            messages = it.messages + ChatMessage("user", userText),
            isThinking = true, error = null,
        ) }
        viewModelScope.launch {
            try {
                val packet = com.pulseloop.coach.context.CoachContextBuilder.build(db)
                val priorMessages = _state.value.messages.dropLast(1).map {
                    com.pulseloop.coach.orchestration.CoachOrchestrator.PriorMessage(it.role, it.text)
                }
                val result = orchestrator.runTurn(userText, packet, priorMessages)
                _state.update { it.copy(
                    messages = it.messages + ChatMessage("assistant", result.assistant.plainText),
                    isThinking = false,
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    messages = it.messages + ChatMessage("assistant", "Sorry, something went wrong: ${e.message}"),
                    isThinking = false, error = e.message,
                ) }
            }
        }
    }
}

// ──────────────────────── Vital Detail ────────────────────────

enum class Period(val label: String) { DAY("Today"), WEEK("Week"), MONTH("Month") }

/**
 * VitalDetailViewModel — drives a single-metric detail screen with Today/Week/Month
 * aggregation, trend detection, and stats.
 */
class VitalDetailViewModel(
    private val db: PulseLoopDatabase,
    private val metric: String,
    private val apiKeyStore: ApiKeyStore? = null,
    private val unitSystem: UnitSystem = UnitSystem.METRIC,
) : ViewModel() {

    enum class Trend { UP, DOWN, FLAT }

    data class DetailState(
        val period: Period = Period.DAY,
        // For DAY the anchor is the *end* of a rolling 24-hour window (defaults to now);
        // for WEEK/MONTH it is the start of the period.
        val anchor: Long = System.currentTimeMillis(),
        val points: List<Double> = emptyList(),
        val secondary: List<Double> = emptyList(),
        val labels: List<String> = emptyList(),
        val timestamps: List<Long> = emptyList(),
        val latest: Double? = null,
        val min: Double? = null,
        val avg: Double? = null,
        val max: Double? = null,
        val resting: Double? = null,   // heart rate only
        val trend: Trend = Trend.FLAT,
        val thresholds: MetricThresholds? = null,
        val loading: Boolean = true,
        val isBP: Boolean = false,
    )

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    // Derived property for the primary kind(s) of this metric
    private val primaryKind: MeasurementKind? get() {
        val t = MetricThresholdTable.forKey(metric) ?: return null
        return MeasurementKind.entries.firstOrNull { MetricThresholdTable.forKind(it) == t }
    }

    init {
        _state.update { it.copy(thresholds = MetricThresholdTable.forKey(metric), isBP = metric == "bp") }
        viewModelScope.launch { refresh() }
        // Poll every 5s so live syncs appear
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                try { refresh() } catch (_: Exception) {}
            }
        }
    }

    /**
     * The latest (resting) anchor per period. The anchor is the window START,
     * so the resting WEEK/MONTH window must TRAIL: it ends after today, not
     * start at today — [today, today+7) is a forward-looking window of future
     * days that structurally contains no data.
     */
    private fun latestAnchor(p: Period): Long = when (p) {
        Period.DAY   -> System.currentTimeMillis()                       // rolling last 24h, ending now
        Period.WEEK  -> TimeUtil.startOfTodayLocal() - 6 * 86_400_000L   // last 7 days incl. today
        Period.MONTH -> Instant.ofEpochMilli(TimeUtil.startOfTodayLocal())
            .atZone(ZoneId.systemDefault()).minusMonths(1).plusDays(1)
            .toInstant().toEpochMilli()                                  // last month incl. today
    }

    fun setPeriod(p: Period) {
        _state.update { it.copy(period = p, anchor = latestAnchor(p)) }
        viewModelScope.launch { refresh() }
    }

    fun prev() {
        val st = _state.value
        val newAnchor = when (st.period) {
            Period.DAY   -> st.anchor - 86_400_000L
            Period.WEEK  -> st.anchor - 7 * 86_400_000L
            Period.MONTH -> {
                val i = Instant.ofEpochMilli(st.anchor).atZone(ZoneId.systemDefault())
                i.minusMonths(1).toInstant().toEpochMilli()
            }
        }
        _state.update { it.copy(anchor = newAnchor) }
        viewModelScope.launch { refresh() }
    }

    fun next() {
        val st = _state.value
        val newAnchor = when (st.period) {
            Period.DAY   -> st.anchor + 86_400_000L
            Period.WEEK  -> st.anchor + 7 * 86_400_000L
            Period.MONTH -> {
                val i = Instant.ofEpochMilli(st.anchor).atZone(ZoneId.systemDefault())
                i.plusMonths(1).toInstant().toEpochMilli()
            }
        }
        if (newAnchor > latestAnchor(st.period)) return
        _state.update { it.copy(anchor = newAnchor) }
        viewModelScope.launch { refresh() }
    }

    /** Whether forward navigation is allowed (disabled at the present/latest window). */
    fun canGoForward(): Boolean {
        val st = _state.value
        return when (st.period) {
            // Enabled only if a full 24h step still fits before now (i.e. we're paged back).
            Period.DAY -> st.anchor + 86_400_000L <= System.currentTimeMillis()
            else -> st.anchor < latestAnchor(st.period)
        }
    }

    private suspend fun refresh() {
        val st = _state.value
        val period = st.period
        val now = System.currentTimeMillis()
        // DAY is a rolling 24h window ending at the anchor; when we're at the latest
        // (can't page further forward) keep the end pinned to "now" so it truly rolls.
        val anchor = if (period == Period.DAY && st.anchor + 86_400_000L > now) now else st.anchor

        val windowStart = when (period) {
            Period.DAY   -> anchor - 86_400_000L   // rolling last 24h
            else         -> anchor                 // WEEK/MONTH: anchor = window start
        }
        val windowEnd = when (period) {
            Period.DAY   -> anchor
            Period.WEEK  -> anchor + 7 * 86_400_000L
            Period.MONTH -> {
                val i = Instant.ofEpochMilli(anchor).atZone(ZoneId.systemDefault())
                i.plusMonths(1).toInstant().toEpochMilli()
            }
        }

        // Previous equal-length window, immediately before this one, for the trend read.
        val prevEnd = windowStart
        val prevStart = when (period) {
            Period.DAY   -> windowStart - 86_400_000L
            Period.WEEK  -> windowStart - 7 * 86_400_000L
            Period.MONTH -> {
                val i = Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault())
                i.minusMonths(1).toInstant().toEpochMilli()
            }
        }

        val dao = db.measurementDao()

        if (metric == "bp") {
            // Every reading, at its real timestamp — no averaging.
            val sysSamples = dao.range(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name, windowStart, windowEnd)
            val diaSamples = dao.range(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC.name, windowStart, windowEnd)

            val points = sysSamples.map { it.value }
            val secondary = diaSamples.map { it.value }
            val times = sysSamples.map { it.timestamp }
            val allValues = points + secondary
            val labels = buildLabels(times, period)

            val prevSys = dao.range(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name, prevStart, prevEnd)
            val prevAvg = if (prevSys.isNotEmpty()) prevSys.map { it.value }.average() else null
            val thisAvg = if (points.isNotEmpty()) points.average() else null
            val trend = computeTrend(thisAvg, prevAvg, if (allValues.isNotEmpty()) allValues.max() - allValues.min() else 1.0)

            val latestSys = dao.latest(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name)

            _state.update { it.copy(
                anchor = anchor,
                points = points, secondary = secondary, labels = labels,
                timestamps = times,
                latest = latestSys,
                min = allValues.minOrNull(), avg = thisAvg, max = allValues.maxOrNull(),
                trend = trend, loading = false,
            ) }
        } else {
            val kind = primaryKind ?: return
            val kindName = kind.name
            val glucoseOffset = apiKeyStore?.glucoseOffsetMgdl ?: 0.0

            fun convert(v: Double): Double = when (kind) {
                MeasurementKind.BLOOD_SUGAR -> v + glucoseOffset
                MeasurementKind.TEMPERATURE -> UnitConverter.temperature(v, unitSystem)
                else -> v
            }

            // Every reading, at its real timestamp — no averaging.
            val samples = dao.range(kindName, windowStart, windowEnd)
            val times = samples.map { it.timestamp }
            val points = samples.map { convert(it.value) }
            val labels = buildLabels(times, period)

            val prevSamples = dao.range(kindName, prevStart, prevEnd)
            val prevAvg = if (prevSamples.isNotEmpty()) convert(prevSamples.map { it.value }.average()) else null
            val thisAvg = if (points.isNotEmpty()) points.average() else null
            val range = if (points.isNotEmpty()) points.max() - points.min() else 1.0
            val trend = computeTrend(thisAvg, prevAvg, range)

            val latest = dao.latest(kindName)?.let(::convert)

            // Resting HR from the window's raw samples (low-percentile estimate).
            val resting = if (kind == MeasurementKind.HEART_RATE)
                HeartRateZones.restingHeartRate(samples.map { it.value })
            else null

            _state.update { it.copy(
                anchor = anchor,
                points = points, secondary = emptyList(), labels = labels,
                timestamps = times,
                latest = latest,
                min = points.minOrNull(), avg = thisAvg, max = points.maxOrNull(),
                resting = resting,
                trend = trend, loading = false,
            ) }
        }
    }

    private fun buildLabels(buckets: List<Long>, period: Period): List<String> {
        if (buckets.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        return when (period) {
            Period.DAY -> buckets.map { bucket ->
                "%02d".format(Instant.ofEpochMilli(bucket).atZone(zone).hour)
            }
            Period.WEEK -> buckets.map { bucket ->
                Instant.ofEpochMilli(bucket).atZone(zone).dayOfWeek.name.take(3)
            }
            Period.MONTH -> buckets.map { bucket ->
                Instant.ofEpochMilli(bucket).atZone(zone).dayOfMonth.toString()
            }
        }
    }

    private fun computeTrend(thisAvg: Double?, prevAvg: Double?, range: Double): Trend {
        if (thisAvg == null || prevAvg == null) return Trend.FLAT
        val delta = thisAvg - prevAvg
        val threshold = range * 0.02
        return when {
            delta > threshold  -> Trend.UP
            delta < -threshold -> Trend.DOWN
            else               -> Trend.FLAT
        }
    }
}
