package com.pulseloop.coach.openrouter

import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.ResponsesError
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the Responses-API → Chat Completions payload conversion ported from
 * OpenRouterClient.swift (request assembly + response ingestion; no network).
 */
class OpenRouterClientTest {

    private fun msg(role: String, content: String) = JsonObject(mapOf(
        "role" to JsonPrimitive(role),
        "content" to JsonPrimitive(content),
    ))

    private fun request(
        input: List<JsonObject>,
        tools: List<JsonObject> = emptyList(),
        textFormat: JsonObject? = null,
        previousResponseId: String? = null,
        reasoning: JsonObject? = null,
    ) = JsonObject(buildMap {
        put("model", JsonPrimitive("anthropic/claude-sonnet-4.6"))
        put("input", JsonArray(input))
        put("tools", JsonArray(tools))
        textFormat?.let { put("text", JsonObject(mapOf("format" to it))) }
        previousResponseId?.let { put("previous_response_id", JsonPrimitive(it)) }
        reasoning?.let { put("reasoning", it) }
    })

    private val functionTool = JsonObject(mapOf(
        "type" to JsonPrimitive("function"),
        "name" to JsonPrimitive("get_hr"),
        "description" to JsonPrimitive("desc"),
        "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
        "strict" to JsonPrimitive(true),
    ))

    private val webSearchTool = JsonObject(mapOf("type" to JsonPrimitive("web_search_preview")))

    private fun chatResponse(
        content: String? = "hello",
        toolCalls: JsonArray? = null,
        id: String = "gen-123",
    ) = JsonObject(buildMap {
        put("id", JsonPrimitive(id))
        put("choices", JsonArray(listOf(JsonObject(mapOf(
            "message" to JsonObject(buildMap {
                put("role", JsonPrimitive("assistant"))
                put("content", content?.let { JsonPrimitive(it) } ?: JsonNull)
                toolCalls?.let { put("tool_calls", it) }
            }),
        )))))
    })

    // ── Conversation setup ──────────────────────────────────────────────

