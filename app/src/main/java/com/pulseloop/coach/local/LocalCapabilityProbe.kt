package com.pulseloop.coach.local

import com.pulseloop.coach.config.LocalStructuredOutput
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.ResponsesHttp
import kotlinx.serialization.json.*

/**
 * Self-configuration for the local provider: given only a base URL, work out which engine is
 * behind it, what model it serves, and which optional request fields it will actually accept —
 * then hand back settings the user doesn't have to reason about.
 *
 * **Why capabilities have to be probed rather than looked up.** `/v1/models` describes the model,
 * not the server's request surface, and the two things most likely to fail a coach turn are
 * decided at *launch time* by flags that endpoint never mentions: vLLM rejects `tools` unless it
 * was started with `--enable-auto-tool-choice --tool-call-parser`, and structured-output support
 * varies by backend and build (LM Studio implements `json_schema` but not `json_object`; some
 * llama.cpp builds error when `json_schema` meets a server-side `grammar`). The only honest test
 * is to send the field and see whether the server takes it — so [run] sends two deliberately tiny
 * chat requests, one carrying a throwaway tool and one carrying a throwaway `response_format`.
 *
 * Cost: two generations of at most a few tokens. On a server that has to page the model in first
 * (Ollama, LM Studio) the first probe can take tens of seconds — hence [PROBE_TIMEOUT_SECONDS].
 *
 * Failure is never destructive. A probe that errors for an unrelated reason (network blip, model
 * still loading) leaves that capability at its safe default rather than switching it off, and the
 * report says the probe was inconclusive so the UI can say so too.
 */
object LocalCapabilityProbe {

    /** Which server is behind the URL. Cosmetic — it drives the summary line and the hints, not
     *  the request body; every real decision comes from [Report]'s probed capabilities. */
    enum class Engine(val label: String) {
        OLLAMA("Ollama"),
        LLAMA_CPP("llama.cpp"),
        VLLM("vLLM"),
        SGLANG("SGLang"),
        LM_STUDIO("LM Studio"),
        UNKNOWN("OpenAI-compatible server"),
    }

    /** A probed capability. [UNKNOWN] means the probe itself failed, so don't change the setting. */
    enum class Support { YES, NO, UNKNOWN }

    data class Report(
        val engine: Engine,
        /** Engine version when it advertises one. */
        val version: String? = null,
        val models: List<String> = emptyList(),
        /** The model to use: the sole served model, else the previously-chosen one if the server
         *  still lists it, else blank for the user to pick. */
        val suggestedModel: String = "",
        val toolCalling: Support = Support.UNKNOWN,
        val jsonSchema: Support = Support.UNKNOWN,
        val jsonObject: Support = Support.UNKNOWN,
        /** Per-probe detail, shown under the summary so an inconclusive result is explainable. */
        val notes: List<String> = emptyList(),
    ) {
        /** The structured-output mode to store: the strongest one the server accepted. Falls back
         *  to OFF, which needs nothing from the server. */
        val suggestedStructuredOutput: LocalStructuredOutput = when {
            jsonSchema == Support.YES -> LocalStructuredOutput.JSON_SCHEMA
            jsonObject == Support.YES -> LocalStructuredOutput.JSON_OBJECT
            else -> LocalStructuredOutput.OFF
        }

        /** Tool calling stays ON unless the server actively refused it — an inconclusive probe
         *  must not silently strip the coach of its ability to read the user's data. */
        val suggestedToolCalling: Boolean get() = toolCalling != Support.NO

        /** One line for the Settings summary. */
        val summary: String get() = buildString {
            append(engine.label)
            version?.let { append(" $it") }
            append(" · ")
            append(if (suggestedModel.isNotBlank()) suggestedModel else "${models.size} model(s)")
            append(" · tools ")
            append(when (toolCalling) {
                Support.YES -> "yes"; Support.NO -> "no"; Support.UNKNOWN -> "unknown"
            })
            append(" · ")
            append(when (suggestedStructuredOutput) {
                LocalStructuredOutput.JSON_SCHEMA -> "strict schema"
                LocalStructuredOutput.JSON_OBJECT -> "JSON mode"
                LocalStructuredOutput.OFF -> "prompt-only"
            })
        }
    }

