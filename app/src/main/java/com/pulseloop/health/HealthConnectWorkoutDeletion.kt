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
 * (plan Phase 4 "Deletion hooks"; iOS parity: `HealthSyncService.deleteExportedWorkout`, fired
 * from both the UI delete and the coach's `delete_activity_session`).
 *
 * The session's route travels INSIDE its [ExerciseSessionRecord] (an embedded field, not a
 * standalone provider record), so deleting the session record removes the route with it; the
 * energy/distance SIBLINGS are standalone records and are deleted by their own clientRecordIds.
 *
 * Best-effort on purpose: a local delete must never fail because of Health Connect. Every gate
 * (export enabled, provider available, permission granted) and every error degrades to a log
 * line — [HealthConnectClient.deleteRecords] has no client-side permission check of its own, so
 * the granted-set diff here is the only guard against a denied-permission call.
 */
object HealthConnectWorkoutDeletion {
    private const val TAG = "HealthConnectExport"

    /**
     * Deletes [sessionId]'s `pl-wk-<id>` session record plus its `-energy` / `-dist` siblings
     * (each only when its write permission is granted). Returns the number of record classes
     * actually deleted; 0 when the export is off, the provider is unavailable, the client cannot
     * be created, or every permission is denied.
     */
    suspend fun removeSessionRecords(context: Context, sessionId: String): Int {
        val store = HealthConnectPrefsStore.get(context.applicationContext)
        if (!store.current.enabled) return 0
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
            if (HealthConnectPermissions.exercise.first() in granted) {
                client.deleteRecords(
                    ExerciseSessionRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(HealthConnectTypeMappings.workoutRecordId(sessionId)),
                )
                deleted++
            }
            if (HealthConnectPermissions.activeCalories.first() in granted) {
                client.deleteRecords(
                    ActiveCaloriesBurnedRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(HealthConnectTypeMappings.workoutChildRecordId(sessionId, WK_ENERGY)),
                )
                deleted++
            }
            if (HealthConnectPermissions.distance.first() in granted) {
                client.deleteRecords(
                    DistanceRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(HealthConnectTypeMappings.workoutChildRecordId(sessionId, WK_DIST)),
                )
                deleted++
            }
            if (deleted > 0) Log.i(TAG, "workout delete: removed $deleted record class(es) for session $sessionId")
            deleted
        } catch (e: Exception) {
            Log.w(TAG, "workout delete: Health Connect error (local delete unaffected)", e)
            0
        }
    }
}
