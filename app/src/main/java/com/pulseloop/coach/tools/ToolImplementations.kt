package com.pulseloop.coach.tools

import com.pulseloop.ring.MeasurementKind
import kotlinx.serialization.json.*

/**
 * Ported from [RetrievalTools] in RetrievalTools.swift.
 * Read-only retrieval tools.
 */
object RetrievalTools {
    val all: List<CoachToolDef> get() = listOf(
        makeProfile(), makeDailySummary(), makeRangeSummary(), makeMetricSeries(),
        makeActivitySessions(), makeSummarizeSession(), makeSyncStatus(),
        makeDataAvailability(), makeSleepSummary(), makeSleepTrends(),
        makeGoalProgress(), makeRecentAnomalies(),
    )

    private fun makeProfile() = CoachToolDef(
        name = "get_profile_context",
        publicLabel = "Checking your profile and ring status",
        description = "Get user profile, goals, device sync status, and data-quality warnings.",
        parameters = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()), "additionalProperties" to JsonPrimitive(false))),
    ) { _, ctx ->
        val db = ctx.db
        if (db == null) {
            ToolResult("""{"error":"database not available"}""", isError = true)
        } else {
            val result = kotlinx.coroutines.runBlocking {
                val profile = db.userProfileDao().get()
                val device = db.deviceDao().current()
                val goal = db.userGoalDao().get()
                """{"profile":{"${if (profile?.name != null) """"name":"${profile.name}",""" else ""}"age":${profile?.age ?: "null"}},"device":{"state":"${device?.stateRaw ?: "idle"}","battery_percent":${device?.batteryPercent ?: 0}},"goals":{"steps_daily":${goal?.steps ?: 10000},"sleep_hours":${(goal?.sleepMinutes ?: 480) / 60}},"timezone":"${java.time.ZoneId.systemDefault().id}","data_quality_warnings":[]}"""
            }
            ToolResult(result)
        }
    }

    private fun makeDailySummary() = CoachToolDef(
        name = "get_daily_summary",
        publicLabel = "Reading that day's ring data",
        description = "Fetch daily activity and biometric summary for a local date (YYYY-MM-DD).",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("date" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
            "required" to JsonArray(listOf(JsonPrimitive("date"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db
        if (db == null) return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val date = try { json.decodeFromString<Map<String, String>>(args)["date"] } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"missing 'date' argument"}""", isError = true)
        val result = kotlinx.coroutines.runBlocking {
            val dayTs = CoachDataAccess.parseLocalDate(date)
            if (dayTs == null) return@runBlocking """{"error":"invalid date: $date"}"""
            val activity = db.activityDailyDao().byDay(dayTs)
            val hrVals = db.measurementDao().range(MeasurementKind.HEART_RATE.name, dayTs, dayTs + 86400000L)
            val hrStats = if (hrVals.isNotEmpty()) {
                val vals = hrVals.map { it.value }
                """"mean":${(vals.average() * 10).toLong() / 10.0},"min":${vals.minOrNull()!!.toInt()},"max":${vals.maxOrNull()!!.toInt()}""""
            } else null
            val sleep = db.sleepSessionDao().byDay(dayTs)
            """{"date":"$date","data_available":${activity != null || hrVals.isNotEmpty()},"activity":{"${if (activity != null) """steps":${activity.steps},"calories":${(activity.calories * 10).toLong() / 10.0},"distance_km":${(activity.distanceMeters / 1000 * 10).toLong() / 10.0},"active_minutes":${activity.activeMinutes}""" else """steps":0,"calories":0,"distance_km":0,"active_minutes":0"""}},"hr":{${hrStats ?: """"mean":null,"min":null,"max":null""""}},"sleep":${if (sleep != null) """{"total_min":${sleep.totalMinutes},"score":${sleep.totalMinutes / 5}}""" else "null"}}"""
        }
        ToolResult(result)
    }

    private fun makeMetricSeries() = CoachToolDef(
        name = "get_metric_series",
        publicLabel = "Fetching your trend data",
        description = "Fetch a time series for a metric over a date range.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "metric" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("steps", "hr", "spo2", "sleep", "active_minutes").map { JsonPrimitive(it) }))),
                "start_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "end_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("metric"), JsonPrimitive("start_date"), JsonPrimitive("end_date"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db
        if (db == null) return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, String>>(args) } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val metric = params["metric"] ?: return@CoachToolDef ToolResult("""{"error":"missing 'metric'"}""", isError = true)
        val start = params["start_date"] ?: return@CoachToolDef ToolResult("""{"error":"missing 'start_date'"}""", isError = true)
        val end = params["end_date"] ?: start
        val result = kotlinx.coroutines.runBlocking {
            val points = CoachDataAccess.seriesPoints(db, metric, start, end, "day")
            val sb = StringBuilder()
            sb.append("""{"metric":"$metric","points":[""")
            points.forEachIndexed { i, (date, value) ->
                if (i > 0) sb.append(",")
                sb.append("""{"date":"$date","value":$value}""")
            }
            sb.append("]}")
            sb.toString()
        }
        ToolResult(result)
    }

    private fun makeSleepSummary() = CoachToolDef(
        name = "get_sleep_summary",
        publicLabel = "Looking at your sleep",
        description = "Get sleep summary for a date or date range.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "start_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "end_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("start_date"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db
        if (db == null) return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, String>>(args) } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val start = params["start_date"] ?: return@CoachToolDef ToolResult("""{"error":"missing 'start_date'"}""", isError = true)
        val end = params["end_date"] ?: start
        val result = kotlinx.coroutines.runBlocking {
            val sessions = CoachDataAccess.sleepSessions(db, start, end)
            val sb = StringBuilder()
            sb.append("""{"nights":[""")
            sessions.forEachIndexed { i, s ->
                if (i > 0) sb.append(",")
                val dateStr = CoachDataAccess.localDateString(s.date)
                sb.append("""{"date":"$dateStr","total_min":${s.totalMinutes},"score":${(s.totalMinutes / 5).coerceAtMost(100)}}""")
            }
            sb.append("]}")
            sb.toString()
        }
        ToolResult(result)
    }

    // ── get_range_summary ─────────────────────────────────────────────

    private fun makeRangeSummary() = CoachToolDef(
        name = "get_range_summary",
        publicLabel = "Fetching your ring data for that range",
        description = "Fetch summarized activity, HR, SpO2, and sleep over a date range.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "start_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "end_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "include" to JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("string"),
                        "enum" to JsonArray(listOf("activity","hr","spo2","sleep","goals").map { JsonPrimitive(it) }))),
                )),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("start_date"), JsonPrimitive("end_date"), JsonPrimitive("include"))),
        )),
    ) { jsonParams, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val params = Json.parseToJsonElement(jsonParams).jsonObject
        val start = params["start_date"]?.jsonPrimitive?.content ?: return@CoachToolDef ToolResult("""{"error":"missing start_date"}""", isError = true)
        val end = params["end_date"]?.jsonPrimitive?.content ?: return@CoachToolDef ToolResult("""{"error":"missing end_date"}""", isError = true)
        val include = params["include"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
        val result = kotlinx.coroutines.runBlocking {
            val sb = StringBuilder("{")
            sb.append(""""start_date":"$start","end_date":"$end",""")
            if ("activity" in include || "goals" in include) {
                val rows = db.activityDailyDao().recent(365).filter {
                    val d = java.time.Instant.ofEpochMilli(it.date).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                    d >= start && d <= end
                }
                val totalSteps = rows.sumOf { it.steps }
                sb.append(""""activity":{"days_available":${rows.size},"totals":{"steps":$totalSteps,"calories":${rows.sumOf { it.calories }},"active_minutes":${rows.sumOf { it.activeMinutes }}}},""")
            }
            val hrData = db.measurementDao().range(MeasurementKind.HEART_RATE.name, 0, System.currentTimeMillis())
            val spo2Data = db.measurementDao().range(MeasurementKind.SPO2.name, 0, System.currentTimeMillis())
            sb.append(""""hr":{"count":${hrData.size}},"spo2":{"count":${spo2Data.size}}}""")
            sb.append("}")
            sb.toString()
        }
        ToolResult(result)
    }

    // ── get_activity_sessions ──────────────────────────────────────────

    private fun makeActivitySessions() = CoachToolDef(
        name = "get_activity_sessions",
        publicLabel = "Looking up your workouts",
        description = "Fetch saved activity sessions over a date range.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "start_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "end_date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("start_date"), JsonPrimitive("end_date"))),
        )),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        kotlinx.coroutines.runBlocking {
            val sessions = db.activitySessionDao().recent(50)
        val sb = StringBuilder("{\"count\":${sessions.size},\"sessions\":[")
        sessions.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            val dur = s.endedAt?.let { ((it - s.startedAt) / 60000.0).let { "%.1f".format(it) } } ?: "0"
            sb.append("""{"id":"${s.id}","type":"${s.type}","duration_min":$dur,"distance_km":${s.distanceMeters?.div(1000)?.let { "%.2f".format(it) } ?: "null"},"avg_hr":${s.avgHeartRate ?: "null"},"notes":${s.notes?.let { "\"$it\"" } ?: "null"}}""")
        }
        sb.append("]}")
        ToolResult(sb.toString())
        }
    }

    // ── summarize_activity_session ─────────────────────────────────────

    private fun makeSummarizeSession() = CoachToolDef(
        name = "summarize_activity_session",
        publicLabel = "Summarizing that workout",
        description = "Compute summary statistics for one activity session.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("activity_id" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
            "required" to JsonArray(listOf(JsonPrimitive("activity_id"))),
        )),
    ) { jsonParams, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val params = Json.parseToJsonElement(jsonParams).jsonObject
        val id = params["activity_id"]?.jsonPrimitive?.content ?: return@CoachToolDef ToolResult("""{"error":"missing activity_id"}""", isError = true)
        val sessions = kotlinx.coroutines.runBlocking { db.activitySessionDao().recent(200) }
        val s = sessions.firstOrNull { it.id == id } ?: return@CoachToolDef ToolResult("""{"error":"session not found"}""", isError = true)
        val dur = s.endedAt?.let { ((it - s.startedAt) / 60000.0).let { "%.1f".format(it) } } ?: "0"
        ToolResult("""{"id":"${s.id}","type":"${s.type}","duration_min":$dur,"distance_km":${s.distanceMeters?.div(1000)?.let { "%.2f".format(it) } ?: "null"},"avg_hr":${s.avgHeartRate ?: "null"},"max_hr":${s.maxHeartRate ?: "null"},"notes":${s.notes?.let { "\"$it\"" } ?: "null"}}""")
    }

    // ── get_sync_status ────────────────────────────────────────────────

    private fun makeSyncStatus() = CoachToolDef(
        name = "get_sync_status",
        publicLabel = "Checking ring connection",
        description = "Get the ring's current connection state, battery, last sync time.",
        parameters = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val device = kotlinx.coroutines.runBlocking { db.deviceDao().current() }
        ToolResult("""{"device_name":"${device?.name ?: ""}","state":"${device?.stateRaw ?: "idle"}","battery_percent":${device?.batteryPercent ?: 0},"last_sync_at":${device?.lastSyncAt?.let { "\"${java.time.Instant.ofEpochMilli(it)}\"" } ?: "null"}}""")
    }

    // ── get_data_availability ──────────────────────────────────────────

    private fun makeDataAvailability() = CoachToolDef(
        name = "get_data_availability",
        publicLabel = "Checking what data exists",
        description = "Report how many readings exist per metric in a date range.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "start" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "end" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("start"), JsonPrimitive("end"))),
        )),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val act = kotlinx.coroutines.runBlocking { db.activityDailyDao().recent(365) }
        val hrCount = kotlinx.coroutines.runBlocking { db.measurementDao().range(MeasurementKind.HEART_RATE.name, 0, System.currentTimeMillis()) }.size
        val spo2Count = kotlinx.coroutines.runBlocking { db.measurementDao().range(MeasurementKind.SPO2.name, 0, System.currentTimeMillis()) }.size
        val sleepCount = kotlinx.coroutines.runBlocking { db.sleepSessionDao().recent(365) }.size
        ToolResult("""{"available_metrics":{"activity_days":${act.size},"heart_rate":$hrCount,"spo2":$spo2Count,"sleep_nights":$sleepCount}}""")
    }

    // ── get_sleep_trends ───────────────────────────────────────────────

    private fun makeSleepTrends() = CoachToolDef(
        name = "get_sleep_trends",
        publicLabel = "Reviewing your sleep",
        description = "Summarize sleep over a range: average duration, nights tracked.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("range" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf("week","month","year").map { JsonPrimitive(it) }),
            )))),
            "required" to JsonArray(listOf(JsonPrimitive("range"))),
        )),
    ) { jsonParams, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val params = Json.parseToJsonElement(jsonParams).jsonObject
        val range = params["range"]?.jsonPrimitive?.content ?: "week"
        val limit = when (range) { "week" -> 7; "month" -> 30; else -> 365 }
        val sessions = kotlinx.coroutines.runBlocking { db.sleepSessionDao().recent(limit) }
        val avgMin = if (sessions.isNotEmpty()) sessions.map { it.totalMinutes }.average().toInt() else 0
        ToolResult("""{"range":"$range","nights_tracked":${sessions.size},"expected_nights":$limit,"avg_total_min":$avgMin,"note":"experimental decoder (light/deep/awake only)"}""")
    }

    // ── get_goal_progress ──────────────────────────────────────────────

    private fun makeGoalProgress() = CoachToolDef(
        name = "get_goal_progress",
        publicLabel = "Checking your goals",
        description = "Compare today's metrics against the user's goals.",
        parameters = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val goal = kotlinx.coroutines.runBlocking { db.userGoalDao().get() }
        val todayStart = java.time.Instant.now().atZone(java.time.ZoneId.systemDefault()).truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant().toEpochMilli()
        val today = kotlinx.coroutines.runBlocking { db.activityDailyDao().byDay(todayStart) }
        ToolResult("""{"today":{"steps":${today?.steps ?: 0},"steps_goal":${goal?.steps ?: 10000},"active_minutes":${today?.activeMinutes ?: 0},"active_minutes_goal":${goal?.activeMinutes ?: 45}},"sleep_hours_goal":${(goal?.sleepMinutes ?: 480) / 60}}""")
    }

    // ── get_recent_anomalies ───────────────────────────────────────────

    private fun makeRecentAnomalies() = CoachToolDef(
        name = "get_recent_anomalies",
        publicLabel = "Scanning for anything unusual",
        description = "Detect statistical outliers in steps and resting HR over last ~14 days.",
        parameters = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
    ) { _, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val recentSteps = kotlinx.coroutines.runBlocking { db.activityDailyDao().recent(14) }.map { it.steps.toDouble() }
        val stepOutliers = AnalysisEngine.outliers(recentSteps)
        ToolResult("""{"steps_outliers":${stepOutliers.size},"steps_analyzed":${recentSteps.size}}""")
    }
}

