package com.pulseloop.coach.orchestration

import com.pulseloop.coach.tools.CoachDataAccess
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity

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
                applyUpdates(db, action.updates, session)
                "Updated the $typeLabel session."
            }
        }
    }

    /**
     * What an [ActivityUpdates] resolves to against a specific session: the target type/start/end
     * plus whether that window actually differs from the session's current one. Pure — no DB —
     * so the interesting branching (duration-derives-end, a start-only shift preserves the span)
     * is independently testable.
     */
    internal data class ResolvedUpdate(
        val type: String,
        val startedAt: Long,
        val endedAt: Long,
        val notes: String?,
        val perceivedEffort: String?,
        val windowChanged: Boolean,
    )

    internal fun resolveUpdates(updates: ActivityUpdates, session: ActivitySessionEntity): ResolvedUpdate {
        val newType = updates.type ?: session.type
        val newStart = updates.startTime?.let { CoachDataAccess.parseLocalDate(it) } ?: session.startedAt
        val currentEnd = session.endedAt ?: System.currentTimeMillis()
        val newEnd = when {
            updates.durationMin != null -> newStart + ((updates.durationMin * 60 + session.totalPauseSeconds) * 1000).toLong()
            // Start moved without a new duration: shift the whole window, keeping its span.
            newStart != session.startedAt -> newStart + (currentEnd - session.startedAt)
            else -> currentEnd
        }
        return ResolvedUpdate(
            type = newType, startedAt = newStart, endedAt = newEnd,
            notes = updates.notes ?: session.notes,
            perceivedEffort = updates.perceivedEffort ?: session.perceivedEffort,
            windowChanged = newType != session.type || newStart != session.startedAt || newEnd != session.endedAt,
        )
    }

    /**
     * Ported from PendingActionExecutor.apply in PendingActionExecutor.swift (iOS #57c). Type/time
     * changes route through [ActivityAggregates.applyEdit] so aggregates, GPS distance, and the
     * daily rollup stay consistent — setting the fields directly left them all stale. An invalid
     * edit (e.g. a future end time) falls back to persisting the other field changes untouched. A
     * user-stated distance overrides the GPS recompute, applied after the edit.
     */
    suspend fun applyUpdates(
        db: PulseLoopDatabase,
        updates: ActivityUpdates?,
        session: ActivitySessionEntity,
    ): ActivitySessionEntity {
        val u = updates ?: return session
        val resolved = resolveUpdates(u, session)
        val withFields = session.copy(notes = resolved.notes, perceivedEffort = resolved.perceivedEffort)

        val edited = resolved.windowChanged &&
            com.pulseloop.service.ActivityAggregates.applyEdit(db, withFields, resolved.type, resolved.startedAt, resolved.endedAt)
        var result = if (edited) {
            db.activitySessionDao().byId(withFields.id) ?: withFields
        } else {
            withFields.copy(updatedAt = System.currentTimeMillis()).also { db.activitySessionDao().upsert(it) }
        }

        if (u.distanceKm != null) {
            result = result.copy(distanceMeters = u.distanceKm * 1000, updatedAt = System.currentTimeMillis())
            db.activitySessionDao().upsert(result)
        }
        return result
    }
}
