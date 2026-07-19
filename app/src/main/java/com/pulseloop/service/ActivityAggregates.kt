package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.ring.MeasurementKind

/**
 * Ported from ActivityService.recomputeSummary/applyEdit in PulseServices.swift (iOS #57a/#57c).
 * [recompute] re-derives calories/HR/SpO2/distance from a session's current
 * [startedAt, endedAt] window — shared by `LiveWorkoutManager.finish()` (window is the full
 * recording span), [applyEdit] (window may have shrunk or moved, so measurements and GPS
 * points outside it must drop out), and `EventPersistenceSubscriber`'s post-sync reconcile (iOS
 * #57e — late ring-log HR/SpO2 history landing inside an already-finished session's window).
 * Never touches [ActivityRollup] (minutes/distance, credited once at finish), so re-running it
 * for the same session is always safe.
 */
object ActivityAggregates {
    suspend fun recompute(db: PulseLoopDatabase, session: ActivitySessionEntity): ActivitySessionEntity {
        val end = session.endedAt ?: System.currentTimeMillis()
        val hrRows = db.measurementDao().range(MeasurementKind.HEART_RATE.name, session.startedAt, end)
            .filter { it.value > 0 }
        val spo2Rows = db.measurementDao().range(MeasurementKind.SPO2.name, session.startedAt, end)
            .filter { it.value > 0 }
            .sortedBy { it.timestamp }
        val duration = maxOf(0, (((end - session.startedAt) / 1000.0) - session.totalPauseSeconds).toInt())
        val profileEntity = db.userProfileDao().get()

        val gpsPoints = if (session.useGps) {
            db.activityGpsPointDao().forSession(session.id).filter { it.timestamp in session.startedAt..end }
        } else emptyList()
        val acceptedCount = gpsPoints.count { it.accepted }
        val distanceMeters = if (session.useGps) {
            RouteDistanceEngine.distanceMeters(gpsPoints, ActivityTrackingProfile.profile(session.type))
        } else null

        val calories = WorkoutMetricsEngine.calories(
            type = session.type,
            durationSeconds = duration,
            distanceMeters = distanceMeters,
            hrSamples = hrRows.map { it.timestamp to it.value },
            profile = MetricsProfileValues(sex = profileEntity?.sex, age = profileEntity?.age, weightKg = profileEntity?.weightKg),
        )
        return session.copy(
            calories = calories,
            distanceMeters = distanceMeters,
            avgHeartRate = hrRows.map { it.value }.average0(),
            maxHeartRate = hrRows.maxOfOrNull { it.value },
            minHeartRate = hrRows.minOfOrNull { it.value },
            avgSpO2 = spo2Rows.map { it.value }.average0(),
            latestSpO2 = spo2Rows.lastOrNull()?.value,
            gpsPointCount = acceptedCount,
            rejectedGpsPointCount = gpsPoints.size - acceptedCount,
            lastGpsPointAt = gpsPoints.maxOfOrNull { it.timestamp },
        )
    }

    /**
     * Post-finish edit, deliberately limited to type + time window (iOS #57c). Reverses the old
     * day's rollup credit, updates type/start/end, recomputes every derived aggregate for the new
     * window via [recompute], and credits the (possibly different) new day. Returns false — no
     * mutation — for an invalid edit (future end time, or a window not longer than the session's
     * recorded pauses).
     */
    suspend fun applyEdit(
        db: PulseLoopDatabase,
        session: ActivitySessionEntity,
        newType: String,
        newStartedAt: Long,
        newEndedAt: Long,
    ): Boolean {
        if (!isValidEdit(session, newStartedAt, newEndedAt)) return false

        ActivityRollup.reverse(db, session)
        val edited = session.copy(
            type = newType,
            startedAt = newStartedAt,
            endedAt = newEndedAt,
            updatedAt = System.currentTimeMillis(),
        )
        val summarized = recompute(db, edited)
        db.activitySessionDao().upsert(summarized)
        ActivityRollup.credit(db, summarized)
        return true
    }

    /** Pulled out of [applyEdit] to be independently testable: only a finished session, an end
     *  time not in the future, and a window longer than the recorded pauses may be edited (iOS
     *  #57c `testInvalidEditsAreRejected`). */
    internal fun isValidEdit(
        session: ActivitySessionEntity,
        newStartedAt: Long,
        newEndedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (session.statusRaw != "finished") return false
        if (newEndedAt > now) return false
        if (newEndedAt - newStartedAt <= (session.totalPauseSeconds * 1000).toLong()) return false
        return true
    }

    /** How long after finish ring-log history may still update a session's aggregates (iOS
     *  `ActivityService.backfillLinkWindowSeconds`, #57e) — covers the post-workout backfill
     *  sync and a reconnect shortly after finishing. */
    const val backfillWindowMillis: Long = 15 * 60_000L

    /** Pulled out of the sync-driven reconcile for independent testability (iOS
     *  `testStaleFinishedSessionDoesNotAttract` / `testHistorySampleLinksToRecentlyFinishedSession`
     *  in WorkoutReconcileTests.swift). */
    internal fun isWithinBackfillWindow(
        session: ActivitySessionEntity,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (session.statusRaw != "finished") return false
        val ended = session.endedAt ?: return false
        return now - ended < backfillWindowMillis
    }

    private fun List<Double>.average0(): Double? = if (isEmpty()) null else average()
}