    /** Raised when the server can't be reached or isn't OpenAI-compatible — [run]'s only hard
     *  failure. Everything after model discovery degrades to [Support.UNKNOWN] instead. */
    class Unreachable(val reason: String) : Exception(reason)

    /**
     * Discovers everything about [baseUrl] in one pass. [currentModel] is preserved when the
     * server still lists it, so re-probing doesn't silently move a working setup to another model.
     *
     * @throws Unreachable when `/v1/models` fails — nothing else can be trusted after that.
     */
    suspend fun run(
        baseUrl: String,
        apiKey: String? = null,
        currentModel: String = "",
    ): Report {
        LocalEndpoint.validate(baseUrl)?.let { throw Unreachable(LocalEndpoint.message(it)) }
        val headers = mutableMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }

        // 1. Models — also the reachability check, so its failure is the one hard failure.
        val models = when (val r = LocalModelCatalog.fetch(baseUrl, apiKey)) {
            is LocalModelCatalog.Result.Success -> r.models
            is LocalModelCatalog.Result.Failure -> throw Unreachable(r.message)
        }
        val model = pickModel(models, currentModel)

        // 2. Engine identity — best-effort, from the engine-specific info routes. Never fatal.
        val (engine, version) = identify(baseUrl, headers)

        val notes = mutableListOf<String>()
        if (model.isBlank()) {
            // Capability probes need a model name on every engine except llama.cpp, and without
            // one a 400 would be indistinguishable from "capability unsupported".
            notes.add("Pick a model, then run this again to detect tools and response format.")
            return Report(engine, version, models, model, notes = notes)
        }

        // 3. Capability probes.
        val tools = probe(baseUrl, headers, model, TOOL_PROBE, "Tool calling", notes)
        val schema = probe(baseUrl, headers, model, SCHEMA_PROBE, "Strict schema", notes)
        // Only worth asking about the weaker mode when the stronger one was refused.
        val obj = if (schema == Support.YES) Support.UNKNOWN
            else probe(baseUrl, headers, model, JSON_OBJECT_PROBE, "JSON mode", notes)