    @Test
    fun testDeveloperFoldsIntoSystemAndSchemaInstructionAppended() {
        val client = OpenRouterClient("key")
        val body = client.buildRequestBody(request(listOf(
            msg("system", "SYS"), msg("developer", "DEV"), msg("user", "hi"),
        )))

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "system", "user", "system"),
            messages.map { it["role"]!!.jsonPrimitive.content })

        // The trailing system message is the coach_response prompt instruction
        // (belt-and-suspenders for models that ignore response_format).
        val last = messages.last()["content"]!!.jsonArray[0].jsonObject
        assertTrue(last["text"]!!.jsonPrimitive.content.contains("coach_response"))
    }

    @Test
    fun testCacheControlBreakpointsOnFirstAndLastSystemMessages() {
        val client = OpenRouterClient("key")
        val body = client.buildRequestBody(request(listOf(
            msg("system", "SYS"), msg("developer", "DEV"), msg("user", "hi"),
        )))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }

        fun cacheControl(m: JsonObject): JsonObject? =
            (m["content"] as? JsonArray)?.get(0)?.jsonObject?.get("cache_control") as? JsonObject

        // First system (static coach prompt) and last system (schema instruction)
        // carry ephemeral breakpoints; the middle one and the user turn don't.
        assertEquals("ephemeral", cacheControl(messages[0])!!["type"]!!.jsonPrimitive.content)
        assertNull(cacheControl(messages[1]))
        assertTrue(messages[2]["content"] is JsonPrimitive)
        assertEquals("ephemeral", cacheControl(messages[3])!!["type"]!!.jsonPrimitive.content)
    }

    // ── Web search → :online suffix ─────────────────────────────────────

    @Test
    fun testWebSearchToolAppendsOnlineSuffix() {
        val client = OpenRouterClient("key")
        val body = client.buildRequestBody(request(
            listOf(msg("user", "hi")), tools = listOf(webSearchTool, functionTool),
        ))
        assertEquals("anthropic/claude-sonnet-4.6:online", body["model"]!!.jsonPrimitive.content)
        // The hosted web_search spec itself is dropped from the tool list.
        assertEquals(1, body["tools"]!!.jsonArray.size)
    }

    @Test
    fun testNoOnlineSuffixWithoutWebSearch() {
        val body = OpenRouterClient("key").buildRequestBody(request(listOf(msg("user", "hi"))))
        assertEquals("anthropic/claude-sonnet-4.6", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun testOnlineSuffixNotDoubled() {
        val client = OpenRouterClient("key", model = "custom/model:online")
        val body = client.buildRequestBody(request(
            listOf(msg("user", "hi")), tools = listOf(webSearchTool),
        ))
        assertEquals("custom/model:online", body["model"]!!.jsonPrimitive.content)
    }

    // ── Tools ───────────────────────────────────────────────────────────

    @Test
    fun testFunctionToolsNestAndLastCarriesCacheControl() {
        val body = OpenRouterClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")), tools = listOf(functionTool),
        ))
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        val fn = tool["function"]!!.jsonObject
        assertEquals("get_hr", fn["name"]!!.jsonPrimitive.content)
        assertEquals(true, fn["strict"]!!.jsonPrimitive.boolean)
        assertNotNull(fn["parameters"])
        assertEquals("ephemeral",
            tool["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ── Structured output + provider routing ────────────────────────────

    @Test
    fun testResponseFormatTranslationRequiresParameters() {
        val format = JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("coach_response"),
            "schema" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
            "strict" to JsonPrimitive(true),
        ))
        val body = OpenRouterClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")), textFormat = format,
        ))

        val responseFormat = body["response_format"]!!.jsonObject
        assertEquals("json_schema", responseFormat["type"]!!.jsonPrimitive.content)
        val jsonSchema = responseFormat["json_schema"]!!.jsonObject
        assertEquals("coach_response", jsonSchema["name"]!!.jsonPrimitive.content)
        assertEquals(true, jsonSchema["strict"]!!.jsonPrimitive.boolean)
        assertNotNull(jsonSchema["schema"])

        // require_parameters routes only to providers that honor response_format.
        val provider = body["provider"]!!.jsonObject
        assertEquals(true, provider["require_parameters"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun testPrivacyRoutingAndProviderSort() {
        val client = OpenRouterClient("key", privacyRouting = true, providerSort = "price")
        val body = client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val provider = body["provider"]!!.jsonObject
        assertEquals("deny", provider["data_collection"]!!.jsonPrimitive.content)
        assertEquals("price", provider["sort"]!!.jsonPrimitive.content)
    }

    @Test
    fun testNoProviderObjectWhenNothingSet() {
        val body = OpenRouterClient("key").buildRequestBody(request(listOf(msg("user", "hi"))))
        assertNull(body["provider"])
    }

    @Test
    fun testReasoningForwardedVerbatim() {
        val body = OpenRouterClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")),
            reasoning = JsonObject(mapOf("effort" to JsonPrimitive("low"))),
        ))
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun testAbsentReasoningStaysAbsent() {
        val body = OpenRouterClient("key").buildRequestBody(request(listOf(msg("user", "hi"))))
        assertNull(body["reasoning"])
    }

    // ── Multimodal content parts ────────────────────────────────────────

    @Test
    fun testImagePartsMapToImageUrl() {
        val content = JsonArray(listOf(
            JsonObject(mapOf("type" to JsonPrimitive("input_text"), "text" to JsonPrimitive("look"))),
            JsonObject(mapOf(
                "type" to JsonPrimitive("input_image"),
                "image_url" to JsonPrimitive("data:image/jpeg;base64,QUJD"),
            )),
        ))
        val item = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to content))
        val body = OpenRouterClient("key").buildRequestBody(request(listOf(item)))

        // messages = [user, system schema instruction]
        val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD",
            parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    // ── Response ingestion + continuation ───────────────────────────────

    @Test
    fun testIngestTextAndToolCalls() {
        val client = OpenRouterClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))

        val toolCalls = JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("call_abc"),
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf(
                "name" to JsonPrimitive("get_hr"),
                "arguments" to JsonPrimitive("""{"days":7}"""),
            )),
        ))))
        val response = client.ingestResponse(chatResponse(content = null, toolCalls = toolCalls))

        assertEquals("gen-123", response.id)
        val call = response.output.filterIsInstance<FunctionCallOutput>().single()
        assertEquals("get_hr", call.name)
        assertEquals("call_abc", call.callId)   // OpenRouter's own id is reused
        assertEquals("""{"days":7}""", call.arguments)
    }

    @Test
    fun testContinuationReplaysAssistantBeforeToolResults() {
        val client = OpenRouterClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val toolCalls = JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("call_abc"),
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf(
                "name" to JsonPrimitive("get_hr"),
                "arguments" to JsonPrimitive("{}"),
            )),
        ))))
        val response = client.ingestResponse(chatResponse(content = null, toolCalls = toolCalls))

        val toolResult = JsonObject(mapOf(
            "type" to JsonPrimitive("function_call_output"),
            "call_id" to JsonPrimitive("call_abc"),
            "output" to JsonPrimitive("""{"avg":61}"""),
        ))
        val body = client.buildRequestBody(request(
            listOf(toolResult), previousResponseId = response.id,
        ))

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        // [user, schema system, assistant tool_calls replay, tool result]
        val assistant = messages[messages.size - 2]
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("call_abc", assistant["tool_calls"]!!.jsonArray[0]
            .jsonObject["id"]!!.jsonPrimitive.content)
        val tool = messages.last()
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_abc", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"avg":61}""", tool["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun testUpstreamErrorOn200Throws() {
        val client = OpenRouterClient("key")
        val root = JsonObject(mapOf(
            "error" to JsonObject(mapOf("message" to JsonPrimitive("upstream sad"))),
        ))
        try {
            client.ingestResponse(root)
            fail("Expected ResponsesError.Decoding")
        } catch (e: ResponsesError.Decoding) {
            assertTrue(e.msg.contains("upstream sad"))
        }
    }

    @Test
    fun testEmptyMessageThrowsEmptyOutput() {
        val client = OpenRouterClient("key")
        try {
            client.ingestResponse(chatResponse(content = null))
            fail("Expected ResponsesError.EmptyOutput")
        } catch (_: ResponsesError.EmptyOutput) {
            // expected
        }
    }
}
