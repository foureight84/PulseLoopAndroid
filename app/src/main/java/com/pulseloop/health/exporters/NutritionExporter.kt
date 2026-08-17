package com.pulseloop.health.exporters

import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.health.HealthConnectTypeMappings.EXCLUDED_SOURCES
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the Phase 5 nutrition records (docs/health-connect-integration.md Phase 5): one
 * [NutritionRecord] per [MealEntryEntity]. A [NutritionRecord] is an IntervalRecord carrying
 * energy + all the macros the app logs (protein/carbs/fat/fiber/sugar/sodium) in a single
 * record - simpler than HealthKit's seven separate dietary types.
 *
 * Identity: `clientRecordId = pl-meal-<MealEntryEntity.id>` - the meal's Room primary key is a
 * stable UUID (a logged meal is insert-once, never churned on re-sync like a sleep block), so it
 * is a safe upsert key; `clientRecordVersion = meal.updatedAt`. The interval is the meal's log
 * instant with a +60 s end (a meal spans some eating time; Health Connect requires end > start).
 *
 * Watermarked on `updatedAt` by [HealthConnectExporter] (Phase 6) - iOS parity: iOS's meal model
 * carries an `updatedAt` driving its export watermark because iOS edits meals in place. Android
 * is insert-once today, so `updatedAt == createdAt` for every row and the switch is
 * behavior-preserving; when an in-place meal-edit path lands it only needs to bump `updatedAt`
 * and the edited meal re-exports under the same clientRecordId at a higher version (write-only
 * means no repair, so the watermark must see the edit).
 */
class NutritionExporter(private val db: PulseLoopDatabase) {

    data class PendingNutrition(
        val records: List<Record>,
        val highWaters: List<Long>,
        val skippedMeals: Int,
    )

    /**
     * Meals newer than the nutrition watermark, reduced to [NutritionRecord]s. A meal whose value
     * is outside the platform's range (a typo) is dropped, never clamped, so one bad meal cannot
     * sink the whole 200-record chunk.
     */
    suspend fun build(watermark: Long?, device: Device): PendingNutrition {
        val wm = watermark ?: 0L
        val zone = ZoneId.systemDefault()
        val meals = db.mealEntryDao().updatedSince(wm).filter { it.sourceRaw !in EXCLUDED_SOURCES }
        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()
        var skipped = 0
        for (meal in meals) {
            val record = buildRecord(meal, device, zone)
            if (record == null) {
                skipped++
                continue
            }
            records += record
            highWaters += meal.updatedAt
        }
        return PendingNutrition(records, highWaters, skipped)
    }

    private fun buildRecord(meal: MealEntryEntity, device: Device, zone: ZoneId): NutritionRecord? {
        // Validate every value we are about to set before building: an out-of-range meal is
        // dropped (never clamped) so a typo cannot throw from the ctor and sink the chunk.
        if (meal.calories > 0.0 && !HealthConnectTypeMappings.isPlausibleNutritionEnergyKcal(meal.calories)) return null
        for (v in listOf(meal.proteinG, meal.carbsG, meal.fatG, meal.fiberG, meal.sugarG, meal.sodiumMg)) {
            if (v != null && v > 0.0 && !HealthConnectTypeMappings.isPlausibleNutritionMass(v)) return null
        }

        val energy = if (meal.calories > 0.0) Energy.kilocalories(meal.calories) else null
        val protein = if (meal.proteinG > 0.0) Mass.grams(meal.proteinG) else null
        val carbs = if (meal.carbsG > 0.0) Mass.grams(meal.carbsG) else null
        val fat = if (meal.fatG > 0.0) Mass.grams(meal.fatG) else null
        val fiber = meal.fiberG?.takeIf { it > 0.0 }?.let { Mass.grams(it) }
        val sugar = meal.sugarG?.takeIf { it > 0.0 }?.let { Mass.grams(it) }
        // sodium is logged in milligrams and the platform field caps at 100 g = 100,000 mg;
        // Mass.milligrams (NOT grams - grams would be a 1000x error and throw for > 100 g).
        val sodium = meal.sodiumMg?.takeIf { it > 0.0 }?.let { Mass.milligrams(it) }

        // A meal with no name and no logged nutrient is an empty entry - nothing to export.
        if (meal.name.isBlank() && energy == null && protein == null && carbs == null && fat == null &&
            fiber == null && sugar == null && sodium == null) return null

        // Clamp a future-dated meal to now (iOS parity, HealthSyncService+Nutrition.swift:79) so a
        // clock-skewed timestamp never produces a future-dated record.
        val start = Instant.ofEpochMilli(minOf(meal.timestamp, System.currentTimeMillis()))
        val end = start.plusSeconds(60)
        val offset = HealthConnectTypeMappings.zoneOffsetAt(start, zone)
        return NutritionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            // Meals are user-initiated (logged in the nutrition screen) - activelyRecorded, the
            // same choice Phase 4 made for user-initiated workout records.
            metadata = Metadata.activelyRecorded(device, HealthConnectTypeMappings.nutritionRecordId(meal.id), meal.updatedAt),
            energy = energy,
            protein = protein,
            totalCarbohydrate = carbs,
            totalFat = fat,
            dietaryFiber = fiber,
            sugar = sugar,
            sodium = sodium,
            name = meal.name,
            mealType = HealthConnectTypeMappings.nutritionMealType(meal.mealTypeRaw),
        )
    }
}
