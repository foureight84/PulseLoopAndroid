package com.pulseloop.nutrition

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Decode + normalization tests for the OFF wire DTOs (the port of OpenFoodFactsTypes.swift).
 * The fixtures are shaped like real OFF payloads: hyphenated nutriments keys, string
 * numbers, kJ-only energy, and both brands shapes.
 */
class OFFProductDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** (a) A valid v2 product decodes; sodium arrives in grams and comes back in mg. */
    @Test
    fun validV2ProductDecodesWithSodiumInMilligrams() {
        val raw = """
            {
              "status": 1,
              "product": {
                "code": "3017620422003",
                "product_name": "Nutella",
                "brands": "Ferrero",
                "nutriments": {
                  "energy-kcal_100g": 539,
                  "energy_100g": 2250,
                  "proteins_100g": 6.3,
                  "carbohydrates_100g": "57.5",
                  "fat_100g": 30.9,
                  "fiber_100g": 3.4,
                  "sugars_100g": 56.3,
                  "saturated-fat_100g": 10.6,
                  "sodium_100g": 0.0428
                },
                "serving_size": "15 g",
                "serving_quantity": 15
              }
            }
        """.trimIndent()
        val response = json.decodeFromString(OFFProductResponse.serializer(), raw)
        assertEquals(1, response.status!!)
        val product = requireNotNull(response.product?.asFoodProduct())
        assertEquals("3017620422003", product.code)
        assertEquals("Nutella", product.name)
        assertEquals("Ferrero", product.brand)
        // kcal wins over the co-present kJ.
        assertEquals(539.0, product.energyKcal100g, 1e-9)
        assertEquals(6.3, product.protein100g, 1e-9)
        // "57.5" arrived as a string — OFFNumber must decode it.
        assertEquals(57.5, product.carbs100g, 1e-9)
        assertEquals(30.9, product.fat100g, 1e-9)
        assertEquals(3.4, product.fiber100g!!, 1e-9)
        assertEquals(56.3, product.sugars100g!!, 1e-9)
        assertEquals(10.6, product.saturatedFat100g!!, 1e-9)
        // 0.0428 g sodium per 100g -> 42.8 mg.
        assertEquals(42.8, product.sodiumMg100g!!, 1e-9)
        assertEquals("15 g", product.servingSizeText)
        assertEquals(15.0, product.servingQuantityG!!, 1e-9)
    }

    /** (b) A row with no name is unusable — dropped, not surfaced. */
    @Test
    fun rowWithoutNameIsDropped() {
        val dto = json.decodeFromString(
            OFFProductDTO.serializer(),
            """{"code":"123","nutriments":{"energy-kcal_100g":100}}""",
        )
        assertNull(dto.asFoodProduct())
    }

    /** A row with no energy in any form is dropped too. */
    @Test
    fun rowWithoutEnergyIsDropped() {
        val dto = json.decodeFromString(
            OFFProductDTO.serializer(),
            """{"code":"123","product_name":"Mystery","nutriments":{"proteins_100g":1}}""",
        )
        assertNull(dto.asFoodProduct())
    }

    /** (c) Energy only in kJ converts to kcal — under BOTH kJ key spellings. */
    @Test
    fun kJOnlyEnergyConvertsToKcal() {
        // v2 product API spelling.
        val v2 = json.decodeFromString(
            OFFProductDTO.serializer(),
            """{"code":"1","product_name":"X","nutriments":{"energy_100g":2092}}""",
        )
        assertEquals(500.0, v2.asFoodProduct()!!.energyKcal100g, 1e-9)

        // Search-a-licious spelling.
        val searchALicious = json.decodeFromString(
            OFFProductDTO.serializer(),
            """{"code":"2","product_name":"Y","nutriments":{"energy-kj_100g":2092}}""",
        )
        assertEquals(500.0, searchALicious.asFoodProduct()!!.energyKcal100g, 1e-9)
    }

    /** (d) v2's comma-separated brands string keeps only the first token. */
    @Test
    fun commaSeparatedBrandsYieldFirstToken() {
        val dto = json.decodeFromString(
            OFFProductDTO.serializer(),
            """{"code":"3","product_name":"Z","brands":"Ferrero, Nutella, Unbranded","nutriments":{"energy-kcal_100g":10}}""",
        )
        assertEquals("Ferrero", dto.asFoodProduct()!!.brand)
    }

    /** (e) Search-a-licious' brands array is accepted and its first token used. */
    @Test
    fun brandsArrayIsAccepted() {
        val dto = json.decodeFromString(
            OFFProductDTO.serializer(),
            """{"code":"4","product_name":"W","brands":["Ferrero","Nutella"],"nutriments":{"energy-kcal_100g":10}}""",
        )
        assertEquals("Ferrero", dto.asFoodProduct()!!.brand)
    }

    /** A malformed brands value must not fail the product decode (iOS's try? cascade). */
    @Test
    fun malformedBrandsYieldNoBrandButKeepTheProduct() {
        val dto = json.decodeFromString(
            OFFProductDTO.serializer(),
            """{"code":"5","product_name":"V","brands":42,"nutriments":{"energy-kcal_100g":10}}""",
        )
        val product = dto.asFoodProduct()
        assertNotNull(product)
        assertNull(product!!.brand)
    }

    /** (f) The lossy search array drops malformed elements and keeps the good ones. */
    @Test
    fun lossySearchArrayDropsMalformedElements() {
        val raw = """
            {
              "hits": [
                {"code":"a","product_name":"Good One","brands":"B","nutriments":{"energy-kcal_100g":100}},
                "this whole element is not even an object",
                {"code":"b","product_name":"Good Two","nutriments":{"energy-kj_100g":4184}},
                {"code":7,"product_name":"Bad code type","nutriments":{"energy-kcal_100g":5}}
              ]
            }
        """.trimIndent()
        val response = OFFSearchResponse.decode(json, raw)
        val products = response.results.mapNotNull { it.asFoodProduct() }
        assertEquals(2, products.size)
        assertEquals(listOf("a", "b"), products.map { it.code })
        // The second good row carried kJ-only energy and was converted on the way in.
        assertEquals(1000.0, products[1].energyKcal100g, 1e-9)
    }

    /** The legacy products key works when hits is absent. */
    @Test
    fun legacyProductsKeyIsUsedWhenHitsIsMissing() {
        val raw = """{"products":[{"code":"a","product_name":"Good","nutriments":{"energy-kcal_100g":100}}]}"""
        val response = OFFSearchResponse.decode(json, raw)
        assertEquals(1, response.results.size)
        assertEquals("a", response.results[0].code)
    }

    /** hits wins when both keys are present (iOS `hits ?? products`). */
    @Test
    fun hitsWinsOverProductsWhenBothArePresent() {
        val raw = """
            {
              "hits": [{"code":"hits","product_name":"H","nutriments":{"energy-kcal_100g":1}}],
              "products": [{"code":"products","product_name":"P","nutriments":{"energy-kcal_100g":1}}]
            }
        """.trimIndent()
        assertEquals("hits", OFFSearchResponse.decode(json, raw).results.single().code)
    }

    /** A search body whose root is not an object is a decode failure, not an empty list. */
    @Test
    fun nonObjectSearchBodyFails() {
        try {
            OFFSearchResponse.decode(json, "[1,2,3]")
            fail("expected a decode failure")
        } catch (expected: Exception) {
            // parseToJsonElement succeeds (it is valid JSON) but jsonObject throws.
        }
    }
}
