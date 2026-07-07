package com.pulseloop.coach.orchestration

import com.pulseloop.coach.attachments.CoachImagePayload
import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.coach.context.CoachPromptBuilder
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.OpenAIRequestBuilder
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.schema.CoachResponse
import com.pulseloop.coach.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Ported from [CoachOrchestrator] in CoachOrchestrator.swift.
 * The coach agent loop: context → model → tools → structured final.
 *
 * The client AND the feature flags are taken as FACTORIES, not instances: the
 * Gemini/OpenRouter adapters are stateful (they accumulate the conversation
 * across `send` calls within one agent turn), so every `runTurn` needs a fresh
 * client — and the flags (enabled gate, model slug, reasoning effort, tool
 * toggles) must be re-read per turn so pasting an API key or switching
 * provider/model in Settings takes effect on the next turn, not on process
 * restart. Wire the client via `CoachClientResolver.clientFactory(store,
 * apiKeyStore)`; the tool registry and execution context are rebuilt per turn
 * from the same fresh flags.
 */
class CoachOrchestrator(
    private val clientFactory: () -> ResponsesClient,
    private val flagsProvider: () -> CoachFeatureFlags,
    private val toolContextFactory: (CoachFeatureFlags) -> ToolExecutionContext,
) {

    private val maxFinalAttempts = 3
    private val maxToolArgRetries = 2
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    data class TurnResult(
        val assistant: CoachResponse,
        val trace: List<CoachToolCallTrace> = emptyList(),
        val pendingActions: List<PendingAction> = emptyList(),
        /** Set when the turn failed; surfaced as a red error bubble instead of
         *  the `assistant` fallback. null on success. */
        val error: CoachTurnError? = null,
    )

    data class PriorMessage(
        val role: String,
        val text: String,
        /** Wire payloads for images attached to this (user) turn. The caller
         *  should populate this only on the most recent prior user turn that has
         *  attachments, to keep context coherent without ballooning the payload
         *  with old base64 (mirrors the iOS CoachViewModel). */
        val images: List<CoachImagePayload> = emptyList(),
    )

    data class CoachToolCallTrace(
        val toolName: String, val label: String, val status: String,
        val resultSummary: String = "",
    )

    /** Thrown by `parseFinal` when the model never returns valid coach_response
     *  JSON after the repair attempts. */
    private class ParseExhausted(val reason: String) : Exception(reason)

    companion object {
        /** Substituted as the user prompt when an image is sent with no text, so
         *  the schema/tool loop still has a non-empty user turn to anchor on. */
        private const val IMAGE_ONLY_PROMPT = "Please look at the attached image."
    }

    suspend fun runTurn(
        userText: String,
        packet: CoachContextPacket,
        recentMessages: List<PriorMessage> = emptyList(),
        userImages: List<CoachImagePayload> = emptyList(),
    ): TurnResult {
        // Fresh flags per turn — picks up key/provider/model changes without a rebuild.
        val flags = flagsProvider()
        if (!flags.coachEnabled) return TurnResult(CoachFallbacks.scripted(packet))
        return try {
            runOpenAI(flags, userText, packet, recentMessages, userImages)
        } catch (e: Exception) {
            TurnResult(CoachFallbacks.fallback(), error = CoachTurnError.from(e))
        }
    }

    private suspend fun runOpenAI(
        flags: CoachFeatureFlags,
        userText: String,
        packet: CoachContextPacket,
        recentMessages: List<PriorMessage>,
        userImages: List<CoachImagePayload>,
    ): TurnResult = withContext(Dispatchers.IO) {
        // Fresh client per turn — required for the stateful Gemini/OpenRouter
        // adapters, and it picks up settings changes without a rebuild.
        val client = clientFactory()
        val registry = ToolRegistry(flags)
        val toolContext = toolContextFactory(flags)
        val toolSpecs = registry.toolSpecs
        val textFormat = coachResponseTextFormat

        // Initial input: system + developer + recent turns + the new user message.
        // Images only ever ride on user turns (system/developer/assistant stay text).
        val input = mutableListOf<JsonObject>()
        input.add(OpenAIRequestBuilder.message("system", CoachPromptBuilder.systemPrompt))
        input.add(OpenAIRequestBuilder.message("developer", CoachPromptBuilder.developerMessage(packet)))
        for (m in recentMessages) {
            val isUser = m.role == "user"
            input.add(OpenAIRequestBuilder.message(
                if (isUser) "user" else "assistant", m.text,
                if (isUser) m.images else emptyList()))
        }
        val userContent = if (userText.isEmpty() && userImages.isNotEmpty()) IMAGE_ONLY_PROMPT else userText
        input.add(OpenAIRequestBuilder.message("user", userContent, userImages))

        var response = send(client, flags, input, toolSpecs, textFormat, null)
        val trace = mutableListOf<CoachToolCallTrace>()
        var toolCalls = 0
        var rounds = 0
        val argFailures = mutableMapOf<String, Int>()

        while (rounds < flags.maxRounds) {
            val functionCalls = response.functionCalls
            if (functionCalls.isEmpty()) break

            val outputs = mutableListOf<JsonObject>()
            for (fc in functionCalls) {
                if (toolCalls >= flags.maxToolCalls) {
                    outputs.add(functionCallOutput(fc.callId, """{"error":"tool-call budget exceeded"}"""))
                    continue
                }
                if ((argFailures[fc.name] ?: 0) > maxToolArgRetries) {
                    outputs.add(functionCallOutput(fc.callId, """{"error":"stop calling ${fc.name}; arguments kept failing"}"""))
                    continue
                }
                toolCalls++

                val result = ToolCallExecutor.execute(fc, registry, toolContext)
                if (result.isError && result.jsonString.contains("invalid arguments")) {
                    argFailures[fc.name] = (argFailures[fc.name] ?: 0) + 1
                }
                trace.add(CoachToolCallTrace(
                    toolName = fc.name,
                    label = registry.tool(fc.name)?.publicLabel ?: fc.name,
                    status = if (result.isError) "error" else "success",
                    resultSummary = result.summary,
                ))
                outputs.add(functionCallOutput(fc.callId, result.jsonString))
            }

            rounds++
            response = send(client, flags, outputs, toolSpecs, textFormat, response.id)
        }

        try {
            val assistant = parseFinal(client, flags, response)
            TurnResult(assistant = assistant, trace = trace, pendingActions = toolContext.pendingActions.toList())
        } catch (e: ParseExhausted) {
            // The model never produced valid coach_response JSON. Surface it as an
            // error bubble, but keep the trace from the tools that did run.
            TurnResult(
                assistant = CoachFallbacks.parseError(), trace = trace,
                error = CoachTurnError(code = "Bad response", reason = e.reason))
        }
    }

    private suspend fun parseFinal(
        client: ResponsesClient,
        flags: CoachFeatureFlags,
        response: com.pulseloop.coach.openai.OpenAIResponse,
    ): CoachResponse {
        var current = response
        var attempts = 1
        while (true) {
            val parsed = CoachResponseParser.parse(current.outputText)
            if (parsed != null) return parsed
            attempts++
            if (attempts > maxFinalAttempts) {
                val snippet = current.outputText.trim().take(200)
                throw ParseExhausted(
                    "The model didn't return a valid structured answer after $maxFinalAttempts attempts." +
                        if (snippet.isEmpty()) "" else " It replied: “$snippet…”")
            }
            val repair = OpenAIRequestBuilder.message("user", "Your previous output did not match the required coach_response JSON schema. Return only valid JSON for that schema now.")
            current = send(client, flags, listOf(repair), emptyList(), coachResponseTextFormat, current.id)
        }
    }

    private suspend fun send(
        client: ResponsesClient,
        flags: CoachFeatureFlags,
        input: List<JsonObject>,
        tools: List<JsonObject>,
        textFormat: JsonObject,
        previousResponseId: String?,
    ): com.pulseloop.coach.openai.OpenAIResponse {
        // Optional reasoning-effort hint (mirrors iOS OpenAIRequestBuilder).
        val reasoning = OpenAIRequestBuilder.reasoningParams(flags.settings.reasoningEffort)
        val body = JsonObject(mapOf(
            "model" to JsonPrimitive(flags.model),
            "input" to JsonArray(input),
            "tools" to JsonArray(tools),
            "text" to textFormat,
        ) + reasoning
            + (previousResponseId?.let { mapOf("previous_response_id" to JsonPrimitive(it)) } ?: emptyMap()))
        val bodyBytes = Json.encodeToString(JsonObject.serializer(), body).toByteArray()
        return client.send(bodyBytes)
    }

    private fun functionCallOutput(callId: String, output: String) =
        OpenAIRequestBuilder.functionCallOutput(callId, output)

    private val coachResponseTextFormat = JsonObject(mapOf(
        "format" to JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("coach_response"),
            "schema" to CoachResponseSchema.schema,
            "strict" to JsonPrimitive(true),
        ))
    ))
}

