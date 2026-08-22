package com.pulseloop.nutrition

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

// Open Food Facts wire types + the app-facing domain product. OFF data is community-sourced
// and messy: numeric fields arrive as numbers or strings, keys are hyphenated, energy may be
// kJ-only, and sodium is grams. Everything is normalized here (and only here) so the rest of
// the app deals in clean per-100g kcal/grams — with [NutritionMath] holding the pure conversion
// logic, heavily unit-tested.
//
// Ported from PulseLoop/Nutrition/OpenFoodFactsTypes.swift (iOS PR #96).

/**
 * A normalized food product: per-100g values (OFF's canonical shape) plus serving info.
 * Per-serving math happens at use time via [NutritionMath.scaled].
 */
data class FoodProduct(
    val code: String,
    val name: String,
    val brand: String? = null,
    val energyKcal100g: Double,
    val protein100g: Double = 0.0,
    val carbs100g: Double = 0.0,
    val fat100g: Double = 0.0,
    val fiber100g: Double? = null,
    val sugars100g: Double? = null,
    val saturatedFat100g: Double? = null,
    /** Milligrams per 100g — OFF reports grams; the conversion happens in [OFFProductDTO.asFoodProduct]. */
    val sodiumMg100g: Double? = null,
    /** Human serving text, e.g. "30 g" or "1 cup (240 ml)". */
    val servingSizeText: String? = null,
    /** Grams per serving when OFF provides a resolvable quantity. */
    val servingQuantityG: Double? = null,
)

/**
 * Pure nutrition conversions — the single place OFF's unit quirks are handled.
 * Ported from `NutritionMath` in OpenFoodFactsTypes.swift; unit-tested in NutritionMathTest.
 */
object NutritionMath {
    /** kJ → kcal. */
    const val kcalPerKJ = 1.0 / 4.184

    /** Nutrient totals for [grams] of a product, scaled from its per-100g values. */
    fun scaled(per100g: Double, grams: Double): Double = per100g * grams / 100.0

    /**
     * Resolve energy in kcal from OFF's fields: prefer `energy-kcal_100g`; fall back to
     * converting the kJ field. Returns null when neither is present. A present-but-zero kcal
     * still wins — 0 is data, not absence (iOS's `if let kcal` guards on presence, not value).
     */
    fun energyKcal(kcal: Double?, kJ: Double?): Double? = kcal ?: kJ?.times(kcalPerKJ)

    /** OFF reports sodium in grams per 100g; the app stores milligrams. */
    fun sodiumMg(fromGrams: Double?): Double? = fromGrams?.times(1000.0)
}

// MARK: - Wire DTOs

/** `GET /api/v2/product/{code}` envelope. `status == 1` means found. */
@Serializable
data class OFFProductResponse(
    val status: Int? = null,
    val product: OFFProductDTO? = null,
)

/**
 * Search envelope. Search-a-licious returns `hits`; the legacy v1/v2 search returns
 * `products` — decode both, and decode each hit *independently* (lossy) so one malformed
 * community-edited product can never fail the whole response.
 *
 * Ported from `OFFSearchResponse` + `LossyArray` in OpenFoodFactsTypes.swift. It is not
 * itself `@Serializable`: kotlinx can't express "decode each element of this list on its
 * own, dropping failures" on a `List<OFFProductDTO>` property, so the lossy decode lives in
 * [decode] — the exact job of the iOS custom `init(from:)` + `LossyArray`. The caller's
 * lenient [Json] is passed in so the per-element decode is as forgiving as the client's.
 */
data class OFFSearchResponse(
    val hits: List<OFFProductDTO>? = null,
    val products: List<OFFProductDTO>? = null,
) {
    /** iOS's `results` computed property: `hits ?? products ?? []`. */
    val results: List<OFFProductDTO> get() = hits ?: products ?: emptyList()

    companion object {
        /**
         * Lossy-decode a search body. Each element of `hits`/`products` is decoded in its
         * own try/catch (mirroring iOS's `LossyArray`) and failures are dropped; a key that
         * is present but not an array is treated as absent, as `decodeIfPresent` would.
         * Throws only when the body is not a JSON object at all — the client wraps that in
         * `OpenFoodFactsError.Decoding`.
         */
        fun decode(json: Json, raw: String): OFFSearchResponse {
            val envelope = json.parseToJsonElement(raw).jsonObject
            return OFFSearchResponse(lossy(json, envelope["hits"]), lossy(json, envelope["products"]))
        }

        private fun lossy(json: Json, element: JsonElement?): List<OFFProductDTO>? {
            val array = element as? JsonArray ?: return null
            return array.mapNotNull { item ->
                runCatching { json.decodeFromJsonElement(OFFProductDTO.serializer(), item) }.getOrNull()
            }
        }
    }
}

/**
 * One product row on the wire, from either endpoint. Ported from `OFFProductDTO` in
 * OpenFoodFactsTypes.swift.
 */
