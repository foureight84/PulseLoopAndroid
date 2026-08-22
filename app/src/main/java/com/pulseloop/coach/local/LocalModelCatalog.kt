package com.pulseloop.coach.local

import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.ResponsesHttp
import kotlinx.serialization.json.*

/**
 * `GET {base}/v1/models` against a self-hosted server, so Settings can offer a real model picker
 * instead of making the user type `qwen3:8b` from memory.
 *
 * Every engine in scope serves this route (it's how the OpenAI SDK enumerates models), and every
 * one returns the same envelope: `{"object":"list","data":[{"id":"…"},…]}`. Ollama lists pulled
 * models, LM Studio lists loaded ones, vLLM/SGLang list the single served model, and llama.cpp
 * lists the loaded model under its `--alias`. The list is advisory — the stored model stays a free
 * string, because a router in front of any of these can serve names the endpoint doesn't
 * enumerate.
 */
object LocalModelCatalog {

    /** The outcome of a refresh, kept as data so Settings can show the failure inline. */
    sealed class Result {
        data class Success(val models: List<String>) : Result()
        /** [message] is already user-facing. */
        data class Failure(val message: String) : Result()
    }

    suspend fun fetch(
        baseUrl: String,
        apiKey: String? = null,
        timeoutSeconds: Int = REFRESH_TIMEOUT_SECONDS,
    ): Result {
        LocalEndpoint.validate(baseUrl)?.let { return Result.Failure(LocalEndpoint.message(it)) }
        val url = LocalEndpoint.modelsUrl(baseUrl)
            ?: return Result.Failure(LocalEndpoint.message(LocalEndpoint.Problem.MALFORMED))

        val headers = mutableMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }

        return try {
            Result.Success(parse(ResponsesHttp.get(url, headers, timeoutSeconds)))
        } catch (e: ResponsesError.Http) {
            Result.Failure("The server answered HTTP ${e.status} for /v1/models.")
        } catch (e: ResponsesError.Transport) {
            Result.Failure("Couldn't reach $url — ${e.underlying.message ?: "no connection"}.")
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Couldn't read the model list.")
        }
    }

    /**
     * Pulls the `id`s out of the OpenAI list envelope, sorted and de-duplicated. Falls back to a
     * bare top-level array, which a couple of thin proxies return instead of the envelope.
     */
    internal fun parse(body: String): List<String> {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
        val data = when (root) {
            is JsonObject -> root["data"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: throw ResponsesError.Decoding("No `data` array in the /v1/models response.")
        return data.mapNotNull { entry ->
            when (entry) {
                is JsonObject -> (entry["id"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> entry.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.distinct().sorted()
    }

    /** Short: this is a list lookup behind a button, not a generation. */
    private const val REFRESH_TIMEOUT_SECONDS = 15
}
