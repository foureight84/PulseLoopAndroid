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
        prefs.edit().putString(KEY_TOKENS, json.encodeToString(tokens)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKENS).apply()
    }

    val isConnected: Boolean get() = get() != null

    companion object {
        private const val KEY_TOKENS = "strava_tokens"
    }
}