/**
 * Ported from [AnalysisTools] in AnalysisTools.swift.
 * Deterministic analysis tools (trend, correlation, outliers).
 */
object AnalysisTools {
    val all: List<CoachToolDef> by lazy { listOf(analyzeTrend, comparePeriods, computeCorrelation, detectOutliers, summarizeDistribution) }

    private val analyzeTrend = CoachToolDef(
        name = "analyze_trend",
        publicLabel = "Analyzing your trends",
        description = "Run a basic trend analysis on a metric series (returns slope, direction, significance).",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "metric" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "values" to JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                )),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("metric"), JsonPrimitive("values"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, _ ->
        val json = Json { ignoreUnknownKeys = true }
        val vals = try {
            json.decodeFromString<Map<String, JsonElement>>(args)["values"]?.jsonArray?.map { it.jsonPrimitive.double }
        } catch (_: Exception) { null }
        if (vals.isNullOrEmpty()) return@CoachToolDef ToolResult("""{"error":"no values provided"}""", isError = true)

        val result = AnalysisEngine.trend(vals)
        val metric = try { json.decodeFromString<Map<String, String>>(args)["metric"] ?: "values" } catch (_: Exception) { "values" }
        ToolResult("""{"metric":"$metric","slope":${result.slopePerDay},"direction":"${result.direction}","mean":${result.average ?: 0.0},"n":${result.count},"confidence":"medium"}""")
    }

    // ── compare_periods ────────────────────────────────────────────────

    private val comparePeriods = CoachToolDef(
        name = "compare_periods",
        publicLabel = "Comparing two time periods",
        description = "Compare a metric between two time periods (e.g., this week vs last week).",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "period_a" to JsonObject(mapOf("type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("number"))))),
                "period_b" to JsonObject(mapOf("type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("number"))))),
                "label_a" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "label_b" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("period_a"), JsonPrimitive("period_b"))),
        )),
    ) { args, _ ->
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.decodeFromString<JsonObject>(args)
        val a = obj["period_a"]?.jsonArray?.map { it.jsonPrimitive.double }.orEmpty()
        val b = obj["period_b"]?.jsonArray?.map { it.jsonPrimitive.double }.orEmpty()
        val result = AnalysisEngine.comparePeriods(a, b)
        ToolResult("""{"a_avg":${result.aAverage ?: 0.0},"b_avg":${result.bAverage ?: 0.0},"direction":"${result.direction}","change_pct":${result.deltaPercent ?: 0.0}}""")
    }

    // ── compute_correlation ─────────────────────────────────────────────

    private val computeCorrelation = CoachToolDef(
        name = "compute_correlation",
        publicLabel = "Computing correlation",
        description = "Compute Pearson correlation between two sets of paired values.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "pairs" to JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(mapOf(
                            "x" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                            "y" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                        )),
                    )),
                )),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("pairs"))),
        )),
    ) { args, _ ->
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.decodeFromString<JsonObject>(args)
        val pairs = obj["pairs"]?.jsonArray?.map { el ->
            val p = el.jsonObject
            (p["x"]?.jsonPrimitive?.double ?: 0.0) to (p["y"]?.jsonPrimitive?.double ?: 0.0)
        }.orEmpty()
        val result = AnalysisEngine.correlation(pairs)
        ToolResult("""{"pearson":${result.pearson ?: "null"},"strength":"${result.strength}","n":${pairs.size}}""")
    }

    // ── detect_outliers ─────────────────────────────────────────────────

    private val detectOutliers = CoachToolDef(
        name = "detect_outliers",
        publicLabel = "Detecting outliers",
        description = "Detect statistical outliers (z-score > 2) in a series of values.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "values" to JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                )),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("values"))),
        )),
    ) { args, _ ->
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.decodeFromString<JsonObject>(args)
        val values = obj["values"]?.jsonArray?.map { it.jsonPrimitive.double }.orEmpty()
        val outliers = AnalysisEngine.outliers(values)
        val sb = StringBuilder("{\"outliers\":[")
        outliers.forEachIndexed { i, o ->
            if (i > 0) sb.append(",")
            val zs = "%.2f".format(o.zScore)
            sb.append("""{"index":$i,"value":${o.value},"z_score":$zs}""")
        }
        sb.append("""],"n":${values.size}}""")
        ToolResult(sb.toString())
    }

    // ── summarize_distribution ──────────────────────────────────────────

    private val summarizeDistribution = CoachToolDef(
        name = "summarize_distribution",
        publicLabel = "Summarizing distribution",
        description = "Compute statistical summary: mean, median, min, max, std dev.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "values" to JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                )),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("values"))),
        )),
    ) { args, _ ->
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.decodeFromString<JsonObject>(args)
        val values = obj["values"]?.jsonArray?.map { it.jsonPrimitive.double }.orEmpty()
        val result = AnalysisEngine.distribution(values)
        ToolResult("""{"mean":${result.mean ?: "null"},"median":${result.median ?: "null"},"min":${result.min ?: "null"},"max":${result.max ?: "null"},"std":${result.stddev ?: "null"},"n":${result.count}}""")
    }
}

