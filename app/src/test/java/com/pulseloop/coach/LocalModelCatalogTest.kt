package com.pulseloop.coach.local

import com.pulseloop.coach.openai.ResponsesError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** `/v1/models` parsing across the envelope shapes the engines actually return. */
class LocalModelCatalogTest {

    @Test
    fun `parses the openai list envelope, sorted and deduplicated`() {
        val body = """
            {"object":"list","data":[
              {"id":"qwen3:8b","object":"model"},
              {"id":"llama3.2:3b","object":"model"},
              {"id":"qwen3:8b","object":"model"}]}
        """.trimIndent()
        assertEquals(listOf("llama3.2:3b", "qwen3:8b"), LocalModelCatalog.parse(body))
    }

    @Test
    fun `parses a bare array, as some thin proxies return`() {
        assertEquals(listOf("a", "b"), LocalModelCatalog.parse("""["b","a"]"""))
        assertEquals(listOf("a"), LocalModelCatalog.parse("""[{"id":"a"}]"""))
    }

    @Test
    fun `an empty list is a valid answer, not an error`() {
        assertEquals(emptyList<String>(), LocalModelCatalog.parse("""{"object":"list","data":[]}"""))
    }

    @Test
    fun `a response with no data array is rejected`() {
        assertThrows(ResponsesError.Decoding::class.java) {
            LocalModelCatalog.parse("""{"models":["a"]}""")
        }
    }
}
