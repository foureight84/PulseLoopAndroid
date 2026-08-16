package com.pulseloop.health.exporters

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.health.HealthConnectTypeMappings.WK_DIST
import com.pulseloop.health.HealthConnectTypeMappings.WK_ENERGY
import com.pulseloop.health.HealthConnectTypeMappings.WorkoutSelection
import com.pulseloop.ui.components.ActivityMeta
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the Phase 4 workout records (docs/health-connect-integration.md Phase 4): one
 * `ExerciseSessionRecord` per finished [ActivitySessionEntity] — the exercise-type map,
 * [ActivityMeta.label] as the title, the session's notes, and an embedded [ExerciseRoute] from
 * the session's accepted GPS fixes — plus sibling `ActiveCaloriesBurnedRecord` /
 * `DistanceRecord` over the session window.
 *
 * Identity (plan §3): `clientRecordId = pl-wk-<sessionId>` (and `-energy` / `-dist` on the
 * siblings), `clientRecordVersion = session.updatedAt` — a post-finish edit or vitals backfill
 * re-upserts the SAME records in place. The session id is the stable Room primary key: unlike
 * sleep blocks, workout rows are never replaced wholesale, so it is a safe upsert key with no
 * suffix scheme.
 *
 * **Siblings == netting set** (Phase 3 amendment): energy is written for every finished session
 * with plausible `calories > 0` — exactly what `workoutNetting` subtracts from the daily total;
 * distance is written **only** for `useGps` sessions — the set `ActivityRollup.credit` folds
 * into the daily row. That equality is what makes a consumer summing the daily aggregate plus
 * the workout siblings land on the app's stored day total; an un-netted sibling would break it,
 * and a netted-but-unwritten one would under-report.
 *
 * Route rules (plan Phase 4): `accepted` fixes only; sanitised by
 * [HealthConnectTypeMappings.sanitizeRoutePoints] (session window, finite/in-range coordinates,
 * duplicate timestamps); ≥ 2 clean points, else no route — the session still writes. [withRoute]
 * is false when `WRITE_EXERCISE_ROUTE` was not granted: the session then writes without a route
 * (partial grants are first-class). Platform update semantics (exercise-routes guide): re-upserting
 * a session record while the route permission is granted but the new build carries no route
 * DELETES the previously exported route — acceptable here for both no-route cases: the session was
 * just edited such that its fixes no longer support a route (removing the stale route is correct),
 * or the route permission was revoked (a revocation implies the user no longer wants routes
 * exported). The 1 MB per-record limit cannot be known up front —
 * [com.pulseloop.health.HealthConnectExporter] wraps the insert with the shrink-retry fallback
 * ([HealthConnectTypeMappings.parseRecordSizeLimit] + [HealthConnectTypeMappings.decimateToSize]).
 *
 * Selection is watermark-driven on `updatedAt`, `ORDER BY updatedAt ASC` for the
 * chunked-watermark invariant (Phase 2/3 fix). Two guard classes, mirroring iOS
 * `exportWorkouts` and [HealthConnectTypeMappings.selectWorkoutSession]:
 *  - zero/negative duration can never become exportable → the exporter may advance the
 *    [PendingWorkouts.invalidHighWater] watermark past it (iOS advances its workout watermark the
 *    same way);
 *  - a future-dated `endedAt` (clock skew) **stops the pass** at that session — it is retried on
 *    the next run once its end passes, never leapfrogged (iOS `guard end <= now else break`).
 *
 * No demo/mock filter exists for this table: `ActivitySessionEntity` has no source column and
 * the demo seeder never creates sessions (it seeds `activity_daily`, measurements and sleep
 * only) — rows here are all real data: live-recorded, manually logged (Log Past Activity),
 * coach-created, or archive-restored workouts.
 *
 * Pure DB → records: no client, no inserts — [com.pulseloop.health.HealthConnectExporter] owns
 * the write path and decides which record types are both toggled on and permission-granted.
 */
class WorkoutExporter(private val db: PulseLoopDatabase) {

    /**
     * Pending workout records with the parallel [highWaters] list: entry i is the source
     * session's `updatedAt` that record i represents, so the exporter can advance the workouts
     * watermark only to a value whose sessions all reached Health Connect.
     * [invalidHighWater] is the max `updatedAt` of zero/negative-duration sessions (never
     * exportable — safe to advance past); [blockedFuture] is set when the pass stopped at a
     * future-dated session (the watermark must NOT be stamped to "now" in that case); and
     * [skippedSessions] counts the invalid sessions dropped along the way.
     */
    data class PendingWorkouts(
        val records: List<Record>,
        val highWaters: List<Long>,
        val invalidHighWater: Long? = null,
        val blockedFuture: Boolean = false,
        val skippedSessions: Int = 0,
    )

