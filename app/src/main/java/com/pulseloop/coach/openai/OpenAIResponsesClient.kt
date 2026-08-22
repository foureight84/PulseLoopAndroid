package com.pulseloop.coach.openai

import com.pulseloop.coach.attachments.CoachImagePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType

/**
 * Ported from the [ResponsesClient] protocol in ResponsesTypes.swift.
 * The provider-neutral coach model client: takes an OpenAI Responses-API JSON
 * request body and returns the parsed response. `OpenAIResponsesClient` speaks
 * the API natively; `GeminiClient` / `OpenRouterClient` adapt it to their
 * providers so no other component needs to know which provider is active.
 */
interface ResponsesClient {
    suspend fun send(requestBody: ByteArray): OpenAIResponse
}

/**
 * Ported from [OpenAIResponsesClient] in OpenAIResponsesClient.swift.
 * OkHttp-based client for the OpenAI Responses API (POST /v1/responses).
 */
class OpenAIResponsesClient(
    private val apiKey: String,
    private val endpoint: String = "https://api.openai.com/v1/responses",
) : ResponsesClient {
    override suspend fun send(requestBody: ByteArray): OpenAIResponse {
        if (apiKey.isBlank()) throw ResponsesError.MissingAPIKey
        val body = ResponsesHttp.post(endpoint, requestBody, mapOf("Authorization" to "Bearer $apiKey"))
        if (body.isBlank()) throw ResponsesError.EmptyOutput
        return OpenAIResponse.parse(body)
    }
}

/**
 * Shared OkHttp transport for every [ResponsesClient]. One process-wide client
 * (30 s connect / 60 s read) instead of a fresh connection pool per agent turn,
 * and one place for the [ResponsesError.Transport]/[ResponsesError.Http] mapping
 * all three providers share.
 *
 * Both entry points switch to [Dispatchers.IO] themselves. `execute()` is a BLOCKING call, and
 * Android throws `NetworkOnMainThreadException` for one on the main thread — an exception whose
 * `message` is **null**, so it surfaces to the user as a bare "no connection" that points at
 * their network instead of at the code. `CoachOrchestrator` happens to wrap its turns in
 * `withContext(Dispatchers.IO)` already, which is why this was invisible until a Compose
 * `scope.launch` (Settings' server-detect button, which runs on `Dispatchers.Main`) became the
 * second caller. Owning the dispatcher here rather than trusting every call site removes the
 * whole class of bug; the redundant nesting for orchestrator calls costs nothing.
 */
