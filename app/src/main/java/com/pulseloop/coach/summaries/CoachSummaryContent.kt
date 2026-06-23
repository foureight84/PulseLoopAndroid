package com.pulseloop.coach.summaries

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.pulseloop.coach.schema.CoachResponse
import com.pulseloop.coach.schema.CoachResponseType
import kotlinx.serialization.encodeToString

/**
 * Ported from CoachSummaryContent.swift.
 * Generated coach-card content: a headline, a short body, and tappable follow-up chips.
 */
@Serializable
data class CoachSummaryContent(
    val title: String,
    val body: String,
    val chips: List<String> = emptyList(),
) {
    /** As a CoachResponse for seeding the chat thread (chips → follow-ups). */
    fun asCoachResponse(): CoachResponse = CoachResponse(
        responseType = CoachResponseType.INSIGHT,
        title = title,
        summary = body,
        followUpChips = chips,
    )

    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String?): CoachSummaryContent? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            // Try direct parse
            return try {
                json.decodeFromString<CoachSummaryContent>(trimmed)
            } catch (_: Exception) {
                // Extract outermost JSON object
                val start = trimmed.indexOf('{')
                val end = trimmed.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    try { json.decodeFromString<CoachSummaryContent>(trimmed.substring(start, end + 1)) }
                    catch (_: Exception) { null }
                } else null
            }
        }
    }
}

/**
 * Ported from CoachSummaryKind in CoachSummary.swift.
 */
enum class CoachSummaryKind(val rawValue: String) {
    TODAY("today"),
    SLEEP_DAY("sleep_day"),
    SLEEP_RANGE_DAY("sleep_range_day"),
    SLEEP_RANGE_WEEK("sleep_range_week"),
    SLEEP_RANGE_MONTH("sleep_range_month"),
    SLEEP_RANGE_YEAR("sleep_range_year");

    val conversationTitle: String get() = when (this) {
        TODAY -> "Today recap"
        SLEEP_DAY -> "Sleep recap"
        else -> "Sleep trend"
    }

    companion object {
        fun sleepRange(range: com.pulseloop.service.SleepRangeKey): CoachSummaryKind = when (range) {
            com.pulseloop.service.SleepRangeKey.DAY -> SLEEP_RANGE_DAY
            com.pulseloop.service.SleepRangeKey.WEEK -> SLEEP_RANGE_WEEK
            com.pulseloop.service.SleepRangeKey.MONTH -> SLEEP_RANGE_MONTH
            com.pulseloop.service.SleepRangeKey.YEAR -> SLEEP_RANGE_YEAR
        }
    }
}

/**
 * Ported from CoachSummarySchema in CoachSummaryContent.swift.
 */
object CoachSummarySchema {
    val name = "coach_summary"

    val jsonSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "maxLength" to 60),
            "body" to mapOf("type" to "string", "maxLength" to 320),
            "chips" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string", "maxLength" to 40),
                "maxItems" to 3,
            ),
        ),
        "required" to listOf("title", "body", "chips"),
        "additionalProperties" to false,
    )
}
