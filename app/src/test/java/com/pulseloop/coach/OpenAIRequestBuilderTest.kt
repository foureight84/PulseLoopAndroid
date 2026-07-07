package com.pulseloop.coach.openai

import com.pulseloop.coach.attachments.CoachImagePayload
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the Responses-API input-item builder ported from ResponsesTypes.swift
 * (iOS PR #31): text messages stay plain strings; images switch the content to
 * the `input_text` + `input_image` content-part array.
 */
class OpenAIRequestBuilderTest {

    @Test
    fun testTextOnlyMessageKeepsStringContent() {
        val m = OpenAIRequestBuilder.message("user", "hello")
        assertEquals("user", m["role"]!!.jsonPrimitive.content)
        assertTrue(m["content"] is JsonPrimitive)
        assertEquals("hello", m["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun testMessageWithImagesBecomesContentPartArray() {
        val payload = CoachImagePayload(
            dataURL = "data:image/jpeg;base64,QUJD",
            rawBase64 = "QUJD",
            mimeType = "image/jpeg",
        )
        val m = OpenAIRequestBuilder.message("user", "look at this", listOf(payload))
        val parts = m["content"]!!.jsonArray.map { it.jsonObject }

        assertEquals("input_text", parts[0]["type"]!!.jsonPrimitive.content)
        assertEquals("look at this", parts[0]["text"]!!.jsonPrimitive.content)
        assertEquals("input_image", parts[1]["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD", parts[1]["image_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun testFunctionCallOutputShape() {
        val item = OpenAIRequestBuilder.functionCallOutput("call_1", """{"ok":true}""")
        assertEquals("function_call_output", item["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"ok":true}""", item["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun testReasoningParamsOmittedWhenNullOrBlank() {
        // null = "don't send reasoning at all" — gpt-4o rejects the field.
        assertTrue(OpenAIRequestBuilder.reasoningParams(null).isEmpty())
        assertTrue(OpenAIRequestBuilder.reasoningParams("").isEmpty())
        assertTrue(OpenAIRequestBuilder.reasoningParams("   ").isEmpty())
    }

    @Test
    fun testReasoningParamsCarriesEffort() {
        val params = OpenAIRequestBuilder.reasoningParams("medium")
        assertEquals(setOf("reasoning"), params.keys)
        assertEquals("medium", params["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }
}
