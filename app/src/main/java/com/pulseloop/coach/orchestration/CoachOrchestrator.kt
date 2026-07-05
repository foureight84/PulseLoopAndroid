package com.pulseloop.coach.orchestration

import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.coach.context.CoachPromptBuilder
import com.pulseloop.coach.openai.OpenAIResponsesClient
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.schema.CoachResponse
import com.pulseloop.coach.schema.CoachResponseType
import com.pulseloop.coach.schema.CoachConfidence
import com.pulseloop.coach.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Ported from [CoachOrchestrator] in CoachOrchestrator.swift.
 * The coach agent loop: context → model → tools → structured final.
 */
class CoachOrchestrator(
    private val client: OpenAIResponsesClient,
    private val registry: ToolRegistry,
    private val flags: CoachFeatureFlags,
    private val toolContext: ToolExecutionContext,
) {
    private val maxFinalAttempts = 3
    private val maxToolArgRetries = 2
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    data class TurnResult(
        val assistant: CoachResponse,
        val trace: List<CoachToolCallTrace> = emptyList(),
    )

    data class PriorMessage(val role: String, val text: String)

    data class CoachToolCallTrace(
        val toolName: String, val label: String, val status: String,
        val resultSummary: String = "",
    )

    suspend fun runTurn(
        userText: String,
        packet: CoachContextPacket,
        recentMessages: List<PriorMessage> = emptyList(),
    ): TurnResult {
        if (!flags.coachEnabled) return TurnResult(CoachFallbacks.scripted(packet))
        return try {
            runOpenAI(userText, packet, recentMessages)
        } catch (e: Exception) {
            TurnResult(CoachFallbacks.fallback())
        }
    }

    private suspend fun runOpenAI(
        userText: String,
        packet: CoachContextPacket,
        recentMessages: List<PriorMessage>,
    ): TurnResult = withContext(Dispatchers.IO) {
        val toolSpecs = registry.toolSpecs
        val textFormat = coachResponseTextFormat

        // Build input messages
        val input = mutableListOf<JsonObject>()
        input.add(message("system", CoachPromptBuilder.systemPrompt))
        input.add(message("developer", CoachPromptBuilder.developerMessage(packet)))
        for (m in recentMessages) {
            input.add(message(if (m.role == "user") "user" else "assistant", m.text))
        }
        input.add(message("user", userText))

        var response = send(input, toolSpecs, textFormat, null)
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
            response = send(outputs, toolSpecs, textFormat, response.id)
        }

        val assistant = parseFinal(response)
        TurnResult(assistant = assistant, trace = trace)
    }

    private suspend fun parseFinal(response: com.pulseloop.coach.openai.OpenAIResponse): CoachResponse {
        var current = response
        var attempts = 1
        while (true) {
            val parsed = CoachResponseParser.parse(current.outputText)
            if (parsed != null) return parsed
            attempts++
            if (attempts > maxFinalAttempts) {
                // Graceful degradation: if the model answered in prose instead of
                // schema JSON (typical for local models), show the prose — a real
                // answer beats the canned apology every time.
                val plain = CoachResponseParser.stripThinking(current.outputText)
                if (plain.isNotBlank() && !plain.startsWith("{")) {
                    return CoachResponse(
                        responseType = CoachResponseType.INSIGHT,
                        title = "Coach",
                        summary = plain.take(2000),
                        confidence = CoachConfidence.MEDIUM,
                    )
                }
                return CoachFallbacks.parseError()
            }
            val repair = message("user", "Your previous output did not match the required coach_response JSON schema. Return only valid JSON for that schema now.")
            current = send(listOf(repair), emptyList(), coachResponseTextFormat, current.id)
        }
    }

    private suspend fun send(
        input: List<JsonObject>,
        tools: List<JsonObject>,
        textFormat: JsonObject,
        previousResponseId: String?,
    ): com.pulseloop.coach.openai.OpenAIResponse {
        val body = JsonObject(mapOf(
            "model" to JsonPrimitive(flags.model),
            "input" to JsonArray(input),
            "tools" to JsonArray(tools),
            "text" to textFormat,
        ) + (previousResponseId?.let { mapOf("previous_response_id" to JsonPrimitive(it)) } ?: emptyMap()))
        val bodyBytes = Json.encodeToString(JsonObject.serializer(), body).toByteArray()
        return client.send(bodyBytes)
    }

    private fun message(role: String, content: String) = JsonObject(mapOf(
        "role" to JsonPrimitive(role),
        "content" to JsonPrimitive(content),
    ))

    private fun functionCallOutput(callId: String, output: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("function_call_output"),
        "call_id" to JsonPrimitive(callId),
        "output" to JsonPrimitive(output),
    ))

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
    /** Local reasoning models (Qwen3 etc.) prepend <think>…</think> blocks that
     *  contain prose (and often braces) — strip them before any JSON hunting. */
    fun stripThinking(text: String): String =
        text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")  // unclosed prefix
            .trim()

    fun parse(text: String): CoachResponse? {
        val trimmed = stripThinking(text)
        // Try direct parse; if fails, try extracting JSON from markdown/code fences
        return try {
            json.decodeFromString<CoachResponse>(trimmed)
        } catch (_: Exception) {
            val extracted = extractJson(trimmed) ?: return null
            try { json.decodeFromString<CoachResponse>(extracted) } catch (_: Exception) { null }
        }
    }

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
