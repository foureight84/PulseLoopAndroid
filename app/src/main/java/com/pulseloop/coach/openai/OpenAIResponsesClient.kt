package com.pulseloop.coach.openai

import com.pulseloop.coach.attachments.CoachImagePayload
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
    fun post(url: String, body: ByteArray, headers: Map<String, String> = emptyMap()): String {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(jsonMediaType, body))
        for ((name, value) in headers) builder.header(name, value)

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            throw ResponsesError.Transport(e)
        }
        val text = response.body?.string() ?: ""
        if (!response.isSuccessful) throw ResponsesError.Http(response.code, text)
        return text
    }
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
}

/**
 * Ported from [OpenAIResponse] in ResponsesTypes.swift.
 * Parsed from the Responses API JSON.
 */
@Serializable
data class OpenAIResponse(
    val id: String = "",
    val output: List<ResponseOutputItem> = emptyList(),
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
        fun parse(json: String): OpenAIResponse {
            return try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<OpenAIResponse>(json)
            } catch (e: Exception) {
                throw ResponsesError.Decoding(e.message ?: "unknown")
            }
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