internal object ResponsesHttp {
    private val jsonMediaType = "application/json".toMediaType()

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * POSTs a JSON [body] to [url] and returns the raw response body. Throws
     * [ResponsesError.Transport] on network failure and [ResponsesError.Http]
     * (with the error body) on a non-2xx status.
     */
    suspend fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        readTimeoutSeconds: Int? = null,
        followRedirects: Boolean = true,
    ): String {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(jsonMediaType, body))
        for ((name, value) in headers) builder.header(name, value)
        val request = builder.build()
        val call = clientFor(readTimeoutSeconds, followRedirects)

        return withContext(Dispatchers.IO) {
            for (attempt in 0..MAX_UNSENT_RETRIES) {
                try {
                    // Both the call and the body read live inside the try. A read timeout can fire
                    // while the body is still streaming, and if that escaped uncaught it would reach
                    // CoachTurnError as a bare SocketTimeoutException — bypassing the transport copy
                    // and printing the JDK's one-word "timeout" again, the exact bug this fixes.
                    // `use` closes the response on every path, including a mid-read failure.
                    return@withContext call.newCall(request).execute().use { response ->
                        val text = response.body?.string() ?: ""
                        if (!response.isSuccessful) throw ResponsesError.Http(response.code, text)
                        text
                    }
                } catch (e: ResponsesError) {
                    // An HTTP status is an answer from the provider, not a transport failure. Never
                    // retried, and never re-wrapped as Transport by the catch below.
                    throw e
                } catch (e: Exception) {
                    // Only retry failures that provably never reached the provider: DNS resolution and
                    // TCP connect. A momentary DNS miss (radio handover, a VPN or private-DNS resolver
                    // still coming up) otherwise kills the whole turn and burns the user's message.
                    //
                    // A read timeout is deliberately NOT retried. OkHttp reports connect and read
                    // timeouts as the same SocketTimeoutException, so we cannot tell "never sent" from
                    // "sent, answer lost" — and re-sending the latter bills the user's API key for a
                    // generation that already ran.
                    if (!isProvablyUnsent(e) || attempt == MAX_UNSENT_RETRIES) throw ResponsesError.Transport(e)
                    // delay(), not Thread.sleep(): the turn is cancellable (the user leaves the coach
                    // screen, WorkManager stops the summary worker), and a blocking sleep would keep an
                    // IO thread parked and then fire the remaining doomed attempts anyway.
                    delay(RETRY_BACKOFF_MS shl attempt)   // 400ms, 800ms
                }
            }
            // Unreachable — the final attempt either returns or throws — but keeps the
            // compiler happy.
            throw IllegalStateException("request never ran")
        }
    }

    /**
     * A one-off `GET`, for the local provider's `/v1/models` discovery. No retry: unlike a chat
     * turn this is a user-initiated refresh they can simply press again, and a failure here is
     * informational rather than a burned message.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutSeconds: Int? = null,
        followRedirects: Boolean = true,
    ): String {
        val builder = okhttp3.Request.Builder().url(url).get()
        for ((name, value) in headers) builder.header(name, value)
        val request = builder.build()
        return withContext(Dispatchers.IO) {
            try {
                clientFor(readTimeoutSeconds, followRedirects).newCall(request).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) throw ResponsesError.Http(response.code, text)
                    text
                }
            } catch (e: ResponsesError) {
                throw e
            } catch (e: Exception) {
                throw ResponsesError.Transport(e)
            }
        }
    }

    /**
     * The shared client, or a derived one with a longer read timeout. `newBuilder()` shares the
     * connection pool and dispatcher, so a self-hosted model that thinks for three minutes doesn't
     * cost us a second pool. Null (every cloud provider) keeps the 60 s default.
     *
     * [followRedirects] = false is the local provider's. `LocalEndpoint.validate` vets the URL the
     * *user typed* — it cannot vet where a redirect lands, and the app permits cleartext app-wide
     * (`network_security_config.xml`, which has no CIDR syntax), so a 307 from the validated LAN
     * host to a public `http://` one would resend the health-context POST body in the clear with
     * nothing left to stop it. Cloud providers keep redirects; their hosts are https:// constants.
     */
    private fun clientFor(
        readTimeoutSeconds: Int?,
        followRedirects: Boolean = true,
    ): okhttp3.OkHttpClient =
        if ((readTimeoutSeconds == null || readTimeoutSeconds <= 0) && followRedirects) client
        else client.newBuilder()
            .apply {
                if (readTimeoutSeconds != null && readTimeoutSeconds > 0)
                    readTimeout(readTimeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                if (!followRedirects) { followRedirects(false); followSslRedirects(false) }
            }
            .build()

    /**
     * True when [e] means the request never left the device, so re-sending it is side-effect free.
     * `UnknownHostException` is DNS; `ConnectException` is a refused/unreachable TCP connect.
     */
    internal fun isProvablyUnsent(e: Throwable): Boolean =
        e is java.net.UnknownHostException || e is java.net.ConnectException

    private const val MAX_UNSENT_RETRIES = 2
    private const val RETRY_BACKOFF_MS = 400L
}

/** One parsed Responses-API function tool spec, provider-neutral. */
internal data class FunctionToolSpec(
    val name: String,
    val description: String?,
    val parameters: JsonObject?,
    val strict: Boolean?,
)

internal data class ParsedToolSpecs(
    val functions: List<FunctionToolSpec>,
    /** True when the OpenAI hosted `web_search` spec was present (it has no
     *  function equivalent; each adapter routes it its own way). */
    val webSearchRequested: Boolean,
)

/**
 * Splits the app's flat Responses-API tool specs (`{type, name, description,
 * parameters, strict}`) into function declarations plus the hosted web-search
 * request. This interpretation must be identical across the Gemini and
 * OpenRouter adapters — only the final provider shape may differ.
 */
