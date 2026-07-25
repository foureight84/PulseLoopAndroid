package com.pulseloop.data.entity

import androidx.room.*
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import java.time.Instant

/**
 * Ported from [Device] in PulseModels.swift.
 * The connected wearable ring.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "SMART_RING",
    val advertisedName: String? = null,
    val peripheralIdentifier: String? = null,
    val bleAddressHint: String? = null,
    val batteryPercent: Int? = null,
    val stateRaw: String = RingConnectionState.IDLE.name,
    val deviceTypeRaw: String = RingDeviceType.JRING.name,
    /** Exact catalog model (e.g. `colmi-r10`), separate from the protocol/driver family (iOS #49). */
    val wearableModelID: String? = null,
    val capabilitiesRaw: String = "",   // CSV of WearableCapability names
    val lastConnectedAt: Long? = null,
    val lastDisconnectedAt: Long? = null,
    val lastSyncAt: Long? = null,
    /** Stamped only when a history sync actually completes (`SyncProgress("done")`) — unlike
     *  [lastSyncAt], which the ring re-stamps on every bare CONNECT before any data streams.
     *  The coach-notification freshness gate reads this one (iOS #61c). */
    val lastFullSyncAt: Long? = null,
    val firmwareVersion: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val state: RingConnectionState get() = try { RingConnectionState.valueOf(stateRaw) } catch (_: Exception) { RingConnectionState.IDLE }
    val deviceType: RingDeviceType get() = try { RingDeviceType.valueOf(deviceTypeRaw) } catch (_: Exception) { RingDeviceType.JRING }
    val capabilities: Set<WearableCapability> get() = WearableCapability.fromCsv(capabilitiesRaw)
}

/**
 * Ported from [Measurement] in PulseModels.swift.
 * A single sensor reading (HR, SpO2, stress, HRV, temperature).
 */
@Entity(
    tableName = "measurements",
    indices = [
        Index("timestamp"),
        Index("activitySessionId"),
        Index("kindRaw"),
        Index(value = ["kindRaw", "timestamp", "sourceRaw"]),
    ],
)
data class MeasurementEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val kindRaw: String,           // MeasurementKind ordinal
    val value: Double,
    val unit: String,
    val timestamp: Long,            // epoch millis
    val sourceRaw: String,          // MeasurementSource ordinal
    val confidenceRaw: String = "known",
    val activitySessionId: String? = null,
    val rawPacketId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivityDaily] in PulseModels.swift.
 * Aggregated daily activity totals.
 */
@Entity(
    tableName = "activity_daily",
    indices = [Index("date", unique = true)],
)
data class ActivityDailyEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val date: Long,                 // epoch millis, start of day
    val steps: Int = 0,
    val calories: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val activeMinutes: Int = 0,
    val source: String = "mock",
    val syncedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivityBucketSample] in PulseModels.swift.
 * One intraday activity bucket from a ring's history sync (a Colmi `0x43` sample).
 * Keyed by `startEpoch` (the bucket's start time, epoch millis) so re-syncing the same bucket
 * **replaces** it rather than accumulating — the daily total is then the sum of distinct buckets.
 * This is the GadgetBridge model and the fix for daily totals drifting across repeated syncs.
 */
@Entity(
    tableName = "activity_buckets",
    indices = [Index("date")],
)
data class ActivityBucketEntity(
    /** Bucket start time in epoch millis — the primary key, so the same bucket upserts. */
    @PrimaryKey val startEpoch: Long,
    val date: Long,                 // epoch millis, local start of day, for per-day queries
    val steps: Int = 0,
    val distanceMeters: Double = 0.0,
    val source: String = "ring_history",
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [BatterySample] in PulseModels.swift (iOS #61b). A throttled log of the ring's
 * battery level over time, feeding the Wearable-settings drainage chart. Written by
 * [com.pulseloop.service.EventPersistenceSubscriber] on change or a 30-min floor — not every
 * `BatteryLevel` event, so the table stays a few dozen rows/day.
 */
@Entity(
    tableName = "battery_samples",
    indices = [Index("timestamp")],
)
data class BatterySampleEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val percent: Int,
    val timestamp: Long,            // epoch millis
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [DeviceMeasurementConfig] in PulseModels.swift (iOS PR #19).
 * Per-device background-measurement preferences pushed to the ring on save and on connect:
 * all-day HR sampling interval plus per-vital enable toggles.
 */
@Entity(tableName = "device_measurement_configs")
data class DeviceMeasurementConfigEntity(
    /** The DeviceEntity id this config belongs to. */
    @PrimaryKey val deviceId: String,
    /** All-day HR sampling interval, minutes. 5-minute steps, 5..60. */
    val hrIntervalMinutes: Int = 5,
    val hrEnabled: Boolean = true,
    val spo2Enabled: Boolean = true,
    val stressEnabled: Boolean = true,
    val hrvEnabled: Boolean = true,
    val temperatureEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivitySession] in PulseModels.swift.
 * A workout recording session.
 */
@Entity(tableName = "activity_sessions", indices = [Index("startedAt")])
data class ActivitySessionEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,
    val statusRaw: String = "recording",
    val startedAt: Long,            // epoch millis
    val endedAt: Long? = null,
    val totalPauseSeconds: Double = 0.0,
    val calories: Double? = null,
    val distanceMeters: Double? = null,
    val avgHeartRate: Double? = null,
    val minHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val avgSpO2: Double? = null,
    val latestSpO2: Double? = null,
    val notes: String? = null,
    val useGps: Boolean = true,
    val perceivedEffort: String? = null,
    val gpsPointCount: Int = 0,
    val rejectedGpsPointCount: Int = 0,
    val hrPollCount: Int = 0,
    val hrPollFailureCount: Int = 0,
    val spo2PollCount: Int = 0,
    val spo2PollFailureCount: Int = 0,
    val liveActivityID: String? = null,
    val lastSensorPollAt: Long? = null,
    val lastGpsPointAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivityGpsPoint] in PulseModels.swift.
 */
@Entity(tableName = "activity_gps_points", indices = [Index("sessionId"), Index("timestamp")])
data class ActivityGpsPointEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val horizontalAccuracy: Double? = null,
    val speed: Double? = null,
    val course: Double? = null,
    val timestamp: Long,
    val accepted: Boolean = true,
    val rejectionReason: String? = null,
)

/**
 * Ported from [UserProfile] in PulseModels.swift.
 */
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String? = null,
    val age: Int? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val onboardingCompleted: Boolean = false,
    val baselineCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [UserGoal] in PulseModels.swift.
 */
@Entity(tableName = "user_goals")
data class UserGoalEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val steps: Int = DEFAULT_STEPS,
    /** Daily distance goal in meters (iOS UserGoal.distanceMeters, added in iOS #48). */
    @ColumnInfo(defaultValue = "8000.0") val distanceMeters: Double = DEFAULT_DISTANCE_METERS,
    /** Daily active-calorie goal in kcal (iOS UserGoal.calories, added in iOS #48). */
    @ColumnInfo(defaultValue = "500") val calories: Int = DEFAULT_CALORIES,
    val sleepMinutes: Int = 480,
    val activeMinutes: Int = 45,
    val workoutsPerWeek: Int = 4,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        // Canonical no-goal-row fallbacks (match iOS UserGoal). Every surface that
        // renders ring progress against a goal — Today tiles, Activity screen, the
        // widget snapshot — must fall back through these, not a local literal, or the
        // widget and the app disagree the day one copy changes.
        const val DEFAULT_STEPS = 10_000
        const val DEFAULT_DISTANCE_METERS = 8_000.0
        const val DEFAULT_CALORIES = 500
    }
}
