package com.pulseloop.coach.tools

import com.pulseloop.coach.orchestration.MealUpdates
import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.nutrition.FoodProduct
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for the pure logic factored out of [NutritionTools] (iOS PR #96 port).
 * The tool bodies take a concrete Room db (the repo has no in-memory harness) and can't be
 * exercised end-to-end here, so the behavior that can drift — timestamp resolution, the
 * source/confidence raw-string mapping, the search clamp and query validation, the per-product
 * payload shape, and the meal-update application — lives in pure [NutritionTools] members and
 * is tested directly, with an injected clock/zone wherever time matters.
 */
class NutritionToolsTest {
    /** A fixed "now": 2026-08-22 15:04:05 UTC — afternoon, so local "today" is 2026-08-22/23. */
    private val now = Instant.parse("2026-08-22T15:04:05Z").toEpochMilli()

    // resolveTimestamp goes through CoachDataAccess.parseLocalDate, which anchors to the system
    // default zone — so these tests run in that same zone (like CoachActionTest does) and
    // compute their expectations in it, staying valid on any CI machine's timezone.
    private val zone = ZoneId.systemDefault()

    private fun dayOf(instantMs: Long): String =
        Instant.ofEpochMilli(instantMs).atZone(zone).toLocalDate().toString()

    // ── resolveTimestamp ───────────────────────────────────────────────

    @Test
    fun todayWithoutTimeLandsAtNow() {
        assertEquals(now, NutritionTools.resolveTimestamp(dayOf(now), null, now, zone))
    }

    @Test
    fun pastDayWithoutTimeLandsAtNoon() {
        // "2026-08-20" can never be the local day of [now] (max zone offset ±14 keeps [now]
        // on 2026-08-22/23 local), so this deterministically takes the noon branch.
        val expected = LocalDate.parse("2026-08-20").atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, NutritionTools.resolveTimestamp("2026-08-20", null, now, zone))
    }

    @Test
    fun explicitTimeIsHonoredOnAPastDay() {
        val expected = LocalDate.parse("2026-08-20").atTime(14, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, NutritionTools.resolveTimestamp("2026-08-20", "14:30", now, zone))
    }

    @Test
    fun explicitTimeIsHonoredOnTodayToo() {
        // An explicit time wins even on today (iOS: the stamped time short-circuits the
        // isDateInToday fallback).
        val startOfDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(startOfDay + 8 * 3600_000L, NutritionTools.resolveTimestamp(dayOf(now), "08:00", now, zone))
    }

    @Test
    fun unstampableTimeFallsBackToNoonOrNow() {
        // 25:99 can't be stamped (iOS's bySettingHour returns null) — a past day falls to
        // noon, today falls to the current clock time.
        val expectedNoon = LocalDate.parse("2026-08-20").atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedNoon, NutritionTools.resolveTimestamp("2026-08-20", "25:99", now, zone))
        assertEquals(now, NutritionTools.resolveTimestamp(dayOf(now), "25:99", now, zone))
    }

    @Test
    fun invalidDateFallsBackToToday() {
        // iOS: parseLocalDate(date) ?? startOfDay(now) — and today with no time is now.
        assertEquals(now, NutritionTools.resolveTimestamp("not-a-date", null, now, zone))
    }

    // ── source / confidence / meal_type raw mapping ────────────────────

    @Test
    fun sourceIsOffSearchOnlyForDatabaseWithAProductCode() {
        // The honesty core: a row is database-verified only when grounded in a real OFF code.
        assertEquals("off_search", NutritionTools.resolveSourceRaw("database", "3017620422003"))
        assertEquals("llm_estimate", NutritionTools.resolveSourceRaw("database", null))
        assertEquals("llm_estimate", NutritionTools.resolveSourceRaw("estimate", "3017620422003"))
        assertEquals("llm_estimate", NutritionTools.resolveSourceRaw("estimate", null))
    }

    @Test
    fun confidenceMapsToTheIosRawValues() {
        assertEquals("known", NutritionTools.decodeConfidenceRaw("high"))
        assertEquals("partial", NutritionTools.decodeConfidenceRaw("medium"))
        assertEquals("unknown", NutritionTools.decodeConfidenceRaw("low"))
        assertEquals("unknown", NutritionTools.decodeConfidenceRaw("bogus"))
        assertEquals("unknown", NutritionTools.decodeConfidenceRaw(null))
    }

    @Test
    fun mealTypeSetMatchesTheAppPickers() {
        // The same four raw strings MealLogDialog's chips offer (iOS MealType rawValues).
        for (t in listOf("breakfast", "lunch", "dinner", "snack")) {
            assertTrue(t in NutritionTools.mealTypeRawValues)
        }
        assertFalse("brunch" in NutritionTools.mealTypeRawValues)
    }

    // ── search limit clamp + query validation ──────────────────────────

    @Test
    fun searchLimitClampsTo1Through5() {
        // iOS: min(5, max(1, Int(maxResults ?? 5)))
        assertEquals(1, NutritionTools.clampSearchLimit(0.0))
        assertEquals(1, NutritionTools.clampSearchLimit(-3.0))
        assertEquals(5, NutritionTools.clampSearchLimit(99.0))
        assertEquals(3, NutritionTools.clampSearchLimit(3.0))
        assertEquals(2, NutritionTools.clampSearchLimit(2.9))  // Int() truncates
        assertEquals(5, NutritionTools.clampSearchLimit(null))
    }

    @Test
    fun searchQueryRequiresTwoCharsAfterTrim() {
        assertEquals("query too short", NutritionTools.searchQueryError("a"))
        assertEquals("query too short", NutritionTools.searchQueryError("   "))
        assertNull(NutritionTools.searchQueryError("ab"))
        assertNull(NutritionTools.searchQueryError("  apples  "))
    }

    // ── per-product payload ────────────────────────────────────────────

    @Test
    fun payloadCarriesOptionalsOnlyWhenPresent() {
        val full = FoodProduct(
            code = "301", name = "Yogurt", brand = "Acme", energyKcal100g = 97.4,
            protein100g = 10.0, carbs100g = 12.0, fat100g = 3.0,
            servingSizeText = "1 cup (240 ml)", servingQuantityG = 240.0,
        )
        val obj = NutritionTools.foodProductPayload(full).jsonObject
        assertEquals("301", obj["code"]!!.jsonPrimitive.content)
        assertEquals("Yogurt", obj["name"]!!.jsonPrimitive.content)
        assertEquals("Acme", obj["brand"]!!.jsonPrimitive.content)
        assertEquals("1 cup (240 ml)", obj["serving"]!!.jsonPrimitive.content)
        assertEquals(240.0, obj["serving_g"]!!.jsonPrimitive.content.toDoubleOrNull()!!, 1e-9)
        val per100 = obj["per_100g"]!!.jsonObject
        // kcal is rounded to an integer, like iOS's energyKcal100g.rounded().
        assertEquals(97.0, per100["kcal"]!!.jsonPrimitive.content.toDoubleOrNull()!!, 1e-9)
        assertEquals(10.0, per100["protein_g"]!!.jsonPrimitive.content.toDoubleOrNull()!!, 1e-9)
        assertEquals(12.0, per100["carbs_g"]!!.jsonPrimitive.content.toDoubleOrNull()!!, 1e-9)
        assertEquals(3.0, per100["fat_g"]!!.jsonPrimitive.content.toDoubleOrNull()!!, 1e-9)

        val bare = FoodProduct(code = "302", name = "Plain", energyKcal100g = 100.6)
        val bareObj = NutritionTools.foodProductPayload(bare).jsonObject
        assertFalse(bareObj.containsKey("brand"))
        assertFalse(bareObj.containsKey("serving"))
        assertFalse(bareObj.containsKey("serving_g"))
        assertEquals(101.0, bareObj["per_100g"]!!.jsonObject["kcal"]!!.jsonPrimitive.content.toDoubleOrNull()!!, 1e-9)
    }

    // ── applyMealUpdates ───────────────────────────────────────────────

    private fun entry(sourceRaw: String) = MealEntryEntity(
        date = 0L, timestamp = 0L, name = "Oatmeal", mealTypeRaw = "breakfast",
        calories = 300.0, sourceRaw = sourceRaw,
    )

    @Test
    fun numericChangeMarksADatabaseRowEdited() {
        // iOS: a user-requested correction to a database/estimate row marks it edited.
        val updated = NutritionTools.applyMealUpdates(MealUpdates(calories = 320.0), entry("off_search"))
        assertEquals(320.0, updated.calories, 1e-9)
        assertTrue(updated.userEdited)
    }

    @Test
    fun numericChangeDoesNotMarkAManualRowEdited() {
        val updated = NutritionTools.applyMealUpdates(MealUpdates(calories = 320.0), entry("manual"))
        assertEquals(320.0, updated.calories, 1e-9)
        assertFalse(updated.userEdited)
    }

    @Test
    fun nonNumericChangeDoesNotMarkEdited() {
        val updated = NutritionTools.applyMealUpdates(
            MealUpdates(name = "Oats", notes = "had berries"), entry("off_search"))
        assertEquals("Oats", updated.name)
        assertEquals("had berries", updated.notes)
        assertFalse(updated.userEdited)
    }

    @Test
    fun unknownMealTypeIsIgnoredNotAnError() {
        // iOS guards with MealType(rawValue:) — an invalid type is simply not applied.
        val updated = NutritionTools.applyMealUpdates(MealUpdates(mealType = "brunch"), entry("off_search"))
        assertEquals("breakfast", updated.mealTypeRaw)
        assertFalse(updated.userEdited)
    }

    @Test
    fun allNullUpdatesOnlyBumpUpdatedAt() {
        val e = entry("llm_estimate")
        val updated = NutritionTools.applyMealUpdates(MealUpdates(), e)
        assertEquals(e.copy(updatedAt = updated.updatedAt), updated)
    }

    @Test
    fun localTimeStringFormatsHourAndMinute() {
        // Pure conversion — no parseLocalDate involved — so a fixed zone is safe.
        val utc = ZoneId.of("UTC")
        assertEquals(
            "08:05",
            NutritionTools.localTimeString(Instant.parse("2026-08-22T08:05:00Z").toEpochMilli(), utc),
        )
        assertEquals(
            "23:59",
            NutritionTools.localTimeString(Instant.parse("2026-08-22T23:59:00Z").toEpochMilli(), utc),
        )
    }
}
