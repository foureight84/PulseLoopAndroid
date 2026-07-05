package com.pulseloop.coach.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType

/**
 * Ported from [OpenAIResponsesClient] in OpenAIResponsesClient.swift.
 * OkHttp-based client for the OpenAI Responses API (POST /v1/responses).
 */
class OpenAIResponsesClient(
    private val apiKey: String,
    private val endpoint: String = "https://api.openai.com/v1/responses",
) {
    private val jsonMediaType = "application/json".toMediaType()

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)  // local 35B models think in minutes
        .build()

    suspend fun send(requestBody: ByteArray): OpenAIResponse {
        // No key is legal for local/LAN servers (llama.cpp ignores auth);
        // only attach the header when a key exists.
        val builder = okhttp3.Request.Builder()
            .url(endpoint)
            .post(okhttp3.RequestBody.create(jsonMediaType, requestBody))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val request = builder.build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            // ponytail: LAN endpoints fall back to the adb-reverse USB tunnel
            // (127.0.0.1:<port>) when the desktop firewall drops inbound Wi-Fi
            // TCP. Remove once the LAN path is confirmed open.
            val lan = Regex("^http://192\\.168\\.[0-9.]+:(\\d+)(/.*)$").find(endpoint)
            if (lan != null) {
                val fallback = "http://127.0.0.1:${lan.groupValues[1]}${lan.groupValues[2]}"
                try {
                    val req2 = builder.url(fallback).build()
                    client.newCall(req2).execute()
                } catch (e2: Exception) {
                    throw ResponsesError.Transport(e)
                }
            } else throw ResponsesError.Transport(e)
        }

        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw ResponsesError.Http(response.code, body)
        }

        val body = response.body?.string() ?: throw ResponsesError.EmptyOutput
        return OpenAIResponse.parse(body)
    }
}

/**
 * Ported from [ResponsesError] in ResponsesErrors.swift.
 */
sealed class ResponsesError(message: String) : Exception(message) {
    data object MissingAPIKey : ResponsesError("No OpenAI API key configured.")
    class Transport(cause: Throwable) : ResponsesError("Network error: ${cause.message}")
    class Http(status: Int, body: String) :
        ResponsesError("OpenAI returned HTTP $status: ${body.take(200)}")
    class Decoding(msg: String) : ResponsesError("Could not parse OpenAI response: $msg")
    data object EmptyOutput : ResponsesError("OpenAI returned no output.")
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
        /**
         * Manual JSON walk keyed on each output item's "type". The old
         * decodeFromString<OpenAIResponse>() could never work at runtime:
         * ResponseOutputItem is an unregistered interface, so kotlinx threw a
         * SerializationException on every response — the coach has been
         * falling back to scripted answers on ALL backends because of it.
         * Unknown item types (e.g. Qwen's "reasoning") are skipped.
         */
        fun parse(json: String): OpenAIResponse {
            return try {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val items = mutableListOf<ResponseOutputItem>()
                for (el in root["output"]?.jsonArray ?: JsonArray(emptyList())) {
                    val obj = el.jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "message" -> items.add(MessageOutput(
                            role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "assistant",
                            content = (obj["content"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { c ->
                                val co = c.jsonObject
                                if (co["type"]?.jsonPrimitive?.contentOrNull == "output_text")
                                    TextContent(co["text"]?.jsonPrimitive?.contentOrNull ?: "")
                                else null
                            },
                        ))
                        "function_call" -> items.add(FunctionCallOutput(
                            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: "",
                            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                        "web_search_call" -> items.add(WebSearchCall(
                            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""))
                        else -> {}  // reasoning blocks, future types: skip
                    }
                }
                OpenAIResponse(id = id, output = items)
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
