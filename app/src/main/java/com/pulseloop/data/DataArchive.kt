package com.pulseloop.data

import kotlinx.serialization.Serializable

@Serializable
data class PulseArchive(
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val appVersion: String,
    val counts: Map<String, Int> = emptyMap(),
    val devices: List<DeviceDTO> = emptyList(),
    val measurements: List<MeasurementDTO> = emptyList(),
    val activityDaily: List<ActivityDailyDTO> = emptyList(),
    val activityBuckets: List<ActivityBucketDTO> = emptyList(),
    val batterySamples: List<BatterySampleDTO> = emptyList(),
    val deviceMeasurementConfigs: List<DeviceMeasurementConfigDTO> = emptyList(),
    val activitySessions: List<ActivitySessionDTO> = emptyList(),
    val activityGpsPoints: List<ActivityGpsPointDTO> = emptyList(),
    val activityEvents: List<ActivityEventDTO> = emptyList(),
    val activitySamples: List<ActivitySampleDTO> = emptyList(),
    val activitySensorPolls: List<ActivitySensorPollDTO> = emptyList(),
    val sleepSessions: List<SleepSessionDTO> = emptyList(),
    val sleepStageBlocks: List<SleepStageBlockDTO> = emptyList(),
    val coachConversations: List<CoachConversationDTO> = emptyList(),
    val coachMessages: List<CoachMessageDTO> = emptyList(),
    val coachMemories: List<CoachMemoryDTO> = emptyList(),
    val coachToolCalls: List<CoachToolCallDTO> = emptyList(),
    val userProfiles: List<UserProfileDTO> = emptyList(),
    val userGoals: List<UserGoalDTO> = emptyList(),
    val rawPackets: List<RawPacketDTO> = emptyList(),
    val derivedUpdates: List<DerivedUpdateDTO> = emptyList(),
    val coachSummaries: List<CoachSummaryDTO> = emptyList(),
    val wearableLogs: List<WearableLogDTO> = emptyList(),
    val coachNotificationRecords: List<CoachNotificationRecordDTO> = emptyList(),
    /** iOS #96 nutrition. Not in iOS's own `DataArchive.swift` — see the note on [MealEntryDTO]. */
    val mealEntries: List<MealEntryDTO> = emptyList(),
    val foodProducts: List<CachedFoodProductDTO> = emptyList(),
)

@Serializable data class DeviceDTO(
    val id: String, val name: String, val advertisedName: String? = null,
    val peripheralIdentifier: String? = null, val bleAddressHint: String? = null,
    val batteryPercent: Int? = null, val stateRaw: String, val deviceTypeRaw: String,
    val wearableModelID: String? = null, val capabilitiesRaw: String,
    val lastConnectedAt: Long? = null, val lastDisconnectedAt: Long? = null,
    val lastSyncAt: Long? = null, val lastFullSyncAt: Long? = null,
    val firmwareVersion: String? = null, val createdAt: Long, val updatedAt: Long,
)

@Serializable data class MeasurementDTO(
    val id: String, val kindRaw: String, val value: Double, val unit: String,
    val timestamp: Long, val sourceRaw: String, val confidenceRaw: String = "known",
    val activitySessionId: String? = null, val rawPacketId: String? = null,
    val createdAt: Long,
)

@Serializable data class ActivityDailyDTO(
    val id: String, val date: Long, val steps: Int = 0, val calories: Double = 0.0,
    val distanceMeters: Double = 0.0, val activeMinutes: Int = 0,
    val source: String = "mock", val syncedAt: Long? = null,
    val createdAt: Long, val updatedAt: Long,
    val estimatedActiveCalories: Double? = null,
)

@Serializable data class ActivityBucketDTO(
    val startEpoch: Long, val date: Long, val steps: Int = 0,
    val distanceMeters: Double = 0.0, val source: String = "ring_history",
    val updatedAt: Long,
)

@Serializable data class BatterySampleDTO(
    val id: String, val percent: Int, val timestamp: Long, val createdAt: Long,
)

@Serializable data class DeviceMeasurementConfigDTO(
    val deviceId: String, val hrIntervalMinutes: Int = 5, val hrEnabled: Boolean = true,
    val spo2Enabled: Boolean = true, val stressEnabled: Boolean = true,
    val hrvEnabled: Boolean = true, val temperatureEnabled: Boolean = true,
    val updatedAt: Long,
)

