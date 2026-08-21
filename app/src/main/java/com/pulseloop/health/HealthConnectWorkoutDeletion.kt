package com.pulseloop.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.pulseloop.health.HealthConnectTypeMappings.WK_DIST
import com.pulseloop.health.HealthConnectTypeMappings.WK_ENERGY

/**
 * Removes one session's exported records from Health Connect when the local row is deleted
 * (plan Phase 4 "Deletion hooks"; iOS parity: `HealthSyncService.deleteExportedWorkout`).
 * Fired from the workout UI delete (WorkoutSummaryScreen). The coach's `delete_activity_session`
 * is a pending-action flow with no confirm handler wired yet, so it does NOT call this today —
 * when it is, it should call this the same way.
 *
 * The session's route travels INSIDE its [ExerciseSessionRecord] (an embedded field, not a
 * standalone provider record), so deleting the session record removes the route with it; the
 * energy/distance SIBLINGS are standalone records and are deleted by their own clientRecordIds.
 *
 * Best-effort on purpose: a local delete must never fail because of Health Connect. Every gate
 * (provider available, permission granted) and every error degrades to a log line —
 * [HealthConnectClient.deleteRecords] has no client-side permission check of its own, so the
 * granted-set diff here is the only guard against a denied-permission call. The three record
 * classes are each deleted in their own runCatching so one unknown id (a conditional sibling
 * that was never written) cannot abort the rest and orphan the survivors.
 *
 * Deliberately NOT gated on the master export toggle (observer review, Phase 4 stage B): a user
 * who exported workouts and later switched the export off still owns those Health Connect
 * records — deleting the local session must remove them, or they become unmanageable ghosts
 * (iOS guards on availability only, not on its export preference).
 */
object HealthConnectWorkoutDeletion {
    private const val TAG = "HealthConnectExport"

    /**
     * Deletes [sessionId]'s `pl-wk-<id>` session record plus its `-energy` / `-dist` siblings
     * (each only when its write permission is granted — a record we never had permission to
     * write is a no-op: its clientRecordId simply isn't in the store). Returns the number of
     * record classes actually deleted; 0 when the provider is unavailable, the client cannot be
     * created, or every permission is denied.
     */
    suspend fun removeSessionRecords(context: Context, sessionId: String): Int {
        if (HealthConnectSdk.availability(context.applicationContext) != HealthConnectAvailability.AVAILABLE) {
            return 0
        }
        val client = try {
            HealthConnectClient.getOrCreate(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "workout delete: no Health Connect client", e)
            return 0
        }
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            var deleted = 0
            // Only our own clientRecordIds scope the delete — recordIdsList (provider-side
            // record ids) stays empty.
            //
            // Each class is isolated in its own runCatching (review, Phase 4): the
            // clientRecordIds overload aborts the whole transaction on any unknown id, and the
            // siblings are conditional — the energy record only when calories are plausible, the
            // distance record only for useGps sessions. Without isolation a GPS run with no
            // calorie figure throws on the (never-written) energy id, a shared catch swallows
            // it, and the distance delete never runs — orphaning a pl-wk-<id>-dist record the
            // app can no longer manage (write-only, local row gone).
            if (HealthConnectPermissions.exercise.first() in granted) {
                runCatching {
                    client.deleteRecords(
                        ExerciseSessionRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = listOf(HealthConnectTypeMappings.workoutRecordId(sessionId)),
                    )
                }
                    .onSuccess { deleted++ }
                    .onFailure { Log.w(TAG, "workout delete: session record for $sessionId", it) }
            }
            if (HealthConnectPermissions.activeCalories.first() in granted) {
                runCatching {
                    client.deleteRecords(
                        ActiveCaloriesBurnedRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = listOf(HealthConnectTypeMappings.workoutChildRecordId(sessionId, WK_ENERGY)),
                    )
                }
                    .onSuccess { deleted++ }
                    .onFailure { Log.w(TAG, "workout delete: energy record for $sessionId", it) }
            }
            if (HealthConnectPermissions.distance.first() in granted) {
                runCatching {
                    client.deleteRecords(
                        DistanceRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = listOf(HealthConnectTypeMappings.workoutChildRecordId(sessionId, WK_DIST)),
                    )
                }
                    .onSuccess { deleted++ }
                    .onFailure { Log.w(TAG, "workout delete: distance record for $sessionId", it) }
            }
            if (deleted > 0) Log.i(TAG, "workout delete: removed $deleted record class(es) for session $sessionId")
            deleted
        } catch (e: Exception) {
            Log.w(TAG, "workout delete: Health Connect error (local delete unaffected)", e)
            0
        }
    }
}
