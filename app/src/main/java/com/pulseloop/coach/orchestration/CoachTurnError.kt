package com.pulseloop.coach.orchestration

import com.pulseloop.R
import com.pulseloop.util.L10n
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
    val plainText: String get() = L10n.get(R.string.cte_plain, code, reason)

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
                code = L10n.get(R.string.cte_no_key),
                reason = L10n.get(R.string.cte_no_key_reason))
            is ResponsesError.Transport -> CoachTurnError(
                code = L10n.get(R.string.cte_network),
                reason = transportReason(error.underlying))
            is ResponsesError.Http -> CoachTurnError(
                code = "HTTP ${error.status}",
                reason = cleanReason(error.body, error.status))
            is ResponsesError.Decoding -> CoachTurnError(code = L10n.get(R.string.cte_response_error), reason = error.msg)
            is ResponsesError.EmptyOutput -> CoachTurnError(
                code = L10n.get(R.string.cte_no_output),
                reason = L10n.get(R.string.cte_no_output_reason))
            else -> CoachTurnError(code = L10n.get(R.string.dialog_error), reason = error.message ?: L10n.get(R.string.cte_generic))
        }

        /**
         * Turns a raw transport exception into something the user can act on.
         *
         * The JDK's own strings are useless in a chat bubble — a blocked resolver surfaces as
         * `Unable to resolve host "generativelanguage.googleapis.com": No address associated with
         * hostname`, and a socket timeout as the single word `timeout`. Neither hints that the
         * usual causes are an active VPN, a Private DNS entry that doesn't resolve Google hosts, or
         * simply no connectivity. The underlying text is still appended so a bug report keeps it.
         */
        internal fun transportReason(underlying: Throwable): String {
            val detail = underlying.message?.trim().orEmpty()
            return when (underlying) {
                is java.net.UnknownHostException -> {
                    val host = hostFrom(detail)
                    L10n.get(R.string.cte_dns, host ?: L10n.get(R.string.cte_the_provider), detail.ifEmpty { "unknown host" })
                }
                // OkHttp reports connect and read timeouts as this same exception, and only the
                // message tells them apart ("failed to connect to … after 30000ms" vs "timeout").
                // The copy therefore stays neutral about which one happened, and the raw text is
                // appended like every other branch so a bug report can still distinguish them.
                is java.net.SocketTimeoutException ->
                    L10n.get(R.string.cte_timeout, detail.ifEmpty { "timeout" })
                is javax.net.ssl.SSLException ->
                    L10n.get(R.string.cte_tls, detail.ifEmpty { "TLS error" })
                is java.net.ConnectException ->
                    L10n.get(R.string.cte_connect, detail.ifEmpty { "connection refused" })
                else -> detail.ifEmpty { L10n.get(R.string.cte_network_failed) }
            }
        }

        /** Pulls `example.com` out of `Unable to resolve host "example.com": …`, if present. */
        private fun hostFrom(message: String): String? =
            Regex("\"([^\"]+)\"").find(message)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

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
            if (trimmed.isEmpty()) return L10n.get(R.string.cte_http_empty, status)
            return trimmed.take(400)
        }
    }
}
