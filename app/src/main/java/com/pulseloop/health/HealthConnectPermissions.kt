package com.pulseloop.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * Health Connect write permissions grouped by logical data type, derived from the record
 * classes via `HealthPermission.getWritePermission` — never hardcoded strings
 * (docs/health-connect-integration.md §3).
 *
 * Only the Phases 1–4 data types live here (and in the manifest). Phase 5 types — blood
 * pressure, glucose, respiratory rate, VO2max, resting heart rate, nutrition — join this
 * object, the manifest, and the permission sheet only when Phase 5 lands, on purpose: a
 * narrower first permission sheet is better UX. No READ_* permissions anywhere — the export
 * is write-only.
 *
 * The exercise route is an embedded field of [ExerciseSessionRecord], not a standalone
 * record: the library exposes its permission as the `PERMISSION_WRITE_EXERCISE_ROUTE`
 * constant. Singular on purpose — the official docs note `READ_EXERCISE_ROUTES` is the
 * plural one.
 */
object HealthConnectPermissions {

    /** Logical data-type rows on the settings screen, in display order. */
    enum class DataTypeRow {
        HEART_RATE,
        OXYGEN_SATURATION,
        HEART_RATE_VARIABILITY,
        BODY_TEMPERATURE,
        SLEEP,
        STEPS_AND_ACTIVITY,
        WORKOUTS,
        NUTRITION,
    }

    val heartRate: Set<String> = setOf(HealthPermission.getWritePermission(HeartRateRecord::class))
    val oxygenSaturation: Set<String> = setOf(HealthPermission.getWritePermission(OxygenSaturationRecord::class))
    val heartRateVariability: Set<String> = setOf(HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class))
    val bodyTemperature: Set<String> = setOf(HealthPermission.getWritePermission(BodyTemperatureRecord::class))
    val sleep: Set<String> = setOf(HealthPermission.getWritePermission(SleepSessionRecord::class))
    val steps: Set<String> = setOf(HealthPermission.getWritePermission(StepsRecord::class))
    val activeCalories: Set<String> = setOf(HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class))
    val distance: Set<String> = setOf(HealthPermission.getWritePermission(DistanceRecord::class))
    val exercise: Set<String> = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
    val exerciseRoute: Set<String> = setOf(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE)

    /** Every write permission the app requests via the master toggle. */
    val all: Set<String> = buildSet {
        addAll(heartRate)
        addAll(oxygenSaturation)
        addAll(heartRateVariability)
        addAll(bodyTemperature)
        addAll(sleep)
        addAll(steps)
        addAll(activeCalories)
        addAll(distance)
        addAll(exercise)
        addAll(exerciseRoute)
    }

    /**
     * The permissions backing one settings-screen row. NUTRITION is empty in Phase 0: its
     * permission is not declared until Phase 5, and the toggle is stored now, enforced then.
     */
    fun permissionsForRow(row: DataTypeRow): Set<String> = when (row) {
        DataTypeRow.HEART_RATE -> heartRate
        DataTypeRow.OXYGEN_SATURATION -> oxygenSaturation
        DataTypeRow.HEART_RATE_VARIABILITY -> heartRateVariability
        DataTypeRow.BODY_TEMPERATURE -> bodyTemperature
        DataTypeRow.SLEEP -> sleep
        DataTypeRow.STEPS_AND_ACTIVITY -> steps + activeCalories + distance
        DataTypeRow.WORKOUTS -> exercise + exerciseRoute
        DataTypeRow.NUTRITION -> emptySet()
    }
}
