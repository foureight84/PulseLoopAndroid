package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.ui.components.ActivityMeta

/** Ported from `ManualActivityCreationError` in ManualActivityService.swift (iOS #57d). */
sealed class ManualActivityError(message: String) : Exception(message) {
    object InvalidActivityType : ManualActivityError("Choose a valid activity type.")
    object InvalidDuration : ManualActivityError("Duration must be greater than zero.")
    object EndsInFuture : ManualActivityError("The activity must end in the past.")
}

/**
 * Ported from `ManualActivityService` in ManualActivityService.swift (iOS #57d). Single creation
 * path for completed workouts supplied manually by either the coach tool
 * (`create_activity_session_from_description`) or the "Log Past Activity" screen — mirrors
 * `LiveWorkoutManager.finish()`'s recompute-then-credit sequence so a manually logged session
 * counts toward the daily rollup exactly like a live-recorded one.
 */
object ManualActivityService {
    /** Pulled out of [create] to be independently testable without a database. */
    internal fun validate(type: String, durationMinutes: Double, startedAt: Long, now: Long): ManualActivityError? {
        if (type !in ActivityMeta.ORDER) return ManualActivityError.InvalidActivityType
        if (durationMinutes <= 0 || !durationMinutes.isFinite()) return ManualActivityError.InvalidDuration
        val endedAt = startedAt + (durationMinutes * 60_000).toLong()
        if (endedAt > now) return ManualActivityError.EndsInFuture
        return null
    }

    suspend fun create(
        db: PulseLoopDatabase,
        type: String,
        startedAt: Long,
        durationMinutes: Double,
        distanceMeters: Double? = null,
        notes: String? = null,
        now: Long = System.currentTimeMillis(),
    ): ActivitySessionEntity {
        validate(type, durationMinutes, startedAt, now)?.let { throw it }
        val endedAt = startedAt + (durationMinutes * 60_000).toLong()

        val session = ActivitySessionEntity(
            type = type,
            statusRaw = "finished",
            startedAt = startedAt,
            endedAt = endedAt,
            distanceMeters = distanceMeters,
            notes = notes?.ifEmpty { null },
            useGps = false,
        )
        val summarized = ActivityAggregates.recompute(db, session)
        db.activitySessionDao().upsert(summarized)
        ActivityRollup.credit(db, summarized)
        return summarized
    }
}