internal object ResponsesToolSpecs {
    fun parse(tools: List<JsonObject>): ParsedToolSpecs {
        var webSearch = false
        val functions = tools.mapNotNull { tool ->
            val type = (tool["type"] as? JsonPrimitive)?.contentOrNull
            if (type == "web_search" || type == "web_search_preview") {
                webSearch = true
                return@mapNotNull null
            }
            if (type != "function") return@mapNotNull null
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            FunctionToolSpec(
                name = name,
                description = (tool["description"] as? JsonPrimitive)?.contentOrNull,
                parameters = tool["parameters"] as? JsonObject,
                strict = (tool["strict"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
        return ParsedToolSpecs(functions, webSearch)
    }
}

/**
 * Ported from [ResponsesError] in ResponsesErrors.swift.
 * Provider-agnostic: thrown by every [ResponsesClient] (OpenAI, Gemini,
 * OpenRouter). The case payloads are exposed as properties so
 * `CoachTurnError` can map them to a displayable code + reason.
 */
sealed class ResponsesError(message: String) : Exception(message) {
    data object MissingAPIKey : ResponsesError("No API key configured for the selected provider.")
    class Transport(val underlying: Throwable) :
        ResponsesError("Network error: ${underlying.message}")
    class Http(val status: Int, val body: String) :
        ResponsesError("The provider returned HTTP $status: ${body.take(200)}")
    class Decoding(val msg: String) : ResponsesError("Could not parse the model response: $msg")
    data object EmptyOutput : ResponsesError("The model returned no output.")
}

/**
 * Ported from [OpenAIRequestBuilder] in ResponsesTypes.swift (message subset).
 * Builds Responses-API input items. The text path keeps `content` a plain
 * string so the adapter clients' string branches are untouched; images are
 * purely additive — only when `images` is non-empty does `content` become the
 * Responses-API content-part array (`input_text` + `input_image`).
 */
object OpenAIRequestBuilder {
    fun message(
        role: String,
        content: String,
        images: List<CoachImagePayload> = emptyList(),
    ): JsonObject {
        if (images.isEmpty()) return JsonObject(mapOf(
            "role" to JsonPrimitive(role),
            "content" to JsonPrimitive(content),
        ))
        val parts = mutableListOf<JsonObject>(JsonObject(mapOf(
            "type" to JsonPrimitive("input_text"),
            "text" to JsonPrimitive(content),
        )))
        for (img in images) {
            parts.add(JsonObject(mapOf(
                "type" to JsonPrimitive("input_image"),
                "image_url" to JsonPrimitive(img.dataURL),
            )))
        }
        return JsonObject(mapOf(
            "role" to JsonPrimitive(role),
            "content" to JsonArray(parts),
        ))
    }

    /** A function-call result item to feed back into the next turn. */
    fun functionCallOutput(callId: String, output: String): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("function_call_output"),
        "call_id" to JsonPrimitive(callId),
        "output" to JsonPrimitive(output),
    ))

    /**
     * Optional `reasoning` body entry from `CoachSettings.reasoningEffort`.
     * null/blank → empty map, so the key is absent entirely — models like
     * gpt-4o reject requests that carry `reasoning` at all. Forwarded verbatim
     * by the OpenRouter adapter and ignored by Gemini.
     */
    fun reasoningParams(effort: String?): Map<String, JsonElement> =
        if (effort.isNullOrBlank()) emptyMap()
        else mapOf("reasoning" to JsonObject(mapOf("effort" to JsonPrimitive(effort))))

    /**
     * Model-aware form: also drops `reasoning` for models that don't support it. OpenAI's legacy
     * chat models (gpt-4o, gpt-4, gpt-3.5, chatgpt-*) reject any request carrying `reasoning` with
     * an HTTP 400 — which, combined with the un-gated Settings combo (a non-reasoning model + a set
     * effort), failed *every* coach turn and showed the user one identical fallback. We default to
     * ALLOWING reasoning so unknown/future models aren't blocked, and suppress only the known
     * non-reasoning families. The OpenRouter `vendor/model` prefix is stripped before matching.
     */
    fun reasoningParams(effort: String?, model: String): Map<String, JsonElement> =
        if (!modelSupportsReasoning(model)) emptyMap() else reasoningParams(effort)

    private fun modelSupportsReasoning(model: String): Boolean {
        val slug = model.lowercase().substringAfterLast('/')  // strip OpenRouter vendor prefix
        return !(slug.startsWith("gpt-4") || slug.startsWith("gpt-3") || slug.startsWith("chatgpt"))
    }
}

/**
 * Ported from [OpenAIResponse] in ResponsesTypes.swift.
 * Parsed from the Responses API JSON.
 */
data class OpenAIResponse(
    val id: String = "",
    val output: List<ResponseOutputItem> = emptyList(),
    /** Token usage for this call, when the provider reports it. `null` for turns
     *  that omit the usage block. */
    val usage: com.pulseloop.coach.usage.CoachTokenUsage? = null,
) {
    /** The final assistant message text. */
    val outputText: String
        get() = output.filterIsInstance<MessageOutput>().lastOrNull()
            ?.content?.filterIsInstance<TextContent>()?.joinToString("\n") { it.text } ?: ""

    /** Function call items from the output. */
    val functionCalls: List<FunctionCallOutput>
        get() = output.filterIsInstance<FunctionCallOutput>()

    /** Web search call IDs from the output. */
    val webSearchCallIDs: List<String>
        get() = output.filterIsInstance<WebSearchCall>().map { it.id }

    companion object {
        /**
         * Parses the raw Responses API JSON by hand (matching iOS's manual
         * `[String: Any]` walk in `OpenAIResponse.parse`), NOT via automatic
         * kotlinx.serialization decode. `output` is a heterogeneous array keyed
         * by a `"type"` discriminator ("message" / "function_call" /
         * "web_search_call" / others like "reasoning" for reasoning-effort
         * models) — that shape needs a sealed+`@Serializable` interface with
         * matching `@SerialName`s to auto-decode, which this codebase never had
         * (`ResponseOutputItem` was a bare marker interface), so any real
         * response with non-empty `output` threw "Serializer for subclass
         * '<type>' is not found in the polymorphic scope". Manual parsing also
         * lets unrecognized item types be skipped instead of failing the whole
         * turn.
         */
        fun parse(json: String): OpenAIResponse {
            return try {
                val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(json).jsonObject
                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val output = (root["output"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::parseOutputItem) }
                    ?: emptyList()
                OpenAIResponse(id = id, output = output, usage = parseUsage(root["usage"] as? JsonObject))
            } catch (e: Exception) {
                throw ResponsesError.Decoding(e.message ?: "unknown")
            }
        }

        private fun parseOutputItem(item: JsonObject): ResponseOutputItem? =
            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                "message" -> MessageOutput(
                    role = (item["role"] as? JsonPrimitive)?.contentOrNull ?: "assistant",
                    content = (item["content"] as? JsonArray)?.mapNotNull { c ->
                        val obj = c as? JsonObject ?: return@mapNotNull null
                        if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "output_text") {
                            TextContent((obj["text"] as? JsonPrimitive)?.contentOrNull ?: "")
                        } else null
                    } ?: emptyList(),
                )
                "function_call" -> FunctionCallOutput(
                    id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: "",
                    callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: "",
                    name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                    arguments = (item["arguments"] as? JsonPrimitive)?.contentOrNull ?: "",
                    status = (item["status"] as? JsonPrimitive)?.contentOrNull ?: "",
                )
                "web_search_call" -> WebSearchCall(id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: "")
                // "reasoning" and any future item types carry nothing this app
                // reads — skip rather than fail the turn.
                else -> null
            }

        /** Reads the Responses-API `usage` block: `input_tokens` / `output_tokens`,
         *  with cached input under `input_tokens_details.cached_tokens`. Returns
         *  `null` when the block is absent so callers keep their default. */
        private fun parseUsage(usage: JsonObject?): com.pulseloop.coach.usage.CoachTokenUsage? {
            if (usage == null) return null
            val cached = (usage["input_tokens_details"] as? JsonObject)
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
            return com.pulseloop.coach.usage.CoachTokenUsage(
                inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                cachedInputTokens = cached,
            )
        }
    }
}

interface ResponseOutputItem

@Serializable
data class MessageOutput(
    val role: String = "assistant",
    val content: List<MessageContent> = emptyList(),
) : ResponseOutputItem

@Serializable
sealed interface MessageContent

@Serializable
data class TextContent(val text: String) : MessageContent

@Serializable
data class FunctionCallOutput(
    val id: String = "",
    val callId: String = "",
    val name: String = "",
    val arguments: String = "",
    val status: String = "",
) : ResponseOutputItem

@Serializable
data class WebSearchCall(val id: String = "") : ResponseOutputItem
