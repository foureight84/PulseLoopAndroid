package com.pulseloop.coach.orchestration

import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.coach.openai.MessageOutput
import com.pulseloop.coach.openai.OpenAIResponse
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openai.TextContent
import com.pulseloop.coach.tools.CoachFeatureFlags
import com.pulseloop.coach.tools.CoachSettings
import com.pulseloop.coach.tools.ToolExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Asserts the null=omit contract for `CoachSettings.reasoningEffort` on the
 * actual OpenAI Responses request body: models like gpt-4o reject requests
 * carrying a `reasoning` field, so null must keep the key out entirely.
 */
class CoachOrchestratorReasoningTest {

    /** Returns a valid coach_response immediately so the turn completes in one round. */
    private class CapturingClient : ResponsesClient {
        val bodies = mutableListOf<JsonObject>()
        override suspend fun send(requestBody: ByteArray): OpenAIResponse {
            bodies.add(Json.parseToJsonElement(String(requestBody)).jsonObject)
            return OpenAIResponse(
                id = "resp_1",
                output = listOf(MessageOutput(content = listOf(TextContent(validResponseJSON)))),
            )
        }
    }

    private fun orchestrator(client: CapturingClient, settings: CoachSettings) = CoachOrchestrator(
        clientFactory = { client },
        flagsProvider = { CoachFeatureFlags(settings = settings) },
        toolContextFactory = { ToolExecutionContext(flags = it) },
    )

    @Test
    fun testNullReasoningEffortOmitsReasoningKey() = runTest {
        val client = CapturingClient()
        val result = orchestrator(client, CoachSettings(reasoningEffort = null))
            .runTurn("hi", CoachContextPacket())

        assertNull(result.error)
        assertTrue(client.bodies.isNotEmpty())
        for (body in client.bodies) {
            assertFalse("null effort must not send a reasoning field", "reasoning" in body)
        }
    }

    @Test
    fun testMediumReasoningEffortSendsReasoningKey() = runTest {
        val client = CapturingClient()
        val result = orchestrator(client, CoachSettings(reasoningEffort = "medium"))
            .runTurn("hi", CoachContextPacket())

        assertNull(result.error)
        assertTrue(client.bodies.isNotEmpty())
        for (body in client.bodies) {
            assertEquals("medium",
                body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    companion object {
        private const val validResponseJSON =
            """{"response_type":"insight","title":"Today","summary":"You did well.","bullets":["A"],"chart":null,"safety_note":null,"data_quality_note":null,"sources":[],"follow_up_chips":[],"actions_taken":[],"confidence":"high"}"""
    }
}
