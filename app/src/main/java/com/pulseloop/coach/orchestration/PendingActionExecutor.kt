package com.pulseloop.coach.orchestration

import com.pulseloop.coach.tools.CoachDataAccess
import com.pulseloop.data.PulseLoopDatabase

/**
 * Ported from PendingActionExecutor.swift.
 * Performs a PendingAction's real mutation — only ever called after the user
 * taps Confirm on the action card. Returns a short human result string.
 */
object PendingActionExecutor {
    private val activityLabels = mapOf(
        "walk" to "Walking", "run" to "Running", "cycle" to "Cycling",
        "gym" to "Gym", "squash" to "Squash", "sport" to "Sport",
        "yoga" to "Yoga", "dance" to "Dance", "hike" to "Hiking", "other" to "Workout",
        "outdoor_run" to "Running", "outdoor_walk" to "Walking",
    )

    suspend fun execute(action: PendingAction, db: PulseLoopDatabase): String {
        val sessions = db.activitySessionDao().recent(200)
        val session = sessions.firstOrNull { it.id == action.activityId }
            ?: return "That workout no longer exists."

        val typeLabel = activityLabels[session.type] ?: session.type

        return when (action.kind) {
            PendingActionKind.DELETE_ACTIVITY_SESSION -> {
                com.pulseloop.service.ActivityRollup.reverse(db, session)
                db.activitySessionDao().upsert(
                    session.copy(
                        statusRaw = "cancelled",
                        endedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                "Deleted the $typeLabel session."
            }
            PendingActionKind.UPDATE_ACTIVITY_SESSION -> {
                val updated = applyUpdates(action.updates, session)
                db.activitySessionDao().upsert(updated)
                "Updated the $typeLabel session."
            }
        }
    }

    fun applyUpdates(
        updates: ActivityUpdates?,
        session: com.pulseloop.data.entity.ActivitySessionEntity,
    ): com.pulseloop.data.entity.ActivitySessionEntity {
        val u = updates ?: return session
        return session.copy(
            type = u.type ?: session.type,
            notes = u.notes ?: session.notes,
            distanceMeters = u.distanceKm?.times(1000) ?: session.distanceMeters,
            perceivedEffort = u.perceivedEffort ?: session.perceivedEffort,
            startedAt = u.startTime?.let { CoachDataAccess.parseLocalDate(it) } ?: session.startedAt,
            endedAt = if (u.durationMin != null) {
                session.startedAt + ((u.durationMin * 60 + session.totalPauseSeconds) * 1000).toLong()
            } else session.endedAt,
            updatedAt = System.currentTimeMillis(),
        )
    }
}
