package com.pulseloop.coach.openrouter

import com.pulseloop.coach.config.OpenRouterModel
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
 * Ported from [OpenRouterClient] in OpenRouterClient.swift.
 * Adapts the app's [ResponsesClient] interface to OpenRouter's OpenAI-compatible
 * **Chat Completions** API (`POST /api/v1/chat/completions`). OpenRouter is an
 * aggregator: `model` is a `vendor/model` slug (e.g. `anthropic/claude-sonnet-4.6`)
 * that it routes to the underlying provider. Translates the app's Responses-API
 * request bodies into Chat Completions requests and maps responses back, so no
 * other component needs to know which provider is active.
 *
 * State across turns: the OpenAI Responses API is stateful (server tracks
 * history via `previous_response_id`); Chat Completions is stateless (caller
 * sends the full message list each time). This class accumulates the
 * conversation as Chat Completions `messages` across `send` calls; each instance
 * covers exactly one agent turn (the orchestrator creates a fresh client per
 * turn via the factory).
 */
class OpenRouterClient(
    private val apiKey: String,
    private val model: String = OpenRouterModel.DEFAULT.slug,
    // OpenRouter-only routing options (see `CoachProviderSettings`). Threaded in
    // from the resolver alongside `model`; the native clients ignore them.
    private val privacyRouting: Boolean = false,
    private val providerSort: String? = null,
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
) : ResponsesClient {
    private val json = Json { ignoreUnknownKeys = true }

    // Accumulated Chat Completions messages for this turn.
    private var messages = mutableListOf<JsonObject>()
    // Maps generated response IDs → the assistant message (content + tool_calls)
    // so a continuation turn can re-insert it before the matching tool results.
    private val storedAssistantMessage = mutableMapOf<String, JsonObject>()
    // Set in `convertTools` when the orchestrator's hosted `web_search` tool spec
    // is seen; routes to OpenRouter's `:online` model suffix (see `onlineModelSlug`).
    private var webSearchRequested = false

    override suspend fun send(requestBody: ByteArray): OpenAIResponse {
        if (apiKey.isBlank()) throw ResponsesError.MissingAPIKey

        val req = try {
            json.parseToJsonElement(String(requestBody)).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("OpenRouterClient: invalid request body")
        }

        val body = buildRequestBody(req)
        val bodyBytes = json.encodeToString(JsonObject.serializer(), body).toByteArray()

        val responseBody = ResponsesHttp.post(endpoint, bodyBytes, mapOf(
            "Authorization" to "Bearer $apiKey",
            // Optional attribution headers OpenRouter uses for its app leaderboard.
            "HTTP-Referer" to "https://github.com/foureight84/PulseLoopAndroid",
            "X-Title" to "PulseLoop",
        ))

        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("OpenRouterClient: response was not a JSON object")
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
        // OpenRouter accepts the same unified `reasoning` object the app already
        // builds (`{ "effort": "low|medium|high" }`); it's ignored for models that
        // don't reason, so forward it as-is when present.
        val reasoning = req["reasoning"]
        // The Responses-API `text.format` carries the strict coach_response JSON
        // schema; we translate it to Chat Completions `response_format`.
        val textFormat = ((req["text"] as? JsonObject)?.get("format")) as? JsonObject

        if (previousResponseId == null) {
            setupConversation(input)
        } else {
            appendContinuation(previousResponseId, input)
        }

        return buildChatBody(convertTools(tools), reasoning, textFormat)
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
        // Belt-and-suspenders for catalog models that ignore `response_format`:
        // inject the coach_response field spec as a system message so the model
        // can produce valid JSON from the prompt alone.
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
     *  `content`. Text items keep `content` a plain string (unchanged path, so
     *  the cache-control rewrite still applies). Image items carry the OpenAI
     *  content-part array (`input_text` + `input_image`), which we map to Chat
     *  Completions parts (`{type:text}` + `{type:image_url, image_url:{url}}`). */
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
     *  shape (`{type: function, function: {...}}`). The OpenAI-hosted
     *  `web_search` tool (type != "function") has no Chat Completions function
     *  equivalent, so it's dropped here and instead routed via OpenRouter's
     *  `:online` model suffix (see `onlineModelSlug`), which works across the
     *  whole catalog and needs no extra tool-loop round trip. */
    private fun convertTools(tools: List<JsonObject>): List<JsonObject> {
        val parsed = ResponsesToolSpecs.parse(tools)
        if (parsed.webSearchRequested) webSearchRequested = true
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

    private fun buildChatBody(
        tools: List<JsonObject>,
        reasoning: JsonElement?,
        textFormat: JsonObject?,
    ): JsonObject {
        val body = mutableMapOf<String, JsonElement>(
            "model" to JsonPrimitive(onlineModelSlug()),
            "messages" to JsonArray(cacheControlledMessages()),
        )

        if (tools.isNotEmpty()) {
            // Cache the (large, static) tool block — re-sent on every round and
            // identical across questions. A breakpoint on the last tool caches the
            // whole tools prefix on providers that support it (Anthropic, etc.);
            // OpenRouter strips `cache_control` for providers that don't.
            val cachedTools = tools.toMutableList()
            cachedTools[cachedTools.size - 1] = JsonObject(
                cachedTools.last() + ("cache_control" to ephemeral())
            )
            body["tools"] = JsonArray(cachedTools)
        }

        // Enforce the coach_response shape via OpenRouter structured outputs.
        // We send `response_format` with `strict: true`, and
        // `provider.require_parameters` (below) makes OpenRouter route only to
        // providers that actually honor it — so models/providers that can't
        // enforce this schema are skipped rather than silently returning prose.
        // `promptInstruction` (a system message) remains as a belt-and-suspenders
        // backup for models that ignore the schema.
        chatResponseFormat(textFormat)?.let { body["response_format"] = it }

        if (reasoning != null && reasoning !is JsonNull) body["reasoning"] = reasoning

        providerOptions(requireParameters = body.containsKey("response_format"))?.let {
            body["provider"] = it
        }

        // Ask OpenRouter to include the usage accounting (token counts + the exact
        // upstream `cost` in USD) in the response, so the persisted cost matches
        // OpenRouter's own Activity page rather than a catalog estimate.
        body["usage"] = JsonObject(mapOf("include" to JsonPrimitive(true)))

        return JsonObject(body)
    }

    /** Translates the Responses-API `text.format` object
     *  (`{type:"json_schema", name, schema, strict}`) into the Chat Completions
     *  `response_format` shape OpenRouter expects
     *  (`{type:"json_schema", json_schema:{name, strict, schema}}`). */
    private fun chatResponseFormat(textFormat: JsonObject?): JsonObject? {
        if (textFormat == null) return null
        if ((textFormat["type"] as? JsonPrimitive)?.contentOrNull != "json_schema") return null
        val schema = textFormat["schema"] as? JsonObject ?: return null
        val jsonSchema = mutableMapOf<String, JsonElement>(
            "schema" to schema,
            "strict" to JsonPrimitive(true),
        )
        (textFormat["name"] as? JsonPrimitive)?.contentOrNull?.let {
            jsonSchema["name"] = JsonPrimitive(it)
        }
        return JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "json_schema" to JsonObject(jsonSchema),
        ))
    }

    /** The model slug to send, with OpenRouter's `:online` web-search suffix
     *  appended when the orchestrator requested web search. Not doubled if the
     *  slug already ends in `:online` (e.g. a user-typed Custom slug). */
    private fun onlineModelSlug(): String {
        if (!webSearchRequested || model.endsWith(":online")) return model
        return "$model:online"
    }

    /** OpenRouter's top-level `provider` routing object, assembled from the
     *  OpenRouter-only settings. `data_collection: "deny"` excludes providers
     *  that log/train on prompts; `sort` biases provider selection;
     *  `require_parameters: true` makes OpenRouter route only to providers that
     *  honor every parameter we send (notably `response_format`), so the
     *  structured output is actually enforced. Returns null when nothing is set. */
    private fun providerOptions(requireParameters: Boolean): JsonObject? {
        val provider = mutableMapOf<String, JsonElement>()
        if (requireParameters) provider["require_parameters"] = JsonPrimitive(true)
        if (privacyRouting) provider["data_collection"] = JsonPrimitive("deny")
        providerSort?.takeIf { it.isNotEmpty() }?.let { provider["sort"] = JsonPrimitive(it) }
        return if (provider.isEmpty()) null else JsonObject(provider)
    }

    // ── Prompt caching ───────────────────────────────────────────────────

    /** Returns `messages` with Anthropic-style `cache_control` breakpoints on
     *  the system messages so the large static prefix isn't re-billed at full
     *  price on every tool-loop round / question. The first system message (the
     *  static coach system prompt) caches cross-question; the last (the
     *  per-question data context) caches across this question's rounds.
     *  `cache_control` is ignored by providers that don't support it. */
    private fun cacheControlledMessages(): List<JsonObject> {
        val out = messages.toMutableList()
        val systemIdxs = out.indices.filter {
            ((out[it]["role"]) as? JsonPrimitive)?.contentOrNull == "system"
        }
        systemIdxs.firstOrNull()?.let { out[it] = withCacheControl(out[it]) }
        systemIdxs.lastOrNull()?.let {
            if (it != systemIdxs.first()) out[it] = withCacheControl(out[it])
        }
        return out
    }

    /** Converts a string-content message into the Chat Completions content-array
     *  form carrying a `cache_control` breakpoint. */
    private fun withCacheControl(message: JsonObject): JsonObject {
        val content = message["content"]
        if (content !is JsonPrimitive || !content.isString) return message
        return JsonObject(message + ("content" to JsonArray(listOf(JsonObject(mapOf(
            "type" to JsonPrimitive("text"),
            "text" to JsonPrimitive(content.content),
            "cache_control" to ephemeral(),
        ))))))
    }

    private fun ephemeral() = JsonObject(mapOf("type" to JsonPrimitive("ephemeral")))

    // ── Parse Chat Completions response → OpenAIResponse (internal for tests) ─

    internal fun ingestResponse(root: JsonObject): OpenAIResponse {
        // OpenRouter can surface an upstream provider error in an `error` object
        // even on an HTTP 200.
        (root["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
            throw ResponsesError.Decoding("OpenRouter error: $msg")
        }

        val first = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = first?.get("message") as? JsonObject
            ?: throw ResponsesError.Decoding(
                "OpenRouterClient: no choices in response — ${root.toString().take(300)}")

        val responseId = (root["id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val outputItems = mutableListOf<ResponseOutputItem>()
        // Persist the raw assistant message so a continuation turn can replay it.
        val assistantMessage = mutableMapOf<String, JsonElement>(
            "role" to JsonPrimitive("assistant"),
        )

        val content = (message["content"] as? JsonPrimitive)?.contentOrNull
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
                // Reuse OpenRouter's own tool_call id as the orchestrator's call_id
                // so the tool result message can reference it on the next turn.
                val callId = (call["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?: ("or_call_" + UUID.randomUUID().toString().replace("-", "").take(12))
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

    /** Maps OpenRouter's Chat Completions `usage` block. `cost` is the exact USD
     *  OpenRouter billed for the call (requested via `usage.include` in the
     *  body), so it's stored as the reported cost instead of a catalog estimate. */
    private fun usage(root: JsonObject): com.pulseloop.coach.usage.CoachTokenUsage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val cached = (usage["prompt_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val cost = (usage["cost"] as? JsonPrimitive)?.doubleOrNull
        return com.pulseloop.coach.usage.CoachTokenUsage(
            inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedInputTokens = cached,
            reportedCostUSD = cost,
        )
    }
}
