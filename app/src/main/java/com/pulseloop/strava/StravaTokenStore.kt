package com.pulseloop.strava

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StravaTokenStore(context: Context) {
    private val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "pulseloop_strava",
        masterKey,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun get(): StravaTokens? {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return null
        return try { json.decodeFromString<StravaTokens>(raw) } catch (_: Exception) { null }
    }

    fun save(tokens: StravaTokens) {
        val edit = prefs.edit().putString(KEY_TOKENS, json.encodeToString(tokens))
        // Stamp the moment auto-upload became live, once. Everything that finished before this is
        // history the user never asked us to publish (iOS's `automaticSince`).
        if (!prefs.contains(KEY_AUTO_SINCE)) edit.putLong(KEY_AUTO_SINCE, System.currentTimeMillis())
        edit.apply()
    }

    /** When auto-upload was switched on, or null when never connected. */
    fun autoUploadSince(): Long? =
        if (prefs.contains(KEY_AUTO_SINCE)) prefs.getLong(KEY_AUTO_SINCE, 0L) else null

    fun clear() {
        prefs.edit()
            .remove(KEY_TOKENS)
            .remove(KEY_PENDING_STATE)
            .remove(KEY_AUTO_SINCE)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    val isConnected: Boolean get() = get() != null

    /**
     * The OAuth CSRF state for an authorization currently in flight. Persisted because the browser
     * (or the Strava app) takes the foreground for the duration, and Android may kill this process
     * while it's away — an in-memory value would be gone by the time the redirect comes back.
     * `commit()`, not `apply()`: we are about to hand control to another app and may not survive
     * long enough for an async write to land.
     */
    @Suppress("ApplySharedPref")
    fun savePendingAuthState(state: String) {
        prefs.edit().putString(KEY_PENDING_STATE, state).commit()
    }

    /** Read and clear in one step — a state may only be redeemed once. */
    fun takePendingAuthState(): String? {
        val state = prefs.getString(KEY_PENDING_STATE, null)
        if (state != null) prefs.edit().remove(KEY_PENDING_STATE).apply()
        return state
    }

    /**
     * Why the last connect attempt failed. The OAuth callback lands in `MainActivity`, not in the
     * settings screen, so the failure has to be left somewhere the screen can pick it up — without
     * this the user just sees the Connect button do nothing.
     */
    fun saveLastError(message: String) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
    }

    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun clearLastError() {
        prefs.edit().remove(KEY_LAST_ERROR).apply()
    }

    companion object {
        private const val KEY_TOKENS = "strava_tokens"
        private const val KEY_PENDING_STATE = "strava_pending_auth_state"
        private const val KEY_LAST_ERROR = "strava_last_error"
        private const val KEY_AUTO_SINCE = "strava_auto_upload_since"
        /** The EncryptedSharedPreferences file, so a data reset knows to wipe it too. */
        const val PREFS_NAME = "pulseloop_strava"
    }
}