/**
 * Ported from the CoachResponseSchema in CoachResponseSchema.swift.
 */
object CoachResponseSchema {
    /**
     * Plain-language description of the exact `coach_response` shape, for
     * providers whose enforcement can be bypassed by catalog models (the
     * OpenRouter adapter injects this as a system message as a backup to its
     * `response_format`). Native OpenAI/Gemini enforce the schema out-of-band
     * and don't need this. Keep in sync with `schema` / `CoachResponse`.
     */
    val promptInstruction: String = """
        Your final answer MUST be a single JSON object (no Markdown, no code fences, no prose before or after) matching this exact `coach_response` schema. Use these exact snake_case keys — all are required:
        {
          "response_type": one of "insight" | "insight_with_chart" | "question" | "action_confirmation" | "data_missing" | "safety_guidance" | "error_recovery",
          "title": string (≤ 90 chars),
          "summary": string (≤ 900 chars) — put the main answer here, not in a "message" field,
          "bullets": array of strings (≤ 5 items, each ≤ 220 chars),
          "chart": null, or a chart object (only when response_type is "insight_with_chart"),
          "safety_note": string or null,
          "data_quality_note": string or null,
          "sources": array of { "title": string, "url": string, "publisher": string } (use [] if none),
          "follow_up_chips": array of strings (≤ 4 items, each ≤ 60 chars),
          "actions_taken": array of strings (use [] if none),
          "confidence": one of "low" | "medium" | "high"
        }
        Do NOT use a "message" key. Do NOT wrap the JSON in ``` fences. Put your formatted text inside "summary" and "bullets".
    """.trimIndent()

