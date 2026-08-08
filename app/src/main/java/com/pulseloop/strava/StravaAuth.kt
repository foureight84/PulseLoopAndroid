package com.pulseloop.strava

import android.net.Uri
import com.pulseloop.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Serializable
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,       // epoch seconds
    val athleteId: Long? = null,
    val athleteName: String? = null,
)

object StravaAuth {

    private const val AUTH_BASE = "https://www.strava.com/oauth"
    private const val REDIRECT_URI = "pulseloop://localhost/strava-auth"
    private const val SCOPES = "activity:write,read"

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = BuildConfig.STRAVA_CLIENT_ID.isNotBlank() && BuildConfig.STRAVA_CLIENT_SECRET.isNotBlank()

    val authUrl: String
        get() = "$AUTH_BASE/mobile/authorize?" +
            "client_id=${BuildConfig.STRAVA_CLIENT_ID}&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
            "&response_type=code&scope=${Uri.encode(SCOPES)}&approval_prompt=auto"

    suspend fun exchangeCode(code: String): StravaTokens {
        val body = okhttp3.FormBody.Builder()
            .add("client_id", BuildConfig.STRAVA_CLIENT_ID)
            .add("client_secret", BuildConfig.STRAVA_CLIENT_SECRET)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()
        val request = okhttp3.Request.Builder()
            .url("$AUTH_BASE/token")
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        val raw = response.body?.string() ?: throw java.io.IOException("Empty token response")
        if (!response.isSuccessful) throw java.io.IOException("Token exchange failed: $raw")
        return parseTokenResponse(raw)
    }

    suspend fun refreshToken(tokens: StravaTokens): StravaTokens {
        val body = okhttp3.FormBody.Builder()
            .add("client_id", BuildConfig.STRAVA_CLIENT_ID)
            .add("client_secret", BuildConfig.STRAVA_CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
            .build()
        val request = okhttp3.Request.Builder()
            .url("$AUTH_BASE/token")
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        val raw = response.body?.string() ?: throw java.io.IOException("Empty refresh response")
        if (!response.isSuccessful) throw java.io.IOException("Token refresh failed: $raw")
        return parseTokenResponse(raw)
    }

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
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${current.accessToken}")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
            "PUT" -> builder.put(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
        }
        val response = httpClient.newCall(builder.build()).execute()
        if (response.code == 401) {
            val refreshed = refreshToken(current)
            tokenStore.save(refreshed)
            val retry = httpClient.newCall(
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
