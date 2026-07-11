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

    @Test
    fun testReasoningGatedForNonReasoningModels() {
        // A set effort must NOT be sent to models that reject `reasoning` — the ungated combo
        // (non-reasoning model + effort) otherwise fails every coach turn (issue #19).
        assertTrue(OpenAIRequestBuilder.reasoningParams("high", "gpt-4o").isEmpty())
        assertTrue(OpenAIRequestBuilder.reasoningParams("high", "gpt-4o-mini").isEmpty())
        assertTrue(OpenAIRequestBuilder.reasoningParams("medium", "gpt-3.5-turbo").isEmpty())
        assertTrue(OpenAIRequestBuilder.reasoningParams("low", "chatgpt-4o-latest").isEmpty())
        // OpenRouter vendor prefix is stripped before matching.
        assertTrue(OpenAIRequestBuilder.reasoningParams("high", "openai/gpt-4o").isEmpty())
    }

    @Test
    fun testReasoningAllowedForReasoningModels() {
        // Reasoning models (and unknown/future slugs) still carry the effort when set.
        assertEquals("high", OpenAIRequestBuilder.reasoningParams("high", "gpt-5.4")["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(setOf("reasoning"), OpenAIRequestBuilder.reasoningParams("medium", "gpt-5.5").keys)
        assertEquals(setOf("reasoning"), OpenAIRequestBuilder.reasoningParams("low", "o3-mini").keys)
        // Null effort is still omitted regardless of model.
        assertTrue(OpenAIRequestBuilder.reasoningParams(null, "gpt-5.4").isEmpty())
    }
}
