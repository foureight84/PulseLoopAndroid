package com.pulseloop.coach.tools

import kotlinx.serialization.json.JsonObject

/**
 * Ported from [CoachTool] in CoachTool.swift.
 * Type-erased tool: a function spec + handler.
 */
data class CoachToolDef(
    val name: String,
    val publicLabel: String,
    val description: String,
    val parameters: JsonObject,
    val strict: Boolean = true,
    val run: (String, ToolExecutionContext) -> ToolResult,
)

data class ToolResult(
    val jsonString: String,
    val isError: Boolean = false,
) {
    val summary: String
        get() {
            if (jsonString.length <= 160) return jsonString
            return jsonString.take(160) + "…"
        }
}

/**
 * Ported from [ToolExecutionContext] in CoachTool.swift.
 */
data class ToolExecutionContext(
    val modelContext: Any? = null,   // Room database access
    val db: com.pulseloop.data.PulseLoopDatabase? = null,  // Room database for tools
    val flags: CoachFeatureFlags = CoachFeatureFlags(),
    val coordinator: com.pulseloop.service.RingSyncCoordinator? = null,  // for live measurements
    val pendingActions: MutableList<com.pulseloop.coach.orchestration.PendingAction> = mutableListOf(),
)

data class CoachFeatureFlags(
    val coachEnabled: Boolean = true,
    val webSearchEnabled: Boolean = false,
    val writeToolsEnabled: Boolean = false,
    val liveMeasurementsEnabled: Boolean = false,
    val model: String = "gpt-5.4",
    val maxRounds: Int = 15,
    val maxToolCalls: Int = 30,
    val settings: CoachSettings = CoachSettings(),
    /** The active provider, for usage/cost attribution (iOS #65 `providerUsed`).
     *  Defaults to the OpenAI-key mode, matching [model]'s default. */
    val providerMode: com.pulseloop.coach.config.CoachProviderMode =
        com.pulseloop.coach.config.CoachProviderMode.USER_OPENAI_KEY,
)

/** `reasoningEffort` is an optional hint ("low"/"medium"/"high"); null = omit
 *  from the request (required by models like gpt-4o that reject `reasoning`). */
data class CoachSettings(val reasoningEffort: String? = null)