@Serializable
data class OFFProductDTO(
    val code: String? = null,
    @SerialName("product_name") val productName: String? = null,
    /** Comma-joined brand list; see [OFFBrands] for the string/array duality. */
    val brands: OFFBrands? = null,
    val nutriments: OFFNutriments? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("serving_quantity") val servingQuantity: OFFNumber? = null,
) {
    /**
     * Normalize to the domain product. Returns null for unusable rows (no code, no name, or
     * no energy in any form) — better to drop a result than present a food with no numbers.
     */
    fun asFoodProduct(): FoodProduct? {
        val productCode = code?.takeIf { it.isNotEmpty() }
        val name = productName?.trim()?.takeIf { it.isNotEmpty() }
        val n = nutriments
        val kcal = n?.let {
            NutritionMath.energyKcal(kcal = it.energyKcal100g?.value, kJ = it.energyKJ?.value)
        }
        if (productCode == null || name == null || n == null || kcal == null) return null
        // OFF brands is a comma-separated list; show the first.
        val brand = brands?.joined?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return FoodProduct(
            code = productCode,
            name = name,
            brand = brand,
            energyKcal100g = kcal,
            protein100g = n.proteins100g?.value ?: 0.0,
            carbs100g = n.carbohydrates100g?.value ?: 0.0,
            fat100g = n.fat100g?.value ?: 0.0,
            fiber100g = n.fiber100g?.value,
            sugars100g = n.sugars100g?.value,
            saturatedFat100g = n.saturatedFat100g?.value,
            sodiumMg100g = NutritionMath.sodiumMg(n.sodium100g?.value),
            servingSizeText = servingSize,
            servingQuantityG = servingQuantity?.value,
        )
    }
}

/**
 * OFF's `brands` field, normalized to the comma-joined string the v2 product API returns.
 *
 * `brands` is a comma-separated STRING on the v2 product API but an ARRAY of strings on
 * Search-a-licious — this mismatch used to fail every search (see the comment on
 * `OFFProductDTO.init(from:)` in OpenFoodFactsTypes.swift). [OFFBrandsSerializer] accepts
 * both — string first, else the array joined with ", " — and yields the "field absent"
 * value (the empty string) for anything else, mirroring the iOS `try?` cascade that set
 * `brands = nil` there: a malformed brands never fails the product decode.
 */
@Serializable(with = OFFBrandsSerializer::class)
data class OFFBrands(val joined: String)

/**
 * The `nutriments` sub-object, with OFF's exact (hyphenated, per-100g) key names.
 * Ported from `OFFNutriments` in OpenFoodFactsTypes.swift.
 */
@Serializable
data class OFFNutriments(
    @SerialName("energy-kcal_100g") val energyKcal100g: OFFNumber? = null,
    /** kJ spelling on the v2 product API. */
    @SerialName("energy_100g") val energyKJ100g: OFFNumber? = null,
    /** Search-a-licious names the kJ field differently from the v2 product API. */
    @SerialName("energy-kj_100g") val energyKJAlt100g: OFFNumber? = null,
    @SerialName("proteins_100g") val proteins100g: OFFNumber? = null,
    @SerialName("carbohydrates_100g") val carbohydrates100g: OFFNumber? = null,
    @SerialName("fat_100g") val fat100g: OFFNumber? = null,
    @SerialName("fiber_100g") val fiber100g: OFFNumber? = null,
    @SerialName("sugars_100g") val sugars100g: OFFNumber? = null,
    @SerialName("saturated-fat_100g") val saturatedFat100g: OFFNumber? = null,
    /** Grams per 100g — the app's mg conversion happens in [OFFProductDTO.asFoodProduct]. */
    @SerialName("sodium_100g") val sodium100g: OFFNumber? = null,
) {
    /**
     * The kJ value under either spelling — v2 uses `energy_100g`, Search-a-licious uses
     * `energy-kj_100g` (iOS's `init(from:)` falls back the same way).
     */
    val energyKJ: OFFNumber? get() = energyKJ100g ?: energyKJAlt100g
}

/**
 * OFF numeric fields arrive as JSON numbers *or* strings ("12.5") — community editors can
 * type anything. Decode either and yield a Double; throw on non-numeric (the lossy search
 * decode then drops just that product — one bad value never fails the whole response).
 * Ported from `OFFNumber` in OpenFoodFactsTypes.swift.
 */
@Serializable(with = OFFNumberSerializer::class)
data class OFFNumber(val value: Double)

/**
 * Decoder for [OFFNumber]. Goes through [JsonDecoder.decodeJsonElement] so one primitive can
 * be inspected: a JSON number is taken as-is, a string is trimmed and parsed (iOS trims
 * whitespace the same way), anything else is a data-corruption error. The message matches
 * iOS's `dataCorrupted` description.
 */
object OFFNumberSerializer : KSerializer<OFFNumber> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OFFNumber", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: OFFNumber) {
        encoder.encodeDouble(value.value)
    }

    override fun deserialize(decoder: Decoder): OFFNumber {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return OFFNumber(decoder.decodeDouble())
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("Expected number or numeric string")
        // A JSON number's [JsonPrimitive.content] is its text form, and a string's content is
        // its text too, so one parse covers both (iOS's OFFNumber does the same).
        return OFFNumber(primitive.content.trim().toDoubleOrNull()
            ?: throw SerializationException("Expected number or numeric string"))
    }
}

/**
 * Decoder for [OFFBrands] — try String first (v2 product API), then an array of strings
 * joined with ", " (Search-a-licious). Like iOS's `[String]` decode, one non-string
 * array element drops the whole field; any other shape does the same. The empty value stands
 * in for "absent": [OFFProductDTO.asFoodProduct] treats it exactly like iOS treats `nil`
 * (no brand).
 */
object OFFBrandsSerializer : KSerializer<OFFBrands> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OFFBrands", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OFFBrands) {
        encoder.encodeString(value.joined)
    }

    override fun deserialize(decoder: Decoder): OFFBrands {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return OFFBrands(decoder.decodeString())
        return when {
            element is JsonPrimitive && element.isString -> OFFBrands(element.content)
            element is JsonArray -> {
                val parts = mutableListOf<String>()
                for (item in element) {
                    val primitive = item as? JsonPrimitive ?: return OFFBrands("")
                    if (!primitive.isString) return OFFBrands("")
                    parts.add(primitive.content)
                }
                OFFBrands(parts.joinToString(", "))
            }
            else -> OFFBrands("")
        }
    }
}