@Serializable data class ActivitySessionDTO(
    val id: String, val type: String, val statusRaw: String = "recording",
    val startedAt: Long, val endedAt: Long? = null, val totalPauseSeconds: Double = 0.0,
    val calories: Double? = null, val distanceMeters: Double? = null,
    val avgHeartRate: Double? = null, val minHeartRate: Double? = null,
    val maxHeartRate: Double? = null, val avgSpO2: Double? = null,
    val latestSpO2: Double? = null, val notes: String? = null,
    val useGps: Boolean = true, val perceivedEffort: String? = null,
    val gpsPointCount: Int = 0, val rejectedGpsPointCount: Int = 0,
    val hrPollCount: Int = 0, val hrPollFailureCount: Int = 0,
    val spo2PollCount: Int = 0, val spo2PollFailureCount: Int = 0,
    val liveActivityID: String? = null, val lastSensorPollAt: Long? = null,
    val lastGpsPointAt: Long? = null, val stravaActivityId: Long? = null,
    val createdAt: Long, val updatedAt: Long,
)

@Serializable data class ActivityGpsPointDTO(
    val id: String, val sessionId: String, val latitude: Double, val longitude: Double,
    val altitude: Double? = null, val horizontalAccuracy: Double? = null,
    val speed: Double? = null, val course: Double? = null, val timestamp: Long,
    val accepted: Boolean = true, val rejectionReason: String? = null,
)

@Serializable data class ActivityEventDTO(
    val id: String, val sessionId: String, val kind: String, val timestamp: Long,
    val payloadJSON: String? = null,
)

@Serializable data class ActivitySampleDTO(
    val id: String, val sessionId: String, val measurementId: String? = null,
    val kind: String, val value: Double, val unit: String, val timestamp: Long,
    val source: String = "mock", val confidenceRaw: String = "known",
)

@Serializable data class ActivitySensorPollDTO(
    val id: String, val sessionId: String, val timestamp: Long,
    val kind: String, val status: String, val value: Double? = null,
    val errorMessage: String? = null,
)

@Serializable data class SleepSessionDTO(
    val id: String, val date: Long, val startAt: Long, val endAt: Long,
    val totalMinutes: Int, val score: Int? = null, val syncedAt: Long? = null,
    val sourceRaw: String = "ring", val createdAt: Long, val updatedAt: Long,
)

@Serializable data class SleepStageBlockDTO(
    val id: String, val sessionId: String, val startAt: Long, val startMinute: Int,
    val durationMinutes: Int, val stageRaw: String,
)

@Serializable data class CoachConversationDTO(
    val id: String, val title: String = "Today check-in", val createdAt: Long,
    val updatedAt: Long, val totalInputTokens: Int = 0, val totalOutputTokens: Int = 0,
    val totalCostUSD: Double = 0.0,
)

@Serializable data class CoachMessageDTO(
    val id: String, val conversationId: String, val role: String, val body: String,
    val cardsJSON: String? = null, val pendingActionJSON: String? = null,
    val attachmentsJson: String? = null, val createdAt: Long,
    val inputTokens: Int? = null, val outputTokens: Int? = null,
    val costUSD: Double? = null, val modelUsed: String? = null,
    val providerUsed: String? = null,
)

@Serializable data class CoachMemoryDTO(
    val id: String, val key: String, val value: String,
    val memoryType: String = "note", val importance: Int = 3,
    val expiresAt: Long? = null, val sourceMessageId: String? = null,
    val isUserEditable: Boolean = true, val createdAt: Long, val updatedAt: Long,
)

@Serializable data class CoachToolCallDTO(
    val id: String, val conversationId: String, val messageId: String? = null,
    val toolName: String, val inputJSON: String? = null, val outputJSON: String? = null,
    val label: String = "", val statusRaw: String = "success", val sequence: Int = 0,
    val createdAt: Long,
)

