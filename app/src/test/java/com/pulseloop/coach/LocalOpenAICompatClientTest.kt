package com.pulseloop.coach.local

import com.pulseloop.coach.config.LocalStructuredOutput
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.ResponsesError
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Request assembly + response ingestion for the self-hosted provider — no network.
 * The cases here are the ones `docs/local-llm-coach.md` says a local backend gets wrong: the
 * `developer` role (SGLang 400s / templates can't render it), a system turn that isn't first,
 * capability toggles, and tool `arguments` returned as an object rather than a JSON string.
 */
class LocalOpenAICompatClientTest {

    private fun client(
        tools: Boolean = true,
        structured: LocalStructuredOutput = LocalStructuredOutput.OFF,
        maxTokens: Int? = null,
        model: String = "qwen3:8b",
    ) = LocalOpenAICompatClient(
        baseUrl = "http://192.168.1.50:11434",
        model = model,
        apiKey = null,
        toolCallingEnabled = tools,
        structuredOutput = structured,
        maxOutputTokens = maxTokens,
    )

    private fun msg(role: String, content: String) = JsonObject(mapOf(
        "role" to JsonPrimitive(role),
        "content" to JsonPrimitive(content),
    ))

    private fun request(
        input: List<JsonObject>,
        tools: List<JsonObject> = emptyList(),
        previousResponseId: String? = null,
    ) = JsonObject(buildMap {
        put("model", JsonPrimitive("qwen3:8b"))
        put("input", JsonArray(input))
        put("tools", JsonArray(tools))
        previousResponseId?.let { put("previous_response_id", JsonPrimitive(it)) }
    })

    private val functionTool = JsonObject(mapOf(
        "type" to JsonPrimitive("function"),
        "name" to JsonPrimitive("get_hr"),
        "description" to JsonPrimitive("desc"),
        "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
        "strict" to JsonPrimitive(true),
    ))

    private fun messages(body: JsonObject) =
        (body["messages"] as JsonArray).map { it.jsonObject }

    private fun role(m: JsonObject) = m["role"]!!.jsonPrimitive.content
    private fun content(m: JsonObject) = m["content"]!!.jsonPrimitive.content

    // ── The developer-role fold ──────────────────────────────────────────

    @Test
    fun `no message ever carries the developer role`() {
        val body = client().buildRequestBody(request(listOf(
            msg("system", "SYS"), msg("developer", "DEV"), msg("user", "hi"),
        )))
        assertTrue(messages(body).none { role(it) == "developer" })
    }

    @Test
    fun `system and developer turns merge into one leading system message`() {
        val body = client().buildRequestBody(request(listOf(
            msg("system", "SYS"), msg("developer", "DEV"), msg("user", "hi"),
        )))
        val m = messages(body)
        // Exactly one system message, and it is first — several local chat templates raise
        // otherwise.
        assertEquals(1, m.count { role(it) == "system" })
        assertEquals("system", role(m[0]))
        assertTrue(content(m[0]).contains("SYS"))
        assertTrue(content(m[0]).contains("DEV"))
        // The coach_response spec joins the system block rather than trailing the conversation.
        assertTrue(content(m[0]).contains("coach_response"))
        assertEquals("user", role(m[1]))
        assertEquals("hi", content(m[1]))
    }

    @Test
    fun `a system turn arriving mid-conversation is folded back into the system block`() {
        val c = client()
        c.buildRequestBody(request(listOf(msg("system", "SYS"), msg("user", "hi"))))
        val body = c.buildRequestBody(request(
            input = listOf(msg("developer", "LATE CONTEXT")),
            previousResponseId = "resp_1",
        ))
        val m = messages(body)
        assertEquals(1, m.count { role(it) == "system" })
        assertTrue(content(m[0]).contains("LATE CONTEXT"))
    }

    // ── Capability toggles ───────────────────────────────────────────────

    @Test
    fun `tools are converted to the nested chat shape without strict`() {
        val body = client().buildRequestBody(
            request(listOf(msg("user", "hi")), tools = listOf(functionTool)))
        val tools = (body["tools"] as JsonArray).map { it.jsonObject }
        assertEquals(1, tools.size)
        assertEquals("function", tools[0]["type"]!!.jsonPrimitive.content)
        val fn = tools[0]["function"]!!.jsonObject
        assertEquals("get_hr", fn["name"]!!.jsonPrimitive.content)
        // `strict` is an OpenAI structured-outputs extension; local engines don't act on it.
        assertNull(fn["strict"])
    }

    @Test
    fun `tool calling off omits tools entirely`() {
        val body = client(tools = false).buildRequestBody(
            request(listOf(msg("user", "hi")), tools = listOf(functionTool)))
        assertNull(body["tools"])
    }

    @Test
    fun `web search is dropped - no local engine hosts one`() {
        val webSearch = JsonObject(mapOf("type" to JsonPrimitive("web_search")))
        val body = client().buildRequestBody(
            request(listOf(msg("user", "hi")), tools = listOf(webSearch)))
        assertNull(body["tools"])
    }

    @Test
    fun `structured output off sends no response_format`() {
        val body = client().buildRequestBody(request(listOf(msg("user", "hi"))))
        assertNull(body["response_format"])
    }

    @Test
    fun `json_object and json_schema send the expected response_format`() {
        val obj = client(structured = LocalStructuredOutput.JSON_OBJECT)
            .buildRequestBody(request(listOf(msg("user", "hi"))))["response_format"]!!.jsonObject
        assertEquals("json_object", obj["type"]!!.jsonPrimitive.content)

        val schema = client(structured = LocalStructuredOutput.JSON_SCHEMA)
            .buildRequestBody(request(listOf(msg("user", "hi"))))["response_format"]!!.jsonObject
        assertEquals("json_schema", schema["type"]!!.jsonPrimitive.content)
        val js = schema["json_schema"]!!.jsonObject
        assertEquals("coach_response", js["name"]!!.jsonPrimitive.content)
        assertTrue(js["strict"]!!.jsonPrimitive.boolean)
        assertNotNull(js["schema"])
    }

    @Test
    fun `max_tokens is omitted unless positive`() {
        assertNull(client().buildRequestBody(request(listOf(msg("user", "hi"))))["max_tokens"])
        assertEquals(2048, client(maxTokens = 2048)
            .buildRequestBody(request(listOf(msg("user", "hi"))))["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an empty model name is still sent - llama-cpp ignores the field`() {
        val body = client(model = "").buildRequestBody(request(listOf(msg("user", "hi"))))
        assertEquals("", body["model"]!!.jsonPrimitive.content)
    }

    // ── Nothing a local backend would reject ─────────────────────────────

    @Test
    fun `no reasoning, cache_control or provider block is ever sent`() {
        val body = client().buildRequestBody(
            request(listOf(msg("user", "hi")), tools = listOf(functionTool)))
        assertNull(body["reasoning"])
        assertNull(body["reasoning_effort"])
        assertNull(body["provider"])
        assertFalse(body.toString().contains("cache_control"))
    }

    // ── Response ingestion ───────────────────────────────────────────────

    private fun parse(json: String) =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun `parses content and strips think blocks`() {
        val r = client().ingestResponse(parse("""
            {"id":"chatcmpl-1","choices":[{"message":{"role":"assistant",
             "content":"<think>hmm</think>{\"title\":\"ok\"}"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":4}}
        """.trimIndent()))
        assertEquals("chatcmpl-1", r.id)
        assertEquals("{\"title\":\"ok\"}", r.outputText)
        assertEquals(10, r.usage?.inputTokens)
        assertEquals(4, r.usage?.outputTokens)
    }

    @Test
    fun `an unmatched leading close-think is stripped`() {
        // R1-style distills on llama.cpp/Ollama get the OPENING tag from the chat template, so the
        // completion starts mid-thought and content carries only the closing tag. Left in, the
        // whole chain of thought reached the parser and burned the repair budget.
        val r = client().ingestResponse(parse("""
            {"id":"c","choices":[{"message":{"role":"assistant",
             "content":"the user wants a plan. let me think.</think>{\"title\":\"ok\"}"}}]}
        """.trimIndent()))
        assertEquals("{\"title\":\"ok\"}", r.outputText)
    }

    @Test
    fun `an unterminated trailing open-think still drops its remainder`() {
        val r = client().ingestResponse(parse("""
            {"id":"c","choices":[{"message":{"role":"assistant",
             "content":"{\"title\":\"ok\"}<think>and then I would"}}]}
        """.trimIndent()))
        assertEquals("{\"title\":\"ok\"}", r.outputText)
    }

    @Test
    fun `text with no think tags at all is untouched`() {
        val r = client().ingestResponse(parse("""
            {"id":"c","choices":[{"message":{"role":"assistant","content":"{\"title\":\"ok\"}"}}]}
        """.trimIndent()))
        assertEquals("{\"title\":\"ok\"}", r.outputText)
    }

    @Test
    fun `tool call arguments survive both the string and object encodings`() {
        val asString = client().ingestResponse(parse("""
            {"id":"a","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"get_hr","arguments":"{\"days\":7}"}}]}}]}
        """.trimIndent()))
        assertEquals("{\"days\":7}",
            (asString.output.first() as FunctionCallOutput).arguments)

        // Several local tool-call parsers emit `arguments` as an object instead of a string.
        val asObject = client().ingestResponse(parse("""
            {"id":"b","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_2","type":"function","function":{"name":"get_hr","arguments":{"days":7}}}]}}]}
        """.trimIndent()))
        assertEquals("{\"days\":7}",
            (asObject.output.first() as FunctionCallOutput).arguments)
    }

    @Test
    fun `a body-level error is surfaced as a decoding failure`() {
        for (body in listOf(
            """{"error":{"message":"model not found"}}""",
            """{"error":"model not found"}""",
        )) {
            val e = assertThrows(ResponsesError.Decoding::class.java) {
                client().ingestResponse(parse(body))
            }
            assertTrue(e.msg.contains("model not found"))
        }
    }

    @Test
    fun `a non-OpenAI response says so instead of throwing a bare parse error`() {
        val e = assertThrows(ResponsesError.Decoding::class.java) {
            client().ingestResponse(parse("""{"response":"hello"}"""))
        }
        assertTrue(e.msg.contains("OpenAI-compatible"))
    }

    @Test
    fun `an empty choice throws EmptyOutput`() {
        assertThrows(ResponsesError.EmptyOutput::class.java) {
            client().ingestResponse(parse(
                """{"id":"c","choices":[{"message":{"role":"assistant","content":""}}]}"""))
        }
    }

    @Test
    fun `a reasoning model truncated mid-thought reports the token limit, not empty output`() {
        // vLLM 0.27 with a reasoning parser: content null, reasoning present, finish_reason length.
        val e = assertThrows(ResponsesError.Decoding::class.java) {
            client().ingestResponse(parse("""
                {"id":"e","choices":[{"finish_reason":"length","message":{
                  "role":"assistant","content":null,"reasoning":"The user asks"}}]}
            """.trimIndent()))
        }
        assertTrue(e.msg, e.msg.contains("token limit"))
        assertTrue(e.msg, e.msg.contains("reasoning"))
    }

    @Test
    fun `usage is null rather than zero when the server omits the block`() {
        val r = client().ingestResponse(parse(
            """{"id":"d","choices":[{"message":{"role":"assistant","content":"hi"}}]}"""))
        assertNull(r.usage)
    }

    // ── Continuation turns ───────────────────────────────────────────────

    @Test
    fun `a continuation replays the assistant tool_calls before the tool results`() {
        val c = client()
        c.buildRequestBody(request(listOf(msg("user", "hi")), tools = listOf(functionTool)))
        c.ingestResponse(parse("""
            {"id":"resp_9","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"get_hr","arguments":"{}"}}]}}]}
        """.trimIndent()))
        val body = c.buildRequestBody(request(
            input = listOf(JsonObject(mapOf(
                "type" to JsonPrimitive("function_call_output"),
                "call_id" to JsonPrimitive("call_1"),
                "output" to JsonPrimitive("""{"bpm":62}"""),
            ))),
            tools = listOf(functionTool),
            previousResponseId = "resp_9",
        ))
        val m = messages(body)
        val assistantIdx = m.indexOfFirst { role(it) == "assistant" }
        val toolIdx = m.indexOfFirst { role(it) == "tool" }
        assertTrue(assistantIdx in 0 until toolIdx)
        assertEquals("call_1", m[toolIdx]["tool_call_id"]!!.jsonPrimitive.content)
    }
}
