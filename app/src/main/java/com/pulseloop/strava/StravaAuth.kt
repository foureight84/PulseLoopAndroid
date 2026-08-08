package com.pulseloop.strava

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,       // epoch seconds
    val athleteId: Long? = null,
    val athleteName: String? = null,
)

object StravaAuth {

    private const val CLIENT_ID = "147230"
    private const val CLIENT_SECRET = "a2a3fca78e1e6fc9ea0e0a2cc5c9b35a049d38b4"
    private const val AUTH_BASE = "https://www.strava.com/oauth"
    private const val API_BASE = "https://www.strava.com/api/v3"
    private const val REDIRECT_URI = "pulseloop://localhost/strava-auth"
    private const val SCOPES = "activity:write,read"

    private val json = Json { ignoreUnknownKeys = true }

    val authUrl: String
        get() {
            val state = java.util.UUID.randomUUID().toString()
            return "$AUTH_BASE/authorize?" +
                "client_id=$CLIENT_ID&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
                "&response_type=code&scope=${Uri.encode(SCOPES)}&state=$state"
        }

    /**
     * Exchange authorization code for tokens. Called after the browser redirects back
     * with the `code` query parameter.
     */
    suspend fun exchangeCode(code: String): StravaTokens {
        val client = okhttp3.OkHttpClient()
        val body = okhttp3.FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()
        val request = okhttp3.Request.Builder()
            .url("$AUTH_BASE/token")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        val raw = response.body?.string() ?: throw java.io.IOException("Empty token response")
        if (!response.isSuccessful) throw java.io.IOException("Token exchange failed: $raw")
        return parseTokenResponse(raw)
    }

    /** Refresh an expired access token. */
    suspend fun refreshToken(tokens: StravaTokens): StravaTokens {
        val client = okhttp3.OkHttpClient()
        val body = okhttp3.FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
            .build()
        val request = okhttp3.Request.Builder()
            .url("$AUTH_BASE/token")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        val raw = response.body?.string() ?: throw java.io.IOException("Empty refresh response")
        if (!response.isSuccessful) throw java.io.IOException("Token refresh failed: $raw")
        return parseTokenResponse(raw)
    }

    /** Wraps an OkHttp request with a valid access token (auto-refreshes if expired). */
    suspend fun authenticatedRequest(
        tokens: StravaTokens,
        tokenStore: StravaTokenStore,
        method: String,
        url: String,
        body: okhttp3.RequestBody? = null,
    ): okhttp3.Response {
        var current = tokens
        if (current.expiresAt - 300 < System.currentTimeMillis() / 1000) {
            current = refreshToken(current)
            tokenStore.save(current)
        }
        val client = okhttp3.OkHttpClient()
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${current.accessToken}")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
            "PUT" -> builder.put(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
        }
        val response = client.newCall(builder.build()).execute()
        if (response.code == 401) {
            val refreshed = refreshToken(current)
            tokenStore.save(refreshed)
            val retry = client.newCall(
                okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${refreshed.accessToken}")
                    .apply {
                        when (method) {
                            "GET" -> get()
                            "POST" -> post(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
                            "PUT" -> put(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
                        }
                    }
                    .build()
            ).execute()
            return retry
        }
        return response
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val refresh_token: String,
        val expires_at: Long,
        val athlete: Athlete? = null,
    )

    @Serializable
    private data class Athlete(val id: Long, val firstname: String? = null, val lastname: String? = null)

    private fun parseTokenResponse(raw: String): StravaTokens {
        val tr = json.decodeFromString<TokenResponse>(raw)
        return StravaTokens(
            accessToken = tr.access_token,
            refreshToken = tr.refresh_token,
            expiresAt = tr.expires_at,
            athleteId = tr.athlete?.id,
            athleteName = listOfNotNull(tr.athlete?.firstname, tr.athlete?.lastname).joinToString(" ").ifBlank { null },
        )
    }
}