    /**
     * Builds the pending records. Sessions with `updatedAt <= [watermark]` were already
     * exported; `null` means export everything (first-enable backfill). [withRoute] /
     * [withEnergy] / [withDistance] mirror the live `WRITE_EXERCISE_ROUTE` /
     * `WRITE_ACTIVE_CALORIES_BURNED` / `WRITE_DISTANCE` grants — the route is embedded, but the
     * siblings are standalone records, so each is gated on its OWN permission: a chunk that
     * carries a sibling its permission was never granted for would fail the whole insert
     * (partial grants are first-class). This keeps the siblings exactly aligned with what the
     * activity group actually writes — [com.pulseloop.health.exporters.ActivityExporter] nets a
     * metric only when that same permission lets it write the day's record for it.
     * [nowMs] is the pass time for the future-dated guard.
     */
    suspend fun build(
        watermark: Long?,
        device: Device,
        withRoute: Boolean,
        withEnergy: Boolean,
        withDistance: Boolean,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PendingWorkouts {
        val wm = watermark ?: 0L
        val sessions = db.activitySessionDao().finishedUpdatedSince(wm)
        if (sessions.isEmpty()) return PendingWorkouts(emptyList(), emptyList())

        // All GPS fixes for the pending sessions in one query — a first-enable backfill can
        // select years of rows.
        val pointsBySession = db.activityGpsPointDao()
            .forSessions(sessions.map { it.id })
            .groupBy { it.sessionId }

        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()
        var invalidHigh: Long? = null
        var skipped = 0
        var blockedFuture = false

        for (session in sessions) { // updatedAt ASC, per the DAO
            val start = session.startedAt
            // The query filters to endedAt IS NOT NULL and the EXPORT selection below requires
            // it non-null; the elvis is the type-system formality, not a live branch.
            val end = session.endedAt ?: continue
            when (HealthConnectTypeMappings.selectWorkoutSession(start, end, nowMs)) {
                WorkoutSelection.INVALID -> {
                    skipped++
                    // Can never become exportable: let the watermark move past it.
                    invalidHigh = maxOf(invalidHigh ?: 0L, session.updatedAt)
                }
                WorkoutSelection.FUTURE -> {
                    // Clock skew: stop the pass — retry on the next run once its end passes.
                    blockedFuture = true
                    break
                }
                WorkoutSelection.EXPORT -> Unit
            }

            val version = session.updatedAt
            val startInstant = Instant.ofEpochMilli(start)
            val endInstant = Instant.ofEpochMilli(end)
            val startOffset = HealthConnectTypeMappings.zoneOffsetAt(startInstant, zone)
            val endOffset = HealthConnectTypeMappings.zoneOffsetAt(endInstant, zone)

            val route = if (withRoute) buildRoute(session, pointsBySession[session.id].orEmpty(), start, end) else null

            records += ExerciseSessionRecord(
                startTime = startInstant,
                startZoneOffset = startOffset,
                endTime = endInstant,
                endZoneOffset = endOffset,
                // ACTIVELY recorded: a PulseLoop workout is user-initiated (the user pressed
                // start) — Gadgetbridge marks its ACTIVITY-type records activelyRecorded for the
                // same reason; the Phase 1-3 groups stay autoRecorded because ring data is
                // collected automatically.
                metadata = Metadata.activelyRecorded(
                    device, HealthConnectTypeMappings.workoutRecordId(session.id), version,
                ),
                exerciseType = HealthConnectTypeMappings.exerciseType(session.type),
                title = ActivityMeta.label(session.type),
                notes = session.notes,
                exerciseRoute = route,
            )
            highWaters += version

            // Sibling energy: netted for every finished session (workoutNetting's kcal rule),
            // so the guard is the same plausibility test the daily record applies.
            val kcal = session.calories
            if (withEnergy && kcal != null && HealthConnectTypeMappings.isPlausibleActiveCalories(kcal)) {
                records += ActiveCaloriesBurnedRecord(
                    startInstant,
                    startOffset,
                    endInstant,
                    endOffset,
                    Energy.kilocalories(kcal),
                    Metadata.activelyRecorded(
                        device, HealthConnectTypeMappings.workoutChildRecordId(session.id, WK_ENERGY), version,
                    ),
                )
                highWaters += version
            }

            // Sibling distance: netted for useGps sessions ONLY (Phase 3 amendment) — the same
            // set ActivityRollup.credit folds into the daily row.
            val meters = session.distanceMeters
            if (withDistance && session.useGps && meters != null && HealthConnectTypeMappings.isPlausibleDistanceMeters(meters)) {
                records += DistanceRecord(
                    startInstant,
                    startOffset,
                    endInstant,
                    endOffset,
                    Length.meters(meters),
                    Metadata.activelyRecorded(
                        device, HealthConnectTypeMappings.workoutChildRecordId(session.id, WK_DIST), version,
                    ),
                )
                highWaters += version
            }
        }
        return PendingWorkouts(records, highWaters, invalidHigh, blockedFuture, skipped)
    }

    /**
     * The session's route from its accepted fixes, or null when sanitisation leaves fewer than
     * two points (the session then writes without a route — plan Phase 4).
     */
    private fun buildRoute(
        session: ActivitySessionEntity,
        rawPoints: List<com.pulseloop.data.entity.ActivityGpsPointEntity>,
        startMs: Long,
        endMs: Long,
    ): ExerciseRoute? {
        val points = rawPoints
            .filter { it.accepted }
            .map {
                HealthConnectTypeMappings.GpsRoutePoint(
                    timeMs = it.timestamp,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    horizontalAccuracyMeters =
                        it.horizontalAccuracy?.takeIf { a -> a.isFinite() && a >= 0.0 },
                    altitudeMeters = it.altitude?.takeIf { a -> a.isFinite() },
                )
            }
        val clean = HealthConnectTypeMappings.sanitizeRoutePoints(startMs, endMs, points)
        if (clean.size < 2) return null
        return ExerciseRoute(
            clean.map {
                ExerciseRoute.Location(
                    time = Instant.ofEpochMilli(it.timeMs),
                    latitude = it.latitude,
                    longitude = it.longitude,
                    horizontalAccuracy = it.horizontalAccuracyMeters?.let { m -> Length.meters(m) },
                    altitude = it.altitudeMeters?.let { m -> Length.meters(m) },
                )
            },
        )
    }
}
