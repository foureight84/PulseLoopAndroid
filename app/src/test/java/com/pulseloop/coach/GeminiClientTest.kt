package com.pulseloop.coach.gemini

import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.ResponsesError
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the Responses-API → Gemini `generateContent` payload conversion ported
 * from GeminiClient.swift (request assembly + response ingestion; no network).
 */
class GeminiClientTest {

    private fun msg(role: String, content: String) = JsonObject(mapOf(
        "role" to JsonPrimitive(role),
        "content" to JsonPrimitive(content),
    ))

    private fun request(
        input: List<JsonObject>,
        tools: List<JsonObject> = emptyList(),
        textFormat: JsonObject? = null,
        previousResponseId: String? = null,
        effort: String? = null,
    ) = JsonObject(buildMap {
        put("model", JsonPrimitive("gemini-2.5-flash"))
        effort?.let { put("reasoning", JsonObject(mapOf("effort" to JsonPrimitive(it)))) }
        put("input", JsonArray(input))
        put("tools", JsonArray(tools))
        textFormat?.let { put("text", JsonObject(mapOf("format" to it))) }
        previousResponseId?.let { put("previous_response_id", JsonPrimitive(it)) }
    })

    private fun functionTool(name: String, parameters: JsonObject) = JsonObject(mapOf(
        "type" to JsonPrimitive("function"),
        "name" to JsonPrimitive(name),
        "description" to JsonPrimitive("desc"),
        "parameters" to parameters,
        "strict" to JsonPrimitive(true),
    ))

    private val webSearchTool = JsonObject(mapOf("type" to JsonPrimitive("web_search_preview")))

    private fun textResponse(text: String) = JsonObject(mapOf(
        "candidates" to JsonArray(listOf(JsonObject(mapOf(
            "content" to JsonObject(mapOf(
                "parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(text))))),
            )),
        )))),
    ))

    // ── Thinking level (iOS #140) ───────────────────────────────────────

    @Test
    fun testReasoningEffortIsSentAsThinkingLevel() {
        val body = GeminiClient("key").buildRequestBody(request(listOf(msg("user", "hi")), effort = "high"))
        val level = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content
        assertEquals("HIGH", level)
    }

    @Test
    fun testNoEffortSendsNoThinkingConfig() {
        val body = GeminiClient("key").buildRequestBody(request(listOf(msg("user", "hi"))))
        assertNull(body["generationConfig"]?.jsonObject?.get("thinkingConfig"))
    }

    // ── First-turn conversion ───────────────────────────────────────────

