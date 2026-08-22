package com.pulseloop.coach.local

import com.pulseloop.coach.openai.ResponsesError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `context window is read under each engine's own field name`() {
        // vLLM: max_model_len. Confirmed live against a 0.27.1 server.
        assertEquals(262144, LocalModelCatalog.parseEntries(
            """{"data":[{"id":"q","max_model_len":262144}]}""").first().contextWindow)
        // llama.cpp: n_ctx as served, n_ctx_train as the model ceiling.
        assertEquals(8192, LocalModelCatalog.parseEntries(
            """{"data":[{"id":"q","n_ctx":8192,"n_ctx_train":32768}]}""").first().contextWindow)
        // LM Studio: what's actually loaded wins over what the model could support.
        assertEquals(4096, LocalModelCatalog.parseEntries(
            """{"data":[{"id":"q","max_context_length":32768,"loaded_context_length":4096}]}""")
            .first().contextWindow)
    }

    @Test
    fun `a missing or zero context window is null, not zero`() {
        assertNull(LocalModelCatalog.parseEntries("""{"data":[{"id":"q"}]}""").first().contextWindow)
        assertNull(LocalModelCatalog.parseEntries(
            """{"data":[{"id":"q","max_model_len":0}]}""").first().contextWindow)
    }

    @Test
    fun `a response with no data array is rejected`() {
        assertThrows(ResponsesError.Decoding::class.java) {
            LocalModelCatalog.parse("""{"models":["a"]}""")
        }
    }
}
