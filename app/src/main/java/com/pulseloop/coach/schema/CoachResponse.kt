@file:OptIn(ExperimentalSerializationApi::class)

package com.pulseloop.coach.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

/**
 * Ported from [CoachResponse] in CoachResponse.swift.
 * Structured coach reply matching the web app's CoachResponseBody schema.
 *
 * Serial names are the snake_case keys the coach_response JSON schema demands of
 * the model (CoachSchema.schema); the @JsonNames alternates keep any previously
 * stored camelCase payloads decodable.
 */
@Serializable
data class CoachResponse(
    @SerialName("response_type") @JsonNames("responseType")
    val responseType: CoachResponseType = CoachResponseType.INSIGHT,
    val title: String = "",
    val summary: String = "",
    val bullets: List<String> = emptyList(),
    val chart: CoachChart? = null,
    @SerialName("safety_note") @JsonNames("safetyNote")
    val safetyNote: String? = null,
    @SerialName("data_quality_note") @JsonNames("dataQualityNote")
    val dataQualityNote: String? = null,
    val sources: List<CoachSource> = emptyList(),
    @SerialName("follow_up_chips") @JsonNames("followUpChips")
    val followUpChips: List<String> = emptyList(),
    @SerialName("actions_taken") @JsonNames("actionsTaken")
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
        // Case-insensitive enums keep legacy stored payloads (UPPERCASE names) decodable.
        private val json = Json { ignoreUnknownKeys = true; decodeEnumsCaseInsensitive = true }
        fun fromJson(raw: String?): CoachResponse? {
            if (raw.isNullOrBlank()) return null
            return try { json.decodeFromString<CoachResponse>(raw) } catch (_: Exception) { null }
        }
    }
}

@Serializable
enum class CoachResponseType {
    @SerialName("insight") INSIGHT,
    @SerialName("insight_with_chart") INSIGHT_WITH_CHART,
    @SerialName("question") QUESTION,
    @SerialName("action_confirmation") ACTION_CONFIRMATION,
    @SerialName("data_missing") DATA_MISSING,
    @SerialName("safety_guidance") SAFETY_GUIDANCE,
    @SerialName("error_recovery") ERROR_RECOVERY,
}

@Serializable
enum class CoachConfidence {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
}

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
    @SerialName("chart_type") @JsonNames("chartType")
    val chartType: String = "line",    // "line" | "bar" | "scatter"
    val title: String = "",
    @SerialName("x_label") @JsonNames("xLabel")
    val xLabel: String = "",
    @SerialName("y_label") @JsonNames("yLabel")
    val yLabel: String = "",
    val points: List<CoachChartPoint> = emptyList(),
)

@Serializable
data class CoachChartPoint(
    @SerialName("x_label") @JsonNames("xLabel")
    val xLabel: String = "",
    @SerialName("y_value") @JsonNames("yValue")
    val yValue: Double = 0.0,
)
