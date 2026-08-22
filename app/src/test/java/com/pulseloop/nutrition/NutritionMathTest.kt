package com.pulseloop.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the pure conversions in [NutritionMath] — OFF's unit quirks live here. */
class NutritionMathTest {
    @Test
    fun kcalPerKJIsTheReciprocalOf4184() {
        assertEquals(1.0 / 4.184, NutritionMath.kcalPerKJ, 0.0)
    }

    @Test
    fun scaledScalesPer100gToGrams() {
        assertEquals(100.0, NutritionMath.scaled(per100g = 250.0, grams = 40.0), 1e-9)
        assertEquals(539.0, NutritionMath.scaled(per100g = 539.0, grams = 100.0), 1e-9)
        assertEquals(0.0, NutritionMath.scaled(per100g = 539.0, grams = 0.0), 1e-9)
    }

    @Test
    fun energyKcalPrefersKcalWhenBothArePresent() {
        // kcal wins even when kJ is also present — 2250 kJ would be ~537.8 kcal, not 539.
        assertEquals(539.0, NutritionMath.energyKcal(kcal = 539.0, kJ = 2250.0)!!, 1e-9)
    }

    @Test
    fun energyKcalFallsBackToKJWhenKcalIsMissing() {
        assertEquals(500.0, NutritionMath.energyKcal(kcal = null, kJ = 2092.0)!!, 1e-9)
    }

    @Test
    fun energyKcalOfZeroKcalStillWins() {
        // A present 0.0 kcal is data (a product can legitimately be recorded with 0 kcal),
        // not absence — iOS's `if let kcal` guards on presence, not on non-zero.
        assertEquals(0.0, NutritionMath.energyKcal(kcal = 0.0, kJ = 4184.0)!!, 1e-9)
    }

    @Test
    fun energyKcalIsNullWhenNeitherIsPresent() {
        assertNull(NutritionMath.energyKcal(kcal = null, kJ = null))
    }

    @Test
    fun sodiumMgConvertsGramsToMilligrams() {
        assertEquals(42.8, NutritionMath.sodiumMg(fromGrams = 0.0428)!!, 1e-9)
        assertEquals(0.0, NutritionMath.sodiumMg(fromGrams = 0.0)!!, 1e-9)
    }

    @Test
    fun sodiumMgIsNullForMissingGrams() {
        assertNull(NutritionMath.sodiumMg(fromGrams = null))
    }
}
