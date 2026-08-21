package com.pulseloop.health

import androidx.health.connect.client.records.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (docs/health-connect-integration.md): the new clientRecordId builders and the
 * plausibility guards for blood pressure, glucose, respiratory rate, VO2max and resting HR.
 * All pure [HealthConnectTypeMappings] helpers - no database or client.
 */
class HealthConnectPhase5MappingTest {

    // ── id builders ──

    @Test
    fun bloodPressureRecordIdIsBpPlusSharedTimestamp() {
        assertEquals("pl-m-bp-1723700000123", HealthConnectTypeMappings.bloodPressureRecordId(1_723_700_000_123L))
        assertEquals("pl-m-bp-0", HealthConnectTypeMappings.bloodPressureRecordId(0L))
    }

    @Test
    fun restingHrRecordIdIsAStableConstant() {
        assertEquals("pl-resting-hr", HealthConnectTypeMappings.RESTING_HR_RECORD_ID)
    }

    @Test
    fun nutritionRecordIdIsMealPlusStableId() {
        assertEquals("pl-meal-abc-123", HealthConnectTypeMappings.nutritionRecordId("abc-123"))
    }

    // ── plausibility guards ──

    @Test
    fun systolicUsesAppRange() {
        assertTrue(HealthConnectTypeMappings.isPlausibleSystolic(120.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleSystolic(60.0))
        // the platform ceiling is 200, not the app's decode ceiling of 250
        assertTrue(HealthConnectTypeMappings.isPlausibleSystolic(200.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleSystolic(59.9))
        assertFalse(HealthConnectTypeMappings.isPlausibleSystolic(200.1))
        assertFalse(HealthConnectTypeMappings.isPlausibleSystolic(250.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleSystolic(0.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleSystolic(Double.NaN))
    }

    @Test
    fun diastolicUsesAppRange() {
        assertTrue(HealthConnectTypeMappings.isPlausibleDiastolic(80.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleDiastolic(30.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleDiastolic(150.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleDiastolic(29.9))
        assertFalse(HealthConnectTypeMappings.isPlausibleDiastolic(150.1))
        assertFalse(HealthConnectTypeMappings.isPlausibleDiastolic(Double.POSITIVE_INFINITY))
    }

    @Test
    fun glucoseIsCappedAtThePlatformCeiling() {
        assertTrue(HealthConnectTypeMappings.isPlausibleBloodGlucose(100.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleBloodGlucose(20.0))
        // the hard cap is 900.0 mg/dL (= 50 mmol/L at the client's 1/18 factor), NOT 900.91:
        // 900.0 maps to 50.0 (accepted), 900.01 maps to 50.0006 (the ctor throws)
        assertTrue(HealthConnectTypeMappings.isPlausibleBloodGlucose(900.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleBloodGlucose(900.01))
        assertFalse(HealthConnectTypeMappings.isPlausibleBloodGlucose(19.9))
        // 0 is the not-measured sentinel - dropped, not clamped
        assertFalse(HealthConnectTypeMappings.isPlausibleBloodGlucose(0.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleBloodGlucose(Double.NaN))
    }

    @Test
    fun respRateUsesAppRange() {
        assertTrue(HealthConnectTypeMappings.isPlausibleRespRate(16.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleRespRate(5.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleRespRate(60.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleRespRate(4.9))
        assertFalse(HealthConnectTypeMappings.isPlausibleRespRate(60.1))
        assertFalse(HealthConnectTypeMappings.isPlausibleRespRate(0.0))
    }

    @Test
    fun vo2MaxUsesAppRange() {
        assertTrue(HealthConnectTypeMappings.isPlausibleVo2Max(45.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleVo2Max(1.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleVo2Max(100.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleVo2Max(0.9))
        assertFalse(HealthConnectTypeMappings.isPlausibleVo2Max(100.1))
        assertFalse(HealthConnectTypeMappings.isPlausibleVo2Max(Double.NaN))
    }

    @Test
    fun restingHrUsesPlatformBound() {
        assertTrue(HealthConnectTypeMappings.isPlausibleRestingHr(57.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleRestingHr(1.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleRestingHr(300.0))
        // the platform rejects 0, unlike the client
        assertFalse(HealthConnectTypeMappings.isPlausibleRestingHr(0.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleRestingHr(300.1))
        assertFalse(HealthConnectTypeMappings.isPlausibleRestingHr(Double.NEGATIVE_INFINITY))
    }

    // ── blood pressure pairing (the crux of Phase 5) ──

    @Test
    fun bloodPressurePairsByExactTimestamp() {
        val sys = listOf(
            HealthConnectTypeMappings.BpSide(1000L, 120.0, 5000L),
            HealthConnectTypeMappings.BpSide(2000L, 110.0, 6000L),
            HealthConnectTypeMappings.BpSide(3000L, 130.0, 7000L),
        )
        val dia = listOf(
            HealthConnectTypeMappings.BpSide(1000L, 80.0, 5100L), // pairs with sys@1000
            HealthConnectTypeMappings.BpSide(2000L, 70.0, 5900L), // pairs with sys@2000
            // 3000 has no diastolic -> unpaired, dropped
        )
        val result = HealthConnectTypeMappings.pairBloodPressure(sys, dia)
        val pairs = result.pairs
        assertEquals(2, pairs.size)
        assertEquals(1000L, pairs[0].timestampMs)
        assertEquals(120.0, pairs[0].systolic, 0.0)
        assertEquals(80.0, pairs[0].diastolic, 0.0)
        // highWater = max of the pair's two createdAts
        assertEquals(5100L, pairs[0].highWater)
        assertEquals(2000L, pairs[1].timestampMs)
        // pair@2000: sys createdAt 6000, dia createdAt 5900 -> max = 6000
        assertEquals(6000L, pairs[1].highWater)
        // ts=3000 has a systolic but no diastolic -> counted as unpaired, not out-of-range
        assertEquals(1, result.unpaired)
        assertEquals(0, result.outOfRange)
    }

    @Test
    fun bloodPressureDropsUnpairedAndOutOfRange() {
        val sys = listOf(
            HealthConnectTypeMappings.BpSide(1000L, 120.0, 100L), // ok
            HealthConnectTypeMappings.BpSide(2000L, 210.0, 100L), // systolic above the 200 platform cap
            HealthConnectTypeMappings.BpSide(3000L, 120.0, 100L), // ok systolic, but diastolic out of range
            HealthConnectTypeMappings.BpSide(4000L, 50.0, 100L),  // systolic below the 60 app floor
        )
        val dia = listOf(
            HealthConnectTypeMappings.BpSide(1000L, 80.0, 100L),  // ok
            HealthConnectTypeMappings.BpSide(2000L, 80.0, 100L),
            HealthConnectTypeMappings.BpSide(3000L, 160.0, 100L), // diastolic above the 150 app ceiling
            HealthConnectTypeMappings.BpSide(4000L, 80.0, 100L),
        )
        val result = HealthConnectTypeMappings.pairBloodPressure(sys, dia)
        assertEquals(1, result.pairs.size)
        assertEquals(1000L, result.pairs[0].timestampMs)
        // 3 out-of-range pairs (sys 210, dia 160, sys 50), 0 unpaired
        assertEquals(0, result.unpaired)
        assertEquals(3, result.outOfRange)
        assertEquals(3, result.dropped)
    }

    // ── review pass 5: out-of-range pairs must release the shared VITALS watermark ──

    @Test
    fun bloodPressureReportsOutOfRangeHighWaterButNotUnpaired() {
        val sys = listOf(
            HealthConnectTypeMappings.BpSide(1000L, 120.0, 100L), // exports
            HealthConnectTypeMappings.BpSide(2000L, 210.0, 900L), // out of range: permanently dead
            HealthConnectTypeMappings.BpSide(3000L, 120.0, 5000L), // unpaired: its diastolic may still arrive
        )
        val dia = listOf(
            HealthConnectTypeMappings.BpSide(1000L, 80.0, 100L),
            HealthConnectTypeMappings.BpSide(2000L, 80.0, 800L),
        )
        val result = HealthConnectTypeMappings.pairBloodPressure(sys, dia)
        assertEquals(1, result.pairs.size)
        assertEquals(1, result.outOfRange)
        assertEquals(1, result.unpaired)
        // max createdAt of the OUT-OF-RANGE pair only (900 vs 800) — never the unpaired row's
        // 5000, which must keep holding the watermark down so the pair can still form.
        assertEquals(900L, result.outOfRangeHighWater)
    }

    @Test
    fun bloodPressureOutOfRangeHighWaterIsNullWhenNothingIsOutOfRange() {
        val sys = listOf(HealthConnectTypeMappings.BpSide(1000L, 120.0, 100L))
        val dia = listOf(HealthConnectTypeMappings.BpSide(1000L, 80.0, 100L))
        assertEquals(null, HealthConnectTypeMappings.pairBloodPressure(sys, dia).outOfRangeHighWater)
        // …and an unpaired-only selection reports none either.
        assertEquals(null, HealthConnectTypeMappings.pairBloodPressure(sys, emptyList()).outOfRangeHighWater)
    }

    @Test
    fun bloodPressureEmptyOrOneSidedInputsProduceNoPairs() {
        assertTrue(HealthConnectTypeMappings.pairBloodPressure(emptyList(), emptyList()).pairs.isEmpty())
        val sys = listOf(HealthConnectTypeMappings.BpSide(1000L, 120.0, 100L))
        assertTrue(HealthConnectTypeMappings.pairBloodPressure(sys, emptyList()).pairs.isEmpty())
        assertTrue(HealthConnectTypeMappings.pairBloodPressure(emptyList(), sys).pairs.isEmpty())
    }

    @Test
    fun nutritionEnergyCapsAtPlatformCeilingInKcal() {
        // platform cap is 100,000,000 small calories = 100,000 kcal, NOT 1e8 kcal
        assertTrue(HealthConnectTypeMappings.isPlausibleNutritionEnergyKcal(500.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleNutritionEnergyKcal(100_000.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleNutritionEnergyKcal(100_001.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleNutritionEnergyKcal(500_000.0)) // a 6-digit kcal typo
        assertFalse(HealthConnectTypeMappings.isPlausibleNutritionEnergyKcal(0.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleNutritionEnergyKcal(Double.NaN))
    }

    @Test
    fun nutritionMassCapsAt100000() {
        assertTrue(HealthConnectTypeMappings.isPlausibleNutritionMass(30.0))
        assertTrue(HealthConnectTypeMappings.isPlausibleNutritionMass(100_000.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleNutritionMass(100_001.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleNutritionMass(0.0))
        assertFalse(HealthConnectTypeMappings.isPlausibleNutritionMass(Double.POSITIVE_INFINITY))
    }

    @Test
    fun nutritionMealTypeMapsTheAppsFour() {
        assertEquals(MealType.MEAL_TYPE_BREAKFAST, HealthConnectTypeMappings.nutritionMealType("breakfast"))
        assertEquals(MealType.MEAL_TYPE_LUNCH, HealthConnectTypeMappings.nutritionMealType("lunch"))
        assertEquals(MealType.MEAL_TYPE_DINNER, HealthConnectTypeMappings.nutritionMealType("dinner"))
        assertEquals(MealType.MEAL_TYPE_SNACK, HealthConnectTypeMappings.nutritionMealType("snack"))
        assertEquals(MealType.MEAL_TYPE_UNKNOWN, HealthConnectTypeMappings.nutritionMealType("brunch"))
        assertEquals(MealType.MEAL_TYPE_UNKNOWN, HealthConnectTypeMappings.nutritionMealType(""))
    }
}
