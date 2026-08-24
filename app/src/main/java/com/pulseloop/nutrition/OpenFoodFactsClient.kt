package com.pulseloop.nutrition

import com.pulseloop.BuildConfig
import com.pulseloop.data.entity.CachedFoodProductEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Interface for food-database lookups, so views/tools depend on the protocol and tests
 * inject a stub (same pattern as `ResponsesClient`). Ported from `FoodDatabaseClient`
 * in OpenFoodFactsClient.swift.
 */
interface FoodDatabaseClient {
    /** Product by barcode. null = not found (a valid answer, not an error). */
    suspend fun product(barcode: String): FoodProduct?

    /** Full-text search, best matches first. */
    suspend fun search(query: String, pageSize: Int = 10): List<FoodProduct>
}

/**
 * OFF failures, kept distinct so the UI can react appropriately. Ported from
 * `OpenFoodFactsError` in OpenFoodFactsClient.swift.
 */
sealed class OpenFoodFactsError(message: String) : Exception(message) {
    /** The request URL could not be built. */
    data object InvalidUrl : OpenFoodFactsError("invalid URL")
    /** HTTP 429 — the caller must back off and offer manual entry; never auto-retry. */
    data object RateLimited : OpenFoodFactsError("rate limited (HTTP 429)")
    /** Any other non-2xx the caller does not special-case. */
    data class HttpStatus(val code: Int) : OpenFoodFactsError("HTTP $code")
    /** The body was not valid OFF JSON. */
    data class Decoding(val msg: String) : OpenFoodFactsError(msg)
    /** The request never completed (timeout, refused connection, ...). */
    data class Network(val msg: String) : OpenFoodFactsError(msg)
}

/**
 * Thin OkHttp client for Open Food Facts (ODbL). Usage rules honored here:
 * - Every call maps to a real user action (explicit search submit, barcode scan, coach tool
 *   call) — callers must check the local `food_products` table first (see
 *   [FoodProductCache]).
 * - A custom User-Agent identifies the app, as OFF requires.
 * - `fields=` trims every payload to the nutriments the app actually stores.
 *
 * Product reads hit world.openfoodfacts.org; full-text search is only served by the newer
 * Search-a-licious host (v2 search is structured-filter only).
 *
 * Ported from `OpenFoodFactsClient` in OpenFoodFactsClient.swift. Like the iOS client it is
 * deliberately thin: it maps every HTTP status the same way and caches nothing of its own —
 * the Room table is the cache.
 */
class OpenFoodFactsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)   // iOS `request.timeoutInterval = 15`
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    // Hosts are injectable so unit tests can point the client at a MockWebServer; the paths
    // built in [product]/[search] are the ported contract and are not configurable.
    private val productBase: String = "https://world.openfoodfacts.org",
    private val searchBase: String = "https://search.openfoodfacts.org",
) : FoodDatabaseClient {

    override suspend fun product(barcode: String): FoodProduct? = withContext(Dispatchers.IO) {
        val base = productBase.toHttpUrlOrNull() ?: throw OpenFoodFactsError.InvalidUrl
        val url = base.newBuilder()
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("product")
            .addPathSegment(barcode)          // percent-encodes the barcode
            .addQueryParameter("fields", PRODUCT_FIELDS)
            .build()

        // OFF returns 404 for unknown barcodes; map that to null, not an error.
        val envelope = try {
            get(url) { text -> json.decodeFromString(OFFProductResponse.serializer(), text) }
        } catch (error: OpenFoodFactsError.HttpStatus) {
            if (error.code == 404) return@withContext null
            throw error
        }
        return@withContext if (envelope.status == 1) envelope.product?.asFoodProduct() else null
    }

    override suspend fun search(query: String, pageSize: Int): List<FoodProduct> =
        withContext(Dispatchers.IO) {
            val base = searchBase.toHttpUrlOrNull() ?: throw OpenFoodFactsError.InvalidUrl
            val url = base.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", query)
                .addQueryParameter("page_size", pageSize.toString())
                .addQueryParameter("fields", PRODUCT_FIELDS)
                .build()

            val envelope = get(url) { text -> OFFSearchResponse.decode(json, text) }
            envelope.results.mapNotNull { it.asFoodProduct() }
        }

    /**
     * One GET with OFF's error contract, mirroring the iOS `get<T>` helper: 429 →
     * [OpenFoodFactsError.RateLimited], any other non-2xx → [OpenFoodFactsError.HttpStatus],
     * a transport failure → [OpenFoodFactsError.Network], an unparseable body →
     * [OpenFoodFactsError.Decoding].
     */
    private fun <T> get(url: HttpUrl, decode: (String) -> T): T {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw OpenFoodFactsError.Network(e.message ?: e.javaClass.simpleName)
        }
        return response.use { resp ->
            val code = resp.code
            if (code !in 200..299) {
                if (code == 429) throw OpenFoodFactsError.RateLimited
                throw OpenFoodFactsError.HttpStatus(code)
            }
            val text = resp.body?.string()
                ?: throw OpenFoodFactsError.Decoding("empty response body")
            try {
                decode(text)
            } catch (e: OpenFoodFactsError) {
                throw e
            } catch (e: Exception) {
                throw OpenFoodFactsError.Decoding(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        /**
         * OFF-required app identification: AppName/Version (contact). iOS reads
         * `CFBundleShortVersionString` with a "1.0" fallback; the Android equivalent is
         * `BuildConfig.VERSION_NAME`, which is always generated — `ifBlank` keeps the same
         * defensive fallback shape.
         */
        val userAgent: String =
            "PulseLoop/${BuildConfig.VERSION_NAME.ifBlank { "1.0" }} (sakshambhutani2001@gmail.com)"

        /** `fields=` trims every payload to the nutriments the app actually stores. */
        const val PRODUCT_FIELDS =
            "code,product_name,brands,nutriments,serving_size,serving_quantity"
    }
}

// ── Domain ⇄ Room mappers ──────────────────────────────────────────────────────────
// Ported from the `extension FoodProduct` / `extension CachedFoodProduct` at the bottom of
// OpenFoodFactsClient.swift.

/** Persist into (or refresh) the local cache table form. */
fun FoodProduct.asCachedProduct(): CachedFoodProductEntity =
    CachedFoodProductEntity(
        code = code,
        name = name,
        brand = brand,
        energyKcal100g = energyKcal100g,
        protein100g = protein100g,
        carbs100g = carbs100g,
        fat100g = fat100g,
        fiber100g = fiber100g,
        sugars100g = sugars100g,
        saturatedFat100g = saturatedFat100g,
        sodiumMg100g = sodiumMg100g,
        servingSizeText = servingSizeText,
        servingQuantityG = servingQuantityG,
        // lastUsedAt / useCount take the entity defaults (now / 0): a freshly fetched row
        // counts as just used, matching the iOS CachedFoodProduct initializer defaults.
    )

/** Back to the value form the pickers work with. */
fun CachedFoodProductEntity.asFoodProduct(): FoodProduct =
    FoodProduct(
        code = code,
        name = name,
        brand = brand,
        energyKcal100g = energyKcal100g,
        protein100g = protein100g,
        carbs100g = carbs100g,
        fat100g = fat100g,
        fiber100g = fiber100g,
        sugars100g = sugars100g,
        saturatedFat100g = saturatedFat100g,
        sodiumMg100g = sodiumMg100g,
        servingSizeText = servingSizeText,
        servingQuantityG = servingQuantityG,
    )
