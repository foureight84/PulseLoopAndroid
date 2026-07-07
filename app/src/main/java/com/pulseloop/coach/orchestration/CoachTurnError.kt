package com.pulseloop.coach.orchestration

import com.pulseloop.coach.openai.ResponsesError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Ported from [CoachTurnError] in CoachTurnError.swift.
 * A user-facing failure for a coach turn, shown as a red-bordered error bubble
 * in chat. Provider-agnostic: every `ResponsesClient` (OpenAI, Gemini,
 * OpenRouter) throws `ResponsesError`, which maps to a short `code` (e.g.
 * "HTTP 401", "Network", "No output") plus a human-readable `reason`.
 */
@Serializable
data class CoachTurnError(
    /** Short, scannable code shown in the bubble header (e.g. "HTTP 429"). */
    val code: String,
    /** The full reason text shown beneath the header. */
    val reason: String,
) {
    /** Plain-text form stored in the message body (history/search fallback). */
    val plainText: String get() = "Coach error · $code\n$reason"

    /** JSON form stored alongside the message so the error bubble can render
     *  the structured code/reason. Reuses the existing field — no schema change. */
    fun encodedJSON(): String? = try {
        json.encodeToString(serializer(), this)
    } catch (_: Exception) {
        null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(fromJSON: String?): CoachTurnError? {
            if (fromJSON.isNullOrBlank()) return null
            return try {
                json.decodeFromString(serializer(), fromJSON)
            } catch (_: Exception) {
                null
            }
        }

        /** Maps a thrown error into a displayable code + reason. Handles the
         *  app's `ResponsesError` cases explicitly and falls back to `message`. */
        fun from(error: Throwable): CoachTurnError = when (error) {
            is ResponsesError.MissingAPIKey -> CoachTurnError(
                code = "No API key",
                reason = "No API key is configured for the selected provider. Add one in Settings → AI Coach.")
            is ResponsesError.Transport -> CoachTurnError(
                code = "Network",
                reason = error.underlying.message ?: "The network request failed.")
            is ResponsesError.Http -> CoachTurnError(
                code = "HTTP ${error.status}",
                reason = cleanReason(error.body, error.status))
            is ResponsesError.Decoding -> CoachTurnError(code = "Response error", reason = error.msg)
            is ResponsesError.EmptyOutput -> CoachTurnError(
                code = "No output",
                reason = "The model returned an empty response. Try again, or pick a different model.")
            else -> CoachTurnError(code = "Error", reason = error.message ?: "Something went wrong.")
        }

        /** Extracts a readable message from a provider error body, which is
         *  usually JSON like `{"error":{"message":"..."}}` (OpenAI/OpenRouter) or
         *  `{"error":{"message":"...","status":"..."}}` (Gemini). Falls back to
         *  the raw body (trimmed) when it isn't that shape. */
        internal fun cleanReason(body: String, status: Int): String {
            val trimmed = body.trim()
            try {
                val root = json.parseToJsonElement(trimmed).jsonObject
                // {"error": {"message": "..."}} or {"error": "..."}
                val err = root["error"]
                if (err is JsonObject) {
                    val msg = (err["message"] as? JsonPrimitive)?.content
                    if (!msg.isNullOrEmpty()) return msg
                }
                if (err is JsonPrimitive && err.isString && err.content.isNotEmpty()) {
                    return err.content
                }
                val msg = root["message"] as? JsonPrimitive
                if (msg != null && msg.isString && msg.content.isNotEmpty()) return msg.content
            } catch (_: Exception) {
                // not JSON — fall through to the raw body
            }
            if (trimmed.isEmpty()) return "The provider returned HTTP $status with no details."
            return trimmed.take(400)
        }
    }
}
