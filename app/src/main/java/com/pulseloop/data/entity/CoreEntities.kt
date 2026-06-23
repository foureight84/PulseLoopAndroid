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
    val capabilitiesRaw: String = "",   // CSV of WearableCapability names
    val lastConnectedAt: Long? = null,
    val lastDisconnectedAt: Long? = null,
    val lastSyncAt: Long? = null,
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
    indices = [Index("timestamp"), Index("activitySessionId"), Index("kindRaw")],
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
    val steps: Int = 10000,
    val sleepMinutes: Int = 480,
    val activeMinutes: Int = 45,
    val workoutsPerWeek: Int = 4,
    val updatedAt: Long = System.currentTimeMillis(),
)
