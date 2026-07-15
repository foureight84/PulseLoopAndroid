package com.pulseloop.coach.gemini

import com.pulseloop.coach.openai.FunctionCallOutput
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
    ) = JsonObject(buildMap {
        put("model", JsonPrimitive("gemini-2.5-flash"))
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
}
