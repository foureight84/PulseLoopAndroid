package com.pulseloop.coach.openai

import org.junit.Assert.*
import org.junit.Test

/**
 * `OpenAIResponse.parse` used to decode via automatic kotlinx.serialization
 * derivation for `output: List<ResponseOutputItem>`, but `ResponseOutputItem`
 * was a bare marker interface (not `sealed`/`@Serializable`) — any real
 * Responses-API JSON with non-empty `output` threw "Serializer for subclass
 * '<type>' is not found in the polymorphic scope" instead of parsing. These
 * lock the manual-parse replacement against realistic API shapes.
 */
class OpenAIResponseParseTest {

    @Test
    fun parsesMessageOutputAndUsage() {
        val json = """
        {
          "id": "resp_123",
          "output": [
            {
              "type": "message",
              "role": "assistant",
              "content": [{"type": "output_text", "text": "hello world"}]
            }
          ],
          "usage": {
            "input_tokens": 100,
            "output_tokens": 20,
            "input_tokens_details": {"cached_tokens": 40}
          }
        }
        """.trimIndent()
        val parsed = OpenAIResponse.parse(json)
        assertEquals("resp_123", parsed.id)
        assertEquals("hello world", parsed.outputText)
        assertEquals(100, parsed.usage?.inputTokens)
        assertEquals(20, parsed.usage?.outputTokens)
        assertEquals(40, parsed.usage?.cachedInputTokens)
    }

    @Test
    fun parsesFunctionCallWithSnakeCaseCallId() {
        val json = """
        {
          "id": "resp_456",
          "output": [
            {
              "type": "function_call",
              "id": "fc_1",
              "call_id": "call_abc",
              "name": "get_weather",
              "arguments": "{\"city\":\"SF\"}",
              "status": "completed"
            }
          ]
        }
        """.trimIndent()
        val parsed = OpenAIResponse.parse(json)
        val call = parsed.functionCalls.single()
        assertEquals("call_abc", call.callId)
        assertEquals("get_weather", call.name)
        assertNull(parsed.usage)
    }

    @Test
    fun skipsUnrecognizedOutputItemTypesInsteadOfThrowing() {
        val json = """
        {
          "id": "resp_789",
          "output": [
            {"type": "reasoning", "id": "rs_1", "summary": []},
            {
              "type": "message",
              "role": "assistant",
              "content": [{"type": "output_text", "text": "final answer"}]
            }
          ]
        }
        """.trimIndent()
        val parsed = OpenAIResponse.parse(json)
        assertEquals("final answer", parsed.outputText)
    }

    @Test
    fun missingUsageBlockStaysNull() {
        val parsed = OpenAIResponse.parse("""{"id": "resp_1", "output": []}""")
        assertNull(parsed.usage)
    }
}
