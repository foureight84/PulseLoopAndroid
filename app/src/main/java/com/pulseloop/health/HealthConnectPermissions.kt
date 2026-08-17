package com.pulseloop.health

import androidx.health.connect.client.permission.HealthPermission
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
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord

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
        BLOOD_PRESSURE,
        BLOOD_GLUCOSE,
        RESPIRATORY_RATE,
        VO2_MAX,
        RESTING_HEART_RATE,
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

    // Phase 5 (beyond iOS) — declared and requested from this phase on.
    val nutrition: Set<String> = setOf(HealthPermission.getWritePermission(NutritionRecord::class))
    val bloodPressure: Set<String> = setOf(HealthPermission.getWritePermission(BloodPressureRecord::class))
    val bloodGlucose: Set<String> = setOf(HealthPermission.getWritePermission(BloodGlucoseRecord::class))
    val respiratoryRate: Set<String> = setOf(HealthPermission.getWritePermission(RespiratoryRateRecord::class))
    val vo2Max: Set<String> = setOf(HealthPermission.getWritePermission(Vo2MaxRecord::class))
    val restingHeartRate: Set<String> = setOf(HealthPermission.getWritePermission(RestingHeartRateRecord::class))

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
        addAll(nutrition)
        addAll(bloodPressure)
        addAll(bloodGlucose)
        addAll(respiratoryRate)
        addAll(vo2Max)
        addAll(restingHeartRate)
    }

    /** The permissions backing one settings-screen row. */
    fun permissionsForRow(row: DataTypeRow): Set<String> = when (row) {
        DataTypeRow.HEART_RATE -> heartRate
        DataTypeRow.OXYGEN_SATURATION -> oxygenSaturation
        DataTypeRow.HEART_RATE_VARIABILITY -> heartRateVariability
        DataTypeRow.BODY_TEMPERATURE -> bodyTemperature
        DataTypeRow.SLEEP -> sleep
        DataTypeRow.STEPS_AND_ACTIVITY -> steps + activeCalories + distance
        DataTypeRow.WORKOUTS -> exercise + exerciseRoute
        DataTypeRow.NUTRITION -> nutrition
        DataTypeRow.BLOOD_PRESSURE -> bloodPressure
        DataTypeRow.BLOOD_GLUCOSE -> bloodGlucose
        DataTypeRow.RESPIRATORY_RATE -> respiratoryRate
        DataTypeRow.VO2_MAX -> vo2Max
        DataTypeRow.RESTING_HEART_RATE -> restingHeartRate
    }

    /**
     * The single write permission per measurement kind, for the exporter's per-kind check
     * (plan: "each pass re-checks its own record class against the granted set"). Keys are the
     * exporter's `kindKey` tokens (the short id tokens, e.g. "hr", "bp") - NOT the
     * `MeasurementEntity.kindRaw` column values (those are the `.name` strings like "HEART_RATE";
     * VitalsExporter's kindKey → kindRaw map bridges the two). The orchestrator looks this up by
     * kindKey, so one entry covers a paired record's both source rows ("bp" → both sys + dia).
     */
    val WRITE_PERMISSION_BY_KIND: Map<String, String> = mapOf(
        "hr" to heartRate.first(),
        "spo2" to oxygenSaturation.first(),
        "hrv" to heartRateVariability.first(),
        "temp" to bodyTemperature.first(),
        // Phase 5 measurement-based kinds (see VitalsExporter's kindKey → kindRaw mapping).
        "glucose" to bloodGlucose.first(),
        "resp_rate" to respiratoryRate.first(),
        "vo2max" to vo2Max.first(),
        "bp" to bloodPressure.first(),
    )
}
