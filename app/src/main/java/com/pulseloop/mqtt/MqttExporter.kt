package com.pulseloop.mqtt

import android.content.Context
import com.pulseloop.AppServices
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.service.DailyCalorieEstimator
import com.pulseloop.service.SleepScore
import com.pulseloop.service.awakeMinutes
import com.pulseloop.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.round
import kotlin.math.roundToInt

object MqttExporter {

    sealed class Result {
        data class Success(
            val messageCount: Int,
            val sensorCount: Int,
            val isLive: Boolean
        ) : Result()

        data object Skipped : Result()

        data class Failure(
            val reason: String,
            val error: Throwable? = null
        ) : Result()
    }

    private val DEMO_SOURCES = setOf("demo", "mock")

    /**
     * Collects the current health snapshot from the local database and publishes it
     * to the configured MQTT broker, including discovery and state messages.
     */
    suspend fun run(
        context: Context,
        isLive: Boolean = false,
        forceDiscovery: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        val store = MqttPrefsStore.get(context)
        val prefs = store.current
        if (!prefs.isConfigured) {
            return@withContext Result.Skipped
        }
        if (isLive && !prefs.liveStreamingEnabled) {
            return@withContext Result.Skipped
        }
        val db = PulseLoopDatabase.getInstance(context)
        val device = db.deviceDao().currentReal() ?: return@withContext Result.Skipped
        publishInternal(context, store, prefs, device, isLive, forceDiscovery)
    }