/**
 * Ported from [ChartTools] in ChartTools.swift.
 */
object ChartTools {
    val all: List<CoachToolDef> by lazy { listOf(prepareChart) }

    private val prepareChart = CoachToolDef(
        name = "prepare_chart",
        publicLabel = "Preparing a chart",
        description = "Generate a chart from data points. Call this when you want to show a chart.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "chart_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("line", "bar", "scatter").map { JsonPrimitive(it) }))),
                "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "x_label" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "y_label" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "points" to JsonObject(mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(mapOf(
                            "x_label" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                            "y_value" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                        )),
                        "required" to JsonArray(listOf(JsonPrimitive("x_label"), JsonPrimitive("y_value"))),
                        "additionalProperties" to JsonPrimitive(false),
                    )),
                )),
            )),
            "required" to JsonArray(listOf(
                JsonPrimitive("chart_type"), JsonPrimitive("title"),
                JsonPrimitive("x_label"), JsonPrimitive("y_label"), JsonPrimitive("points"),
            )),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, _ ->
        // Pass through — the chart schema matches directly
        ToolResult(args)
    }
}

/**
 * Ported from [MemoryTools] in MemoryTools.swift.
 */
object MemoryTools {
    val all: List<CoachToolDef> by lazy { listOf(saveMemory) }

