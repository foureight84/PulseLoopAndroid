package com.pulseloop.coach.config

import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.ResponsesHttp
import kotlinx.serialization.json.*

/**
 * The models a cloud provider serves right now, read from its own model-list endpoint once the
 * provider is selected and its key saved — so Settings stops depending on preset slugs that go
 * stale (the presets in [CoachModelPresets] stay as the curated picks shown first, and as the
 * fallback when the list can't be fetched).
 *
 * Advisory, like [com.pulseloop.coach.local.LocalModelCatalog]: the stored model stays a free
 * string, so a model the list omits still works if the provider serves it.
 *
 * | Provider   | Endpoint                                   | What it adds                         |
 * |------------|--------------------------------------------|--------------------------------------|
 * | OpenAI     | `GET api.openai.com/v1/models`             | ids only — filtered to chat models   |
 * | Gemini     | `GET …/v1beta/models` (paged)              | display name, `generateContent` only |
 * | OpenRouter | `GET openrouter.ai/api/v1/models`          | name, reasoning levels, expiry date  |
 * | MiniMax    | `GET api.minimax.io/v1/models`             | ids only                             |
 *
 * OpenRouter is the only one that says which reasoning levels a model accepts
 * (`reasoning.supported_efforts`) or when it is being retired (`expiration_date`); for the others
 * those come from the presets, or [ReasoningEfforts] falls back to the common levels.
 */
object CoachModelCatalog {

    /**
     * One model the provider lists. [reasoningEfforts] null = the listing doesn't say;
     * [expiresOn] is an ISO date (`2026-11-18`) when the provider has announced a retirement.
     */
    data class Entry(
        val id: String,
        val name: String? = null,
        val reasoningEfforts: List<ReasoningEffort>? = null,
        val expiresOn: String? = null,
    )

    sealed class Result {
        data class Success(val entries: List<Entry>, val fetchedAtMillis: Long) : Result() {
            fun find(id: String): Entry? = entries.firstOrNull { it.id == id.trim() }
        }
        /** [message] is already user-facing. */
        data class Failure(val message: String) : Result()
    }

    /**
     * True when a fetched listing doesn't include [model] — it has most likely been retired, or
     * (OpenRouter) no longer supports tools. Two exceptions that the listing never shows by design:
     * Gemini's rolling `-latest` aliases, and OpenAI dated snapshots, which [parseOpenAI] drops.
     */
    fun isUnlisted(provider: CoachProviderMode, listing: Result.Success, model: String): Boolean {
        val slug = model.trim()
        if (slug.isEmpty() || listing.find(slug) != null) return false
        if (provider == CoachProviderMode.USER_GEMINI_KEY && slug.endsWith("-latest")) return false
        if (provider == CoachProviderMode.USER_OPENAI_KEY && DATED_SNAPSHOT.containsMatchIn(slug)) return false
        // Unverified whether MiniMax lists every variant (the -highspeed ones), so only a slug we
        // no longer preset — M2.5/M2.7 after their retirement — is flagged there.
        if (provider == CoachProviderMode.USER_MINIMAX_KEY && MiniMaxModel.entries.any { it.slug == slug }) return false
        return true
    }

    /** The providers with a model-list endpoint. Local has its own ([com.pulseloop.coach.local.LocalModelCatalog]). */
    fun supports(provider: CoachProviderMode): Boolean = provider in ENDPOINTS

    /** The last successful listing for [provider], when it was fetched with the same key and is fresh. */
    fun cached(provider: CoachProviderMode, apiKey: String, nowMillis: Long = System.currentTimeMillis()): Result.Success? =
        cache[provider]?.takeIf { it.keyHash == apiKey.hashCode() && nowMillis - it.result.fetchedAtMillis < TTL_MILLIS }?.result

    /** The listed entry for [model] under the active provider, from the cache only (no network). */
    fun cachedEntry(provider: CoachProviderMode, model: String): Entry? =
        cache[provider]?.result?.find(model)

