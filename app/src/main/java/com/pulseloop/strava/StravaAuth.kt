package com.pulseloop.strava

import android.net.Uri
import com.pulseloop.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID
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

    private val refreshMutex = Mutex()

    val isConfigured: Boolean
        get() = BuildConfig.STRAVA_CLIENT_ID.isNotBlank() && BuildConfig.STRAVA_CLIENT_SECRET.isNotBlank()

    /**
     * Build the authorize URL and record its CSRF state.
     *
     * The state is persisted, not held in a field: the flow leaves for a browser or the Strava app,
     * and Android is free to kill this process while it's gone — which is exactly why
     * `MainActivity` has a cold-start redirect handler at all. An in-memory state would be null on
     * that path, so every process-death authorization would fail validation and be dropped in
     * silence.
     *
     * `GET /oauth/mobile/authorize` (not `/oauth/authorize`) is the Android endpoint per Strava's
     * own docs — dispatched with an implicit ACTION_VIEW intent so the Strava app handles it when
     * installed and the browser otherwise.
     */
    fun generateAuthUrl(store: StravaTokenStore): String {
        val state = UUID.randomUUID().toString()
        store.savePendingAuthState(state)
        return "$AUTH_BASE/mobile/authorize?" +
            "client_id=${BuildConfig.STRAVA_CLIENT_ID}&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
            "&response_type=code&scope=${Uri.encode(SCOPES)}&approval_prompt=auto&state=${Uri.encode(state)}"
    }

    /** One-shot: a state can only be redeemed once, and is cleared whether or not it matched. */
    fun validateState(store: StravaTokenStore, state: String?): Boolean {
        val expected = store.takePendingAuthState()
        return expected != null && state != null && expected == state
    }

    /**
     * Strava echoes the granted scopes on the callback, and the user can untick "Upload your
     * activities" on the consent screen. Without `activity:write` every upload would fail later
     * with an opaque 401, so the connect is rejected up front instead.
     */
    fun grantedScopeIncludesWrite(scope: String?): Boolean =
        scope?.split(",")?.map { it.trim() }?.contains("activity:write") == true

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

    /**
     * Single-flight refresh. Strava **rotates** the refresh token on every exchange, so two
     * concurrent callers must not each run a refresh: the second would present a token the first
     * already burned and get rejected, and its failure would look like a revoked authorization.
     *
     * Holding a mutex is not enough on its own — the previous version serialized the two calls but
     * each still used the refresh token it had captured *before* the lock. The fix is to re-read
     * the store inside the lock: whoever gets there second sees the rotated token already saved by
     * the first and returns it instead of spending it again.
     */
    suspend fun refreshToken(tokens: StravaTokens, tokenStore: StravaTokenStore): StravaTokens =
        refreshMutex.withLock {
            val current = tokenStore.get() ?: tokens
            val alreadyRotated = current.refreshToken != tokens.refreshToken ||
                current.expiresAt - EXPIRY_LEEWAY_SECONDS > System.currentTimeMillis() / 1000
            if (alreadyRotated) return@withLock current
            val rotated = refreshTokenInternal(current)
            tokenStore.save(rotated)
            rotated
        }

    private suspend fun refreshTokenInternal(tokens: StravaTokens): StravaTokens {
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
        if (current.expiresAt - EXPIRY_LEEWAY_SECONDS < System.currentTimeMillis() / 1000) {
            current = refreshToken(current, tokenStore)
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
            response.close()
            val refreshed = refreshToken(current, tokenStore)
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

    /**
     * Best-effort revoke, so disconnecting here also drops PulseLoop from the athlete's
     * "My Apps" list on strava.com. Without it, clearing the local tokens leaves the
     * authorization live forever — iOS calls this on every disconnect.
     *
     * Uses the legacy `POST /oauth/deauthorize`; Strava's docs now recommend `/oauth/revoke` with
     * HTTP Basic client credentials (as of 2026-06-01) but keep deauthorize working.
     */
    suspend fun deauthorize(tokens: StravaTokens) {
        val request = okhttp3.Request.Builder()
            .url("$AUTH_BASE/deauthorize")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .post(okhttp3.FormBody.Builder().build())
            .build()
        runCatching { httpClient.newCall(request).execute().close() }
    }

    /** Refresh when fewer than this many seconds of validity remain (iOS `expiryLeeway`). */
    private const val EXPIRY_LEEWAY_SECONDS = 300L

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
