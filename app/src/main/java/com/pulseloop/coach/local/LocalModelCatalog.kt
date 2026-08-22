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

    /**
     * One entry from the listing. [contextWindow] is the model's **context window** (prompt +
     * completion), when the server volunteers it — NOT an output budget; see
     * [LocalCapabilityProbe] for the derivation. Null when the engine doesn't report it here.
     */
    data class ModelInfo(val id: String, val contextWindow: Int? = null)

    /** The outcome of a refresh, kept as data so Settings can show the failure inline. */
    sealed class Result {
        data class Success(val entries: List<ModelInfo>) : Result() {
            val models: List<String> get() = entries.map { it.id }
        }
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
            Result.Success(parseEntries(ResponsesHttp.get(url, headers, timeoutSeconds)))
        } catch (e: ResponsesError.Http) {
            Result.Failure("The server answered HTTP ${e.status} for /v1/models.")
        } catch (e: ResponsesError.Transport) {
            // Some exceptions carry no message at all (NetworkOnMainThreadException is the one
            // that bit us), and "no connection" then sends the user hunting a network fault that
            // isn't there. Fall back to the class name, which at least names the real failure.
            val why = e.underlying.message?.takeIf { it.isNotBlank() }
                ?: e.underlying::class.java.simpleName
            Result.Failure("Couldn't reach $url — $why.")
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Couldn't read the model list.")
        }
    }

    /**
     * Pulls the `id`s out of the OpenAI list envelope, sorted and de-duplicated. Falls back to a
     * bare top-level array, which a couple of thin proxies return instead of the envelope.
     */
    internal fun parse(body: String): List<String> = parseEntries(body).map { it.id }

    /**
     * As [parse], but keeps each entry's context window when the server reports one alongside the
     * id. The field name differs per engine and none of them is the OpenAI spec — vLLM writes
     * `max_model_len`, llama.cpp `n_ctx` (with `n_ctx_train` as the model's trained maximum), and
     * LM Studio `loaded_context_length` / `max_context_length` in its own richer listing. We take
     * the first present, preferring what's actually *loaded* over what the model could support,
     * because the loaded value is the one a request is measured against.
     */
    internal fun parseEntries(body: String): List<ModelInfo> {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
        val data = when (root) {
            is JsonObject -> root["data"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: throw ResponsesError.Decoding("No `data` array in the /v1/models response.")
        return data.mapNotNull { entry ->
            when (entry) {
                is JsonObject -> (entry["id"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ModelInfo(it, contextWindowOf(entry)) }
                is JsonPrimitive -> entry.contentOrNull?.takeIf { it.isNotBlank() }?.let { ModelInfo(it) }
                else -> null
            }
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    /** The context window an entry advertises, under whichever name its engine uses. */
    internal fun contextWindowOf(entry: JsonObject): Int? {
        for (key in CONTEXT_KEYS) {
            val v = (entry[key] as? JsonPrimitive)?.intOrNull
            if (v != null && v > 0) return v
        }
        return null
    }

    /** Ordered by preference: loaded-context first, then configured, then trained maximum. */
    private val CONTEXT_KEYS = listOf(
        "loaded_context_length",   // LM Studio (actually loaded)
        "max_model_len",           // vLLM
        "n_ctx",                   // llama.cpp (as served)
        "max_context_length",      // LM Studio (model ceiling)
        "context_length",          // generic / proxies
        "n_ctx_train",             // llama.cpp (model ceiling)
    )

    /** Short: this is a list lookup behind a button, not a generation. */
    private const val REFRESH_TIMEOUT_SECONDS = 15
}