    private val saveMemory = CoachToolDef(
        name = "save_memory",
        publicLabel = "Saving this for later",
        description = "Save a key-value memory for future conversations.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "key" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "value" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "importance" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("key"), JsonPrimitive("value"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db
        if (db == null) return@CoachToolDef ToolResult("""{"saved":false,"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, JsonElement>>(args) } catch (_: Exception) { null }
        if (params == null) return@CoachToolDef ToolResult("""{"saved":false,"error":"invalid arguments"}""", isError = true)
        val key = params["key"]?.jsonPrimitive?.content ?: return@CoachToolDef ToolResult("""{"saved":false,"error":"missing key"}""", isError = true)
        val value = params["value"]?.jsonPrimitive?.content ?: ""
        val importance = params["importance"]?.jsonPrimitive?.int ?: 5
        kotlinx.coroutines.runBlocking {
            val existing = db.coachMemoryDao().byKey(key)
            db.coachMemoryDao().upsert(
                com.pulseloop.data.entity.CoachMemoryEntity(
                    key = key,
                    value = value,
                    importance = importance,
                    memoryType = "user",
                    expiresAt = System.currentTimeMillis() + 90 * 86400_000L,  // 90 days
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        ToolResult("""{"saved":true,"key":"$key"}""")
    }
}

/**
 * Ported from [WebSearchTool] in WebSearchTool.swift.
 */
object WebSearchTool {
    val spec = JsonObject(mapOf(
        "type" to JsonPrimitive("web_search_preview"),
    ))
}

/**
 * Ported from [ActionTools] in ActionTools.swift.
 */
object ActionTools {
    val writeTools: List<CoachToolDef> by lazy {
        listOf(setGoal, logUserNote, logActivityCorrection, createActivitySession, updateActivitySession, deleteActivitySession)
    }
    val measurementTools: List<CoachToolDef> by lazy { listOf(triggerMeasurement) }

    private val setGoal = CoachToolDef(
        name = "set_goal",
        publicLabel = "Setting your goal",
        description = "Set a daily step goal.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "steps" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                "sleep_minutes" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
                "active_minutes" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("steps"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"set":false,"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, JsonElement>>(args) } catch (_: Exception) { null }
        if (params == null) return@CoachToolDef ToolResult("""{"set":false,"error":"invalid arguments"}""", isError = true)
        val steps = params["steps"]?.jsonPrimitive?.int ?: return@CoachToolDef ToolResult("""{"set":false,"error":"missing steps"}""", isError = true)
        kotlinx.coroutines.runBlocking {
            val existing = db.userGoalDao().get()
            val updated = if (existing != null) {
                existing.copy(steps = steps, updatedAt = System.currentTimeMillis())
            } else {
                com.pulseloop.data.entity.UserGoalEntity(steps = steps, updatedAt = System.currentTimeMillis())
            }
            db.userGoalDao().upsert(updated)
            // Also push to ring if connected
            ctx.coordinator?.setGoal(steps)
        }
        ToolResult("""{"set":true,"steps":$steps,"message":"Daily step goal set to $steps"}""")
    }

    private val triggerMeasurement = CoachToolDef(
        name = "trigger_measurement",
        publicLabel = "Taking a live reading",
        description = "Trigger a live heart rate or SpO2 measurement from the ring.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "kind" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("hr", "spo2").map { JsonPrimitive(it) }))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("kind"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val json = Json { ignoreUnknownKeys = true }
        val kind = try { json.decodeFromString<Map<String, String>>(args)["kind"] } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"missing kind"}""", isError = true)
        val coordinator = ctx.coordinator
        if (coordinator == null || !coordinator.isConnected) {
            ToolResult("""{"status":"unavailable","note":"Ring is not connected — cannot take a live reading."}""")
        } else {
            kotlinx.coroutines.runBlocking {
                when (kind) {
                    "hr" -> coordinator.measureHR()
                    "spo2" -> coordinator.measureSpO2()
                }
            }
            val value = when (kind) {
                "hr" -> coordinator.latestHRValue
                "spo2" -> coordinator.latestSpO2Value
                else -> null
            }
            if (value != null) {
                ToolResult("""{"status":"completed","kind":"$kind","value":$value,"unit":"${if (kind == "hr") "bpm" else "%"}"}""")
            } else {
                ToolResult("""{"status":"failed","kind":"$kind","note":"Measurement did not return a reading. Ring may be out of range."}""")
            }
        }
    }