    val schema: JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "response_type" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf(
                    "insight", "insight_with_chart", "question",
                    "action_confirmation", "data_missing", "safety_guidance", "error_recovery"
                ).map { JsonPrimitive(it) }),
            )),
            "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "bullets" to JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "chart" to JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "chart_type" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
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
            )),
            "confidence" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf("low", "medium", "high").map { JsonPrimitive(it) }),
            )),
            "safety_note" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "data_quality_note" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "sources" to JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "url" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "publisher" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("url"))),
                    "additionalProperties" to JsonPrimitive(false),
                )),
            )),
            "follow_up_chips" to JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
            "actions_taken" to JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            )),
        )),
        "required" to JsonArray(listOf(
            "response_type", "title", "summary", "confidence"
        ).map { JsonPrimitive(it) }),
        "additionalProperties" to JsonPrimitive(false),
    ))
}

/**
 * Ported from CoachResponseParser.
 */
object CoachResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val requiredKeys = listOf("response_type", "title", "summary", "confidence")

    fun parse(text: String): CoachResponse? {
        val trimmed = text.trim()
        // Try direct parse; if fails, try extracting JSON from markdown/code fences
        return decode(trimmed) ?: extractJson(trimmed)?.let { decode(it) }
    }

    private fun decode(text: String): CoachResponse? = try {
        val obj = json.parseToJsonElement(text) as? JsonObject
        // Every field has a default, so decoding alone accepts any object; enforce the
        // schema's required keys here so a bad reply reaches the repair loop instead of
        // rendering as an empty response.
        if (obj == null || !requiredKeys.all { it in obj }) null
        else json.decodeFromJsonElement<CoachResponse>(obj)
    } catch (_: Exception) { null }

    private fun extractJson(text: String): String? {
        // Try code-fenced JSON
        val fenceMatch = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```").find(text)
        if (fenceMatch != null) return fenceMatch.groupValues[1].trim()
        // Try to find { ... } block
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        return null
    }
}

object ToolCallExecutor {
    fun execute(
        fc: FunctionCallOutput,
        registry: ToolRegistry,
        context: ToolExecutionContext,
    ): ToolResult {
        val tool = registry.tool(fc.name) ?: return ToolResult(
            """{"error":"unknown tool: ${fc.name}"}""", isError = true
        )
        return try {
            tool.run(fc.arguments, context)
        } catch (e: Exception) {
            ToolResult("""{"error":"tool execution failed: ${e.message}"}""", isError = true)
        }
    }
}
