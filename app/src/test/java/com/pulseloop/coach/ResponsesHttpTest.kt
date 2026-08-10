package com.pulseloop.coach.openai

import com.pulseloop.coach.orchestration.CoachTurnError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Drives [ResponsesHttp.post] against a real socket. The hand-built `ResponsesError.Transport(…)`
 * tests in [CoachTurnErrorTest] cover the *mapping*; these cover whether the transport actually
 * produces that wrapper on the paths users hit.
 */
class ResponsesHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/v1/responses").toString()

    private fun post() = runBlocking { ResponsesHttp.post(url(), "{}".toByteArray()) }

    @Test
    fun testSuccessfulResponseReturnsTheBody() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertEquals("""{"ok":true}""", post())
        assertEquals(1, server.requestCount)
    }

    /**
     * A non-2xx is an *answer*, not a transport failure: it must surface as [ResponsesError.Http]
     * with the body intact, and must not be retried or re-wrapped as Transport by the retry
     * loop's catch-all.
     */
    @Test
    fun testHttpErrorIsNotRetriedAndKeepsItsBody() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        val thrown = try {
            post(); null
        } catch (e: ResponsesError) {
            e
        }
        val http = thrown as? ResponsesError.Http
        assertNotNull("expected ResponsesError.Http, got $thrown", http)
        assertEquals(429, http!!.status)
        assertTrue(http.body.contains("slow down"))
        assertEquals("one attempt only — an HTTP status is an answer", 1, server.requestCount)
    }

    /**
     * The regression this file exists for. The body read used to sit *outside* the try, so any
     * failure after the response headers arrived — a mid-stream disconnect, or a read timeout that
     * fires while the body is still coming — escaped unwrapped and `CoachTurnError` fell through to
     * its generic branch, printing the raw JDK string again (for a timeout, the one word "timeout")
     * instead of the transport copy.
     *
     * Uses a mid-body disconnect rather than a stalled socket so the test doesn't have to wait out
     * the client's 60 s read timeout; both take the identical path through the body read.
     */
    @Test
    fun testFailureWhileStreamingTheBodyIsStillATransportError() {
        server.enqueue(
            MockResponse()
                .setBody("""{"partial":""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val thrown = try {
            post(); null
        } catch (e: Exception) {
            e
        }
        assertTrue(
            "expected ResponsesError.Transport, got $thrown",
            thrown is ResponsesError.Transport,
        )
        // And it therefore reaches the user through the Network branch rather than the generic
        // "else -> error.message" fallback that produced the unreadable strings.
        assertEquals("Network", CoachTurnError.from(thrown as ResponsesError).code)
        assertEquals("a body-read failure is not retried", 1, server.requestCount)
    }

    /**
     * DNS never left the device, so re-sending is free — three attempts total (1 + 2 retries), then
     * the failure surfaces as Transport. Uses a host that cannot resolve rather than the server.
     */
    @Test
    fun testUnresolvableHostRetriesThenFailsAsTransport() {
        val thrown = try {
            runBlocking {
                ResponsesHttp.post("https://pulseloop.invalid/v1/responses", "{}".toByteArray())
            }
            null
        } catch (e: Exception) {
            e
        }
        val transport = thrown as? ResponsesError.Transport
        assertNotNull("expected ResponsesError.Transport, got $thrown", transport)
        assertTrue(transport!!.underlying is java.net.UnknownHostException)
    }
}
