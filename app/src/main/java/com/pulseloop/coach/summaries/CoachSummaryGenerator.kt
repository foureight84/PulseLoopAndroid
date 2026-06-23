package com.pulseloop.coach.summaries

import com.pulseloop.coach.openai.OpenAIResponsesClient
import com.pulseloop.coach.tools.CoachFeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Ported from CoachSummaryGenerator.swift.
 * Single-shot OpenAI call that produces a coach-card summary. No tools — just
 * system + developer → strict {title, body, chips}. Falls back to scripted
 * content when the coach is disabled or the call fails.
 */
object CoachSummaryGenerator {
    private val json = Json { prettyPrint = false }

    suspend fun generate(
        kind: CoachSummaryKind,
        contextJSON: String,
        fallback: CoachSummaryContent,
        flags: CoachFeatureFlags,
        apiKey: String,
    ): CoachSummaryContent = withContext(Dispatchers.IO) {
        if (!flags.coachEnabled || apiKey.isBlank()) return@withContext fallback
        try {
            val input = JsonArray(listOf(
                message("system", CoachSummaryPromptBuilder.systemPrompt(kind)),
                message("developer", CoachSummaryPromptBuilder.developerMessage(contextJSON)),
            ))
            val textFormat = JsonObject(mapOf(
                "format" to JsonObject(mapOf(
                    "type" to JsonPrimitive("json_schema"),
                    "name" to JsonPrimitive(CoachSummarySchema.name),
                    "schema" to summarySchemaJson,
                    "strict" to JsonPrimitive(true),
                ))
            ))
            val body = JsonObject(mapOf(
                "model" to JsonPrimitive(flags.model),
                "input" to input,
                "text" to textFormat,
            ))
            val client = OpenAIResponsesClient(apiKey)
            val bodyBytes = Json.encodeToString(JsonObject.serializer(), body).toByteArray()
            val response = client.send(bodyBytes)
            val outputText = response.outputText ?: return@withContext fallback
            CoachSummaryContent.fromJson(outputText) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun message(role: String, content: String) = JsonObject(mapOf(
        "role" to JsonPrimitive(role),
        "content" to JsonPrimitive(content),
    ))

    private val summarySchemaJson: JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "body" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "chips" to JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "maxItems" to JsonPrimitive(3),
            )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("body"), JsonPrimitive("chips"))),
        "additionalProperties" to JsonPrimitive(false),
    ))
}
