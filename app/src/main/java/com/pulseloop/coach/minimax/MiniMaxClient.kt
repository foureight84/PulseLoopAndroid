package com.pulseloop.coach.minimax

import com.pulseloop.coach.config.MiniMaxModel
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.MessageOutput
import com.pulseloop.coach.openai.OpenAIResponse
import com.pulseloop.coach.openai.ResponseOutputItem
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.ResponsesHttp
import com.pulseloop.coach.openai.ResponsesToolSpecs
import com.pulseloop.coach.openai.TextContent
import com.pulseloop.coach.orchestration.CoachResponseSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Ported from [MiniMaxClient] in MiniMaxClient.swift (iOS #54).
 * Adapts the app's [ResponsesClient] interface to MiniMax's OpenAI-compatible
 * **Chat Completions** API (`POST /v1/chat/completions`) — a translating adapter
 * just like [com.pulseloop.coach.openrouter.OpenRouterClient], so the rest of the
 * coach is unchanged.
 *
 * Deliberately simpler than OpenRouter: MiniMax's compat endpoint has no hosted
 * web search, no provider-routing block, and no `:online` suffix; it also doesn't
 * document `response_format`, Anthropic `cache_control`, or the OpenAI `reasoning`
 * object — so none of those are sent. The coach's output shape is enforced by the
 * injected [CoachResponseSchema.promptInstruction] system message plus the
 * orchestrator's JSON-repair loop, exactly as in the OpenRouter fallback path.
 *
 * State across turns: the OpenAI Responses API is stateful; Chat Completions is
 * stateless. This class accumulates the conversation as Chat Completions
 * `messages` across `send` calls; the orchestrator creates a fresh client per
 * agent turn via the factory.
 */
class MiniMaxClient(
    private val apiKey: String,
    private val model: String = MiniMaxModel.DEFAULT.slug,
    // Global (international) host. China-region accounts use `api.minimaxi.com`
    // instead — swap the host here if the key belongs to the CN platform.
    private val endpoint: String = "https://api.minimax.io/v1/chat/completions",
) : ResponsesClient {
    private val json = Json { ignoreUnknownKeys = true }

    // Accumulated Chat Completions messages for this turn.
    private var messages = mutableListOf<JsonObject>()
    // Maps generated response IDs → the assistant message (content + tool_calls)
    // so a continuation turn can re-insert it before the matching tool results.
    private val storedAssistantMessage = mutableMapOf<String, JsonObject>()

    override suspend fun send(requestBody: ByteArray): OpenAIResponse {
        if (apiKey.isBlank()) throw ResponsesError.MissingAPIKey

        val req = try {
            json.parseToJsonElement(String(requestBody)).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("MiniMaxClient: invalid request body")
        }

        val body = buildRequestBody(req)
        val bodyBytes = json.encodeToString(JsonObject.serializer(), body).toByteArray()

        val responseBody = ResponsesHttp.post(endpoint, bodyBytes, mapOf(
            "Authorization" to "Bearer $apiKey",
        ))

        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("MiniMaxClient: response was not a JSON object")
        }
        return ingestResponse(root)
    }

    // ── Request assembly (internal for unit tests) ───────────────────────

    /** Translates one Responses-API request into the Chat Completions body,
     *  updating the accumulated conversation state. */
    internal fun buildRequestBody(req: JsonObject): JsonObject {
        val input = (req["input"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val tools = (req["tools"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val previousResponseId = (req["previous_response_id"] as? JsonPrimitive)?.contentOrNull

        if (previousResponseId == null) {
            setupConversation(input)
        } else {
            appendContinuation(previousResponseId, input)
        }

        return buildChatBody(convertTools(tools))
    }

    // ── Conversation setup ───────────────────────────────────────────────

    /** First turn: convert the Responses `input` items into Chat Completions
     *  `messages`. Responses uses a `developer` role; Chat Completions doesn't,
     *  so fold it into `system`. */
    private fun setupConversation(input: List<JsonObject>) {
        messages = mutableListOf()
        storedAssistantMessage.clear()
        for (item in input) {
            val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (item["content"] == null) continue
            messages.add(JsonObject(mapOf(
                "role" to JsonPrimitive(chatRole(role)),
                "content" to chatContent(item),
            )))
        }
        // MiniMax's compat endpoint isn't sent an enforced `response_format`, so the
        // model isn't told the required output shape out-of-band. Inject the field
        // spec as a system message so it can produce valid coach_response JSON from
        // the prompt alone (the orchestrator's JSON-repair loop is the backstop).
        messages.add(JsonObject(mapOf(
            "role" to JsonPrimitive("system"),
            "content" to JsonPrimitive(CoachResponseSchema.promptInstruction),
        )))
    }

    /** Subsequent turns: replay the stored assistant message for `previousId`
     *  (Chat Completions requires the assistant `tool_calls` message to precede
     *  the `tool` results answering them), then append the new tool results /
     *  messages. */
    private fun appendContinuation(previousId: String, input: List<JsonObject>) {
        storedAssistantMessage[previousId]?.let { messages.add(it) }
        for (item in input) {
            val type = (item["type"] as? JsonPrimitive)?.contentOrNull
            val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
            val output = (item["output"] as? JsonPrimitive)?.contentOrNull
            if (type == "function_call_output" && callId != null && output != null) {
                messages.add(JsonObject(mapOf(
                    "role" to JsonPrimitive("tool"),
                    "tool_call_id" to JsonPrimitive(callId),
                    "content" to JsonPrimitive(output),
                )))
            } else {
                val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: continue
                if (item["content"] == null) continue
                messages.add(JsonObject(mapOf(
                    "role" to JsonPrimitive(chatRole(role)),
                    "content" to chatContent(item),
                )))
            }
        }
    }

    private fun chatRole(responsesRole: String): String =
        if (responsesRole == "developer") "system" else responsesRole

    /** Converts a Responses-API message item's `content` into Chat Completions
     *  `content`. Text items keep `content` a plain string. Image items carry the
     *  OpenAI content-part array (`input_text` + `input_image`), which we map to
     *  Chat Completions parts (`{type:text}` + `{type:image_url, image_url:{url}}`). */
    private fun chatContent(item: JsonObject): JsonElement {
        val content = item["content"]
        if (content is JsonPrimitive && content.isString) return content
        val parts = (content as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?: return JsonPrimitive("")
        val out = mutableListOf<JsonObject>()
        for (part in parts) {
            when ((part["type"] as? JsonPrimitive)?.contentOrNull) {
                "input_text", "text" -> {
                    (part["text"] as? JsonPrimitive)?.contentOrNull?.let {
                        out.add(JsonObject(mapOf(
                            "type" to JsonPrimitive("text"),
                            "text" to JsonPrimitive(it),
                        )))
                    }
                }
                "input_image" -> {
                    (part["image_url"] as? JsonPrimitive)?.contentOrNull?.let {
                        out.add(JsonObject(mapOf(
                            "type" to JsonPrimitive("image_url"),
                            "image_url" to JsonObject(mapOf("url" to JsonPrimitive(it))),
                        )))
                    }
                }
            }
        }
        return JsonArray(out)
    }

    // ── Tool conversion (Responses flat → Chat Completions nested) ───────

    /** Converts the app's flat Responses function specs
     *  (`{type, name, description, parameters}`) into Chat Completions' nested
     *  shape (`{type: function, function: {...}}`). The OpenAI-hosted `web_search`
     *  tool has no MiniMax equivalent, so it's dropped (the Settings UI also hides
     *  the web-search toggle for MiniMax; dropping it here is a defensive backstop). */
    private fun convertTools(tools: List<JsonObject>): List<JsonObject> {
        val parsed = ResponsesToolSpecs.parse(tools)
        // Note: unlike OpenRouter, a requested web-search is simply dropped — MiniMax
        // has no hosted search and no `:online` suffix to route it to.
        return parsed.functions.map { spec ->
            val fn = mutableMapOf<String, JsonElement>("name" to JsonPrimitive(spec.name))
            spec.description?.let { fn["description"] = JsonPrimitive(it) }
            spec.parameters?.let { fn["parameters"] = it }
            spec.strict?.let { fn["strict"] = JsonPrimitive(it) }
            JsonObject(mapOf(
                "type" to JsonPrimitive("function"),
                "function" to JsonObject(fn),
            ))
        }
    }

    // ── Build request body ───────────────────────────────────────────────

    private fun buildChatBody(tools: List<JsonObject>): JsonObject {
        val body = mutableMapOf<String, JsonElement>(
            "model" to JsonPrimitive(model),
            "messages" to JsonArray(messages),
        )
        if (tools.isNotEmpty()) body["tools"] = JsonArray(tools)
        return JsonObject(body)
    }

    // ── Parse Chat Completions response → OpenAIResponse (internal for tests) ─

    internal fun ingestResponse(root: JsonObject): OpenAIResponse {
        // MiniMax can surface an upstream error (and sometimes a base_resp status)
        // even on an HTTP 200.
        (root["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
            throw ResponsesError.Decoding("MiniMax error: $msg")
        }
        (root["base_resp"] as? JsonObject)?.let { base ->
            val statusCode = (base["status_code"] as? JsonPrimitive)?.intOrNull
            if (statusCode != null && statusCode != 0) {
                val msg = (base["status_msg"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
                throw ResponsesError.Decoding("MiniMax error ($statusCode): $msg")
            }
        }

        val first = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = first?.get("message") as? JsonObject
            ?: throw ResponsesError.Decoding(
                "MiniMaxClient: no choices in response — ${root.toString().take(300)}")

        val responseId = (root["id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val outputItems = mutableListOf<ResponseOutputItem>()
        // Persist the raw assistant message so a continuation turn can replay it.
        val assistantMessage = mutableMapOf<String, JsonElement>(
            "role" to JsonPrimitive("assistant"),
        )

        val rawContent = (message["content"] as? JsonPrimitive)?.contentOrNull
        // M-series models emit reasoning inline as `<think>…</think>` blocks by
        // default; strip them so the coach_response JSON parses cleanly.
        val content = rawContent?.let { stripThinking(it) }
        if (!content.isNullOrEmpty()) {
            outputItems.add(MessageOutput(role = "assistant", content = listOf(TextContent(content))))
            assistantMessage["content"] = JsonPrimitive(content)
        } else {
            // Chat Completions allows null content when tool_calls are present.
            assistantMessage["content"] = JsonNull
        }

        val toolCalls = (message["tool_calls"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        if (toolCalls != null) {
            val storedCalls = mutableListOf<JsonObject>()
            for (call in toolCalls) {
                val fn = call["function"] as? JsonObject ?: continue
                val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                // Reuse MiniMax's own tool_call id as the orchestrator's call_id so
                // the tool result message can reference it on the next turn.
                val callId = (call["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?: ("mm_call_" + UUID.randomUUID().toString().replace("-", "").take(12))
                val args = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}"
                outputItems.add(FunctionCallOutput(id = callId, callId = callId, name = name, arguments = args))
                storedCalls.add(JsonObject(mapOf(
                    "id" to JsonPrimitive(callId),
                    "type" to JsonPrimitive("function"),
                    "function" to JsonObject(mapOf(
                        "name" to JsonPrimitive(name),
                        "arguments" to JsonPrimitive(args),
                    )),
                )))
            }
            if (storedCalls.isNotEmpty()) assistantMessage["tool_calls"] = JsonArray(storedCalls)
        }

        if (outputItems.isEmpty()) throw ResponsesError.EmptyOutput

        storedAssistantMessage[responseId] = JsonObject(assistantMessage)
        return OpenAIResponse(id = responseId, output = outputItems, usage = usage(root))
    }

    /** Maps MiniMax's `usage` block. Only the prompt/completion split is used; if
     *  MiniMax reports just `total_tokens` (no split), usage stays `null` rather
     *  than mis-attributing the total to either side. */
    private fun usage(root: JsonObject): com.pulseloop.coach.usage.CoachTokenUsage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val input = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: return null
        val output = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: return null
        return com.pulseloop.coach.usage.CoachTokenUsage(inputTokens = input, outputTokens = output)
    }

    /** Removes `<think>…</think>` reasoning blocks (and any leading whitespace they
     *  leave behind) from assistant content. Tolerant of an unterminated trailing
     *  `<think>` (truncated output). */
    private fun stripThinking(text: String): String {
        val out = StringBuilder()
        var scan = 0
        while (true) {
            val open = text.indexOf("<think>", scan)
            if (open < 0) break
            out.append(text, scan, open)
            val close = text.indexOf("</think>", open + 7)
            if (close < 0) { scan = text.length; break }   // unterminated — drop the rest
            scan = close + 8
        }
        out.append(text, scan, text.length)
        return out.toString().trim()
    }
}
