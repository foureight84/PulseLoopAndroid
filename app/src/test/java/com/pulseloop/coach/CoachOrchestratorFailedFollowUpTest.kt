package com.pulseloop.coach.orchestration

import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.coach.openai.FunctionCallOutput
import com.pulseloop.coach.openai.MessageOutput
import com.pulseloop.coach.openai.OpenAIResponse
import com.pulseloop.coach.openai.ResponsesClient
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.coach.openai.TextContent
import com.pulseloop.coach.tools.CoachFeatureFlags
import com.pulseloop.coach.tools.ToolExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * iOS 6d006ec: once a tool has run, a failed follow-up request must not drop the tool trace — the
 * error bubble is otherwise the only thing the user sees of a write that may have happened.
 */
class CoachOrchestratorFailedFollowUpTest {

    /** Scripted replies: each entry is returned, or thrown when it is an exception. */
    private class ScriptedClient(private val replies: List<Any>) : ResponsesClient {
        var calls = 0
        override suspend fun send(requestBody: ByteArray): OpenAIResponse {
            val reply = replies[calls++]
            if (reply is Exception) throw reply
            return reply as OpenAIResponse
        }
    }

    private fun orchestrator(client: ScriptedClient) = CoachOrchestrator(
        clientFactory = { client },
        flagsProvider = { CoachFeatureFlags() },
        toolContextFactory = { ToolExecutionContext(flags = it) },
    )

    private val toolCall = OpenAIResponse(
        id = "resp_1",
        output = listOf(FunctionCallOutput(callId = "call_1", name = "get_profile_context", arguments = "{}")),
    )

    @Test
    fun `a failed request after a tool ran keeps the trace`() = runTest {
        val client = ScriptedClient(listOf(toolCall, ResponsesError.Transport(java.net.SocketTimeoutException("timeout"))))
        val result = orchestrator(client).runTurn("how am I doing", CoachContextPacket())

        assertEquals(2, client.calls)
        assertEquals("Network", result.error?.code)
        assertEquals(listOf("get_profile_context"), result.trace.map { it.toolName })
    }

    @Test
    fun `a failed schema repair after a tool ran keeps the trace`() = runTest {
        val prose = OpenAIResponse(id = "resp_2", output = listOf(MessageOutput(content = listOf(TextContent("not json")))))
        val client = ScriptedClient(listOf(toolCall, prose, ResponsesError.Http(500, "boom")))
        val result = orchestrator(client).runTurn("how am I doing", CoachContextPacket())

        assertEquals(3, client.calls)
        assertEquals("HTTP 500", result.error?.code)
        assertEquals(listOf("get_profile_context"), result.trace.map { it.toolName })
    }
}
