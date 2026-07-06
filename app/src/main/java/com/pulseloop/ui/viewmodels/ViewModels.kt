package com.pulseloop.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.dao.Bucket
import com.pulseloop.data.entity.*
import com.pulseloop.ring.*
import com.pulseloop.coach.summaries.CoachSummaryKind
import com.pulseloop.service.HeartRateZones
import com.pulseloop.service.SleepCoach
import com.pulseloop.service.SleepInsights
import com.pulseloop.service.SleepRangeKey
import com.pulseloop.service.SleepScore
import com.pulseloop.service.SleepScoreResult
import com.pulseloop.service.UserPhysiologyProfile
import com.pulseloop.service.VitalSample
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.MetricThresholds
import com.pulseloop.ui.components.MetricThresholdTable
import com.pulseloop.ui.components.toColor
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
        // Daily activity-ring goals, read reactively from the stored [UserGoalEntity]
        // (distance/calorie columns added with iOS #48; defaults match iOS UserGoal).
        val stepGoal: Int = 10000,
        val distanceGoalMeters: Double = 8000.0,
        val caloriesGoal: Int = 500,
        /** Last 7 days of step totals (oldest→newest) for the hero delta (iOS trends.steps7d). */
        val steps7d: List<Int> = emptyList(),
        /** Latest "today" coach summary card, when the coach has generated one. */
        val coachSummary: CoachSummaryEntity? = null,
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
            try {
                db.userGoalDao().getFlow().collect { goal ->
                    if (goal != null) {
                        _state.update { it.copy(
                            stepGoal = goal.steps,
                            distanceGoalMeters = goal.distanceMeters,
                            caloriesGoal = goal.calories,
                        ) }
                    }
                }
            } catch (_: Exception) {}
        }
        // 7-day step series for the hero delta (oldest→newest; DAO returns newest-first).
        viewModelScope.launch {
            db.activityDailyDao().recentFlow(7).collect { days ->
                _state.update { it.copy(steps7d = days.reversed().map { d -> d.steps }) }
            }
        }
        // Today's coach summary card (kind="today", scopeKey=local date).
        viewModelScope.launch {
            db.coachSummaryDao()
                .getFlow(com.pulseloop.coach.summaries.CoachSummaryKind.TODAY.rawValue, java.time.LocalDate.now().toString())
                .collect { summary -> _state.update { it.copy(coachSummary = summary) } }
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
 * SleepViewModel — reads Room data for the Sleep screen, ported from
 * SleepService.sleepRange (PulseServices.swift) + SleepView.swift range handling.
 * Day view anchors on the *reference night* (before 4 AM local = yesterday's night);
 * Week/Month/Year anchor on the last recorded session so history still surfaces.
 */
class SleepViewModel(private val db: PulseLoopDatabase) : ViewModel() {
    data class SleepState(
        val range: SleepRangeKey = SleepRangeKey.DAY,
        // Day view (also feeds the Today sleep tile)
        val lastNight: SleepSessionEntity? = null,
        val lastNightBlocks: List<SleepStageBlockEntity> = emptyList(),
        val score: SleepScoreResult? = null,
        val coach: SleepCoach? = null,
        val recentSessions: List<SleepSessionEntity> = emptyList(),
        // Aggregate view (week/month/year)
        val rangeSessions: List<SleepSessionEntity> = emptyList(),
        val expectedNights: Int = 7,
        val avgMinutes: Int? = null,
        val avgScore: Int? = null,
        /** Average deep/light/awake minutes across the range's valid nights. */
        val stageAvg: Triple<Int, Int, Int>? = null,
        val bars: List<com.pulseloop.service.SleepBar> = emptyList(),
        val aggregateCoach: SleepCoach? = null,
        val goalMinutes: Int? = null,
        // LLM summaries when present (fallback = the scripted coach above)
        val daySummary: CoachSummaryEntity? = null,
        val rangeSummary: CoachSummaryEntity? = null,
    )

    private val _state = MutableStateFlow(SleepState())
    val state: StateFlow<SleepState> = _state.asStateFlow()

    fun setRange(range: SleepRangeKey) {
        _state.update { it.copy(range = range) }
        viewModelScope.launch { try { rebuild(range) } catch (_: Exception) {} }
    }

    init {
        // Any sleep-table change retriggers a rebuild of the selected range.
        viewModelScope.launch {
            db.sleepSessionDao().recentFlow(7).collect { sessions ->
                _state.update { it.copy(recentSessions = sessions) }
                try { rebuild(_state.value.range) } catch (_: Exception) {}
            }
        }
    }

    private suspend fun rebuild(range: SleepRangeKey) {
        val blocksCache = HashMap<String, List<SleepStageBlockEntity>>()
        suspend fun blocksFor(id: String): List<SleepStageBlockEntity> =
            blocksCache.getOrPut(id) { db.sleepStageBlockDao().forSession(id) }
        // aggregateCoach/averageScore take a synchronous lookup; pre-warm the cache first.
        val goal = try { db.userGoalDao().get()?.sleepMinutes } catch (_: Exception) { null }

        // ── Day view ──
        val reference = dayReferenceNight()
        val night = db.sleepSessionDao().byDay(reference)?.takeIf { it.totalMinutes > 0 }
        val nightBlocks = night?.let { blocksFor(it.id) } ?: emptyList()
        val scoreResult = night?.let { SleepScore.calculate(it, nightBlocks) }
        val steps = try { db.activityDailyDao().byDay(TimeUtil.startOfTodayLocal())?.steps } catch (_: Exception) { null }
        val dayCoach = if (night != null) SleepInsights.dayCoach(night, nightBlocks, steps)
            else SleepInsights.dayNoDataCoach

        // ── Aggregate ──
        val expected = when (range) {
            SleepRangeKey.DAY -> 1
            SleepRangeKey.WEEK -> 7
            SleepRangeKey.MONTH -> 30
            SleepRangeKey.YEAR -> 365
        }
        val anchor = if (range == SleepRangeKey.DAY) reference
            else db.sleepSessionDao().recent(1).firstOrNull()?.let { TimeUtil.startOfDayLocal(it.date) }
                ?: TimeUtil.startOfTodayLocal()
        val start = anchor - (expected - 1) * 86_400_000L
        val end = anchor + 86_400_000L - 1
        val sessions = db.sleepSessionDao().inRange(start, end)
        val valid = SleepInsights.validSessions(sessions)
        valid.forEach { blocksFor(it.id) }  // warm cache for the sync lambdas below
        val lookup: (String) -> List<SleepStageBlockEntity> = { blocksCache[it] ?: emptyList() }
        val stageAvg = if (valid.isEmpty()) null else Triple(
            valid.sumOf { s -> lookup(s.id).filter { it.stageRaw == "DEEP" }.sumOf { b -> b.durationMinutes } } / valid.size,
            valid.sumOf { s -> lookup(s.id).filter { it.stageRaw == "LIGHT" }.sumOf { b -> b.durationMinutes } } / valid.size,
            valid.sumOf { s -> lookup(s.id).filter { it.stageRaw == "AWAKE" }.sumOf { b -> b.durationMinutes } } / valid.size,
        )
        val bars = when (range) {
            SleepRangeKey.YEAR -> SleepInsights.buildMonthBuckets(anchor, sessions, lookup)
            else -> SleepInsights.buildNightAxis(start, end, sessions, lookup, range)
        }

        // LLM summaries (kind sleep_day / sleep_range_*), newest wins.
        val daySummary = try { db.coachSummaryDao().latest(CoachSummaryKind.SLEEP_DAY.rawValue) } catch (_: Exception) { null }
        val rangeSummary = if (range == SleepRangeKey.DAY) null else try {
            db.coachSummaryDao().latest(CoachSummaryKind.sleepRange(range).rawValue)
        } catch (_: Exception) { null }

        _state.update {
            it.copy(
                lastNight = night,
                lastNightBlocks = nightBlocks,
                score = scoreResult,
                coach = dayCoach,
                rangeSessions = sessions,
                expectedNights = expected,
                avgMinutes = SleepInsights.averageDuration(valid),
                avgScore = SleepInsights.averageScore(valid, lookup),
                stageAvg = stageAvg,
                bars = bars,
                aggregateCoach = SleepInsights.aggregateCoach(range, sessions, expected, goal, lookup),
                goalMinutes = goal,
                daySummary = daySummary,
                rangeSummary = rangeSummary,
            )
        }
    }

    /** The night to show on the Day view: before 4 AM local, still last night (PulseServices).
     *  Shared with the widget snapshot publisher via [TimeUtil.referenceNightLocal]. */
    private fun dayReferenceNight(): Long = TimeUtil.referenceNightLocal()
}

/**
 * ActivityViewModel — reads Room data for the Activity screen.
 */
class ActivityViewModel(db: PulseLoopDatabase) : ViewModel() {
    data class ActivityState(
        val recentDays: List<ActivityDailyEntity> = emptyList(),
        /** All finished sessions, newest first (drives Today + the history sheet). */
        val finishedWorkouts: List<ActivitySessionEntity> = emptyList(),
        val today: ActivityDailyEntity? = null,
        val stepGoal: Int = 10000,
        val activeMinutesGoal: Int = 45,
        val distanceGoalMeters: Double = 8000.0,
        val caloriesGoal: Int = 500,
        val sleepMinutesGoal: Int = 480,
        val workoutsPerWeekGoal: Int = 4,
    )

    private val _state = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private val db = db

    init {
        viewModelScope.launch {
            db.activityDailyDao().recentFlow(7).collect { days ->
                _state.update { it.copy(recentDays = days) }
            }
        }
        viewModelScope.launch {
            db.activityDailyDao().byDayFlow(TimeUtil.startOfTodayLocal()).collect { day ->
                _state.update { it.copy(today = day) }
            }
        }
        viewModelScope.launch {
            db.activitySessionDao().recentFlow(200).collect { sessions ->
                _state.update { it.copy(finishedWorkouts = sessions.filter { s -> s.statusRaw == "finished" }) }
            }
        }
        // Reactive: goals saved from onboarding/Settings show up without a manual reload.
        viewModelScope.launch {
            try {
                db.userGoalDao().getFlow().collect { if (it != null) reloadGoals() }
            } catch (_: Exception) {}
        }
    }

    suspend fun reloadGoals() {
        try {
            db.userGoalDao().get()?.let { goal ->
                _state.update { it.copy(
                    stepGoal = goal.steps,
                    activeMinutesGoal = goal.activeMinutes,
                    distanceGoalMeters = goal.distanceMeters,
                    caloriesGoal = goal.calories,
                    sleepMinutesGoal = goal.sleepMinutes,
                    workoutsPerWeekGoal = goal.workoutsPerWeek,
                ) }
            }
        } catch (_: Exception) {}
    }

    suspend fun saveGoals(steps: Int, activeMinutes: Int, sleepMinutes: Int, workoutsPerWeek: Int) {
        val existing = try { db.userGoalDao().get() } catch (_: Exception) { null }
        db.userGoalDao().upsert(
            (existing ?: UserGoalEntity()).copy(
                steps = steps,
                activeMinutes = activeMinutes,
                sleepMinutes = sleepMinutes,
                workoutsPerWeek = workoutsPerWeek,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        reloadGoals()
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
        // Timestamped copies of the sample lists above, for VitalsCardFactory (trend/staleness
        // math needs real timestamps). Same rows, same order, same offsets applied.
        val hrSeries: List<VitalSample> = emptyList(),
        val spo2Series: List<VitalSample> = emptyList(),
        val hrvSeries: List<VitalSample> = emptyList(),
        val stressSeries: List<VitalSample> = emptyList(),
        val fatigueSeries: List<VitalSample> = emptyList(),
        val tempSeries: List<VitalSample> = emptyList(),
        val bpSysSeries: List<VitalSample> = emptyList(),
        val bpDiaSeries: List<VitalSample> = emptyList(),
        val glucoseSeries: List<VitalSample> = emptyList(),
        /** 24h max HR, for the HR card's "Peak" subtitle. */
        val peakHr: Double? = null,
        /** Physiology inputs (age/sex) from the stored user profile — shifts reference ranges. */
        val profile: UserPhysiologyProfile = UserPhysiologyProfile.UNKNOWN,
        /** A cuff reference (BP adjust offsets) has been entered in Settings. */
        val hasBPReference: Boolean = false,
        /** A glucose reference/offset has been entered in Settings. */
        val isGlucoseCalibrated: Boolean = false,
        val supportsHrv: Boolean = false,
        val supportsStress: Boolean = false,
        val supportsFatigue: Boolean = false,
        val supportsTemp: Boolean = false,
        val supportsBP: Boolean = false,
        val supportsGlucose: Boolean = false,
        val supportsManualHr: Boolean = false,
        val supportsManualSpo2: Boolean = false,
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
        _state.value = buildState(db, apiKeyStore)
    }

    companion object {

    /**
     * The full Vitals-state assembly, extracted so it is shared verbatim between the screen's
     * polling refresh and the home-screen-widget publisher
     * ([com.pulseloop.widgets.WidgetSnapshotPublisher]) — a widget tile is built from exactly the
     * same series fetches, offsets, and capability gating as its Vitals/Today card.
     */
    suspend fun buildState(db: PulseLoopDatabase, apiKeyStore: ApiKeyStore? = null): VitalsState {
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

        // iOS `rangeSamples`: demo mode charts the FULL seeded history (per kind) instead of the
        // 24h window — that's what makes sparse demo series (daily HRV/temp) render as the
        // month-long scatter the Simulator shows. Real ring data keeps the 24h window.
        suspend fun series(kind: MeasurementKind): List<com.pulseloop.data.entity.MeasurementEntity> =
            if (db.measurementDao().hasDemo(kind.name))
                db.measurementDao().range(kind.name, 0, Long.MAX_VALUE)
            else
                db.measurementDao().range(kind.name, twentyFourHoursAgo, now)

        val hr = series(MeasurementKind.HEART_RATE)
        val spo2 = series(MeasurementKind.SPO2)
        val hrv = if (caps.contains(WearableCapability.HRV)) series(MeasurementKind.HRV) else emptyList()
        val stress = if (caps.contains(WearableCapability.STRESS)) series(MeasurementKind.STRESS) else emptyList()
        val fatigue = if (caps.contains(WearableCapability.FATIGUE)) series(MeasurementKind.FATIGUE) else emptyList()
        val temp = if (caps.contains(WearableCapability.TEMPERATURE)) series(MeasurementKind.TEMPERATURE) else emptyList()
        val bpSys = if (caps.contains(WearableCapability.BLOOD_PRESSURE)) series(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC) else emptyList()
        val bpDia = if (caps.contains(WearableCapability.BLOOD_PRESSURE)) series(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC) else emptyList()
        val glucoseOffset = apiKeyStore?.glucoseOffsetMgdl ?: 0.0
        val gluc = if (caps.contains(WearableCapability.BLOOD_SUGAR)) series(MeasurementKind.BLOOD_SUGAR) else emptyList()
        val userProfile = db.userProfileDao().get()

        return VitalsState(
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
            // Latest = the series' last sample (iOS `inputs.systolic.last`) — demo seeds today's
            // fixed-hour readings which a `timestamp <= now` probe would skip. Falls back to the
            // repo's latest row when the window is empty.
            bpSystolic = (bpSys.lastOrNull()?.value ?: db.measurementDao().latest(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name))?.toInt()
                ?.plus(apiKeyStore?.bpAdjustSystolic ?: 0),
            bpDiastolic = (bpDia.lastOrNull()?.value ?: db.measurementDao().latest(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC.name))?.toInt()
                ?.plus(apiKeyStore?.bpAdjustDiastolic ?: 0),
            bloodSugar = (gluc.lastOrNull()?.value ?: db.measurementDao().latest(MeasurementKind.BLOOD_SUGAR.name))
                ?.plus(glucoseOffset),
            bpSysSamples = bpSys.map { it.value },
            bpDiaSamples = bpDia.map { it.value },
            glucoseSamples = gluc.map { it.value + glucoseOffset },
            hrSeries = hr.map { VitalSample(it.timestamp, it.value) },
            spo2Series = spo2.map { VitalSample(it.timestamp, it.value) },
            hrvSeries = hrv.map { VitalSample(it.timestamp, it.value) },
            stressSeries = stress.map { VitalSample(it.timestamp, it.value) },
            fatigueSeries = fatigue.map { VitalSample(it.timestamp, it.value) },
            tempSeries = temp.map { VitalSample(it.timestamp, it.value) },
            bpSysSeries = bpSys.map { VitalSample(it.timestamp, it.value) },
            bpDiaSeries = bpDia.map { VitalSample(it.timestamp, it.value) },
            glucoseSeries = gluc.map { VitalSample(it.timestamp, it.value + glucoseOffset) },
            peakHr = hr.maxOfOrNull { it.value },
            profile = UserPhysiologyProfile.fromProfile(userProfile?.age, userProfile?.sex),
            // "Reference entered" ⇔ a non-zero calibration offset in Settings (0 = not set).
            hasBPReference = (apiKeyStore?.bpAdjustSystolic ?: 0) != 0 || (apiKeyStore?.bpAdjustDiastolic ?: 0) != 0,
            isGlucoseCalibrated = glucoseOffset != 0.0 || (apiKeyStore?.glucoseRefMgdl ?: 0.0) != 0.0,
            supportsHrv = caps.contains(WearableCapability.HRV),
            supportsStress = caps.contains(WearableCapability.STRESS),
            supportsFatigue = caps.contains(WearableCapability.FATIGUE),
            supportsTemp = caps.contains(WearableCapability.TEMPERATURE),
            supportsBP = caps.contains(WearableCapability.BLOOD_PRESSURE),
            supportsGlucose = caps.contains(WearableCapability.BLOOD_SUGAR),
            supportsManualHr = caps.contains(WearableCapability.MANUAL_HEART_RATE),
            supportsManualSpo2 = caps.contains(WearableCapability.MANUAL_SPO2),
        )
    }

    }
}

/**
 * The one [VitalsCardFactory.Inputs] construction from a [VitalsViewModel.VitalsState], shared by
 * the Today grid, the Vitals screen, and the widget snapshot publisher so all three feed the
 * factory identically (iOS TodayStore parity).
 */
fun VitalsViewModel.VitalsState.toCardInputs(units: UnitSystem): VitalsCardFactory.Inputs =
    VitalsCardFactory.Inputs(
        hr = hrSeries,
        spo2 = spo2Series,
        hrv = hrvSeries,
        stress = stressSeries,
        fatigue = fatigueSeries,
        temperature = tempSeries,
        systolic = bpSysSeries,
        diastolic = bpDiaSeries,
        glucose = glucoseSeries,
        latestHr = latestHr?.toDouble(),
        latestSpo2 = latestSpo2?.toDouble(),
        restingHr = restingHr,
        peakHr = peakHr,
        unitSystem = units,
        hasBPReference = hasBPReference,
        isGlucoseCalibrated = isGlucoseCalibrated,
    )

/**
 * CoachViewModel — wires the coach orchestrator to the UI.
 */
class CoachViewModel(
    private val db: PulseLoopDatabase,
    private val orchestrator: com.pulseloop.coach.orchestration.CoachOrchestrator,
    /** Resolves staged attachment refs to wire payloads (injected so the VM stays context-free). */
    private val attachmentPayloads: (List<com.pulseloop.coach.attachments.CoachAttachmentRef>) -> List<com.pulseloop.coach.attachments.CoachImagePayload> = { emptyList() },
) : ViewModel() {
    data class CoachState(
        val messages: List<ChatMessage> = emptyList(),
        val isThinking: Boolean = false,
        val error: String? = null,
    )

    data class ChatMessage(
        val role: String,
        val text: String,
        /** Image attachments on a user turn (iOS #31); thumbnails render from the stored files. */
        val attachments: List<com.pulseloop.coach.attachments.CoachAttachmentRef> = emptyList(),
    )

    private val _state = MutableStateFlow(CoachState(
        messages = listOf(ChatMessage("assistant",
            "Hi! I'm your PulseLoop coach. I can answer questions about your sleep, heart rate, activity, and recovery. What would you like to know?"))
    ))
    val state: StateFlow<CoachState> = _state.asStateFlow()

    /** Reset to a fresh thread (iOS CoachView "+" button). In-memory only, like the thread itself. */
    fun newConversation() {
        _state.value = CoachState(
            messages = listOf(ChatMessage("assistant",
                "Hi! I'm your PulseLoop coach. I can answer questions about your sleep, heart rate, activity, and recovery. What would you like to know?"))
        )
    }

    fun sendMessage(
        userText: String,
        attachments: List<com.pulseloop.coach.attachments.CoachAttachmentRef> = emptyList(),
    ) {
        _state.update { it.copy(
            messages = it.messages + ChatMessage("user", userText, attachments),
            isThinking = true, error = null,
        ) }
        viewModelScope.launch {
            try {
                val packet = com.pulseloop.coach.context.CoachContextBuilder.build(db)
                val prior = _state.value.messages.dropLast(1)
                // Replay images only on the most recent prior user turn that has attachments,
                // keeping context coherent without ballooning payloads with old base64.
                val lastAttached = prior.indexOfLast { it.role == "user" && it.attachments.isNotEmpty() }
                val priorMessages = prior.mapIndexed { i, m ->
                    com.pulseloop.coach.orchestration.CoachOrchestrator.PriorMessage(
                        m.role, m.text,
                        images = if (i == lastAttached) attachmentPayloads(m.attachments) else emptyList(),
                    )
                }
                val result = orchestrator.runTurn(
                    userText, packet, priorMessages,
                    userImages = attachmentPayloads(attachments),
                )
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
        /** Profile-aware engine zones (unclamped bounds) for the iOS-style REFERENCE ZONES card. */
        val engineZones: List<com.pulseloop.service.MetricZone> = emptyList(),
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
        // Swap in the same profile-aware engine zones the Vitals cards use, so the detail
        // chart's line/band colors match the dashboard exactly. The static table stays as
        // the fallback (and provides the display range the chart is drawn against).
        viewModelScope.launch {
            try {
                val userProfile = db.userProfileDao().get()
                val physiology = UserPhysiologyProfile.fromProfile(userProfile?.age, userProfile?.sex)
                engineThresholds(metric, physiology)?.let { engine ->
                    _state.update { it.copy(thresholds = engine) }
                }
            } catch (_: Exception) {}
        }
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
     * The Vitals-card zone model (VitalsThresholdEngine) converted into the [MetricThresholds]
     * shape the interactive detail chart consumes — the PR #5 chart machinery is untouched;
     * only the zone table it colors from changes. Open-ended engine bounds clamp to the static
     * table's display range so the bar/domain stay finite.
     */
    private fun engineThresholds(metricKey: String, physiology: UserPhysiologyProfile): MetricThresholds? {
        val kind = engineKind(metricKey) ?: return null
        val base = MetricThresholdTable.forKey(metricKey) ?: return null
        val zones = com.pulseloop.service.VitalsThresholdEngine.zones(kind, physiology)
        if (zones.isEmpty()) return null
        return base.copy(
            zones = zones.map { z ->
                com.pulseloop.ui.components.ThresholdZone(
                    label = z.label,
                    start = z.lower ?: base.displayMin,
                    end = z.upper ?: base.displayMax,
                    color = z.colorToken.toColor(),
                )
            },
        )
    }

    /** The zone-engine metric for a detail-route key. */
    private fun engineKind(metricKey: String): com.pulseloop.service.MetricKind? = when (metricKey) {
        "hr" -> com.pulseloop.service.MetricKind.HEART_RATE
        "spo2" -> com.pulseloop.service.MetricKind.SPO2
        "stress" -> com.pulseloop.service.MetricKind.STRESS
        "fatigue" -> com.pulseloop.service.MetricKind.FATIGUE
        "hrv" -> com.pulseloop.service.MetricKind.HRV
        "temp" -> com.pulseloop.service.MetricKind.TEMPERATURE
        "bp" -> com.pulseloop.service.MetricKind.BLOOD_PRESSURE
        "glucose" -> com.pulseloop.service.MetricKind.GLUCOSE
        else -> null
    }

    fun setPeriod(p: Period) {
        val anchor = when (p) {
            Period.DAY   -> System.currentTimeMillis()   // rolling last 24h, ending now
            Period.WEEK  -> TimeUtil.startOfDayLocal(System.currentTimeMillis())
            Period.MONTH -> TimeUtil.startOfDayLocal(System.currentTimeMillis())
        }
        _state.update { it.copy(period = p, anchor = anchor) }
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
        val todayStart = TimeUtil.startOfTodayLocal()
        val maxAnchor = when (st.period) {
            Period.DAY -> System.currentTimeMillis()  // rolling: end can reach now
            Period.WEEK -> todayStart
            Period.MONTH -> {
                val i = Instant.ofEpochMilli(todayStart).atZone(ZoneId.systemDefault())
                i.withDayOfMonth(1).toInstant().toEpochMilli()
            }
        }
        if (newAnchor > maxAnchor) return
        _state.update { it.copy(anchor = newAnchor) }
        viewModelScope.launch { refresh() }
    }

    /** Whether forward navigation is allowed (disabled at the present/latest window). */
    fun canGoForward(): Boolean {
        val st = _state.value
        val todayStart = TimeUtil.startOfTodayLocal()
        return when (st.period) {
            // Enabled only if a full 24h step still fits before now (i.e. we're paged back).
            Period.DAY   -> st.anchor + 86_400_000L <= System.currentTimeMillis()
            Period.WEEK  -> st.anchor < todayStart
            Period.MONTH -> {
                val i = Instant.ofEpochMilli(todayStart).atZone(ZoneId.systemDefault())
                st.anchor < i.withDayOfMonth(1).toInstant().toEpochMilli()
            }
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
        // Profile-aware zones for the REFERENCE ZONES card (iOS legend) — HRV gets its
        // baseline-relative zones from the window's samples further below.
        val physiology = try {
            val userProfile = db.userProfileDao().get()
            UserPhysiologyProfile.fromProfile(userProfile?.age, userProfile?.sex)
        } catch (_: Exception) { UserPhysiologyProfile.UNKNOWN }

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

            _state.update { it.copy(
                anchor = anchor,
                points = points, secondary = secondary, labels = labels,
                timestamps = times,
                // iOS uses the window's last reading, not the global latest.
                latest = points.lastOrNull(),
                min = allValues.minOrNull(), avg = thisAvg, max = allValues.maxOrNull(),
                trend = trend,
                engineZones = com.pulseloop.service.VitalsThresholdEngine.zones(
                    com.pulseloop.service.MetricKind.BLOOD_PRESSURE, physiology,
                ),
                loading = false,
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

            // Resting HR from the window's raw samples (low-percentile estimate).
            val resting = if (kind == MeasurementKind.HEART_RATE)
                HeartRateZones.restingHeartRate(samples.map { it.value })
            else null

            // HRV zones are baseline-relative; the detail window (Week/Month) carries enough
            // history to establish one (iOS `baselineForChart`).
            val baseline = if (metric == "hrv")
                com.pulseloop.service.BaselineStats.compute(samples.map { VitalSample(it.timestamp, it.value) })
            else null
            val engineZones = engineKind(metric)?.let { k ->
                com.pulseloop.service.VitalsThresholdEngine.zones(k, physiology, baseline = baseline)
            } ?: emptyList()

            _state.update { it.copy(
                anchor = anchor,
                points = points, secondary = emptyList(), labels = labels,
                timestamps = times,
                // iOS uses the window's last reading, not the global latest.
                latest = points.lastOrNull(),
                min = points.minOrNull(), avg = thisAvg, max = points.maxOrNull(),
                resting = resting,
                trend = trend,
                engineZones = engineZones,
                loading = false,
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