    /**
     * Validates the broker settings and attempts a bare connection.
     * Never swallows [CancellationException].
     */
    suspend fun testConnection(context: Context): Result = withContext(Dispatchers.IO) {
        val store = MqttPrefsStore.get(context)
        val prefs = store.current
        if (prefs.host.isBlank() || prefs.port !in 1..65535) {
            return@withContext Result.Failure("Broker host and port are required")
        }
        val db = PulseLoopDatabase.getInstance(context)
        val deviceId = db.deviceDao().currentReal()?.id ?: "android"
        val config = buildEndpointConfig(prefs, store, deviceId)
        try {
            MqttClient.testConnection(config)
            store.update { it.copy(lastError = null) }
            Result.Success(0, 0, false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            store.update { it.copy(lastError = message) }
            Result.Failure("Connection failed: $message", t)
        }
    }

    /**
     * Forces a full publish (including discovery) using a one-shot connection.
     */
    suspend fun publishNow(context: Context): Result = withContext(Dispatchers.IO) {
        val store = MqttPrefsStore.get(context)
        val prefs = store.current
        if (prefs.host.isBlank() || prefs.port !in 1..65535) {
            return@withContext Result.Failure("Broker host and port are required")
        }
        val db = PulseLoopDatabase.getInstance(context)
        val device = db.deviceDao().currentReal()
            ?: return@withContext Result.Failure("No paired ring found")
        publishInternal(context, store, prefs, device, isLive = false, forceDiscovery = true)
    }

    /**
     * Publishes zero-byte tombstones for all retained discovery and state topics
     * and clears the local discovery bookkeeping.
     */
    suspend fun clearRetainedTopics(context: Context): Result = withContext(Dispatchers.IO) {
        val store = MqttPrefsStore.get(context)
        val prefs = store.current
        if (prefs.host.isBlank() || prefs.port !in 1..65535) {
            return@withContext Result.Failure("Broker host and port are required")
        }
        val db = PulseLoopDatabase.getInstance(context)
        val device = db.deviceDao().currentReal()
        val identity = device?.let { MqttDiscovery.resolveDeviceIdentity(it, prefs) }
        val keys = buildSet {
            addAll(prefs.discoveredSensorKeys)
            if (device != null) {
                addAll(MqttDiscovery.sensorsFor(device, MqttDataSnapshot()).map { it.key })
            }
        }

        var messageCount = 0
        if (identity != null && keys.isNotEmpty()) {
            val messages = MqttDiscovery.buildRemovalMessages(
                prefs.normalizedDiscoveryPrefix,
                identity.uniquePrefix,
                keys
            ).toMutableList()
            val stateTopic = MqttDiscovery.stateTopic(prefs.normalizedBaseTopic, identity)
            messages.add(
                MqttMessage(
                    topic = stateTopic,
                    payload = ByteArray(0),
                    qos = 1,
                    retain = true
                )
            )
            val config = buildEndpointConfig(prefs, store, device?.id ?: "android")
            try {
                MqttClient.publishAll(config, messages)
                messageCount = messages.size
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                store.update { it.copy(lastError = t.message ?: t.javaClass.simpleName) }
                return@withContext Result.Failure(
                    t.message ?: "Failed to clear retained MQTT topics",
                    t
                )
            }
        }

        store.update {
            it.copy(
                discoveredSensorKeys = emptySet(),
                lastDiscoveredDeviceSlug = null,
                lastPublishAt = null,
                lastPublishSummary = null,
                lastError = null
            )
        }
        Result.Success(messageCount, keys.size, false)
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private suspend fun publishInternal(
        context: Context,
        store: MqttPrefsStore,
        prefs: MqttPrefs,
        device: DeviceEntity,
        isLive: Boolean,
        forceDiscovery: Boolean
    ): Result {
        val db = PulseLoopDatabase.getInstance(context)
        val identity = MqttDiscovery.resolveDeviceIdentity(device, prefs)
        val snapshot = collectSnapshot(db, device)
        val (messages, discoveredKeys) = MqttDiscovery.buildMqttMessages(
            device,
            snapshot,
            prefs,
            forceDiscovery
        )
        if (messages.isEmpty()) {
            return Result.Skipped
        }
        val config = buildEndpointConfig(prefs, store, device.id)
        return try {
            if (isLive) {
                MqttSessionPool.shared.publish(config, messages)
            } else {
                MqttClient.publishAll(config, messages)
            }
            val now = System.currentTimeMillis()
            store.update {
                it.copy(
                    discoveredSensorKeys = discoveredKeys,
                    lastDiscoveredDeviceSlug = identity.slug,
                    lastPublishAt = now,
                    lastPublishSummary = "${messages.size} message(s), ${discoveredKeys.size} sensor(s)" +
                            if (isLive) " (live)" else "",
                    lastError = null
                )
            }
            Result.Success(messages.size, discoveredKeys.size, isLive)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            store.update { it.copy(lastError = t.message ?: t.javaClass.simpleName) }
            Result.Failure(t.message ?: "MQTT publish failed", t)
        }
    }

    private fun buildEndpointConfig(
        prefs: MqttPrefs,
        store: MqttPrefsStore,
        deviceId: String
    ): MqttEndpointConfig = MqttEndpointConfig(
        host = prefs.host,
        port = prefs.port,
        useTls = prefs.useTls,
        transport = prefs.transport,
        wsPath = prefs.normalizedWsPath,
        username = prefs.username,
        password = store.getPassword(),
        clientId = buildClientId(deviceId)
    )

    /**
     * Deterministic MQTT client id, guaranteed to be at most 23 characters:
     * "pl_" (3) + up to 12 hex-ish chars = at most 15 characters.
     */
    private fun buildClientId(deviceId: String): String =
        ("pl_" + deviceId.replace("-", "").takeLast(12)).take(23)

    internal suspend fun collectSnapshot(
        db: PulseLoopDatabase,
        device: DeviceEntity
    ): MqttDataSnapshot {
        val measurementDao = db.measurementDao()

        val availableKinds = buildSet<String> {
            for (kind in MeasurementKind.entries) {
                if (measurementDao.hasReal(kind.name)) {
                    add(kind.name)
                }
            }
        }

        suspend fun latest(kind: MeasurementKind): Double? = measurementDao.latestReal(kind.name)?.value

        var heartRateBpm = latest(MeasurementKind.HEART_RATE)?.roundToInt()
        var spo2Percent = latest(MeasurementKind.SPO2)?.roundToInt()
        val hrvMs = latest(MeasurementKind.HRV)?.let { roundTo1Decimal(it) }
        val stressScore = if (MeasurementKind.STRESS.name in availableKinds) {
            latest(MeasurementKind.STRESS)?.roundToInt()
        } else {
            null
        }
        val temperatureCelsius = latest(MeasurementKind.TEMPERATURE)?.let { roundTo1Decimal(it) }
        val bpSystolicMmhg = latest(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC)?.roundToInt()
        val bpDiastolicMmhg = latest(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC)?.roundToInt()
        val bloodSugarMmol = latest(MeasurementKind.BLOOD_SUGAR)?.let { roundTo1Decimal(it) }
        val respiratoryRateBrpm = if (MeasurementKind.RESPIRATORY_RATE.name in availableKinds) {
            latest(MeasurementKind.RESPIRATORY_RATE)?.let { roundTo1Decimal(it) }
        } else {
            null
        }
        val vo2Max = latest(MeasurementKind.VO2MAX)?.let { roundTo1Decimal(it) }

        val profile = db.userProfileDao().get()
        val restingHeartRateBpm = profile?.hrRestingBaseline?.roundToInt()

        // Today's real activity
        val todayRow = db.activityDailyDao()
            .byDay(TimeUtil.startOfTodayLocal())
            ?.takeIf { it.source !in DEMO_SOURCES }
        val steps = todayRow?.steps
        val caloriesKcal = todayRow?.let {
            DailyCalorieEstimator.effectiveCalories(
                it,
                DailyCalorieEstimator.Profile(
                    profile?.sex,
                    profile?.age,
                    profile?.weightKg,
                    profile?.heightCm
                ),
                System.currentTimeMillis()
            )?.roundToInt()?.toDouble()
        }
        val distanceMeters = todayRow?.distanceMeters?.roundToInt()
        val activeMinutes = todayRow?.activeMinutes

        // Last night's real sleep
        val sleepSession = db.sleepSessionDao()
            .ringAllByDay(TimeUtil.referenceNightLocal())
            .filter { it.sourceRaw !in DEMO_SOURCES && it.totalMinutes > 0 }
            .maxByOrNull { it.totalMinutes }
        val sleepBlocks = sleepSession
            ?.let { db.sleepStageBlockDao().forSession(it.id) }
            .orEmpty()
        val sleepTotalMinutes = sleepSession?.totalMinutes
        val sleepScore = sleepSession?.let { SleepScore.calculate(it, sleepBlocks).score }
        val sleepDeepMinutes = if (sleepSession != null) {
            sleepBlocks
                .filter { it.stageRaw.equals("DEEP", ignoreCase = true) }
                .sumOf { it.durationMinutes }
        } else {
            null
        }
        val sleepLightMinutes = if (sleepSession != null) {
            sleepBlocks
                .filter { it.stageRaw.equals("LIGHT", ignoreCase = true) }
                .sumOf { it.durationMinutes }
        } else {
            null
        }
        val sleepRemMinutes = if (sleepSession != null) {
            sleepBlocks
                .filter { it.stageRaw.equals("REM", ignoreCase = true) }
                .sumOf { it.durationMinutes }
        } else {
            null
        }
        val sleepAwakeMinutes = sleepSession?.let { awakeMinutes(sleepBlocks) }

        // Live workout
        val workoutState = AppServices.peek()?.liveWorkout?.state?.value
        val activeSession = if (workoutState?.isRecording == true) {
            workoutState.activeSession
        } else {
            db.activitySessionDao().active()
        }
        val workoutActive = (workoutState?.isRecording == true) || activeSession != null
        heartRateBpm = workoutState?.takeIf { it.isRecording }?.latestHeartRate ?: heartRateBpm
        spo2Percent = workoutState?.takeIf { it.isRecording }?.latestSpO2 ?: spo2Percent
        val workoutType = activeSession?.type
            ?.replace('_', ' ')
            ?.lowercase()
            ?.replaceFirstChar { it.uppercaseChar() }
        val workoutDurationSeconds = when {
            workoutState?.isRecording == true -> workoutState.elapsedSeconds
            activeSession != null -> ((System.currentTimeMillis() - activeSession.startedAt) / 1000L)
                .toInt()
                .coerceAtLeast(0)
            else -> null
        }

        val lastSyncIso = listOfNotNull(device.lastSyncAt, device.lastFullSyncAt)
            .maxOrNull()
            ?.let { Instant.ofEpochMilli(it).toString() }

        return MqttDataSnapshot(
            availableKinds = availableKinds,
            heartRateBpm = heartRateBpm,
            spo2Percent = spo2Percent,
            hrvMs = hrvMs,
            stressScore = stressScore,
            temperatureCelsius = temperatureCelsius,
            bpSystolicMmhg = bpSystolicMmhg,
            bpDiastolicMmhg = bpDiastolicMmhg,
            bloodSugarMmol = bloodSugarMmol,
            respiratoryRateBrpm = respiratoryRateBrpm,
            vo2Max = vo2Max,
            restingHeartRateBpm = restingHeartRateBpm,
            steps = steps,
            caloriesKcal = caloriesKcal,
            distanceMeters = distanceMeters,
            activeMinutes = activeMinutes,
            sleepTotalMinutes = sleepTotalMinutes,
            sleepScore = sleepScore,
            sleepDeepMinutes = sleepDeepMinutes,
            sleepLightMinutes = sleepLightMinutes,
            sleepRemMinutes = sleepRemMinutes,
            sleepAwakeMinutes = sleepAwakeMinutes,
            workoutActive = workoutActive,
            workoutType = workoutType,
            workoutDurationSeconds = workoutDurationSeconds,
            lastSyncIso = lastSyncIso,
            batteryPercent = device.batteryPercent
        )
    }

    private fun roundTo1Decimal(value: Double): Double =
        round(value * 10.0) / 10.0
}
