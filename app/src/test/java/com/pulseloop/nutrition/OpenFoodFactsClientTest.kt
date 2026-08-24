package com.pulseloop.nutrition

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Drives [OpenFoodFactsClient] against a real MockWebServer so the HTTP status mapping,
 * the User-Agent requirement, and the fields= trim are tested through a real socket —
 * the same approach [com.pulseloop.coach.openai.ResponsesHttpTest] uses for the coach
 * client. The injectable host bases keep the production endpoints as the defaults.
 */
class OpenFoodFactsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenFoodFactsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        client = OpenFoodFactsClient(productBase = base, searchBase = base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun v2ProductBody(status: Int = 1): String = """
        {
          "status": $status,
          "product": {
            "code": "3017620422003",
            "product_name": "Nutella",
            "brands": "Ferrero",
            "nutriments": {"energy-kcal_100g": 539, "sodium_100g": 0.0428},
            "serving_size": "15 g",
            "serving_quantity": 15
          }
        }
    """.trimIndent()

    @Test
    fun productFoundReturnsTheNormalizedProduct() = runBlocking {
        server.enqueue(MockResponse().setBody(v2ProductBody()))
        val product = client.product("3017620422003")
        assertNotNull(product)
        assertEquals("3017620422003", product!!.code)
        assertEquals(539.0, product.energyKcal100g, 1e-9)
        // Sodium arrives in grams on the wire and is stored in mg.
        assertEquals(42.8, product.sodiumMg100g!!, 1e-9)

        val request = server.takeRequest()
        assertEquals("/api/v2/product/3017620422003", request.requestUrl?.encodedPath)
        // fields= trims the payload to what the app actually stores.
        assertEquals(OpenFoodFactsClient.PRODUCT_FIELDS, request.requestUrl?.queryParameter("fields"))
        // OFF requires a custom User-Agent identifying the app + contact.
        assertTrue(request.getHeader("User-Agent")!!.startsWith("PulseLoop/"))
    }

    /** OFF answers 404 for unknown barcodes — a valid "not found", not an error. */
    @Test
    fun product404ReturnsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":0}"""))
        assertNull(client.product("0000000000000"))
    }

    /** status 0 over a 200 also means "not found". */
    @Test
    fun productStatusZeroReturnsNull() = runBlocking {
        server.enqueue(MockResponse().setBody(v2ProductBody(status = 0)))
        assertNull(client.product("3017620422003"))
    }

    /** 429 is RateLimited, never retried — the caller must back off and offer manual entry. */
    @Test
    fun product429ThrowsRateLimited() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            client.product("3017620422003")
            fail("expected OpenFoodFactsError.RateLimited")
        } catch (expected: OpenFoodFactsError.RateLimited) {
        }
        assertEquals("one attempt only — a 429 is an answer, not a retry trigger", 1, server.requestCount)
    }

    /** Any other non-2xx is a distinct HttpStatus error. */
    @Test
    fun product500ThrowsHttpStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val thrown = try {
            client.product("3017620422003"); null
        } catch (e: OpenFoodFactsError) {
            e
        }
        val http = thrown as? OpenFoodFactsError.HttpStatus
        assertNotNull("expected OpenFoodFactsError.HttpStatus, got $thrown", http)
        assertEquals(500, http!!.code)
    }

    /** A transport failure (connection refused) is a Network error, not a Decoding one. */
    @Test
    fun unreachableServerSurfacesAsNetworkError() = runBlocking {
        val offline = OpenFoodFactsClient(productBase = "http://127.0.0.1:1")
        try {
            offline.product("123")
            fail("expected OpenFoodFactsError.Network")
        } catch (expected: OpenFoodFactsError.Network) {
        }
    }

    /** A 200 whose body is not JSON is a Decoding error. */
    @Test
    fun nonJsonBodySurfacesAsDecodingError() = runBlocking {
        server.enqueue(MockResponse().setBody("this is not json"))
        try {
            client.product("123")
            fail("expected OpenFoodFactsError.Decoding")
        } catch (expected: OpenFoodFactsError.Decoding) {
        }
    }

    @Test
    fun searchReturnsNormalizedResultsAndSendsTheSearchContract() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "hits": [
                    {"code":"a","product_name":"Choc One","brands":["Bar","Brand"],"nutriments":{"energy-kcal_100g":100}},
                    "malformed element",
                    {"code":"b","product_name":"Choc Two","nutriments":{"energy-kj_100g":4184}}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val results = client.search("dark chocolate")
        assertEquals(2, results.size)
        assertEquals(listOf("a", "b"), results.map { it.code })
        assertEquals("Bar", results[0].brand)
        assertEquals(1000.0, results[1].energyKcal100g, 1e-9)

        val request = server.takeRequest()
        assertEquals("/search", request.requestUrl?.encodedPath)
        // Query parameters are URL-encoded on the wire and come back decoded here.
        assertEquals("dark chocolate", request.requestUrl?.queryParameter("q"))
        // Default page size is 10 (iOS signature default).
        assertEquals("10", request.requestUrl?.queryParameter("page_size"))
        assertEquals(OpenFoodFactsClient.PRODUCT_FIELDS, request.requestUrl?.queryParameter("fields"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("PulseLoop/"))
    }

    @Test
    fun searchRespectsAnExplicitPageSize() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"hits":[]}"""))
        client.search("oat", pageSize = 3)
        assertEquals("3", server.takeRequest().requestUrl?.queryParameter("page_size"))
    }

    @Test
    fun search429ThrowsRateLimited() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            client.search("chocolate")
            fail("expected OpenFoodFactsError.RateLimited")
        } catch (expected: OpenFoodFactsError.RateLimited) {
        }
        assertEquals(1, server.requestCount)
    }
}
