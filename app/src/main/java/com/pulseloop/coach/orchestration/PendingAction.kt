package com.pulseloop.coach.orchestration

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ported from PendingAction.swift.
 * A risky write the coach proposed but has NOT performed — surfaced to the user
 * as a Confirm/Cancel card and only executed on tap.
 */
@Serializable
data class PendingAction(
    val kind: PendingActionKind,
    val activityId: String,
    val summary: String,           // human-readable description for the card
    val confirmLabel: String,
    val updates: ActivityUpdates? = null,  // only for updateActivitySession
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(jsonStr: String?): PendingAction? {
            if (jsonStr.isNullOrBlank()) return null
            return try { Json.decodeFromString<PendingAction>(jsonStr) }
            catch (_: Exception) { null }
        }
    }
}

@Serializable
enum class PendingActionKind {
    DELETE_ACTIVITY_SESSION,
    UPDATE_ACTIVITY_SESSION,
}

/**
 * Field updates for updateActivitySession (null = leave unchanged).
 */
@Serializable
data class ActivityUpdates(
    val type: String? = null,
    val notes: String? = null,
    val distanceKm: Double? = null,
    val durationMin: Double? = null,
    val perceivedEffort: String? = null,
    val startTime: String? = null,
)
