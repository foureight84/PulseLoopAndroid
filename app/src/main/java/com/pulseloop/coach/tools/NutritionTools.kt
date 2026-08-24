package com.pulseloop.coach.tools

import com.pulseloop.coach.orchestration.MealUpdates
import com.pulseloop.coach.orchestration.PendingAction
import com.pulseloop.coach.orchestration.PendingActionKind
import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.nutrition.FoodProduct
import com.pulseloop.nutrition.FoodProductCache
import com.pulseloop.nutrition.asFoodProduct
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Ported from [NutritionTools] in NutritionTools.swift (iOS PR #96).
 *
 * The read tools are grounding: `search_food_database` is cache-first (the local
 * `food_products` table, zero network) and only falls through to Open Food Facts on a cache
 * miss; on any lookup failure it returns a structured error telling the model to fall back to a
 * *labeled* estimate — numbers are grounded or flagged, never silently invented.
 * `get_nutrition_log` reads the day's meals + totals straight off the DAOs.
 *
 * The write tools carry the same risk model as ActionTools: `log_meal` applies immediately
 * (`loggedByCoach = true`), `update_meal_entry` applies immediately for a meal logged today
 * but routes older meals through a Confirm/Cancel [PendingAction], and `delete_meal_entry`
 * always goes through a [PendingAction]. Registration keeps the same split — [all] in the
 * always-on set, [writeTools] behind `flags.writeToolsEnabled` (iOS `enableWriteTools`).
 */

/** Rounds to the nearest whole number (iOS `.rounded()`) for the integer tool payloads. */
private fun Double.roundToLong(): Long = Math.round(this)

object NutritionTools {
    /**
     * Meal types as the raw strings [MealEntryEntity.mealTypeRaw] stores — the same four
     * values MealLogDialog's picker offers (iOS `MealType` rawValues: breakfast/lunch/dinner/
     * snack). The entity has no enum column, so validation is against this set.
     */
    internal val mealTypeRawValues = setOf("breakfast", "lunch", "dinner", "snack")

    /**
     * The sourceRaw values a coach-logged row can carry (iOS `MealEntrySource` rawValues —
     * "Raw values are persisted; append, never rename"). The app's own meal-logging UI writes
     * only the entity default ("manual"), so the database/estimate split below uses iOS's
     * canonical strings; the archive and Health Connect exporters round-trip any of them.
     */
    internal const val sourceRawOffBarcode = "off_barcode"    // scanned barcode resolved via OFF
    internal const val sourceRawOffSearch = "off_search"      // OFF text-search pick
    internal const val sourceRawLlmEstimate = "llm_estimate"  // coach estimate, no grounding
    internal const val sourceRawManual = "manual"             // user typed the numbers

    val all: List<CoachToolDef> = listOf(makeSearchFoodDatabase(), makeGetNutritionLog())
    val writeTools: List<CoachToolDef> =
        listOf(makeLogMeal(), makeUpdateMealEntry(), makeDeleteMealEntry())

    // ── search_food_database ───────────────────────────────────────────

    /**
     * Grounding tool (iOS #96): cache-first, then Open Food Facts. On rate-limit or network
     * failure it returns a structured error instructing the model to fall back to a *labeled*
     * estimate — numbers are grounded or flagged, never silently invented.
     */
    private fun makeSearchFoodDatabase() = CoachToolDef(
        name = "search_food_database",
        publicLabel = "Checking the food database",
        description = "Search Open Food Facts for a food's verified nutrition (per 100 g). " +
            "Use before logging any nameable or packaged food. " +
            "If it errors, estimate instead and say so, with source 'estimate'.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "query" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "max_results" to nullableNumber(),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("query"), JsonPrimitive("max_results"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val params = parseArgs(args) ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val rawQuery = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'query' argument"}""", isError = true)
        val query = rawQuery.trim()
        // iOS: min(5, max(1, Int(maxResults ?? 5))) — clamp, not trust.
        val limit = clampSearchLimit(params["max_results"]?.jsonPrimitive?.doubleOrNull)
        searchQueryError(query)?.let { err ->
            return@CoachToolDef ToolResult("""{"error":"$err"}""", isError = true)
        }
        val db = ctx.db
        // Without a database the Room product cache is unreachable, and fetched products must
        // be cached back into it — so db == null degrades to the same "unavailable" path as a
        // missing client (iOS's modelContext is never null; this keeps the Android null-db
        // contract identical to the other tools).
        val client = ctx.foodClient
        if (db == null || client == null) {
            return@CoachToolDef ToolResult(
                """{"ok":false,"error":"database_unavailable","instruction":"Food database unavailable. Estimate nutrition yourself, set source to 'estimate', and tell the user the numbers are estimated."}"""
            )
        }
        val dao = db.foodProductDao()
        val result = kotlinx.coroutines.runBlocking {
            // Cache-first: substring match against locally cached products (zero network).
            // Matched in SQL over the whole table — a recent-N page filtered in memory misses
            // cached products that fell out of that window and pays a network call for them.
            val cached = FoodProductCache.search(dao, query, limit)
            if (cached.isNotEmpty()) {
                resultsJson(cached.map { it.asFoodProduct() }, "local_cache")
            } else {
                try {
                    val results = client.search(query, limit)
                    // Every fetched product is cached so repeat lookups stay off the rate-limited API.
                    results.forEach { FoodProductCache.upsertCached(dao, it) }
                    resultsJson(results, "open_food_facts")
                } catch (_: Exception) {
                    // Rate limit (HTTP 429) or any network/decode failure: the same honest fallback.
                    """{"ok":false,"error":"lookup_failed","instruction":"Food database lookup failed. Estimate nutrition yourself, set source to 'estimate', and tell the user the numbers are estimated."}"""
                }
            }
        }
        ToolResult(result)
    }

    /** The `results` envelope shared by the cache and network paths. */
    private fun resultsJson(results: List<FoodProduct>, source: String): String = buildJsonObject {
        put("ok", true)
        put("source", source)
        put("results", JsonArray(results.map { foodProductPayload(it) }))
    }.toString()

    // ── get_nutrition_log ──────────────────────────────────────────────

    /**
     * Read tool (iOS #96): the day's logged meals + totals, so the coach can answer
     * "what did I eat" for any day without the context packet carrying history.
     */
    private fun makeGetNutritionLog() = CoachToolDef(
        name = "get_nutrition_log",
        publicLabel = "Reading your food log",
        description = "Get the user's logged meals and calorie/macro totals for a date (YYYY-MM-DD).",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("date" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
            "required" to JsonArray(listOf(JsonPrimitive("date"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val params = parseArgs(args) ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val date = params["date"]?.jsonPrimitive?.contentOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'date' argument"}""", isError = true)
        // Same helper the retrieval tools use (iOS CoachDataAccess.parseLocalDate).
        val day = CoachDataAccess.parseLocalDate(date)
            ?: return@CoachToolDef ToolResult("""{"error":"invalid date '$date' — use YYYY-MM-DD"}""", isError = true)
        val result = kotlinx.coroutines.runBlocking {
            val entries = db.mealEntryDao().byDay(day)
            // iOS totals(of:) sums at read time — no stored daily row to keep in sync.
            val kcal = entries.sumOf { it.calories }
            val protein = entries.sumOf { it.proteinG }
            val carbs = entries.sumOf { it.carbsG }
            val fat = entries.sumOf { it.fatG }
            val meals = entries.map { e ->
                buildJsonObject {
                    put("meal_id", e.id)
                    put("name", e.name)
                    put("meal_type", e.mealTypeRaw)
                    put("time", localTimeString(e.timestamp))
                    put("kcal", e.calories.roundToLong())
                    put("protein_g", e.proteinG)
                    put("carbs_g", e.carbsG)
                    put("fat_g", e.fatG)
                    put("source", e.sourceRaw)
                }
            }
            buildJsonObject {
                put("ok", true)
                put("date", date)
                put("entry_count", entries.size)
                putJsonObject("totals") {
                    put("kcal", kcal.roundToLong())
                    put("protein_g", protein)
                    put("carbs_g", carbs)
                    put("fat_g", fat)
                }
                put("meals", JsonArray(meals))
            }.toString()
        }
        ToolResult(result)
    }

    // ── log_meal ───────────────────────────────────────────────────────

    /**
     * Write tool (iOS #96): applies immediately — logging a meal is the low-risk write.
     * Values are TOTALS for what was eaten. The honesty core is the source mapping:
     * "database" + an OFF product code is the only way a row becomes database-verified
     * (sourceRaw off_search); everything else is llm_estimate, with the model instructed
     * to state portion assumptions and confidence.
     */
    private fun makeLogMeal() = CoachToolDef(
        name = "log_meal",
        publicLabel = "Logging your meal",
        description = "Log a meal/food/drink the user ate. Values are TOTALS for what was eaten. " +
            "Ground nameable foods via search_food_database first (source 'database'); " +
            "for home-cooked or unverifiable food use source 'estimate' with honest confidence " +
            "and stated portion assumptions. One call per meal.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "meal_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to mealTypeEnumArray())),
                "date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "time" to nullableString(),
                "calories" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                "protein_g" to nullableNumber(),
                "carbs_g" to nullableNumber(),
                "fat_g" to nullableNumber(),
                "fiber_g" to nullableNumber(),
                "sugar_g" to nullableNumber(),
                "sodium_mg" to nullableNumber(),
                "quantity" to nullableNumber(),
                "serving_description" to nullableString(),
                "source" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray(listOf(JsonPrimitive("database"), JsonPrimitive("estimate"))),
                )),
                "off_product_code" to nullableString(),
                "confidence" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray(listOf(JsonPrimitive("low"), JsonPrimitive("medium"), JsonPrimitive("high"))),
                )),
                "notes" to nullableString(),
            )),
            // Strict schema (as on iOS): every argument is required, optional values sent as JSON null.
            "required" to JsonArray(listOf(
                JsonPrimitive("name"), JsonPrimitive("meal_type"), JsonPrimitive("date"),
                JsonPrimitive("time"), JsonPrimitive("calories"), JsonPrimitive("protein_g"),
                JsonPrimitive("carbs_g"), JsonPrimitive("fat_g"), JsonPrimitive("fiber_g"),
                JsonPrimitive("sugar_g"), JsonPrimitive("sodium_mg"), JsonPrimitive("quantity"),
                JsonPrimitive("serving_description"), JsonPrimitive("source"),
                JsonPrimitive("off_product_code"), JsonPrimitive("confidence"), JsonPrimitive("notes"),
            )),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val params = parseArgs(args) ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'name' argument"}""", isError = true)
        val mealType = params["meal_type"]?.jsonPrimitive?.contentOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'meal_type' argument"}""", isError = true)
        // iOS validates the raw string against MealType.allCases before touching the clock or DB.
        if (mealType !in mealTypeRawValues) {
            return@CoachToolDef ToolResult("""{"error":"invalid meal_type '$mealType'"}""", isError = true)
        }
        val date = params["date"]?.jsonPrimitive?.contentOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'date' argument"}""", isError = true)
        val time = params["time"]?.jsonPrimitive?.contentOrNull
        val calories = params["calories"]?.jsonPrimitive?.doubleOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'calories' argument"}""", isError = true)
        // Plausibility gate (iOS: 0...6000) — a typo'd value is a tool error, not a stored row.
        if (calories < 0.0 || calories > 6000.0) {
            return@CoachToolDef ToolResult("""{"error":"calories out of plausible range"}""", isError = true)
        }
        val timestamp = resolveTimestamp(date, time)
        val sourceRaw = resolveSourceRaw(
            params["source"]?.jsonPrimitive?.contentOrNull,
            params["off_product_code"]?.jsonPrimitive?.contentOrNull,
        )
        val entry = MealEntryEntity(
            date = CoachDataAccess.startOfDay(timestamp),
            timestamp = timestamp,
            name = name,
            mealTypeRaw = mealType,
            calories = calories,
            proteinG = params["protein_g"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            carbsG = params["carbs_g"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            fatG = params["fat_g"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            // Nil must stay distinct from 0 (unknown vs measured zero) — the entity's nullable
            // columns keep that, matching iOS's "nil means unknown" comment.
            fiberG = params["fiber_g"]?.jsonPrimitive?.doubleOrNull,
            sugarG = params["sugar_g"]?.jsonPrimitive?.doubleOrNull,
            sodiumMg = params["sodium_mg"]?.jsonPrimitive?.doubleOrNull,
            sourceRaw = sourceRaw,
            offProductCode = params["off_product_code"]?.jsonPrimitive?.contentOrNull,
            servingDescription = params["serving_description"]?.jsonPrimitive?.contentOrNull,
            quantity = params["quantity"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
            confidenceRaw = decodeConfidenceRaw(params["confidence"]?.jsonPrimitive?.contentOrNull),
            notes = params["notes"]?.jsonPrimitive?.contentOrNull,
            loggedByCoach = true,
        )
        kotlinx.coroutines.runBlocking { db.mealEntryDao().upsert(entry) }
        // iOS appends entry.id to ctx.loggedMealIds so the chat renders a tappable meal card.
        // Android has no logged-meal card mechanism yet (nothing in the coach chat consumes
        // one), so the insert IS the core behavior here; the in-chat tappable card is a known
        // gap on Android rather than a fabricated feature.
        ToolResult(buildJsonObject {
            put("ok", true)
            put("meal_id", entry.id)
            put("name", name)
            put("kcal", calories.roundToLong())
            put("source", sourceRaw)
            put("note", "Logged. The user can adjust it in the food log.")
        }.toString())
    }

    // ── update_meal_entry ──────────────────────────────────────────────

    /**
     * Write tool (iOS #96): today's meals edit in place immediately; older meals are
     * higher-stakes (they can shift historical totals and exports) and go through a
     * Confirm/Cancel [PendingAction] — the same risk split as update_activity_session.
     */
    private fun makeUpdateMealEntry() = CoachToolDef(
        name = "update_meal_entry",
        publicLabel = "Updating that meal",
        description = "Edit a logged meal. Applies immediately for a meal logged today; " +
            "for an older meal, returns needs_confirmation and shows a Confirm card.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "meal_id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "name" to nullableString(),
                // iOS: ["string", "null"] plus the meal-type enum with null appended.
                "meal_type" to JsonObject(mapOf(
                    "type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))),
                    "enum" to JsonArray(mealTypeRawValues.map { JsonPrimitive(it) } + JsonNull),
                )),
                "calories" to nullableNumber(),
                "protein_g" to nullableNumber(),
                "carbs_g" to nullableNumber(),
                "fat_g" to nullableNumber(),
                "notes" to nullableString(),
                "reason" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(
                JsonPrimitive("meal_id"), JsonPrimitive("name"), JsonPrimitive("meal_type"),
                JsonPrimitive("calories"), JsonPrimitive("protein_g"), JsonPrimitive("carbs_g"),
                JsonPrimitive("fat_g"), JsonPrimitive("notes"), JsonPrimitive("reason"),
            )),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val params = parseArgs(args) ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val mealId = params["meal_id"]?.jsonPrimitive?.contentOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'meal_id' argument"}""", isError = true)
        val entry = kotlinx.coroutines.runBlocking { db.mealEntryDao().byId(mealId) }
            ?: return@CoachToolDef ToolResult("""{"error":"meal '$mealId' not found"}""", isError = true)

        val updates = MealUpdates(
            name = params["name"]?.jsonPrimitive?.contentOrNull,
            mealType = params["meal_type"]?.jsonPrimitive?.contentOrNull,
            calories = params["calories"]?.jsonPrimitive?.doubleOrNull,
            proteinG = params["protein_g"]?.jsonPrimitive?.doubleOrNull,
            carbsG = params["carbs_g"]?.jsonPrimitive?.doubleOrNull,
            fatG = params["fat_g"]?.jsonPrimitive?.doubleOrNull,
            notes = params["notes"]?.jsonPrimitive?.contentOrNull,
        )

        // Today = same local calendar day as the entry's timestamp (iOS isDateInToday).
        val todayStart = CoachDataAccess.startOfDay(System.currentTimeMillis())
        if (CoachDataAccess.startOfDay(entry.timestamp) == todayStart) {
            kotlinx.coroutines.runBlocking { db.mealEntryDao().upsert(applyMealUpdates(updates, entry)) }
            // iOS also appends to ctx.loggedMealIds (tappable card refresh); no Android
            // equivalent yet — see log_meal's note.
            ToolResult("""{"ok":true,"updated":true,"meal_id":"$mealId"}""")
        } else {
            ctx.pendingActions.add(PendingAction(
                kind = PendingActionKind.UPDATE_MEAL_ENTRY,
                activityId = mealId,
                summary = "Update \"${entry.name}\" from ${CoachDataAccess.localDateString(entry.timestamp)}?",
                confirmLabel = "Save changes",
                mealUpdates = updates,
            ))
            ToolResult("""{"ok":true,"needs_confirmation":true,"summary":"Awaiting your confirmation to edit that meal."}""")
        }
    }

    // ── delete_meal_entry ──────────────────────────────────────────────

    /**
     * Write tool (iOS #96): ALWAYS confirms — a deletion is the one meal write that can't be
     * re-derived from the conversation, so it never applies in-turn.
     */
    private fun makeDeleteMealEntry() = CoachToolDef(
        name = "delete_meal_entry",
        publicLabel = "Removing that meal",
        description = "Delete a logged meal. Always returns needs_confirmation and shows a " +
            "Confirm card; the deletion only happens after the user taps Confirm.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "meal_id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "reason" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("meal_id"), JsonPrimitive("reason"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val params = parseArgs(args) ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val mealId = params["meal_id"]?.jsonPrimitive?.contentOrNull
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'meal_id' argument"}""", isError = true)
        val entry = kotlinx.coroutines.runBlocking { db.mealEntryDao().byId(mealId) }
            ?: return@CoachToolDef ToolResult("""{"error":"meal '$mealId' not found"}""", isError = true)
        ctx.pendingActions.add(PendingAction(
            kind = PendingActionKind.DELETE_MEAL_ENTRY,
            activityId = mealId,
            summary = "Delete \"${entry.name}\" (${entry.calories.roundToLong()} kcal) from ${CoachDataAccess.localDateString(entry.timestamp)}?",
            confirmLabel = "Delete",
        ))
        ToolResult("""{"ok":true,"needs_confirmation":true,"summary":"Awaiting your confirmation to delete that meal."}""")
    }

    // ── shared ─────────────────────────────────────────────────────────

    private fun parseArgs(args: String): JsonObject? =
        try { Json { ignoreUnknownKeys = true }.decodeFromString<JsonObject>(args) } catch (_: Exception) { null }

    private fun nullableNumber() = JsonObject(mapOf(
        "type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))),
    ))

    private fun nullableString() = JsonObject(mapOf(
        "type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))),
    ))

    private fun mealTypeEnumArray() = JsonArray(mealTypeRawValues.map { JsonPrimitive(it) })

    // ── pure logic (unit-tested in NutritionToolsTest) ─────────────────
    // The tool bodies need a concrete Room db (no in-memory harness in the repo), so the
    // behavior that can drift — timestamp resolution, the source/confidence raw mapping, the
    // search clamp, the payload shape, and the update application — lives in these pure
    // members, exactly as the private statics live on iOS's NutritionTools.

    /**
     * Ported from resolveTimestamp in NutritionTools.swift. Combines a YYYY-MM-DD date with an
     * optional HH:mm time; an unstated time on a past day lands at noon, today at the current
     * clock time. `now`/`zone` are injectable so the branching is unit-testable.
     */
    internal fun resolveTimestamp(
        date: String,
        time: String?,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val day: LocalDate = CoachDataAccess.parseLocalDate(date)
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (time != null) {
            // iOS splits on ":" and Int-parses; a time that can't be stamped (e.g. 25:99)
            // falls through to the noon/now default instead of erroring.
            val parts = time.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size >= 2 && parts[0] in 0..23 && parts[1] in 0..59) {
                return day.atTime(parts[0], parts[1]).atZone(zone).toInstant().toEpochMilli()
            }
        }
        if (day == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) return now
        return day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** iOS: min(5, max(1, Int(maxResults ?? 5))) — truncate, then clamp to 1...5. */
    internal fun clampSearchLimit(maxResults: Double?): Int =
        minOf(5, maxOf(1, (maxResults?.toInt() ?: 5)))

    /** iOS: the trimmed query must be at least 2 chars, else the tool errors "query too short". */
    internal fun searchQueryError(query: String): String? =
        if (query.trim().length < 2) "query too short" else null

    /**
     * iOS log_meal source mapping: "database" + an OFF product code → off_search (the only
     * coach path to a database-verified row); anything else → llm_estimate. Numbers are
     * grounded or flagged, never silently invented.
     */
    internal fun resolveSourceRaw(source: String?, offProductCode: String?): String =
        if (source == "database" && offProductCode != null) sourceRawOffSearch else sourceRawLlmEstimate

    /**
     * iOS decodeConfidence: "high" → known, "medium" → partial, anything else → unknown.
     * The stored strings are iOS DecodeConfidence's rawValues (known/partial/unknown) —
     * the raw values the iOS entity persists.
     */
    internal fun decodeConfidenceRaw(raw: String?): String = when (raw) {
        "high" -> "known"
        "medium" -> "partial"
        else -> "unknown"
    }

    /**
     * Per-product payload for search_food_database results — mirrors iOS `payload(_)`:
     * code/name/per_100g always; brand/serving/serving_g only when present.
     */
    internal fun foodProductPayload(product: FoodProduct): JsonObject = buildJsonObject {
        put("code", product.code)
        put("name", product.name)
        putJsonObject("per_100g") {
            put("kcal", product.energyKcal100g.roundToLong())
            put("protein_g", product.protein100g)
            put("carbs_g", product.carbs100g)
            put("fat_g", product.fat100g)
        }
        product.brand?.let { put("brand", it) }
        product.servingSizeText?.let { put("serving", it) }
        product.servingQuantityG?.let { put("serving_g", it) }
    }

    /**
     * Ported from NutritionTools.apply in NutritionTools.swift (iOS #96). Pure over the Room
     * entity (copy-based, no DAO): applies the non-null fields, marks the row edited when a
     * number changed on a non-manual row, and bumps updatedAt. Both the today-path of
     * update_meal_entry and the [PendingActionExecutor] confirm path persist the returned copy,
     * so an immediate edit and a confirmed older-edit behave identically.
     */
    internal fun applyMealUpdates(updates: MealUpdates, entry: MealEntryEntity): MealEntryEntity {
        var numbersChanged = false
        var next = entry
        updates.name?.let { next = next.copy(name = it) }
        // iOS guards with MealType(rawValue:) — an unknown type is ignored, not an error.
        updates.mealType?.let { if (it in mealTypeRawValues) next = next.copy(mealTypeRaw = it) }
        updates.calories?.let { next = next.copy(calories = it); numbersChanged = true }
        updates.proteinG?.let { next = next.copy(proteinG = it); numbersChanged = true }
        updates.carbsG?.let { next = next.copy(carbsG = it); numbersChanged = true }
        updates.fatG?.let { next = next.copy(fatG = it); numbersChanged = true }
        updates.notes?.let { next = next.copy(notes = it) }
        // A user-requested correction to a database/estimate row marks it edited.
        if (numbersChanged && next.sourceRaw != sourceRawManual) next = next.copy(userEdited = true)
        return next.copy(updatedAt = System.currentTimeMillis())
    }

    /** iOS CoachDataAccess.localTimeString: "HH:mm" in the (injectable) zone. */
    internal fun localTimeString(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ts).atZone(zone)
        return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }
}
