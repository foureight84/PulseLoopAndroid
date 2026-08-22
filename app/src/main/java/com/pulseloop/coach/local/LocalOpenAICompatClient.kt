package com.pulseloop.coach.local

import com.pulseloop.coach.config.LocalStructuredOutput
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
 * The self-hosted / local coach client — see `docs/local-llm-coach.md`.
 *
 * Adapts the app's [ResponsesClient] interface to the OpenAI **Chat Completions** API
 * (`POST {base}/v1/chat/completions`) as implemented by Ollama, llama.cpp's `llama-server`,
 * vLLM, SGLang, LM Studio and friends. Structurally this is
 * [com.pulseloop.coach.minimax.MiniMaxClient] — same Responses→Chat translation, same
 * accumulate-messages-across-`send` statefulness, same fresh-client-per-turn contract from the
 * factory — with four deliberate differences, each forced by something a local backend does that
 * a hosted one doesn't:
 *
 *  1. **The API key is optional.** Every one of these servers runs unauthenticated by default
 *     (`--api-key` is opt-in on llama.cpp/vLLM/SGLang; Ollama ignores the field entirely). A blank
 *     key omits the `Authorization` header rather than throwing — the readiness sentinel is the
 *     **base URL** instead (see `CoachClientResolver`).
 *  2. **`developer` is folded into `system`, and all system turns are merged into one leading
 *     message.** SGLang validates roles against a pydantic `Literal` and raises (→ HTTP 400) for a
 *     role outside it; vLLM accepts `developer` and hands it to a Jinja chat template that usually
 *     has no branch for it. Many local templates additionally require the system turn to be first
 *     and singular. Folding + merging is lossless and works on all of them.
 *  3. **Capabilities are user-declared, not assumed.** vLLM 400s on `tools` unless the server was
 *     started with `--enable-auto-tool-choice`; LM Studio has no `json_object` mode. Tool calling
 *     and structured output are therefore switches, defaulting to the combination that works
 *     everywhere (tools on, `response_format` off + prompt-injected schema).
 *  4. **A long, configurable read timeout.** A 30B model on CPU can spend minutes on one round.
 *
 * Nothing else is sent: no `reasoning`, no `cache_control`, no provider-routing block. Only Ollama
 * documents `reasoning_effort`, and vLLM/SGLang would warn or reject the rest.
 */