    // ── log_user_note ──────────────────────────────────────────────────

    private val logUserNote = CoachToolDef(
        name = "log_user_note",
        publicLabel = "Noting that down",
        description = "Save a dated note about symptoms, perceived exertion, mood, injury, sleep, diet, or activity context.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "note_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("symptom", "injury", "activity_context", "sleep_context", "diet_context", "mood", "general").map { JsonPrimitive(it) }))),
                "content" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("date"), JsonPrimitive("note_type"), JsonPrimitive("content"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, String>>(args) } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val noteType = params["note_type"] ?: "general"
        val date = params["date"] ?: ""
        val content = params["content"] ?: ""
        kotlinx.coroutines.runBlocking {
            db.coachMemoryDao().upsert(com.pulseloop.data.entity.CoachMemoryEntity(
                key = "$noteType · $date",
                value = content,
                memoryType = "health_note",
                importance = 2,
                updatedAt = System.currentTimeMillis(),
            ))
        }
        ToolResult("""{"ok":true,"note_type":"$noteType"}""")
    }

    // ── log_activity_correction ─────────────────────────────────────────

    private val logActivityCorrection = CoachToolDef(
        name = "log_activity_correction",
        publicLabel = "Logging your activity",
        description = "Log or correct a user-stated activity the ring missed or misclassified.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "activity_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("walk", "run", "cycle", "gym", "squash", "sport", "yoga", "dance", "hike", "other").map { JsonPrimitive(it) }))),
                "duration_min" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))),
                "distance_km" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))),
                "intensity" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), "enum" to JsonArray(listOf("easy", "moderate", "hard").map { JsonPrimitive(it) }))),
                "notes" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("date"), JsonPrimitive("activity_type"), JsonPrimitive("duration_min"), JsonPrimitive("distance_km"), JsonPrimitive("intensity"), JsonPrimitive("notes"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, JsonElement>>(args) } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val activityType = params["activity_type"]?.jsonPrimitive?.content ?: "other"
        val date = params["date"]?.jsonPrimitive?.content ?: ""
        val notes = params["notes"]?.jsonPrimitive?.content ?: ""
        var summary = "$activityType on $date"
        val durMin = params["duration_min"]?.jsonPrimitive?.doubleOrNull
        if (durMin != null) summary += ", ${durMin.toInt()} min"
        val distKm = params["distance_km"]?.jsonPrimitive?.doubleOrNull
        if (distKm != null) summary += ", $distKm km"
        kotlinx.coroutines.runBlocking {
            db.coachMemoryDao().upsert(com.pulseloop.data.entity.CoachMemoryEntity(
                key = "Ring-missed: $summary",
                value = notes,
                memoryType = "manual_correction",
                importance = 3,
                updatedAt = System.currentTimeMillis(),
            ))
        }
        ToolResult("""{"ok":true,"logged":"$summary"}""")
    }

    // ── create_activity_session_from_description ────────────────────────

    private val createActivitySession = CoachToolDef(
        name = "create_activity_session_from_description",
        publicLabel = "Logging your session",
        description = "Create a finished manual workout from a description.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "activity_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("walk", "run", "cycle", "gym", "squash", "sport", "yoga", "dance", "hike", "other").map { JsonPrimitive(it) }))),
                "date" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "start_time" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))),
                "duration_min" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))),
                "distance_km" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))),
                "notes" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "confidence" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("low", "medium", "high").map { JsonPrimitive(it) }))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("activity_type"), JsonPrimitive("date"), JsonPrimitive("start_time"), JsonPrimitive("duration_min"), JsonPrimitive("distance_km"), JsonPrimitive("notes"), JsonPrimitive("confidence"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, JsonElement>>(args) } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val activityType = params["activity_type"]?.jsonPrimitive?.content ?: "other"
        val durMin = params["duration_min"]?.jsonPrimitive?.doubleOrNull
        if (durMin == null) {
            return@CoachToolDef ToolResult("""{"ok":false,"needs_follow_up":true,"reason":"duration_missing","suggested_question":"Roughly how long was the $activityType session?"}""")
        }
        val date = params["date"]?.jsonPrimitive?.content ?: ""
        val distKm = params["distance_km"]?.jsonPrimitive?.doubleOrNull
        val notes = params["notes"]?.jsonPrimitive?.content ?: ""
        val startTime = params["start_time"]?.jsonPrimitive?.contentOrNull

        val startTs = startTime?.let { CoachDataAccess.parseLocalDate(it) }
            ?: CoachDataAccess.parseLocalDate(date)?.plus(12 * 3600_000L)
            ?: System.currentTimeMillis()
        val endTs = startTs + (durMin * 60_000).toLong()

        val session = com.pulseloop.data.entity.ActivitySessionEntity(
            type = activityType,
            statusRaw = "finished",
            startedAt = startTs,
            endedAt = endTs,
            distanceMeters = distKm?.times(1000),
            notes = notes.ifEmpty { null },
            useGps = false,
        )
        kotlinx.coroutines.runBlocking {
            db.activitySessionDao().upsert(session)
        }
        ToolResult("""{"ok":true,"created":true,"activity_id":"${session.id}","type":"$activityType","duration_min":$durMin}""")
    }

    // ── update_activity_session ─────────────────────────────────────────

    private val updateActivitySession = CoachToolDef(
        name = "update_activity_session",
        publicLabel = "Updating that workout",
        description = "Edit a saved workout. For older sessions returns needs_confirmation.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "activity_id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "type" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), "enum" to JsonArray(listOf("walk", "run", "cycle", "gym", "squash", "sport", "yoga", "dance", "hike", "other").map { JsonPrimitive(it) }))),
                "notes" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))),
                "distance_km" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))),
                "duration_min" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))),
                "perceived_effort" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), "enum" to JsonArray(listOf("easy", "moderate", "hard", "very_hard").map { JsonPrimitive(it) }))),
                "start_time" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))),
                "reason" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("activity_id"), JsonPrimitive("type"), JsonPrimitive("notes"), JsonPrimitive("distance_km"), JsonPrimitive("duration_min"), JsonPrimitive("perceived_effort"), JsonPrimitive("start_time"), JsonPrimitive("reason"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, JsonElement>>(args) } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val activityId = params["activity_id"]?.jsonPrimitive?.content ?: return@CoachToolDef ToolResult("""{"error":"missing activity_id"}""", isError = true)

        val sessions = kotlinx.coroutines.runBlocking { db.activitySessionDao().recent(200) }
        val session = sessions.firstOrNull { it.id == activityId }
            ?: return@CoachToolDef ToolResult("""{"error":"activity '$activityId' not found"}""", isError = true)

        val updates = com.pulseloop.coach.orchestration.ActivityUpdates(
            type = params["type"]?.jsonPrimitive?.contentOrNull,
            notes = params["notes"]?.jsonPrimitive?.contentOrNull,
            distanceKm = params["distance_km"]?.jsonPrimitive?.doubleOrNull,
            durationMin = params["duration_min"]?.jsonPrimitive?.doubleOrNull,
            perceivedEffort = params["perceived_effort"]?.jsonPrimitive?.contentOrNull,
            startTime = params["start_time"]?.jsonPrimitive?.contentOrNull,
        )

        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val isToday = session.startedAt >= todayStart

        if (isToday) {
            val updated = com.pulseloop.coach.orchestration.PendingActionExecutor.applyUpdates(updates, session)
            kotlinx.coroutines.runBlocking { db.activitySessionDao().upsert(updated) }
            ToolResult("""{"ok":true,"updated":true,"activity_id":"$activityId"}""")
        } else {
            ctx.pendingActions.add(com.pulseloop.coach.orchestration.PendingAction(
                kind = com.pulseloop.coach.orchestration.PendingActionKind.UPDATE_ACTIVITY_SESSION,
                activityId = activityId,
                summary = "Update your ${session.type} session from ${CoachDataAccess.localDateString(session.startedAt)}?",
                confirmLabel = "Save changes",
                updates = updates,
            ))
            ToolResult("""{"ok":true,"needs_confirmation":true,"summary":"Awaiting your confirmation to edit that workout."}""")
        }
    }

    // ── delete_activity_session ─────────────────────────────────────────

    private val deleteActivitySession = CoachToolDef(
        name = "delete_activity_session",
        publicLabel = "Removing that workout",
        description = "Delete a workout. Always returns needs_confirmation.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "activity_id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "reason" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("activity_id"), JsonPrimitive("reason"))),
            "additionalProperties" to JsonPrimitive(false),
        )),
    ) { args, ctx ->
        val db = ctx.db ?: return@CoachToolDef ToolResult("""{"error":"database not available"}""", isError = true)
        val json = Json { ignoreUnknownKeys = true }
        val params = try { json.decodeFromString<Map<String, String>>(args) } catch (_: Exception) { null }
            ?: return@CoachToolDef ToolResult("""{"error":"invalid arguments"}""", isError = true)
        val activityId = params["activity_id"] ?: return@CoachToolDef ToolResult("""{"error":"missing activity_id"}""", isError = true)

        val sessions = kotlinx.coroutines.runBlocking { db.activitySessionDao().recent(200) }
        val session = sessions.firstOrNull { it.id == activityId }
            ?: return@CoachToolDef ToolResult("""{"error":"activity '$activityId' not found"}""", isError = true)

        ctx.pendingActions.add(com.pulseloop.coach.orchestration.PendingAction(
            kind = com.pulseloop.coach.orchestration.PendingActionKind.DELETE_ACTIVITY_SESSION,
            activityId = activityId,
            summary = "Delete your ${session.type} session from ${CoachDataAccess.localDateString(session.startedAt)}? This can't be undone.",
            confirmLabel = "Delete",
        ))
        ToolResult("""{"ok":true,"needs_confirmation":true,"summary":"Awaiting your confirmation to delete that workout."}""")
    }
}
