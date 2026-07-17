package com.pulseloop.coach.minimax

import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.ResponsesError
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the Responses-API → Chat Completions payload conversion ported from
 * MiniMaxClient.swift (iOS #54): request assembly + response ingestion, no network.
 * MiniMax is the deliberately-simpler sibling of OpenRouter — no response_format,
 * reasoning, provider block, or :online suffix — plus <think>-block stripping.
 */
class MiniMaxClientTest {

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
        put("model", JsonPrimitive("MiniMax-M3"))
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
        id: String = "mm-123",
        extra: Map<String, JsonElement> = emptyMap(),
    ) = JsonObject(buildMap {
        put("id", JsonPrimitive(id))
        put("choices", JsonArray(listOf(JsonObject(mapOf(
            "message" to JsonObject(buildMap {
                put("role", JsonPrimitive("assistant"))
                put("content", content?.let { JsonPrimitive(it) } ?: JsonNull)
                toolCalls?.let { put("tool_calls", it) }
            }),
        )))))
        putAll(extra)
    })

    // ── Request assembly ────────────────────────────────────────────────

    @Test
    fun testModelSentVerbatimAndSchemaInstructionAppended() {
        val client = MiniMaxClient("key", model = "MiniMax-M3")
        val body = client.buildRequestBody(request(listOf(
            msg("system", "SYS"), msg("developer", "DEV"), msg("user", "hi"),
        )))

        assertEquals("MiniMax-M3", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        // developer folds into system; a trailing system schema instruction is appended.
        assertEquals(listOf("system", "system", "user", "system"),
            messages.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(messages.last()["content"]!!.jsonPrimitive.content.contains("coach_response"))
    }

    @Test
    fun testWebSearchToolDroppedAndFunctionsNest() {
        val body = MiniMaxClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")), tools = listOf(webSearchTool, functionTool),
        ))
        // No :online suffix, and the hosted web_search spec is dropped — only the function survives.
        assertEquals("MiniMax-M3", body["model"]!!.jsonPrimitive.content)
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val tool = tools[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        val fn = tool["function"]!!.jsonObject
        assertEquals("get_hr", fn["name"]!!.jsonPrimitive.content)
        assertEquals(true, fn["strict"]!!.jsonPrimitive.boolean)
        // No cache_control breakpoint (unlike OpenRouter).
        assertNull(tool["cache_control"])
    }

    @Test
    fun testNoToolsKeyWhenOnlyWebSearchRequested() {
        val body = MiniMaxClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")), tools = listOf(webSearchTool),
        ))
        assertNull(body["tools"])
    }

    @Test
    fun testResponseFormatReasoningProviderNeverSent() {
        val format = JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("coach_response"),
            "schema" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
            "strict" to JsonPrimitive(true),
        ))
        val body = MiniMaxClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")),
            textFormat = format,
            reasoning = JsonObject(mapOf("effort" to JsonPrimitive("low"))),
        ))
        // MiniMax's compat endpoint documents none of these — the body carries only model+messages.
        assertNull(body["response_format"])
        assertNull(body["reasoning"])
        assertNull(body["provider"])
        assertEquals(setOf("model", "messages"), body.keys)
    }

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
        val body = MiniMaxClient("key").buildRequestBody(request(listOf(item)))

        val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD",
            parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    // ── Response ingestion ──────────────────────────────────────────────

    @Test
    fun testThinkBlocksStrippedFromContent() {
        val client = MiniMaxClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val response = client.ingestResponse(
            chatResponse(content = "<think>plan the reply</think>Here is your recap."))
        assertEquals("Here is your recap.", response.outputText)
    }

    @Test
    fun testReasoningOnlyContentThrowsEmptyOutput() {
        val client = MiniMaxClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        // Content that is nothing but a <think> block strips to empty; with no tool calls that
        // leaves no output item at all.
        try {
            client.ingestResponse(chatResponse(content = "<think>all reasoning, no answer</think>"))
            fail("Expected ResponsesError.EmptyOutput")
        } catch (_: ResponsesError.EmptyOutput) {
            // expected
        }
    }

    @Test
    fun testTextBeforeUnterminatedThinkSurvives() {
        val client = MiniMaxClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val response = client.ingestResponse(chatResponse(content = "Recap ready. <think>cut off"))
        assertEquals("Recap ready.", response.outputText)
    }

    @Test
    fun testIngestToolCallsReuseUpstreamId() {
        val client = MiniMaxClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val toolCalls = JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("call_1"),
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf(
                "name" to JsonPrimitive("get_hr"),
                "arguments" to JsonPrimitive("""{"days":7}"""),
            )),
        ))))
        val response = client.ingestResponse(chatResponse(content = null, toolCalls = toolCalls))
        val call = response.output.filterIsInstance<FunctionCallOutput>().single()
        assertEquals("get_hr", call.name)
        assertEquals("call_1", call.callId)
        assertEquals("""{"days":7}""", call.arguments)
    }

    @Test
    fun testContinuationReplaysAssistantBeforeToolResults() {
        val client = MiniMaxClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val toolCalls = JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("call_1"),
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf(
                "name" to JsonPrimitive("get_hr"), "arguments" to JsonPrimitive("{}"),
            )),
        ))))
        val response = client.ingestResponse(chatResponse(content = null, toolCalls = toolCalls))

        val toolResult = JsonObject(mapOf(
            "type" to JsonPrimitive("function_call_output"),
            "call_id" to JsonPrimitive("call_1"),
            "output" to JsonPrimitive("""{"avg":61}"""),
        ))
        val body = client.buildRequestBody(request(listOf(toolResult), previousResponseId = response.id))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        val assistant = messages[messages.size - 2]
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        val tool = messages.last()
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"avg":61}""", tool["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun testErrorObjectOn200Throws() {
        val client = MiniMaxClient("key")
        try {
            client.ingestResponse(JsonObject(mapOf(
                "error" to JsonObject(mapOf("message" to JsonPrimitive("bad key"))),
            )))
            fail("Expected ResponsesError.Decoding")
        } catch (e: ResponsesError.Decoding) {
            assertTrue(e.msg.contains("bad key"))
        }
    }

    @Test
    fun testBaseRespNonZeroStatusThrows() {
        val client = MiniMaxClient("key")
        val root = chatResponse(extra = mapOf("base_resp" to JsonObject(mapOf(
            "status_code" to JsonPrimitive(1004),
            "status_msg" to JsonPrimitive("auth failed"),
        ))))
        try {
            client.ingestResponse(root)
            fail("Expected ResponsesError.Decoding")
        } catch (e: ResponsesError.Decoding) {
            assertTrue(e.msg.contains("1004"))
            assertTrue(e.msg.contains("auth failed"))
        }
    }

    @Test
    fun testBaseRespZeroStatusIsFine() {
        val client = MiniMaxClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val root = chatResponse(content = "ok", extra = mapOf(
            "base_resp" to JsonObject(mapOf("status_code" to JsonPrimitive(0), "status_msg" to JsonPrimitive("success"))),
        ))
        val response = client.ingestResponse(root)
        assertEquals("mm-123", response.id)
    }

    @Test
    fun testEmptyMessageThrowsEmptyOutput() {
        val client = MiniMaxClient("key")
        try {
            client.ingestResponse(chatResponse(content = null))
            fail("Expected ResponsesError.EmptyOutput")
        } catch (_: ResponsesError.EmptyOutput) {
            // expected
        }
    }
}
