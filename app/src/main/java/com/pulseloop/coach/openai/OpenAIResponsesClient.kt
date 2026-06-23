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
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun send(requestBody: ByteArray): OpenAIResponse {
        if (apiKey.isBlank()) throw ResponsesError.MissingAPIKey

        val request = okhttp3.Request.Builder()
            .url(endpoint)
            .post(okhttp3.RequestBody.create(jsonMediaType, requestBody))
            .header("Authorization", "Bearer $apiKey")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw ResponsesError.Transport(e)
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