class LocalOpenAICompatClient(
    /** Base URL as the user typed it; normalized via [LocalEndpoint]. */
    private val baseUrl: String,
    private val model: String,
    /** Optional — blank means send no `Authorization` header at all. */
    private val apiKey: String? = null,
    private val toolCallingEnabled: Boolean = true,
    private val structuredOutput: LocalStructuredOutput = LocalStructuredOutput.OFF,
    /** `null`/0 = omit `max_tokens` and let the server decide. */
    private val maxOutputTokens: Int? = null,
    private val readTimeoutSeconds: Int = DEFAULT_READ_TIMEOUT_SECONDS,
) : ResponsesClient {
    private val json = Json { ignoreUnknownKeys = true }

    // Accumulated Chat Completions messages for this turn, minus the system block.
    private var messages = mutableListOf<JsonObject>()
    // The merged leading system message (instructions + per-turn context + schema instruction).
    private var systemPrompt: String = ""
    // Maps generated response IDs → the assistant message (content + tool_calls) so a
    // continuation turn can re-insert it before the matching tool results.
    private val storedAssistantMessage = mutableMapOf<String, JsonObject>()

    override suspend fun send(requestBody: ByteArray): OpenAIResponse {
        // The base URL, not the key, is what makes this provider usable.
        val endpoint = LocalEndpoint.chatCompletionsUrl(baseUrl) ?: throw ResponsesError.MissingAPIKey
        LocalEndpoint.validate(baseUrl)?.let { throw ResponsesError.Decoding(LocalEndpoint.message(it)) }

        val req = try {
            json.parseToJsonElement(String(requestBody)).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("LocalOpenAICompatClient: invalid request body")
        }

        val body = buildRequestBody(req)
        val bodyBytes = json.encodeToString(JsonObject.serializer(), body).toByteArray()

        val headers = mutableMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }

        val responseBody = ResponsesHttp.post(endpoint, bodyBytes, headers, readTimeoutSeconds)

        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding(
                "The server at $endpoint did not return JSON — is it an OpenAI-compatible endpoint?")
        }
        return ingestResponse(root)
    }

    // ── Request assembly (internal for unit tests) ───────────────────────

    internal fun buildRequestBody(req: JsonObject): JsonObject {
        val input = (req["input"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val tools = (req["tools"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val previousResponseId = (req["previous_response_id"] as? JsonPrimitive)?.contentOrNull

        if (previousResponseId == null) setupConversation(input)
        else appendContinuation(previousResponseId, input)

        return buildChatBody(if (toolCallingEnabled) convertTools(tools) else emptyList())
    }

    // ── Conversation setup ───────────────────────────────────────────────

    /**
     * First turn. Every `system`/`developer` item is merged, in order, into a single leading
     * system message; `user`/`assistant` items keep their order after it. The schema instruction
     * joins the system block rather than trailing the conversation (where MiniMax puts it) because
     * a system turn after a user turn raises in several local chat templates.
     */
    private fun setupConversation(input: List<JsonObject>) {
        messages = mutableListOf()
        storedAssistantMessage.clear()

        val systemParts = mutableListOf<String>()
        val conversation = mutableListOf<JsonObject>()
        for (item in input) {
            val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (item["content"] == null) continue
            if (role == "system" || role == "developer") {
                // A system turn is always plain instruction text; flatten any content parts.
                systemParts.add(flattenText(item))
            } else {
                conversation.add(JsonObject(mapOf(
                    "role" to JsonPrimitive(role),
                    "content" to chatContent(item),
                )))
            }
        }
        // Only the prompt tells an unconstrained local model what shape to answer in. Even with
        // `response_format` on, this stays — it's what the orchestrator's JSON-repair loop leans on
        // when a small model ignores the grammar.
        systemParts.add(CoachResponseSchema.promptInstruction)
        systemPrompt = systemParts.filter { it.isNotBlank() }.joinToString("\n\n")
        messages.addAll(conversation)
    }

    /**
     * Subsequent turns: replay the stored assistant message for [previousId] (Chat Completions
     * requires the assistant `tool_calls` message to precede the `tool` results answering them),
     * then append the new tool results / messages. A stray system/developer item here is folded
     * into the leading system block rather than appended mid-conversation.
     */
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
                if (role == "system" || role == "developer") {
                    systemPrompt = listOf(systemPrompt, flattenText(item))
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                } else {
                    messages.add(JsonObject(mapOf(
                        "role" to JsonPrimitive(role),
                        "content" to chatContent(item),
                    )))
                }
            }
        }
    }

    /** All text in a message item, whether `content` is a string or a content-part array. */
    private fun flattenText(item: JsonObject): String {
        val content = item["content"]
        if (content is JsonPrimitive && content.isString) return content.content
        val parts = (content as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return ""
        return parts.mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
    }

    /**
     * Converts a Responses-API message item's `content` into Chat Completions `content`. Text
     * stays a plain string; images map to `{type:image_url, image_url:{url}}` parts. Local vision
     * backends take base64 `data:` URLs (Ollama explicitly rejects remote image URLs), which is
     * exactly what `CoachImagePayload.dataURL` produces.
     */
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

    /**
     * Flat Responses function specs → Chat Completions' nested `{type:function, function:{…}}`.
     * The hosted `web_search` tool is dropped: no local engine has one. `strict` is dropped too —
     * it's an OpenAI structured-outputs extension that vLLM/SGLang don't act on and some stricter
     * proxies reject inside a function spec.
     */
    private fun convertTools(tools: List<JsonObject>): List<JsonObject> =
        ResponsesToolSpecs.parse(tools).functions.map { spec ->
            val fn = mutableMapOf<String, JsonElement>("name" to JsonPrimitive(spec.name))
            spec.description?.let { fn["description"] = JsonPrimitive(it) }
            spec.parameters?.let { fn["parameters"] = it }
            JsonObject(mapOf(
                "type" to JsonPrimitive("function"),
                "function" to JsonObject(fn),
            ))
        }

    // ── Build request body ───────────────────────────────────────────────

    internal fun buildChatBody(tools: List<JsonObject>): JsonObject {
        val allMessages = mutableListOf<JsonObject>()
        if (systemPrompt.isNotBlank()) {
            allMessages.add(JsonObject(mapOf(
                "role" to JsonPrimitive("system"),
                "content" to JsonPrimitive(systemPrompt),
            )))
        }
        allMessages.addAll(messages)

        val body = mutableMapOf<String, JsonElement>(
            // llama.cpp ignores `model` unless started with --alias; everyone else requires it.
            // Sending it unconditionally is correct for both.
            "model" to JsonPrimitive(model),
            "messages" to JsonArray(allMessages),
        )
        if (tools.isNotEmpty()) body["tools"] = JsonArray(tools)
        responseFormat()?.let { body["response_format"] = it }
        maxOutputTokens?.takeIf { it > 0 }?.let { body["max_tokens"] = JsonPrimitive(it) }
        return JsonObject(body)
    }

    /**
     * The `response_format` block, or null when the user left structured output off (the default,
     * and the only setting that works on every backend). `JSON_SCHEMA` uses the nested OpenAI
     * shape — `{type:"json_schema", json_schema:{name, strict, schema}}` — which vLLM, SGLang,
     * LM Studio and recent llama.cpp all accept. `JSON_OBJECT` is the older, weaker mode; LM
     * Studio doesn't implement it, hence the choice.
     */
    internal fun responseFormat(): JsonObject? = when (structuredOutput) {
        LocalStructuredOutput.OFF -> null
        LocalStructuredOutput.JSON_OBJECT -> JsonObject(mapOf("type" to JsonPrimitive("json_object")))
        LocalStructuredOutput.JSON_SCHEMA -> JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "json_schema" to JsonObject(mapOf(
                "name" to JsonPrimitive("coach_response"),
                "strict" to JsonPrimitive(true),
                "schema" to CoachResponseSchema.schema,
            )),
        ))
    }

    // ── Parse Chat Completions response → OpenAIResponse (internal for tests) ─

    internal fun ingestResponse(root: JsonObject): OpenAIResponse {
        // Some servers (and most reverse proxies in front of them) report errors in the body on an
        // HTTP 200. `error` may be an object or, on llama.cpp, a bare string.
        (root["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: err.toString().take(200)
            throw ResponsesError.Decoding("Server error: $msg")
        }
        (root["error"] as? JsonPrimitive)?.contentOrNull?.let {
            throw ResponsesError.Decoding("Server error: $it")
        }

        val first = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = first?.get("message") as? JsonObject
            ?: throw ResponsesError.Decoding(
                "No `choices` in the response — the server may not be OpenAI-compatible. " +
                "Got: ${root.toString().take(300)}")

        val responseId = (root["id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val outputItems = mutableListOf<ResponseOutputItem>()
        val assistantMessage = mutableMapOf<String, JsonElement>("role" to JsonPrimitive("assistant"))

        // Open reasoning models emit their chain of thought either as an inline `<think>…</think>`
        // block (llama.cpp/Ollama without a reasoning parser) or split into a separate
        // `reasoning_content` field (vLLM/SGLang with one). Neither belongs in the coach_response
        // JSON: the first is stripped, the second is simply not read.
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull?.let { stripThinking(it) }
        if (!content.isNullOrEmpty()) {
            outputItems.add(MessageOutput(role = "assistant", content = listOf(TextContent(content))))
            assistantMessage["content"] = JsonPrimitive(content)
        } else {
            assistantMessage["content"] = JsonNull
        }

        val toolCalls = (message["tool_calls"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        if (toolCalls != null) {
            val storedCalls = mutableListOf<JsonObject>()
            for (call in toolCalls) {
                val fn = call["function"] as? JsonObject ?: continue
                val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                val callId = (call["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?: ("local_call_" + UUID.randomUUID().toString().replace("-", "").take(12))
                // `arguments` is a JSON *string* per the spec, but several local tool-call parsers
                // emit a JSON object instead. Re-encode that so the orchestrator's parse succeeds
                // instead of failing the round on a well-formed-but-differently-typed field.
                val args = when (val a = fn["arguments"]) {
                    is JsonPrimitive -> a.contentOrNull ?: "{}"
                    is JsonObject -> a.toString()
                    else -> "{}"
                }
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

    /** Maps the `usage` block when present. Local servers all report the OpenAI split; a server
     *  that omits it leaves usage null, and the coach shows no token counts rather than zeros. */
    private fun usage(root: JsonObject): com.pulseloop.coach.usage.CoachTokenUsage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val input = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: return null
        val output = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: return null
        return com.pulseloop.coach.usage.CoachTokenUsage(inputTokens = input, outputTokens = output)
    }

    /** Removes `<think>…</think>` reasoning blocks. Tolerant of an unterminated trailing `<think>`
     *  (truncated output) — the remainder is dropped rather than fed to the JSON parser. */
    private fun stripThinking(text: String): String {
        val out = StringBuilder()
        var scan = 0
        while (true) {
            val open = text.indexOf("<think>", scan)
            if (open < 0) break
            out.append(text, scan, open)
            val close = text.indexOf("</think>", open + 7)
            if (close < 0) { scan = text.length; break }
            scan = close + 8
        }
        out.append(text, scan, text.length)
        return out.toString().trim()
    }

    companion object {
        /** Generous by cloud standards, ordinary for a quantized model on consumer hardware. */
        const val DEFAULT_READ_TIMEOUT_SECONDS = 180
    }
}
