package com.pulseloop.coach

import com.pulseloop.coach.local.LocalEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers `docs/local-llm-coach.md` §3 — URL normalization and the plaintext-host rule. */
class LocalEndpointTest {

    @Test
    fun `normalizes a bare host and port to http`() {
        assertEquals("http://192.168.1.50:11434", LocalEndpoint.normalize("192.168.1.50:11434"))
        assertEquals("http://localhost:1234", LocalEndpoint.normalize("localhost:1234"))
    }

    @Test
    fun `strips a trailing slash, v1, and a pasted full endpoint`() {
        val expected = "http://localhost:11434"
        for (input in listOf(
            "http://localhost:11434",
            "http://localhost:11434/",
            "http://localhost:11434/v1",
            "http://localhost:11434/v1/",
            "http://localhost:11434/v1/chat/completions",
        )) {
            assertEquals(input, expected, LocalEndpoint.normalize(input))
        }
    }

    @Test
    fun `keeps a reverse-proxy path prefix`() {
        assertEquals("https://box.example.com/llm",
            LocalEndpoint.normalize("https://box.example.com/llm/v1/"))
    }

    @Test
    fun `builds the chat and models urls`() {
        assertEquals("http://localhost:8080/v1/chat/completions",
            LocalEndpoint.chatCompletionsUrl("localhost:8080/v1"))
        assertEquals("http://localhost:8080/v1/models",
            LocalEndpoint.modelsUrl("localhost:8080"))
    }

    @Test
    fun `accepts cleartext only for private hosts`() {
        for (host in listOf(
            "http://localhost:11434", "http://127.0.0.1:8080", "http://10.0.2.2:1234",
            "http://192.168.1.50:11434", "http://10.1.2.3:8000", "http://172.16.0.9:30000",
            "http://100.64.1.2:11434", "http://mac-studio.local:1234", "http://[::1]:8080",
            // Name forms that only resolve on a local network. Rejecting these told the user the
            // server had to be on their LAN, which is exactly where it was.
            "http://nas:11434", "http://ollama.lan:8080", "http://box.tail1234.ts.net:11434",
            "http://pi.home:8000", "http://llm.internal:1234", "http://srv.home.arpa:11434",
        )) {
            assertNull(host, LocalEndpoint.validate(host))
        }
        for (host in listOf("http://example.com:11434", "http://8.8.8.8:8080", "http://172.32.0.1:80")) {
            assertEquals(host, LocalEndpoint.Problem.PUBLIC_CLEARTEXT, LocalEndpoint.validate(host))
        }
    }

    @Test
    fun `https is unrestricted and other schemes are rejected`() {
        assertNull(LocalEndpoint.validate("https://llm.example.com"))
        assertEquals(LocalEndpoint.Problem.UNSUPPORTED_SCHEME, LocalEndpoint.validate("ftp://box/llm"))
    }

    @Test
    fun `blank and malformed are distinguished`() {
        assertEquals(LocalEndpoint.Problem.BLANK, LocalEndpoint.validate("   "))
        assertEquals(LocalEndpoint.Problem.MALFORMED, LocalEndpoint.validate("http://"))
    }

    @Test
    fun `a public dotted hostname is still rejected over cleartext`() {
        // The single-label allowance must not leak into ordinary registered domains.
        assert(!LocalEndpoint.isPrivateHost("example.com"))
        assert(!LocalEndpoint.isPrivateHost("llm.example.com"))
        assert(!LocalEndpoint.isPrivateHost("ollama.io"))
        assert(!LocalEndpoint.isPrivateHost("notlocal.localdomain"))
    }

    @Test
    fun `172 private range boundaries`() {
        // 172.16-172.31 are private; 172.15 and 172.32 are not.
        assert(LocalEndpoint.isPrivateHost("172.16.0.1"))
        assert(LocalEndpoint.isPrivateHost("172.31.255.254"))
        assert(!LocalEndpoint.isPrivateHost("172.15.0.1"))
        assert(!LocalEndpoint.isPrivateHost("172.32.0.1"))
    }
}
