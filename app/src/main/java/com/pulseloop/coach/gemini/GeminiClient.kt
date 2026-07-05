package com.pulseloop.coach.gemini

import com.pulseloop.coach.config.GeminiModel
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.MessageOutput
import com.pulseloop.coach.openai.OpenAIResponse
import com.pulseloop.coach.openai.ResponseOutputItem
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.TextContent
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import java.util.UUID

/**
 * Ported from [GeminiClient] in GeminiClient.swift.
 * Adapts the app's [ResponsesClient] interface to Google Gemini's
 * `generateContent` API. Translates OpenAI Responses-API request bodies into
 * Gemini requests and maps responses back so no other component needs to know
 * which provider is active.
 *
 * State across turns: the OpenAI Responses API is stateful (server tracks
 * history via `previous_response_id`). Gemini is stateless (caller sends full
 * history each time). This class accumulates the conversation as Gemini
 * `contents` entries across `send` calls; each instance covers exactly one
 * agent turn (the orchestrator creates a fresh client per turn via the factory).
 */
class GeminiClient(
    private val apiKey: String,
    model: String = GeminiModel.DEFAULT.slug,
    private val baseURL: String = "https://generativelanguage.googleapis.com/v1beta/models",
) : ResponsesClient {
    // The model the orchestrator selected, taken from each request body's `model`
    // field (see `send`); the constructor value is only a fallback if that's absent.
    private var model = model

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Accumulated Gemini-format conversation contents.
    private var systemText = ""
    private var contents = mutableListOf<JsonObject>()
    // Maps generated response IDs → Gemini model parts (function calls + text).
    private val storedModelParts = mutableMapOf<String, List<JsonObject>>()
    // Maps generated call IDs → function names (Gemini uses name, not call_id).
    private val callIdToName = mutableMapOf<String, String>()
    // Web search: the orchestrator requests it via the OpenAI hosted-tool spec.
    // Gemini 2.5 can't combine google_search with functionDeclarations or a JSON
    // responseSchema in one request, so we ground only on a tool-less turn and
    // fire it at most once per agent turn (later repair turns enforce the schema).
    private var webSearchRequested = false
    private var webSearchGrounded = false
    // Sources cited by a google_search turn, queued to prepend to the next user
    // message so the schema turn can fill the response `sources` array.
    private var pendingSourcesNote: String? = null

    override suspend fun send(requestBody: ByteArray): OpenAIResponse {
        if (apiKey.isBlank()) throw ResponsesError.MissingAPIKey

        val req = try {
            json.parseToJsonElement(String(requestBody)).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("GeminiClient: invalid request body")
        }

        val geminiBody = buildRequestBody(req)
        val bodyBytes = json.encodeToString(JsonObject.serializer(), geminiBody).toByteArray()

        val request = okhttp3.Request.Builder()
            .url("$baseURL/$model:generateContent?key=$apiKey")
            .post(okhttp3.RequestBody.create(jsonMediaType, bodyBytes))
            .header("Content-Type", "application/json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw ResponsesError.Transport(e)
        }

        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) throw ResponsesError.Http(response.code, body)

        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw ResponsesError.Decoding("GeminiClient: response was not a JSON object")
        }
        return ingestResponse(root)
    }

    // ── Request assembly (internal for unit tests) ───────────────────────

    /** Translates one Responses-API request into the Gemini `generateContent`
     *  body, updating the accumulated conversation state. */
    internal fun buildRequestBody(req: JsonObject): JsonObject {
        // The orchestrator/services pass the user-selected model in the body
        // (`flags.model`). Honor it so the picker in settings actually takes effect.
        (req["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { model = it }

        val input = (req["input"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val tools = (req["tools"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val textFormat = ((req["text"] as? JsonObject)?.get("format")) as? JsonObject
        val previousResponseId = (req["previous_response_id"] as? JsonPrimitive)?.contentOrNull

        if (previousResponseId == null) {
            setupConversation(input)
        } else {
            appendContinuation(previousResponseId, input)
        }

        return buildGeminiBody(convertTools(tools), textFormat)
    }

    // ── Conversation setup ───────────────────────────────────────────────

    /** First turn: separate system/developer messages into systemInstruction and
     *  convert the remaining history into Gemini contents. */
    private fun setupConversation(input: List<JsonObject>) {
        contents = mutableListOf()
        callIdToName.clear()
        storedModelParts.clear()

        val systemParts = mutableListOf<String>()
        val conversationItems = mutableListOf<JsonObject>()

        for (item in input) {
            val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: ""
            if (role == "system" || role == "developer") {
                val content = (item["content"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (content.isNotEmpty()) systemParts.add(content)
            } else {
                conversationItems.add(item)
            }
        }

        systemText = systemParts.joinToString("\n\n")

        for (item in conversationItems) {
            val role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            val parts = geminiParts(item)
            if (parts.isEmpty()) continue
            val geminiRole = if (role == "assistant") "model" else "user"
            contents.add(JsonObject(mapOf(
                "role" to JsonPrimitive(geminiRole),
                "parts" to JsonArray(parts),
            )))
        }
    }

    /** Converts a Responses-API message item's `content` into Gemini `parts`.
     *  Text items keep `content` as a string → `[{"text": …}]` (unchanged path).
     *  Image items carry `content` as the OpenAI content-part array (`input_text`
     *  + `input_image`), which we map to `{"text": …}` +
     *  `{"inlineData": {mimeType, data}}`. */
    private fun geminiParts(item: JsonObject): List<JsonObject> {
        val content = item["content"]
        if (content is JsonPrimitive && content.isString) {
            return listOf(JsonObject(mapOf("text" to JsonPrimitive(content.content))))
        }
        val parts = (content as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        val out = mutableListOf<JsonObject>()
        for (part in parts) {
            when ((part["type"] as? JsonPrimitive)?.contentOrNull) {
                "input_text", "text" -> {
                    (part["text"] as? JsonPrimitive)?.contentOrNull?.let {
                        out.add(JsonObject(mapOf("text" to JsonPrimitive(it))))
                    }
                }
                "input_image" -> {
                    inlineData((part["image_url"] as? JsonPrimitive)?.contentOrNull)?.let {
                        out.add(JsonObject(mapOf("inlineData" to it)))
                    }
                }
            }
        }
        return out
    }

    /** Splits an `input_image` `data:<mime>;base64,<data>` URL into Gemini's
     *  `inlineData` object (`mimeType` + bare base64 `data`). */
    private fun inlineData(url: String?): JsonObject? {
        if (url == null || !url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        val semicolon = url.indexOf(';')
        if (comma < 0 || semicolon < 0 || semicolon >= comma) return null
        val mime = url.substring("data:".length, semicolon)
        val data = url.substring(comma + 1)
        return JsonObject(mapOf(
            "mimeType" to JsonPrimitive(mime),
            "data" to JsonPrimitive(data),
        ))
    }

    /** Subsequent turns (tool results or repair messages): append the stored
     *  model response then the new user content. */
    private fun appendContinuation(previousId: String, input: List<JsonObject>) {
        storedModelParts[previousId]?.let { modelParts ->
            contents.add(JsonObject(mapOf(
                "role" to JsonPrimitive("model"),
                "parts" to JsonArray(modelParts),
            )))
        }

        // Input items may be tool results (function_call_output) or plain messages.
        var userParts = mutableListOf<JsonObject>()
        // Prepend any queued grounding-sources note so it rides with this user turn.
        pendingSourcesNote?.let {
            userParts.add(JsonObject(mapOf("text" to JsonPrimitive(it))))
            pendingSourcesNote = null
        }
        for (item in input) {
            val toolPart = convertToolResult(item)
            if (toolPart != null) {
                userParts.add(toolPart)
            } else if (item["role"] is JsonPrimitive && item["content"] != null) {
                val role = (item["role"] as JsonPrimitive).contentOrNull
                if (role == "assistant") {
                    if (userParts.isNotEmpty()) {
                        contents.add(JsonObject(mapOf(
                            "role" to JsonPrimitive("user"),
                            "parts" to JsonArray(userParts),
                        )))
                        userParts = mutableListOf()
                    }
                    // Assistant replays are always text, but route through the same
                    // converter so a string content still yields `[{"text": …}]`.
                    contents.add(JsonObject(mapOf(
                        "role" to JsonPrimitive("model"),
                        "parts" to JsonArray(geminiParts(item)),
                    )))
                } else {
                    userParts.addAll(geminiParts(item))
                }
            }
        }
        if (userParts.isNotEmpty()) {
            contents.add(JsonObject(mapOf(
                "role" to JsonPrimitive("user"),
                "parts" to JsonArray(userParts),
            )))
        }
    }

    // ── Tool conversion (OpenAI → Gemini) ────────────────────────────────

    /** Converts OpenAI function-tool specs to Gemini `functionDeclarations`. The
     *  OpenAI hosted `web_search` spec has no Gemini function equivalent; instead
     *  it records that grounding is wanted (see `webSearchRequested`) so we can
     *  attach `google_search` on the tool-less final turn. */
    private fun convertTools(tools: List<JsonObject>): List<JsonObject> {
        val decls = tools.mapNotNull { tool ->
            val type = (tool["type"] as? JsonPrimitive)?.contentOrNull
            if (type == "web_search" || type == "web_search_preview") {
                webSearchRequested = true
                return@mapNotNull null
            }
            if (type != "function") return@mapNotNull null
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val decl = mutableMapOf<String, JsonElement>("name" to JsonPrimitive(name))
            (tool["description"] as? JsonPrimitive)?.contentOrNull?.let {
                decl["description"] = JsonPrimitive(it)
            }
            (tool["parameters"] as? JsonObject)?.let { decl["parameters"] = cleanSchema(it) }
            JsonObject(decl)
        }
        if (decls.isEmpty()) return emptyList()
        return listOf(JsonObject(mapOf("functionDeclarations" to JsonArray(decls))))
    }

    /** Strips JSON Schema keywords Gemini rejects and rewrites the parts whose
     *  syntax differs from the OpenAPI subset Gemini expects:
     *    • `additionalProperties` / `$schema` / `strict` — unsupported keywords.
     *    • union types like `["string", "null"]` → single `type` + `nullable`. */
    private fun cleanSchema(schema: JsonObject): JsonObject {
        val out = schema.toMutableMap()
        out.remove("additionalProperties")
        out.remove("\$schema")
        out.remove("strict")

        // Gemini uses `nullable: true` rather than JSON Schema union types.
        (out["type"] as? JsonArray)?.let { types ->
            val names = types.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (names.contains("null")) out["nullable"] = JsonPrimitive(true)
            out["type"] = JsonPrimitive(names.firstOrNull { it != "null" } ?: "string")
        }

        (out["properties"] as? JsonObject)?.let { props ->
            out["properties"] = JsonObject(props.mapValues { (_, v) ->
                (v as? JsonObject)?.let { cleanSchema(it) } ?: v
            })
        }
        (out["items"] as? JsonObject)?.let { out["items"] = cleanSchema(it) }
        return JsonObject(out)
    }

    // ── Tool result conversion ───────────────────────────────────────────

    private fun convertToolResult(item: JsonObject): JsonObject? {
        if ((item["type"] as? JsonPrimitive)?.contentOrNull != "function_call_output") return null
        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val output = (item["output"] as? JsonPrimitive)?.contentOrNull ?: return null
        val name = callIdToName[callId] ?: callId
        val resultData = try {
            json.parseToJsonElement(output)
        } catch (_: Exception) {
            JsonPrimitive(output)
        }
        return JsonObject(mapOf(
            "functionResponse" to JsonObject(mapOf(
                "name" to JsonPrimitive(name),
                "response" to JsonObject(mapOf("result" to resultData)),
            )),
        ))
    }

    // ── Build Gemini request body ────────────────────────────────────────

    private fun buildGeminiBody(tools: List<JsonObject>, textFormat: JsonObject?): JsonObject {
        val body = mutableMapOf<String, JsonElement>("contents" to JsonArray(contents))

        if (systemText.isNotEmpty()) {
            body["systemInstruction"] = JsonObject(mapOf(
                "parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(systemText))))),
            ))
        }
        if (tools.isNotEmpty()) {
            body["tools"] = JsonArray(tools)
            // VALIDATED mode validates function calls with constrained decoding —
            // Gemini's equivalent of OpenAI strict mode. Without it Gemini omits
            // required-but-empty fields (e.g. an empty `annotations` array, a
            // `reason` string), which then fail our non-optional arg decoders.
            body["toolConfig"] = JsonObject(mapOf(
                "functionCallingConfig" to JsonObject(mapOf("mode" to JsonPrimitive("VALIDATED"))),
            ))
        }

        // Web search (Google Search grounding). On Gemini 2.5 google_search can't
        // share a request with functionDeclarations or a JSON responseSchema, so
        // we ground exactly once, on the first tool-less turn: attach google_search
        // and skip the schema. The grounded prose + cited sources land in history;
        // the orchestrator's subsequent tool-less repair turn then enforces the
        // coach_response schema (and can fill the `sources` array from them).
        val groundingTurn = tools.isEmpty() && webSearchRequested && !webSearchGrounded
        if (groundingTurn) {
            body["tools"] = JsonArray(listOf(JsonObject(mapOf(
                "google_search" to JsonObject(emptyMap()),
            ))))
            webSearchGrounded = true
        }

        // Gemini rejects function declarations combined with a JSON response
        // schema, so only constrain output to structured JSON on tool-less turns.
        // The grounding turn also omits the schema (google_search + responseSchema
        // is rejected); the repair turn that follows applies it.
        val genConfig = mutableMapOf<String, JsonElement>()
        if (tools.isEmpty() && !groundingTurn && textFormat != null) {
            val fmtType = (textFormat["type"] as? JsonPrimitive)?.contentOrNull ?: ""
            if (fmtType == "json_schema" || fmtType == "json_object") {
                genConfig["responseMimeType"] = JsonPrimitive("application/json")
                (textFormat["schema"] as? JsonObject)?.let {
                    genConfig["responseSchema"] = cleanSchema(it)
                }
            }
        }
        if (genConfig.isNotEmpty()) body["generationConfig"] = JsonObject(genConfig)

        return JsonObject(body)
    }

    // ── Parse Gemini response → OpenAIResponse (internal for unit tests) ─

    internal fun ingestResponse(root: JsonObject): OpenAIResponse {
        val first = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = ((first?.get("content") as? JsonObject)?.get("parts") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: throw ResponsesError.Decoding(
                "GeminiClient: no candidates in response — ${root.toString().take(300)}")

        val responseId = UUID.randomUUID().toString()
        val outputItems = mutableListOf<ResponseOutputItem>()
        val modelParts = mutableListOf<JsonObject>()
        val textParts = mutableListOf<TextContent>()

        for (part in parts) {
            val text = (part["text"] as? JsonPrimitive)?.contentOrNull
            val fc = part["functionCall"] as? JsonObject
            if (text != null) {
                textParts.add(TextContent(text))
                modelParts.add(JsonObject(mapOf("text" to JsonPrimitive(text))))
            } else if (fc != null) {
                val name = (fc["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                val args = fc["args"] as? JsonObject ?: JsonObject(emptyMap())
                val argsStr = json.encodeToString(JsonObject.serializer(), args)
                val callId = "gemini_call_" + UUID.randomUUID().toString().replace("-", "").take(12)
                callIdToName[callId] = name
                outputItems.add(FunctionCallOutput(id = callId, callId = callId, name = name, arguments = argsStr))
                modelParts.add(JsonObject(mapOf(
                    "functionCall" to JsonObject(mapOf(
                        "name" to JsonPrimitive(name),
                        "args" to args,
                    )),
                )))
            }
        }
        // One MessageOutput carrying every text part — OpenAIResponse.outputText
        // reads the last MessageOutput, so splitting would drop earlier prose.
        if (textParts.isNotEmpty()) {
            outputItems.add(0, MessageOutput(role = "assistant", content = textParts))
        }

        // Grounding sources: when google_search ran, Gemini returns the cited
        // pages in groundingMetadata. Queue them so the next (schema) turn can
        // populate the response `sources` array. Queuing (rather than appending to
        // `contents` here) keeps the user/model turn order valid — the note rides
        // with the next user message in appendContinuation.
        val sources = groundingSources(first)
        if (sources.isNotEmpty()) {
            val sourcesJson = json.encodeToString(JsonArray.serializer(), JsonArray(sources))
            pendingSourcesNote = "Web search sources (use these verbatim to fill the " +
                "response `sources` array as title/url/publisher): $sourcesJson"
        }

        if (outputItems.isEmpty()) throw ResponsesError.EmptyOutput

        storedModelParts[responseId] = modelParts
        return OpenAIResponse(id = responseId, output = outputItems)
    }

    /** Extracts cited pages from a candidate's `groundingMetadata` as
     *  `{title, url, publisher}` objects matching the coach_response sources schema. */
    private fun groundingSources(candidate: JsonObject?): List<JsonObject> {
        val chunks = ((candidate?.get("groundingMetadata") as? JsonObject)
            ?.get("groundingChunks") as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: return emptyList()
        val seen = mutableSetOf<String>()
        val sources = mutableListOf<JsonObject>()
        for (chunk in chunks) {
            val web = chunk["web"] as? JsonObject ?: continue
            val uri = (web["uri"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (uri.isEmpty() || !seen.add(uri)) continue
            val title = (web["title"] as? JsonPrimitive)?.contentOrNull ?: uri
            sources.add(JsonObject(mapOf(
                "title" to JsonPrimitive(title),
                "url" to JsonPrimitive(uri),
                "publisher" to JsonPrimitive(title),
            )))
        }
        return sources
    }
}
