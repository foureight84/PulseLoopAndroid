package com.pulseloop.coach.local

import java.net.URI

/**
 * URL handling for the self-hosted ("local") coach provider — see
 * `docs/local-llm-coach.md` §3.
 *
 * The user types a *base* URL (`http://192.168.1.50:11434`, `http://localhost:1234/v1`,
 * `https://llm.example.com`), not a full endpoint path, because every engine serves the same
 * OpenAI-compatible routes under a `/v1` prefix: Ollama on 11434, llama.cpp on 8080, vLLM on
 * 8000, SGLang on 30000, LM Studio on 1234. This object turns whatever they typed into the two
 * concrete URLs the app calls, and enforces the plaintext-host rule that Android's Network
 * Security Config can't express.
 */
object LocalEndpoint {

    /** Why a base URL can't be used. `null` from [validate] means it's fine. */
    enum class Problem { BLANK, MALFORMED, UNSUPPORTED_SCHEME, PUBLIC_CLEARTEXT }

    /**
     * Normalizes a user-typed base URL to its scheme+authority+path root, with any trailing `/`
     * and any trailing `/v1` (or `/v1/chat/completions`, if they pasted the full endpoint)
     * stripped — so [chatCompletionsUrl] and [modelsUrl] can append the canonical suffix without
     * producing `/v1/v1`. A bare `host:port` with no scheme is assumed to be `http://` (the
     * overwhelmingly common local case; a public host would be rejected by [validate] anyway).
     *
     * Returns null when the input can't be parsed into a scheme + host.
     */
    fun normalize(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "http://$text"
        val uri = try { URI(text) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null

        var path = (uri.path ?: "").trimEnd('/')
        // Tolerate a pasted full endpoint or an explicit /v1 — both are re-appended by callers.
        for (suffix in listOf("/v1/chat/completions", "/chat/completions", "/v1")) {
            if (path.endsWith(suffix)) { path = path.dropLast(suffix.length); break }
        }
        path = path.trimEnd('/')

        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path"
    }

    /** `POST` target for a chat turn. */
    fun chatCompletionsUrl(base: String): String? = normalize(base)?.let { "$it/v1/chat/completions" }

    /** `GET` target that lists the models the server currently has loaded/available. */
    fun modelsUrl(base: String): String? = normalize(base)?.let { "$it/v1/models" }

    /**
     * The reason [raw] can't be used, or null if it's usable.
     *
     * `https://` is unrestricted — a self-hosted box with a real certificate is the user's call.
     * Plaintext `http://` is confined to hosts that can't be on the public internet: loopback,
     * RFC1918 / CGNAT / link-local addresses, and mDNS `*.local` names. The app permits cleartext
     * app-wide in `network_security_config.xml` (Network Security Config has no CIDR syntax), so
     * this is the check that actually keeps an API key and a stream of health data off the open
     * internet in the clear.
     */
    fun validate(raw: String): Problem? {
        if (raw.isBlank()) return Problem.BLANK
        val normalized = normalize(raw) ?: return Problem.MALFORMED
        val uri = try { URI(normalized) } catch (_: Exception) { return Problem.MALFORMED }
        val scheme = uri.scheme?.lowercase() ?: return Problem.MALFORMED
        val host = uri.host?.lowercase() ?: return Problem.MALFORMED
        return when (scheme) {
            "https" -> null
            "http" -> if (isPrivateHost(host)) null else Problem.PUBLIC_CLEARTEXT
            else -> Problem.UNSUPPORTED_SCHEME
        }
    }

    /** A short, user-facing explanation for a [Problem], for the Settings field. */
    fun message(problem: Problem): String = when (problem) {
        Problem.BLANK -> "Enter your server's address, e.g. http://192.168.1.50:11434"
        Problem.MALFORMED -> "That doesn't look like a URL — use host:port or http://host:port"
        Problem.UNSUPPORTED_SCHEME -> "Only http:// and https:// are supported."
        Problem.PUBLIC_CLEARTEXT ->
            "Plain http:// is only allowed for a server on this device or your local network. " +
            "Use https:// to reach one over the internet."
    }

    /**
     * True when [host] provably can't be routed off the local network: loopback (incl. the
     * `10.0.2.2` alias an emulator uses for the dev machine, which is RFC1918 anyway), RFC1918
     * (`10/8`, `172.16/12`, `192.168/16`), CGNAT `100.64/10` (Tailscale), link-local `169.254/16`,
     * IPv6 loopback/ULA/link-local, and mDNS `.local` names.
     */
    internal fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.endsWith(".local")) return true
        if (h.contains(':')) {   // IPv6
            val v6 = h.lowercase()
            return v6 == "::1" || v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe80:")
        }
        val octets = h.split('.')
        if (octets.size != 4) return false
        val nums = octets.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val (a, b) = nums[0] to nums[1]
        return when {
            a == 127 -> true                    // loopback
            a == 10 -> true                     // RFC1918
            a == 192 && b == 168 -> true        // RFC1918
            a == 172 && b in 16..31 -> true     // RFC1918
            a == 169 && b == 254 -> true        // link-local
            a == 100 && b in 64..127 -> true    // CGNAT / Tailscale
            else -> false
        }
    }
}
