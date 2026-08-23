package com.pulseloop.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure decision logic ported from iOS's MealEstimator
 * (MealAnalysisSheet.swift) — the enable predicates, the fence-tolerant decode, the
 * confidence-to-provenance mapping, and the meal-type inference.
 */
class MealAnalysisLogicTest {

    // ── canAnalyze — iOS MealAnalysisSheet.swift:39-41 ─────────────────

    @Test
    fun canAnalyzeWithAnImageAlone() {
        assertTrue(MealAnalysisLogic.canAnalyze(hasImage = true, description = ""))
    }

    @Test
    fun canAnalyzeWithThreeTrimmedCharacters() {
        // iOS trims whitespace BEFORE counting, so padding never qualifies a too-short
        // description.
        assertTrue(MealAnalysisLogic.canAnalyze(hasImage = false, description = "two eggs"))
        assertTrue(MealAnalysisLogic.canAnalyze(hasImage = false, description = "  abc  "))
    }

    @Test
    fun cannotAnalyzeShortOrBlankDescriptionsWithoutImage() {
        assertFalse(MealAnalysisLogic.canAnalyze(hasImage = false, description = ""))
        // "ab" padded to 4 raw characters still trims to 2.
        assertFalse(MealAnalysisLogic.canAnalyze(hasImage = false, description = " ab "))
    }

    // ── canSave — iOS MealAnalysisSheet.swift:43-45 ────────────────────

    @Test
    fun canSavesWithNumericCalories() {
        assertTrue(MealAnalysisLogic.canSave(name = "Omelette", calories = "520"))
        assertTrue(MealAnalysisLogic.canSave(name = " Omelette ", calories = "520.5"))
    }

    @Test
    fun cannotSaveWithoutANameOrANumber() {
        assertFalse(MealAnalysisLogic.canSave(name = "", calories = "520"))
        assertFalse(MealAnalysisLogic.canSave(name = "   ", calories = "520"))
        // iOS Double(calories) == nil — any non-numeric string fails.
        assertFalse(MealAnalysisLogic.canSave(name = "Omelette", calories = ""))
        assertFalse(MealAnalysisLogic.canSave(name = "Omelette", calories = "about 500"))
    }

    // ── confidence mapping — iOS save(), MealAnalysisSheet.swift:306 ───

    @Test
    fun confidenceMapsToProvenanceKnownPartialUnknown() {
        assertEquals("known", MealAnalysisLogic.confidenceRaw("high"))
        assertEquals("partial", MealAnalysisLogic.confidenceRaw("medium"))
        assertEquals("unknown", MealAnalysisLogic.confidenceRaw("low"))
        // Anything else (missing, garbage) lands on unknown, like iOS's else branch.
        assertEquals("unknown", MealAnalysisLogic.confidenceRaw(null))
        assertEquals("unknown", MealAnalysisLogic.confidenceRaw("HIGH"))
    }

    // ── inferred meal type — iOS NutritionModels.swift:20-27 ──────────

    @Test
    fun inferredMealTypeFollowsTheClockBuckets() {
        assertEquals("breakfast", MealAnalysisLogic.inferredMealType(4))
        assertEquals("breakfast", MealAnalysisLogic.inferredMealType(10))
        assertEquals("lunch", MealAnalysisLogic.inferredMealType(11))
        assertEquals("lunch", MealAnalysisLogic.inferredMealType(14))
        assertEquals("snack", MealAnalysisLogic.inferredMealType(15))
        assertEquals("dinner", MealAnalysisLogic.inferredMealType(17))
        assertEquals("dinner", MealAnalysisLogic.inferredMealType(21))
        assertEquals("snack", MealAnalysisLogic.inferredMealType(22))
        assertEquals("snack", MealAnalysisLogic.inferredMealType(3))
    }

    // ── fence-tolerant decode — iOS MealEstimator.decode :413-422 ─────

    private val fullJson =
        """{"name":"Rice and beans","calories":450.0,"protein_g":15.0,"carbs_g":80.0,"fat_g":6.0,"assumptions":"1 cup cooked","confidence":"high"}"""

    @Test
    fun decodesPlainJsonObject() {
        val e = MealAnalysisLogic.decode(fullJson)
        assertNotNull(e)
        assertEquals("Rice and beans", e!!.name)
        assertEquals(450.0, e.calories, 1e-9)
        assertEquals(15.0, e.proteinG, 1e-9)
        assertEquals(80.0, e.carbsG, 1e-9)
        assertEquals(6.0, e.fatG, 1e-9)
        assertEquals("high", e.confidence)
    }

    @Test
    fun decodesInsideMarkdownFences() {
        val fenced = "```json\n" + fullJson + "\n```"
        assertEquals("Rice and beans", MealAnalysisLogic.decode(fenced)!!.name)
    }

    @Test
    fun decodesInsideSurroundingProse() {
        val prose = "Here is your estimate:\n" + fullJson + "\nHope this helps!"
        assertEquals("Rice and beans", MealAnalysisLogic.decode(prose)!!.name)
    }

    @Test
    fun toleratesUnknownKeysAndMissingAssumptions() {
        val text = "{\"name\":\"Soup\",\"calories\":120,\"protein_g\":4,\"carbs_g\":10,\"fat_g\":2,\"confidence\":\"medium\",\"extra\":true}"
        val e = MealAnalysisLogic.decode(text)!!
        assertEquals("Soup", e.name)
        // iOS decodes assumptions as a present String; the Android port defaults it so a
        // provider omitting the key still yields a usable estimate.
        assertEquals("", e.assumptions)
    }

    @Test
    fun returnsNullForGarbageAndMissingRequiredFields() {
        assertNull(MealAnalysisLogic.decode(""))
        assertNull(MealAnalysisLogic.decode("no json here at all"))
        // A JSON object missing required fields is unusable — same as iOS's decode failure.
        assertNull(MealAnalysisLogic.decode("{\"name\":\"Soup\"}"))
        assertNull(MealAnalysisLogic.decode("{}"))
    }
}