        return Report(engine, version, models, model, tools, schema, obj, notes)
    }

    /** Sole model → use it. Otherwise keep the user's current pick when the server still has it;
     *  else blank, because guessing among several would silently switch a working setup. */
    internal fun pickModel(models: List<String>, currentModel: String): String = when {
        currentModel.isNotBlank() && currentModel in models -> currentModel
        models.size == 1 -> models.first()
        else -> ""
    }

    // ── Engine identity ──────────────────────────────────────────────────

    /**
     * Asks each engine's own info route in turn and stops at the first that answers. These are
     * distinct paths rather than a single field because `owned_by` in `/v1/models` is unreliable
     * (vLLM says "vllm", but Ollama says "library" and LM Studio says "organization_owner", and a
     * proxy rewrites all of them). Every call is best-effort — an engine we can't name still works.
     */
    private suspend fun identify(baseUrl: String, headers: Map<String, String>): Pair<Engine, String?> {
        val base = LocalEndpoint.normalize(baseUrl) ?: return Engine.UNKNOWN to null
        // vLLM: GET /version -> {"version":"0.27.1"}
        get("$base/version", headers)?.let { body ->
            versionField(body)?.let { return Engine.VLLM to it }
        }
        // Ollama: GET /api/version -> {"version":"0.x.y"}
        get("$base/api/version", headers)?.let { body ->
            versionField(body)?.let { return Engine.OLLAMA to it }
        }
        // llama.cpp: GET /props -> build_info / default_generation_settings
        get("$base/props", headers)?.let { body ->
            val root = jsonObjectOrNull(body)
            if (root != null && (root.containsKey("build_info") || root.containsKey("default_generation_settings"))) {
                return Engine.LLAMA_CPP to (root["build_info"] as? JsonPrimitive)?.contentOrNull
            }
        }
        // SGLang: GET /get_server_info -> model_path / version
        get("$base/get_server_info", headers)?.let { body ->
            val root = jsonObjectOrNull(body)
            if (root != null && (root.containsKey("model_path") || root.containsKey("version"))) {
                return Engine.SGLANG to (root["version"] as? JsonPrimitive)?.contentOrNull
            }
        }
        // LM Studio: its richer native listing, absent everywhere else.
        get("$base/api/v0/models", headers)?.let { return Engine.LM_STUDIO to null }
        return Engine.UNKNOWN to null
    }

    private suspend fun get(url: String, headers: Map<String, String>): String? = try {
        ResponsesHttp.get(url, headers, IDENTIFY_TIMEOUT_SECONDS)
    } catch (_: Exception) {
        null   // A 404 here just means "not this engine".
    }

    private fun jsonObjectOrNull(body: String): JsonObject? = try {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) { null }

    private fun versionField(body: String): String? =
        (jsonObjectOrNull(body)?.get("version") as? JsonPrimitive)?.contentOrNull

    // ── Capability probes ────────────────────────────────────────────────

    /**
     * Sends [extra] alongside a one-token chat request and classifies the answer.
     *
     * A 4xx is the server telling us it won't take the field — that's [Support.NO], and the exact
     * status doesn't matter (vLLM answers 400 for a disabled tool parser and 422 for a field its
     * deserializer doesn't know). A 5xx or a transport failure says nothing about the capability,
     * so it stays [Support.UNKNOWN] and the caller keeps its default.
     */
    private suspend fun probe(
        baseUrl: String,
        headers: Map<String, String>,
        model: String,
        extra: Map<String, JsonElement>,
        label: String,
        notes: MutableList<String>,
    ): Support {
        val url = LocalEndpoint.chatCompletionsUrl(baseUrl) ?: return Support.UNKNOWN
        val body = JsonObject(buildMap {
            put("model", JsonPrimitive(model))
            put("messages", JsonArray(listOf(JsonObject(mapOf(
                "role" to JsonPrimitive("user"),
                "content" to JsonPrimitive("hi"),
            )))))
            put("max_tokens", JsonPrimitive(PROBE_MAX_TOKENS))
            putAll(extra)
        })
        return try {
            ResponsesHttp.post(
                url,
                Json.encodeToString(JsonObject.serializer(), body).toByteArray(),
                headers,
                PROBE_TIMEOUT_SECONDS,
            )
            Support.YES
        } catch (e: ResponsesError.Http) {
            if (e.status in 400..499) {
                notes.add("$label: not supported (HTTP ${e.status}) — ${shorten(e.body)}")
                Support.NO
            } else {
                notes.add("$label: couldn't tell (HTTP ${e.status}) — left unchanged.")
                Support.UNKNOWN
            }
        } catch (e: Exception) {
            notes.add("$label: couldn't tell (${e.message ?: "no response"}) — left unchanged.")
            Support.UNKNOWN
        }
    }

    /** Server error bodies are verbose; the first line is the part worth showing. */
    private fun shorten(body: String): String {
        val message = try {
            ((jsonObjectOrNull(body)?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)
                ?.contentOrNull
        } catch (_: Exception) { null } ?: body
        return message.trim().lineSequence().firstOrNull().orEmpty().take(160)
    }

    /** A throwaway tool. Named so it can't collide with a real coach tool in a server-side log. */
    private val TOOL_PROBE: Map<String, JsonElement> = mapOf(
        "tools" to JsonArray(listOf(JsonObject(mapOf(
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf(
                "name" to JsonPrimitive("pulseloop_probe"),
                "description" to JsonPrimitive("Capability probe. Do not call."),
                "parameters" to JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
            )),
        )))),
    )

    /** A minimal schema, not the coach's: we're testing whether the *field* is accepted, and a
     *  large schema risks a rejection about the schema itself rather than the capability. */
    private val SCHEMA_PROBE: Map<String, JsonElement> = mapOf(
        "response_format" to JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "json_schema" to JsonObject(mapOf(
                "name" to JsonPrimitive("pulseloop_probe"),
                "strict" to JsonPrimitive(true),
                "schema" to JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ok" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ok"))),
                    "additionalProperties" to JsonPrimitive(false),
                )),
            )),
        )),
    )

    private val JSON_OBJECT_PROBE: Map<String, JsonElement> = mapOf(
        "response_format" to JsonObject(mapOf("type" to JsonPrimitive("json_object"))),
    )

    /** Long enough for a cold model to page in on Ollama/LM Studio. */
    private const val PROBE_TIMEOUT_SECONDS = 120
    /** Short: these routes either exist or 404 immediately. */
    private const val IDENTIFY_TIMEOUT_SECONDS = 10
    /** Enough that a grammar-constrained probe emits something, small enough to stay cheap. */
    private const val PROBE_MAX_TOKENS = 8
}
