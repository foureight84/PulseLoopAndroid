package com.pulseloop.coach.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ported from [CoachResponse] in CoachResponse.swift.
 * Structured coach reply matching the web app's CoachResponseBody schema.
 */
@Serializable
data class CoachResponse(
    val responseType: CoachResponseType = CoachResponseType.INSIGHT,
    val title: String = "",
    val summary: String = "",
    val bullets: List<String> = emptyList(),
    val chart: CoachChart? = null,
    val safetyNote: String? = null,
    val dataQualityNote: String? = null,
    val sources: List<CoachSource> = emptyList(),
    val followUpChips: List<String> = emptyList(),
    val actionsTaken: List<String> = emptyList(),
    val confidence: CoachConfidence = CoachConfidence.MEDIUM,
    val cards: List<CoachCard> = emptyList(),
) {
    val plainText: String
        get() = buildString {
            append(summary)
            if (bullets.isNotEmpty()) {
                append("\n\n")
                bullets.forEach { append("• $it\n") }
            }
        }

    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(raw: String?): CoachResponse? {
            if (raw.isNullOrBlank()) return null
            return try { json.decodeFromString<CoachResponse>(raw) } catch (_: Exception) { null }
        }
    }
}

@Serializable
enum class CoachResponseType {
    INSIGHT, INSIGHT_WITH_CHART, QUESTION,
    ACTION_CONFIRMATION, DATA_MISSING, SAFETY_GUIDANCE, ERROR_RECOVERY
}

@Serializable
enum class CoachConfidence { LOW, MEDIUM, HIGH }

@Serializable
data class CoachSource(
    val title: String = "",
    val url: String = "",
    val publisher: String = "",
)

@Serializable
data class CoachCard(
    val kind: String = "",
    val title: String? = null,
    val body: String? = null,
)

/**
 * Ported from [CoachChart] in CoachChart.swift.
 */
@Serializable
data class CoachChart(
    val chartType: String = "line",    // "line" | "bar" | "scatter"
    val title: String = "",
    val xLabel: String = "",
    val yLabel: String = "",
    val points: List<CoachChartPoint> = emptyList(),
)

@Serializable
data class CoachChartPoint(
    val xLabel: String = "",
    val yValue: Double = 0.0,
)
