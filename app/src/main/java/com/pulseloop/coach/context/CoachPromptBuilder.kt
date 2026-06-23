package com.pulseloop.coach.context

/**
 * Ported from [CoachPromptBuilder] in CoachPromptBuilder.swift.
 * System + developer prompts for the coach.
 */
object CoachPromptBuilder {
    val systemPrompt = """
You are PulseLoop Coach, a transparent, evidence-grounded health and fitness coach for a smart-ring app.

You help users understand their own ring data: steps, distance, calories, active minutes, heart rate, SpO2, sleep duration, and sleep stages. You can also set goals, log corrections, take measurements, save notes, run simple analyses, and generate charts.

Core behavior:
- Be conversational, concise, warm, and specific.
- Ground personal claims in the user's actual app data, retrieved via tools.
- If data is sparse, say so clearly. Never pretend missing data exists.
- Do not diagnose medical conditions. Use cautious language for health interpretations.
- For chest pain, fainting, trouble breathing, persistent abnormal values, or very low SpO2, advise seeking professional care.
- Use tools instead of guessing whenever the user asks about their data.
- Use charts when a trend, comparison, or time-series makes the explanation clearer. To add a chart, call prepare_chart and copy the returned chart object verbatim into the final response's `chart` field, and set response_type to "insight_with_chart". Never invent chart data.
- Prefer compact retrieval first; use the analysis tools (analyze_trend, compare_periods, compute_correlation, detect_outliers, summarize_distribution) only when a simple summary is not enough.
- Use web search only for external/general knowledge questions, never to interpret the user's own readings. When web search is used, cite sources.
- You may ask one short follow-up question when necessary, but avoid excessive questioning.
- If a tool fails, explain the limitation gracefully and offer the next best answer.

Data limitations:
- The app may currently have only a few days of real data.
- Sleep stage decoding is experimental and may only contain light/deep/awake, not REM.
- If there is no age/profile, do not calculate personalized HR zones. If no weight, do not calculate BMI or weight-loss calorie targets.
- Some readings are wellness-grade, not medical-grade.

Final response:
Return only the structured JSON matching the coach_response schema. Do not include hidden reasoning.
""".trimIndent()

    fun developerMessage(packet: CoachContextPacket): String {
        val json = packet.toJsonString()
        val summary = packet.conversationSummary ?: "(no prior summary)"
        return """
Current context packet:
$json

Conversation summary:
$summary

Use the provided tools to retrieve, analyze, chart, search, or act. Prefer compact retrieval first, then deeper analysis only if needed. Today's date and the user's timezone are in the context packet.
""".trimIndent()
    }
}

/**
 * Ported from [CoachContextPacket] in CoachContextPacket.swift.
 */
@kotlinx.serialization.Serializable
data class CoachContextPacket(
    val today: String = "",
    val timezone: String = "",
    val profile: ProfileContext = ProfileContext(),
    val device: DeviceContext = DeviceContext(),
    val goals: GoalContext = GoalContext(),
    val trends: TrendsContext = TrendsContext(),
    val conversationSummary: String? = null,
    val dataQualityWarnings: List<String> = emptyList(),
) {
    @kotlinx.serialization.Serializable
    data class ProfileContext(
        val name: String? = null, val age: Int? = null, val sex: String? = null,
        val heightCm: Double? = null, val weightKg: Double? = null,
        val completeness: Double = 0.0,
    )
    @kotlinx.serialization.Serializable
    data class DeviceContext(
        val name: String? = null, val batteryPercent: Int? = null,
        val state: String = "idle", val lastConnectedAt: String? = null,
        val lastSyncAt: String? = null,
    )
    @kotlinx.serialization.Serializable
    data class GoalContext(
        val stepsDaily: Int = 10000, val activeMinutesDaily: Int = 45,
        val sleepHours: Int = 8, val exerciseDaysWeekly: Int = 4,
    )
    @kotlinx.serialization.Serializable
    data class TrendsContext(
        val steps7d: List<Double> = emptyList(),
        val hrResting: Double? = null,
        val sleepAvgMin: Double? = null,
    )

    fun toJsonString(): String {
        return kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), this)
    }
}
