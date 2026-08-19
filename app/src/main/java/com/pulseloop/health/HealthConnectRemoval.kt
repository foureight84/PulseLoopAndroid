package com.pulseloop.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

/**
 * "Remove PulseLoop data from Health Connect" (iOS `removeAllExportedData` parity;
 * docs/health-connect-integration.md §4 Phase 6).
 *
 * Deletes every record PulseLoop wrote — [HealthConnectClient.deleteRecords] by record type over
 * the full time range — then clears all watermarks + the last-sync stamp so the export state
 * matches an empty Health store (a later re-grant / re-enable starts a fresh backfill).
 *
 * Scoping: Health Connect attributes each record to the app that wrote it (the data source) and
 * the platform filters `deleteRecords(type, range)` to records "belonging to the calling
 * application" (1.1.0 client KDoc), so this removes only the records PulseLoop wrote — never
 * another app's records of the same type. Only the WRITE permission for a type is needed;
 * READ is not (verified against the 1.1.0 enforcement path). Each type is deleted only if its
 * WRITE permission is still granted — both to avoid a SecurityException mid-removal and because
 * we could only ever have written types we were granted.
 *
 * Concurrency: a pending/in-flight export pass is cancelled first (iOS guards the same operation
 * with an isSyncing latch; Android has no such latch, so we cancel the unique work instead).
 * Per-type failures are logged and skipped so one bad type cannot sink the rest (the same
 * "one bad record must not sink the chunk" rule the exporters apply).
 */
object HealthConnectRemoval {

    private const val TAG = "HealthConnectRemoval"

    /**
     * The 15 record types PulseLoop writes (the exercise route is an embedded field of
     * [ExerciseSessionRecord], not a standalone record), each paired with the single WRITE
     * permission that guards it. Deletion is attempted only for granted types.
     */
    private val RECORD_TYPES: List<Pair<KClass<out Record>, String>> = listOf(
        HeartRateRecord::class to HealthConnectPermissions.heartRate.first(),
        OxygenSaturationRecord::class to HealthConnectPermissions.oxygenSaturation.first(),
        HeartRateVariabilityRmssdRecord::class to HealthConnectPermissions.heartRateVariability.first(),
        BodyTemperatureRecord::class to HealthConnectPermissions.bodyTemperature.first(),
        SleepSessionRecord::class to HealthConnectPermissions.sleep.first(),
        StepsRecord::class to HealthConnectPermissions.steps.first(),
        ActiveCaloriesBurnedRecord::class to HealthConnectPermissions.activeCalories.first(),
        DistanceRecord::class to HealthConnectPermissions.distance.first(),
        ExerciseSessionRecord::class to HealthConnectPermissions.exercise.first(),
        NutritionRecord::class to HealthConnectPermissions.nutrition.first(),
        BloodPressureRecord::class to HealthConnectPermissions.bloodPressure.first(),
        BloodGlucoseRecord::class to HealthConnectPermissions.bloodGlucose.first(),
        RespiratoryRateRecord::class to HealthConnectPermissions.respiratoryRate.first(),
        Vo2MaxRecord::class to HealthConnectPermissions.vo2Max.first(),
        RestingHeartRateRecord::class to HealthConnectPermissions.restingHeartRate.first(),
    )

    /** Outcome of a removal, for the settings screen to surface. */
    data class RemovalResult(val deletedTypes: Int, val skippedUngranted: Int, val failedTypes: Int)

    suspend fun removeAll(context: Context, client: HealthConnectClient, store: HealthConnectPrefsStore): RemovalResult {
        // Never race a running export pass (iOS isSyncing-latch analogue).
        HealthConnectExportWorker.cancel(context)
        val granted = client.permissionController.getGrantedPermissions()
        // Everything we ever wrote: from the epoch to now (Health Connect requires a range).
        val range = TimeRangeFilter.after(Instant.EPOCH)
        var deleted = 0
        var skipped = 0
        var failed = 0
        for ((type, permission) in RECORD_TYPES) {
            if (permission !in granted) {
                skipped++
                continue // never wrote it (no grant) -> nothing to delete; avoid a SecurityException
            }
            try {
                client.deleteRecords(type, range)
                deleted++
            } catch (e: Exception) {
                // One failing type must not sink the rest: log and continue (the watermarks are
                // still cleared below, so the next pass re-attempts anything that survived).
                failed++
                Log.w(TAG, "deleteRecords(${type.simpleName}) failed", e)
            }
        }
        // The export state must match the now-empty store: no watermarks, no last-sync stamp. The
        // export is also turned OFF and the backfill choice + first-enable stamp marker reset
        // ("reset the export to start fresh"): leaving it enabled with backfillChoice=EXPORT_ALL
        // would let the very next background trigger (ring sync, app-start grow, settings open)
        // re-export the whole history within ~15 s, silently undoing the destructive removal.
        // Re-enabling re-offers the backfill dialog (the choice is back to NOT_ASKED) so the user
        // picks fresh.
        store.clearWatermarks()
        store.update {
            it.copy(
                enabled = false,
                backfillChoice = HealthConnectPrefs.BackfillChoice.NOT_ASKED,
                newOnlyStamped = false,
                lastSyncAt = null,
                lastSyncSummary = null,
            )
        }
        return RemovalResult(deleted, skipped, failed)
    }
}