    /**
     * Fetches the listing, or returns the cached one when [force] is false and it is still fresh.
     * Never throws: a failure is returned as data for Settings to show inline.
     */
    suspend fun fetch(provider: CoachProviderMode, apiKey: String, force: Boolean = false): Result {
        if (!force) cached(provider, apiKey)?.let { return it }
        val url = ENDPOINTS[provider] ?: return Result.Failure("This provider has no model list.")
        if (apiKey.isBlank()) return Result.Failure("Save an API key to load the model list.")
        return try {
            val entries = when (provider) {
                CoachProviderMode.USER_GEMINI_KEY -> fetchGemini(url, apiKey)
                CoachProviderMode.USER_OPENAI_KEY -> parseOpenAI(get(url, mapOf("Authorization" to "Bearer $apiKey")))
                CoachProviderMode.USER_OPENROUTER_KEY -> parseOpenRouter(get(url, mapOf("Authorization" to "Bearer $apiKey")))
                CoachProviderMode.USER_MINIMAX_KEY -> parseIds(get(url, mapOf("Authorization" to "Bearer $apiKey")))
                else -> emptyList()
            }
            if (entries.isEmpty()) return Result.Failure("The provider listed no usable models.")
            Result.Success(entries, System.currentTimeMillis()).also {
                cache[provider] = Cached(apiKey.hashCode(), it)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ResponsesError.Http) {
            Result.Failure(
                if (e.status == 401 || e.status == 403) "The provider rejected the key (HTTP ${e.status})."
                else "The provider answered HTTP ${e.status} for its model list."
            )
        } catch (e: ResponsesError.Transport) {
            val why = e.underlying.message?.takeIf { it.isNotBlank() } ?: e.underlying::class.java.simpleName
            Result.Failure("Couldn't load the model list — $why.")
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Couldn't read the model list.")
        }
    }

    // ── Parsing (internal for unit tests) ────────────────────────────────

    /**
     * OpenAI lists every model the key can use — embeddings, speech, image, moderation and
     * realtime ones included — with nothing but an id. Keep the text models the coach can drive
     * through the Responses API, drop dated snapshots (each is also listed under its alias) and the
     * slugs the picker retired (issue #77).
     */
    internal fun parseOpenAI(body: String): List<Entry> =
        dataArray(body).mapNotNull { (it as? JsonObject)?.string("id") }
            .filter { id ->
                (id.startsWith("gpt-") || O_SERIES.matches(id)) &&
                    NON_CHAT.none { it in id } &&
                    !DATED_SNAPSHOT.containsMatchIn(id) &&
                    id !in OpenAIModel.RETIRED_SLUGS
            }
            .distinct().sorted().map { Entry(it) }

    /** One page of `models.list`; ids come as `models/gemini-…` and are stored without the prefix. */
    internal fun parseGeminiPage(body: String): Pair<List<Entry>, String?> {
        val root = JSON.parseToJsonElement(body) as? JsonObject
            ?: throw ResponsesError.Decoding("The Gemini model list was not a JSON object.")
        val entries = (root["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { m ->
            val id = m.string("name")?.removePrefix("models/") ?: return@mapNotNull null
            val methods = (m["supportedGenerationMethods"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (!id.startsWith("gemini") || "generateContent" !in methods || GEMINI_NON_CHAT.any { it in id }) null
            else Entry(id, name = m.string("displayName"))
        }
        return entries to root.string("nextPageToken")?.takeIf { it.isNotBlank() }
    }

    /**
     * OpenRouter's listing is the richest: a name, `supported_parameters`, the reasoning levels the
     * model accepts and an expiry date. The coach drives a tool loop, so a model without `tools`
     * is left out; so are the `:batch` variants, which aren't for interactive use.
     */
    internal fun parseOpenRouter(body: String): List<Entry> =
        dataArray(body).mapNotNull { it as? JsonObject }.mapNotNull { m ->
            val id = m.string("id") ?: return@mapNotNull null
            val params = (m["supported_parameters"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if ("tools" !in params || id.endsWith(":batch")) return@mapNotNull null
            val efforts = ((m["reasoning"] as? JsonObject)?.get("supported_efforts") as? JsonArray)
                ?.mapNotNull { ReasoningEffort.fromRaw((it as? JsonPrimitive)?.contentOrNull) }
                ?.sortedBy { it.ordinal }
            Entry(
                id = id,
                name = m.string("name"),
                // Listed but empty: the model takes no effort hint.
                reasoningEfforts = efforts ?: if ("reasoning" in params) null else emptyList(),
                expiresOn = m.string("expiration_date"),
            )
        }.distinctBy { it.id }.sortedBy { it.id }

    /** The bare OpenAI-style `{"data":[{"id":…}]}` envelope (MiniMax). */
    internal fun parseIds(body: String): List<Entry> =
        dataArray(body).mapNotNull { (it as? JsonObject)?.string("id") }.distinct().sorted().map { Entry(it) }

    // ── Internals ────────────────────────────────────────────────────────

    private suspend fun fetchGemini(url: String, apiKey: String): List<Entry> {
        val all = mutableListOf<Entry>()
        var token: String? = null
        // The listing is a few dozen models; the page cap is only a guard against a looping token.
        repeat(MAX_GEMINI_PAGES) {
            val page = get("$url?pageSize=1000" + (token?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), mapOf("x-goog-api-key" to apiKey))
            val (entries, next) = parseGeminiPage(page)
            all += entries
            token = next ?: return all.distinctBy { it.id }.sortedBy { it.id }
        }
        return all.distinctBy { it.id }.sortedBy { it.id }
    }

    private suspend fun get(url: String, headers: Map<String, String>): String =
        ResponsesHttp.get(url, headers, REFRESH_TIMEOUT_SECONDS)

    private fun dataArray(body: String): JsonArray =
        when (val root = JSON.parseToJsonElement(body)) {
            is JsonObject -> root["data"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: throw ResponsesError.Decoding("No `data` array in the model list.")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private data class Cached(val keyHash: Int, val result: Result.Success)

    private val cache = java.util.concurrent.ConcurrentHashMap<CoachProviderMode, Cached>()

    private val ENDPOINTS = mapOf(
        CoachProviderMode.USER_OPENAI_KEY to "https://api.openai.com/v1/models",
        CoachProviderMode.USER_GEMINI_KEY to "https://generativelanguage.googleapis.com/v1beta/models",
        CoachProviderMode.USER_OPENROUTER_KEY to "https://openrouter.ai/api/v1/models",
        // Global host, as MiniMaxClient uses.
        CoachProviderMode.USER_MINIMAX_KEY to "https://api.minimax.io/v1/models",
    )

    private val JSON = Json { ignoreUnknownKeys = true }
    private val O_SERIES = Regex("^o\\d+(-.*)?$")
    private val DATED_SNAPSHOT = Regex("-\\d{4}-\\d{2}-\\d{2}$")
    private val NON_CHAT = listOf(
        "embedding", "tts", "whisper", "transcribe", "audio", "realtime", "image", "dall-e",
        "moderation", "search", "instruct", "computer-use", "sora",
    )
    private val GEMINI_NON_CHAT = listOf("embedding", "tts", "image", "native-audio", "live", "aqa")

    /** A refresh sits behind a provider switch, so it should fail fast rather than hang the picker. */
    private const val REFRESH_TIMEOUT_SECONDS = 15
    private const val MAX_GEMINI_PAGES = 5
    /** Long enough that reopening Settings doesn't refetch; short enough to notice a new model the same day. */
    private const val TTL_MILLIS = 6 * 60 * 60 * 1000L
}
