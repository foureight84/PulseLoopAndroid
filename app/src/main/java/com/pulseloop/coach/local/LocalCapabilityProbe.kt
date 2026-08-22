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
 * Cost: three generations of at most a few tokens — a plain baseline request first, then the two
 * carrying the fields under test. The baseline is what makes a 4xx readable as "this field is
 * refused" rather than "this request is refused". On a server that has to page the model in first
 * (Ollama, LM Studio) the first of them can take tens of seconds — hence [PROBE_TIMEOUT_SECONDS].
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
        /** The server's **context window** for the chosen model (prompt + completion), when it
         *  reports one. This is NOT an output budget — see [suggestedMaxTokens]. */
        val contextWindow: Int? = null,
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

        /**
         * Whether the probes actually reached a verdict, so a suggestion may **overwrite a setting
         * the user chose by hand**.
         *
         * [suggestedToolCalling] and [suggestedStructuredOutput] both have a safe default for the
         * inconclusive case, which is right for a first-time setup and wrong for a re-detect: a
         * user who turned tools off for a vLLM server without `--enable-auto-tool-choice`, then
         * pressed Detect to refresh the model list, would have them switched back on and every
         * turn would 400. Probes are skipped entirely when the model comes back blank
         * ([pickModel] on a multi-model server) or when the baseline request fails — neither says
         * anything about capabilities.
         */
        val toolCallingConclusive: Boolean get() = toolCalling != Support.UNKNOWN

        /** As [toolCallingConclusive]. One conclusive probe is enough: a `YES` on the strict
         *  schema deliberately leaves the weaker JSON mode untested. */
        val structuredOutputConclusive: Boolean
            get() = jsonSchema != Support.UNKNOWN || jsonObject != Support.UNKNOWN

        /**
         * The value to store in **Max tokens**, derived from [contextWindow]; 0 means "leave
         * blank and let the server decide".
         *
         * A context window is not an output budget, and copying it across would be actively
         * harmful: `max_tokens` is checked against what's *left* after the prompt, so a request
         * with `prompt + max_tokens > context` is rejected outright. We therefore reserve
         * [PROMPT_RESERVE_TOKENS] for the coach's own prompt — measured at ~3.3k for a plain turn,
         * doubled to cover tool results and replayed history — and cap the remainder at
         * [MAX_SUGGESTED_TOKENS], well past what a coach_response needs, so a huge context doesn't
         * turn into a runaway generation budget.
         *
         * When the headroom is too small to be worth setting, this returns 0 and
         * [contextTooSmall] carries the warning instead: a server whose context can't even hold
         * the prompt (Ollama ships a 2048-token `num_ctx` default, smaller than our prompt) will
         * silently truncate, and a wrong `max_tokens` would only mask that.
         */
        val suggestedMaxTokens: Int get() {
            val ctx = contextWindow ?: return 0
            val headroom = ctx - PROMPT_RESERVE_TOKENS
            if (headroom < MIN_USEFUL_OUTPUT_TOKENS) return 0
            return minOf(headroom, MAX_SUGGESTED_TOKENS)
        }

        /** True when the reported context can't comfortably hold the coach's prompt, so the user
         *  needs to raise it on the server (Ollama `num_ctx`, llama.cpp `-c`, vLLM
         *  `--max-model-len`) rather than tune anything in the app. */
        val contextTooSmall: Boolean
            get() = contextWindow != null &&
                contextWindow - PROMPT_RESERVE_TOKENS < MIN_USEFUL_OUTPUT_TOKENS

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
            contextWindow?.let { append(" · ${formatTokens(it)} ctx") }
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
        val entries = when (val r = LocalModelCatalog.fetch(baseUrl, apiKey)) {
            is LocalModelCatalog.Result.Success -> r.entries
            is LocalModelCatalog.Result.Failure -> throw Unreachable(r.message)
        }
        val models = entries.map { it.id }
        val model = pickModel(models, currentModel)

        // 2. Engine identity — best-effort, from the engine-specific info routes. Never fatal.
        val (engine, version) = identify(baseUrl, headers)

        // Context window: from the listing when the engine puts it there (vLLM, llama.cpp,
        // LM Studio), else from that engine's own route.
        val context = entries.firstOrNull { it.id == model }?.contextWindow
            ?: contextFromEngine(baseUrl, headers, engine, model)

        val notes = mutableListOf<String>()
        if (context != null && context - PROMPT_RESERVE_TOKENS < MIN_USEFUL_OUTPUT_TOKENS) {
            notes.add(
                "Context is only ${formatTokens(context)} — the coach's prompt alone is around " +
                "${formatTokens(PROMPT_RESERVE_TOKENS / 2)}. Raise it on the server " +
                "(Ollama `num_ctx`, llama.cpp `-c`, vLLM `--max-model-len`) or replies will be " +
                "truncated."
            )
        }
        if (model.isBlank()) {
            // Capability probes need a model name on every engine except llama.cpp, and without
            // one a 400 would be indistinguishable from "capability unsupported".
            notes.add("Pick a model, then run this again to detect tools and response format.")
            return Report(engine, version, models, model, contextWindow = context, notes = notes)
        }

        // 3. Baseline. A plain chat request with no optional fields at all, so the 4xx-means-no
        //    reading below is about the probed field rather than about the request as a whole.
        //    Skipping this was how an unloadable model id or an auth-gated chat route turned into
        //    "tools: not supported" and a persisted `toolCalling = false`.
        when (val baseline = send(baseUrl, headers, model, BASELINE_PROBE)) {
            is Outcome.Accepted -> Unit
            is Outcome.Refused -> {
                notes.add(
                    "The server refused a plain chat request for `$model` (HTTP " +
                    "${baseline.status}) — ${shorten(baseline.body)}. Tools and response format " +
                    "couldn't be tested, so both are left unchanged. Check the model can actually " +
                    "load and that the chat route accepts the same key as /v1/models."
                )
                return Report(engine, version, models, model, contextWindow = context, notes = notes)
            }
            is Outcome.Inconclusive -> {
                notes.add(
                    "Couldn't complete a plain chat request (${baseline.reason}) — tools and " +
                    "response format are left unchanged."
                )
                return Report(engine, version, models, model, contextWindow = context, notes = notes)
            }
        }

        // 4. Capability probes.
        val tools = probe(baseUrl, headers, model, TOOL_PROBE, "Tool calling", notes)
        val schema = probe(baseUrl, headers, model, SCHEMA_PROBE, "Strict schema", notes)
        // Only worth asking about the weaker mode when the stronger one was refused.
        val obj = if (schema == Support.YES) Support.UNKNOWN
            else probe(baseUrl, headers, model, JSON_OBJECT_PROBE, "JSON mode", notes)

        return Report(engine, version, models, model, tools, schema, obj, context, notes)
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

    /**
     * The context window from an engine's own route, for the two that don't put it in
     * `/v1/models`. Best-effort: a null here just means the app won't suggest a budget.
     *
     * Ollama is the one that matters. Its `/v1/models` carries no context at all, and its default
     * `num_ctx` is **2048** — smaller than the coach's own prompt — so without this a user would
     * get silently truncated context and blame the model.
     */
    private suspend fun contextFromEngine(
        baseUrl: String,
        headers: Map<String, String>,
        engine: Engine,
        model: String,
    ): Int? {
        val base = LocalEndpoint.normalize(baseUrl) ?: return null
        return when (engine) {
            Engine.OLLAMA -> {
                if (model.isBlank()) return null
                val body = JsonObject(mapOf("model" to JsonPrimitive(model)))
                val text = try {
                    ResponsesHttp.post(
                        "$base/api/show",
                        Json.encodeToString(JsonObject.serializer(), body).toByteArray(),
                        headers,
                        IDENTIFY_TIMEOUT_SECONDS,
                        followRedirects = false,
                    )
                } catch (_: Exception) { return null }
                // `model_info` is keyed by architecture, e.g. "qwen3.context_length", so match on
                // the suffix rather than guessing the family.
                val info = jsonObjectOrNull(text)?.get("model_info") as? JsonObject ?: return null
                info.entries.firstOrNull { it.key.endsWith(".context_length") }
                    ?.value?.let { (it as? JsonPrimitive)?.intOrNull }?.takeIf { it > 0 }
            }
            Engine.SGLANG -> {
                val text = get("$base/get_model_info", headers) ?: return null
                val root = jsonObjectOrNull(text) ?: return null
                LocalModelCatalog.contextWindowOf(root)
            }
            Engine.LLAMA_CPP -> {
                val text = get("$base/props", headers) ?: return null
                val root = jsonObjectOrNull(text) ?: return null
                LocalModelCatalog.contextWindowOf(root)
                    ?: (root["default_generation_settings"] as? JsonObject)
                        ?.let { LocalModelCatalog.contextWindowOf(it) }
            }
            else -> null
        }
    }

    /** "262,144" → "262k"; small values stay exact so a 2048 warning reads literally. */
    internal fun formatTokens(tokens: Int): String =
        if (tokens >= 10_000) "${tokens / 1000}k" else tokens.toString()

    private suspend fun get(url: String, headers: Map<String, String>): String? = try {
        ResponsesHttp.get(url, headers, IDENTIFY_TIMEOUT_SECONDS, followRedirects = false)
    } catch (_: Exception) {
        null   // A 404 here just means "not this engine".
    }

    private fun jsonObjectOrNull(body: String): JsonObject? = try {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) { null }

    private fun versionField(body: String): String? =
        (jsonObjectOrNull(body)?.get("version") as? JsonPrimitive)?.contentOrNull

    // ── Capability probes ────────────────────────────────────────────────

    /** What one probe request actually got back, before it is read as a capability verdict. */
    private sealed interface Outcome {
        object Accepted : Outcome
        /** The server answered 4xx — it read the request and refused it. */
        data class Refused(val status: Int, val body: String) : Outcome
        /** 5xx, transport failure, or an unusable URL: says nothing either way. */
        data class Inconclusive(val reason: String) : Outcome
    }

    /** Sends a one-token chat request carrying [extra] and reports what came back. */
    private suspend fun send(
        baseUrl: String,
        headers: Map<String, String>,
        model: String,
        extra: Map<String, JsonElement>,
    ): Outcome {
        val url = LocalEndpoint.chatCompletionsUrl(baseUrl)
            ?: return Outcome.Inconclusive("the server address couldn't be parsed")
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
                followRedirects = false,
            )
            Outcome.Accepted
        } catch (e: ResponsesError.Http) {
            if (e.status in 400..499) Outcome.Refused(e.status, e.body)
            else Outcome.Inconclusive("HTTP ${e.status}")
        } catch (e: Exception) {
            Outcome.Inconclusive(e.message ?: "no response")
        }
    }

    /**
     * Sends [extra] alongside a one-token chat request and classifies the answer.
     *
     * A 4xx is the server telling us it won't take the field — that's [Support.NO], and the exact
     * status doesn't matter (vLLM answers 400 for a disabled tool parser and 422 for a field its
     * deserializer doesn't know). A 5xx or a transport failure says nothing about the capability,
     * so it stays [Support.UNKNOWN] and the caller keeps its default.
     *
     * Reading a 4xx as "this field is unsupported" is only sound because [run] has already
     * established with [BASELINE_PROBE] that a request carrying *no* optional fields succeeds.
     * Without that, every whole-request rejection — a model id `/v1/models` lists but can't load
     * (LM Studio with JIT off, a model pulled between the two calls), a chat route that wants auth
     * when the listing didn't, a broken chat template — would come back as "tools: no" and
     * silently persist `toolCalling = false`, which costs the coach all access to the user's data.
     */
    private suspend fun probe(
        baseUrl: String,
        headers: Map<String, String>,
        model: String,
        extra: Map<String, JsonElement>,
        label: String,
        notes: MutableList<String>,
    ): Support = when (val outcome = send(baseUrl, headers, model, extra)) {
        is Outcome.Accepted -> Support.YES
        is Outcome.Refused -> {
            notes.add("$label: not supported (HTTP ${outcome.status}) — ${shorten(outcome.body)}")
            Support.NO
        }
        is Outcome.Inconclusive -> {
            notes.add("$label: couldn't tell (${outcome.reason}) — left unchanged.")
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

    /** Nothing optional at all — the control the capability probes are measured against. */
    private val BASELINE_PROBE: Map<String, JsonElement> = emptyMap()

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

    /**
     * Context reserved for input before any of it is offered as output budget. A plain coach turn
     * measured 3.1–3.3k input tokens on a real device; this doubles that so a turn that replays
     * history and feeds back tool results still fits.
     */
    internal const val PROMPT_RESERVE_TOKENS = 6144

    /** Below this much headroom, suggesting a budget is worse than saying the context is too small. */
    internal const val MIN_USEFUL_OUTPUT_TOKENS = 512

    /** A coach_response plus reasoning needs far less than this; the cap stops a 262k context from
     *  becoming a licence for a runaway generation. */
    internal const val MAX_SUGGESTED_TOKENS = 32_768
}