    @Test
    fun testSystemAndDeveloperFoldIntoSystemInstruction() {
        val client = GeminiClient("key")
        val body = client.buildRequestBody(request(listOf(
            msg("system", "SYS"), msg("developer", "DEV"),
            msg("user", "hello"), msg("assistant", "hi there"), msg("user", "next"),
        )))

        val sysText = body["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("SYS\n\nDEV", sysText)

        val contents = body["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("hello", contents[0]["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun testFunctionToolsBecomeValidatedFunctionDeclarations() {
        val params = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "additionalProperties" to JsonPrimitive(false),
            "\$schema" to JsonPrimitive("http://json-schema.org/draft-07/schema#"),
            "properties" to JsonObject(mapOf(
                "metric" to JsonObject(mapOf(
                    "type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))),
                )),
            )),
        ))
        val client = GeminiClient("key")
        val body = client.buildRequestBody(request(
            listOf(msg("user", "hi")), tools = listOf(functionTool("get_hr", params)),
        ))

        val decls = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        val decl = decls[0].jsonObject
        assertEquals("get_hr", decl["name"]!!.jsonPrimitive.content)

        // Gemini-incompatible keywords stripped, union type → nullable.
        val cleaned = decl["parameters"]!!.jsonObject
        assertFalse(cleaned.containsKey("additionalProperties"))
        assertFalse(cleaned.containsKey("\$schema"))
        val metric = cleaned["properties"]!!.jsonObject["metric"]!!.jsonObject
        assertEquals("string", metric["type"]!!.jsonPrimitive.content)
        assertEquals(true, metric["nullable"]!!.jsonPrimitive.boolean)

        // VALIDATED constrained decoding — Gemini's strict mode.
        val mode = body["toolConfig"]!!.jsonObject["functionCallingConfig"]!!
            .jsonObject["mode"]!!.jsonPrimitive.content
        assertEquals("VALIDATED", mode)
    }

    @Test
    fun testResponseSchemaOnlyOnToolLessTurns() {
        val format = JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("coach_response"),
            "schema" to JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "additionalProperties" to JsonPrimitive(false),
            )),
        ))

        // With tools: schema omitted (Gemini rejects declarations + responseSchema).
        val withTools = GeminiClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")),
            tools = listOf(functionTool("t", JsonObject(mapOf("type" to JsonPrimitive("object"))))),
            textFormat = format,
        ))
        assertNull(withTools["generationConfig"])

        // Tool-less: structured output enforced, schema cleaned.
        val toolLess = GeminiClient("key").buildRequestBody(request(
            listOf(msg("user", "hi")), textFormat = format,
        ))
        val genConfig = toolLess["generationConfig"]!!.jsonObject
        assertEquals("application/json", genConfig["responseMimeType"]!!.jsonPrimitive.content)
        assertFalse(genConfig["responseSchema"]!!.jsonObject.containsKey("additionalProperties"))
    }

    @Test
    fun testImagePartsMapToInlineData() {
        val content = JsonArray(listOf(
            JsonObject(mapOf("type" to JsonPrimitive("input_text"), "text" to JsonPrimitive("look"))),
            JsonObject(mapOf(
                "type" to JsonPrimitive("input_image"),
                "image_url" to JsonPrimitive("data:image/jpeg;base64,QUJD"),
            )),
        ))
        val item = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to content))
        val body = GeminiClient("key").buildRequestBody(request(listOf(item)))

        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("look", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        val inline = parts[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("QUJD", inline["data"]!!.jsonPrimitive.content)
    }

    // ── Continuation + tool results ─────────────────────────────────────

    @Test
    fun testContinuationReplaysModelPartsAndConvertsToolResults() {
        val client = GeminiClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))

        // Model asks for a function call.
        val response = client.ingestResponse(JsonObject(mapOf(
            "candidates" to JsonArray(listOf(JsonObject(mapOf(
                "content" to JsonObject(mapOf(
                    "parts" to JsonArray(listOf(JsonObject(mapOf(
                        "functionCall" to JsonObject(mapOf(
                            "name" to JsonPrimitive("get_hr"),
                            "args" to JsonObject(mapOf("days" to JsonPrimitive(7))),
                        )),
                    )))),
                )),
            )))),
        )))
        val call = response.output.filterIsInstance<FunctionCallOutput>().single()
        assertEquals("get_hr", call.name)
        assertEquals("""{"days":7}""", call.arguments)
        assertTrue(call.callId.startsWith("gemini_call_"))

        // Feed the tool result back with previous_response_id.
        val toolResult = JsonObject(mapOf(
            "type" to JsonPrimitive("function_call_output"),
            "call_id" to JsonPrimitive(call.callId),
            "output" to JsonPrimitive("""{"avg":61}"""),
        ))
        val body = client.buildRequestBody(request(
            listOf(toolResult), previousResponseId = response.id,
        ))

        val contents = body["contents"]!!.jsonArray.map { it.jsonObject }
        // [user hi, model functionCall replay, user functionResponse]
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"]!!.jsonPrimitive.content })
        val fr = contents[2]["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get_hr", fr["name"]!!.jsonPrimitive.content)
        assertEquals(61, fr["response"]!!.jsonObject["result"]!!.jsonObject["avg"]!!.jsonPrimitive.int)
    }

    @Test
    fun testThoughtSignatureIsPreservedWhenReplayingModelParts() {
        // Gemini 3.x attaches an encrypted thoughtSignature to functionCall/text parts; the API
        // rejects (HTTP 400) any replayed model turn that drops it. The replay must echo it back
        // verbatim on every part (issue #22).
        val client = GeminiClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))

        val response = client.ingestResponse(JsonObject(mapOf(
            "candidates" to JsonArray(listOf(JsonObject(mapOf(
                "content" to JsonObject(mapOf(
                    "parts" to JsonArray(listOf(
                        JsonObject(mapOf(
                            "text" to JsonPrimitive("let me check"),
                            "thoughtSignature" to JsonPrimitive("SIG_TEXT"),
                        )),
                        JsonObject(mapOf(
                            "functionCall" to JsonObject(mapOf(
                                "name" to JsonPrimitive("get_hr"),
                                "args" to JsonObject(mapOf("days" to JsonPrimitive(7))),
                            )),
                            "thoughtSignature" to JsonPrimitive("SIG_FC"),
                        )),
                    )),
                )),
            )))),
        )))
        val call = response.output.filterIsInstance<FunctionCallOutput>().single()

        val toolResult = JsonObject(mapOf(
            "type" to JsonPrimitive("function_call_output"),
            "call_id" to JsonPrimitive(call.callId),
            "output" to JsonPrimitive("""{"avg":61}"""),
        ))
        val body = client.buildRequestBody(request(
            listOf(toolResult), previousResponseId = response.id,
        ))

        // Replayed model turn keeps the signature on both the text part and the functionCall part.
        val modelParts = body["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        val textPart = modelParts.single { it.containsKey("text") }
        assertEquals("SIG_TEXT", textPart["thoughtSignature"]!!.jsonPrimitive.content)
        val fcPart = modelParts.single { it.containsKey("functionCall") }
        assertEquals("SIG_FC", fcPart["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun testMissingThoughtSignatureOmitsTheKey() {
        // Gemini 2.5 responses carry no signature — the replayed part must not invent one.
        val client = GeminiClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))
        val response = client.ingestResponse(JsonObject(mapOf(
            "candidates" to JsonArray(listOf(JsonObject(mapOf(
                "content" to JsonObject(mapOf(
                    "parts" to JsonArray(listOf(JsonObject(mapOf(
                        "functionCall" to JsonObject(mapOf(
                            "name" to JsonPrimitive("get_hr"),
                            "args" to JsonObject(emptyMap()),
                        )),
                    )))),
                )),
            )))),
        )))
        val call = response.output.filterIsInstance<FunctionCallOutput>().single()
        val body = client.buildRequestBody(request(
            listOf(JsonObject(mapOf(
                "type" to JsonPrimitive("function_call_output"),
                "call_id" to JsonPrimitive(call.callId),
                "output" to JsonPrimitive("{}"),
            ))),
            previousResponseId = response.id,
        ))
        val fcPart = body["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertFalse(fcPart.containsKey("thoughtSignature"))
    }

    // ── Web search grounding ────────────────────────────────────────────

    @Test
    fun testWebSearchGroundsExactlyOnceOnToolLessTurn() {
        val client = GeminiClient("key")
        val format = JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "schema" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
        ))

        // Tool turn: web_search_preview is dropped from declarations but remembered.
        val first = client.buildRequestBody(request(
            listOf(msg("user", "hi")),
            tools = listOf(webSearchTool, functionTool("t", JsonObject(mapOf("type" to JsonPrimitive("object"))))),
            textFormat = format,
        ))
        val decls = first["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, decls.size)

        val id1 = client.ingestResponse(textResponse("prose")).id

        // First tool-less turn grounds with google_search and skips the schema.
        val grounding = client.buildRequestBody(request(
            listOf(msg("user", "continue")), textFormat = format, previousResponseId = id1,
        ))
        assertTrue(grounding["tools"]!!.jsonArray[0].jsonObject.containsKey("google_search"))
        assertNull(grounding["generationConfig"])

        val id2 = client.ingestResponse(textResponse("grounded")).id

        // The next tool-less turn enforces the schema (grounded already).
        val schemaTurn = client.buildRequestBody(request(
            listOf(msg("user", "format it")), textFormat = format, previousResponseId = id2,
        ))
        assertNull(schemaTurn["tools"])
        assertNotNull(schemaTurn["generationConfig"])
    }

    @Test
    fun testGroundingSourcesRideOnNextUserTurn() {
        val client = GeminiClient("key")
        client.buildRequestBody(request(listOf(msg("user", "hi"))))

        val grounded = client.ingestResponse(JsonObject(mapOf(
            "candidates" to JsonArray(listOf(JsonObject(mapOf(
                "content" to JsonObject(mapOf(
                    "parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("prose"))))),
                )),
                "groundingMetadata" to JsonObject(mapOf(
                    "groundingChunks" to JsonArray(listOf(JsonObject(mapOf(
                        "web" to JsonObject(mapOf(
                            "uri" to JsonPrimitive("https://example.com"),
                            "title" to JsonPrimitive("Example"),
                        )),
                    )))),
                )),
            )))),
        )))

        val body = client.buildRequestBody(request(
            listOf(msg("user", "format it")), previousResponseId = grounded.id,
        ))
        val userParts = body["contents"]!!.jsonArray.last().jsonObject["parts"]!!.jsonArray
        val note = userParts[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(note.startsWith("Web search sources"))
        assertTrue(note.contains("https://example.com"))
    }

    // ── Overload retry (issue #77) ─────────────────────────────────────

    @Test
    fun `a 503 is retried and the turn survives`() = kotlinx.coroutines.runBlocking {
        // gemini-flash-latest routes to whatever pool is congested; "high demand" 503s killed
        // every coach turn for days on end. A 503 means the request was never served, so
        // retrying cannot double-bill — unlike a read timeout, which stays never-retried.
        val client = GeminiClient("key")
        var attempts = 0
        val result = client.postWithOverloadRetry(
            "https://example.test/generateContent", ByteArray(0), backoffMs = 1,
        ) { _, _ ->
            if (attempts++ < 2) throw ResponsesError.Http(503, "This model is currently experiencing high demand.")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `the last 503 still surfaces`() = kotlinx.coroutines.runBlocking {
        val client = GeminiClient("key")
        var attempts = 0
        try {
            client.postWithOverloadRetry(
                "https://example.test/generateContent", ByteArray(0), backoffMs = 1,
            ) { _, _ ->
                attempts++
                throw ResponsesError.Http(503, "still overloaded")
            }
            fail("a persistently overloaded model must surface, not hang")
        } catch (e: ResponsesError.Http) {
            assertEquals(503, e.status)
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `a 429 is never retried`() = kotlinx.coroutines.runBlocking {
        // Quota buckets refill daily, not in seconds — retrying only burns the turn's wall time.
        val client = GeminiClient("key")
        var attempts = 0
        try {
            client.postWithOverloadRetry(
                "https://example.test/generateContent", ByteArray(0), backoffMs = 1,
            ) { _, _ ->
                attempts++
                throw ResponsesError.Http(429, "You exceeded your current quota.")
            }
            fail("429 must surface immediately")
        } catch (e: ResponsesError.Http) {
            assertEquals(429, e.status)
        }
        assertEquals(1, attempts)
    }

    // ── Refused grounding turn (issue #123) ────────────────────────────

    private val schemaFormat = JsonObject(mapOf(
        "type" to JsonPrimitive("json_schema"),
        "schema" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
    ))

    private fun bytes(req: JsonObject) = req.toString().toByteArray()

    private fun isGrounding(body: JsonObject) =
        body["tools"]?.jsonArray?.any { it.jsonObject.containsKey("google_search") } == true

    /** Tool turn (web search requested) answered with prose; returns its response id. */
    private suspend fun toolTurn(client: GeminiClient): String = client.send(bytes(request(
        listOf(msg("user", "hi")),
        tools = listOf(webSearchTool, functionTool("t", JsonObject(mapOf("type" to JsonPrimitive("object"))))),
        textFormat = schemaFormat,
    ))).id

    private suspend fun repairTurn(client: GeminiClient, previousId: String) = client.send(bytes(request(
        listOf(msg("user", "format it")), textFormat = schemaFormat, previousResponseId = previousId,
    )))

    private fun lastUserText(body: JsonObject) = body["contents"]!!.jsonArray.last().jsonObject["parts"]!!
        .jsonArray.joinToString(" ") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }

    @Test
    fun `a 429 on the grounding turn is retried once without google_search`() = kotlinx.coroutines.runBlocking {
        // Free-tier keys get 429 RESOURCE_EXHAUSTED for google_search alone while plain, function
        // and schema calls succeed — every coach question failed with Web Search on.
        val sent = mutableListOf<JsonObject>()
        val client = GeminiClient("key-123-retry", transport = { _, b ->
            val body = Json.parseToJsonElement(String(b)).jsonObject
            sent.add(body)
            if (isGrounding(body)) throw ResponsesError.Http(429, "You exceeded your current quota")
            textResponse(if (sent.size == 1) "prose" else """{"title":"ok"}""").toString()
        })

        val answer = repairTurn(client, toolTurn(client))

        assertEquals("""{"title":"ok"}""", answer.outputText)
        assertEquals(3, sent.size)
        assertTrue(isGrounding(sent[1]))
        val retry = sent[2]
        assertFalse(isGrounding(retry))
        assertNull(retry["tools"])
        // The retry is the schema turn it would otherwise have been, and says search was skipped.
        assertNotNull(retry["generationConfig"]!!.jsonObject["responseSchema"])
        assertTrue(lastUserText(retry).contains("Web search was not available"))
    }

    @Test
    fun `a refused key skips grounding on later turns without the extra round trip`() = kotlinx.coroutines.runBlocking {
        val key = "key-123-remembered"
        var refusals = 0
        val transport: suspend (String, ByteArray) -> String = { _, b ->
            val body = Json.parseToJsonElement(String(b)).jsonObject
            if (isGrounding(body)) { refusals++; throw ResponsesError.Http(429, "quota") }
            textResponse("prose").toString()
        }
        val first = GeminiClient(key, transport = transport)
        repairTurn(first, toolTurn(first))
        assertEquals(1, refusals)

        // Next agent turn: a fresh client (as the orchestrator builds one per turn).
        val sent = mutableListOf<JsonObject>()
        val second = GeminiClient(key, transport = { u, b ->
            sent.add(Json.parseToJsonElement(String(b)).jsonObject)
            transport(u, b)
        })
        repairTurn(second, toolTurn(second))
        assertEquals(1, refusals)
        assertEquals(2, sent.size)
        assertFalse(isGrounding(sent[1]))
        assertNotNull(sent[1]["generationConfig"]!!.jsonObject["responseSchema"])
        assertTrue(lastUserText(sent[1]).contains("Web search was not available"))

        // A different key is unaffected.
        val other = GeminiClient("key-123-other", transport = { _, b ->
            sent.add(Json.parseToJsonElement(String(b)).jsonObject)
            textResponse("prose").toString()
        })
        repairTurn(other, toolTurn(other))
        assertTrue(isGrounding(sent.last()))
    }

    @Test
    fun `a 429 on a non-grounding turn still surfaces`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val client = GeminiClient("key-123-plain", transport = { _, _ ->
            attempts++
            throw ResponsesError.Http(429, "You exceeded your current quota")
        })
        try {
            toolTurn(client)
            fail("a quota refusal on a normal turn must surface")
        } catch (e: ResponsesError.Http) {
            assertEquals(429, e.status)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `a 429 on the ungrounded retry surfaces and is not remembered`() = kotlinx.coroutines.runBlocking {
        // The key itself is out of quota: the retry fails too, and grounding is not blamed.
        val key = "key-123-exhausted"
        var calls = 0
        val client = GeminiClient(key, transport = { _, _ ->
            if (calls++ == 0) textResponse("prose").toString()
            else throw ResponsesError.Http(429, "You exceeded your current quota")
        })
        val id = toolTurn(client)
        try {
            repairTurn(client, id)
            fail("a 429 on the retry must surface, not loop")
        } catch (e: ResponsesError.Http) {
            assertEquals(429, e.status)
        }
        assertEquals(3, calls)   // tool turn, grounding turn, one retry

        // Not marked as grounding-refused: a later turn on the same key still grounds.
        val sent = mutableListOf<JsonObject>()
        val next = GeminiClient(key, transport = { _, b ->
            sent.add(Json.parseToJsonElement(String(b)).jsonObject)
            textResponse("prose").toString()
        })
        repairTurn(next, toolTurn(next))
        assertTrue(isGrounding(sent.last()))
    }
}