@Serializable data class UserProfileDTO(
    val id: String, val name: String? = null, val age: Int? = null,
    val sex: String? = null, val heightCm: Double? = null, val weightKg: Double? = null,
    val onboardingCompleted: Boolean = false, val baselineCompleted: Boolean = false,
    val hrZoneModeRaw: String = "auto",
    val hrRestingBaseline: Double? = null,
    val hrRestingBaselineUpdatedAt: Long? = null,
    val hrCustomLowUpper: Double? = null,
    val hrCustomAthleticUpper: Double? = null,
    val hrCustomElevatedStart: Double? = null,
    val hrCustomHighStart: Double? = null,
    val createdAt: Long, val updatedAt: Long,
)

@Serializable data class UserGoalDTO(
    val id: String, val steps: Int = 10000, val distanceMeters: Double = 8000.0,
    val calories: Int = 500, val sleepMinutes: Int = 480,
    val activeMinutes: Int = 45, val workoutsPerWeek: Int = 4,
    // iOS #96 intake goals. Defaulted so archives written before these existed still import.
    val intakeCalories: Double? = null, val intakeProteinG: Double? = null,
    val intakeCarbsG: Double? = null, val intakeFatG: Double? = null,
    val nutritionEnabled: Boolean = false,
    val updatedAt: Long,
)

@Serializable data class RawPacketDTO(
    val id: String, val timestamp: Long, val directionRaw: String,
    val commandId: Int, val hexPayload: String, val decodedKind: String? = null,
    val decodedJSON: String? = null, val confidenceRaw: String = "unknown",
    val createdAt: Long,
)

@Serializable data class DerivedUpdateDTO(
    val id: String, val timestamp: Long, val kind: String,
    val entityType: String, val entityId: String, val payloadJSON: String? = null,
)

@Serializable data class CoachSummaryDTO(
    val id: String, val kind: String, val scopeKey: String,
    val title: String, val body: String, val chipsJSON: String? = null,
    val conversationId: String? = null, val dataSignature: String,
    val createdAt: Long, val updatedAt: Long,
)

@Serializable data class WearableLogDTO(
    val id: String, val timestamp: Long, val event: String,
    val detail: String? = null, val deviceId: String? = null,
    val categoryRaw: String? = null, val levelRaw: String? = null,
)

@Serializable data class CoachNotificationRecordDTO(
    val id: String, val title: String, val body: String, val createdAt: Long,
)

/**
 * iOS #96 meal log. **Android-originated — iOS's `DataArchive.swift` does not carry these.** Its
 * exporter landed in the same week as the nutrition models and was never extended, so an iOS
 * export→import silently drops the user's meals. Included here because the Android import dialog
 * promises to "permanently delete everything currently in the app and replace it", and wiping
 * meal_entries without restoring them would make that literally true in the worst way. Upstream
 * candidate for iOS.
 */
@Serializable data class MealEntryDTO(
    val id: String, val date: Long, val timestamp: Long, val name: String,
    val mealTypeRaw: String = "snack", val calories: Double,
    val proteinG: Double = 0.0, val carbsG: Double = 0.0, val fatG: Double = 0.0,
    val fiberG: Double? = null, val sugarG: Double? = null, val sodiumMg: Double? = null,
    val sourceRaw: String = "manual", val offProductCode: String? = null,
    val servingDescription: String? = null, val servingGrams: Double? = null,
    val quantity: Double = 1.0, val confidenceRaw: String = "medium",
    val userEdited: Boolean = false, val notes: String? = null,
    val loggedByCoach: Boolean = false, val createdAt: Long,
    // Phase 6: exported so an in-place-edited meal's updatedAt survives an archive round-trip.
    // Old archives lack it (deserializes to 0) -> restore backfills from createdAt.
    val updatedAt: Long = 0L,
)

@Serializable data class CachedFoodProductDTO(
    val code: String, val name: String, val brand: String? = null,
    val energyKcal100g: Double, val protein100g: Double = 0.0,
    val carbs100g: Double = 0.0, val fat100g: Double = 0.0,
    val fiber100g: Double? = null, val sugars100g: Double? = null,
    val saturatedFat100g: Double? = null, val sodiumMg100g: Double? = null,
    val servingSizeText: String? = null, val servingQuantityG: Double? = null,
    val lastUsedAt: Long, val useCount: Int = 0,
)
