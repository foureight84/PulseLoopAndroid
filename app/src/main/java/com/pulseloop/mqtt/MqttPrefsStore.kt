package com.pulseloop.mqtt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Network transport used to reach the MQTT broker.
 */
@Serializable
enum class MqttTransport {
    TCP,
    WEBSOCKET,
}

/**
 * Serializable MQTT integration preferences for PulseLoop.
 */
@Serializable
data class MqttPrefs(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 1883,
    val useTls: Boolean = false,
    val transport: MqttTransport = MqttTransport.TCP,
    val wsPath: String = "/mqtt",
    val username: String = "",
    val discoveryPrefix: String = "homeassistant",
    val baseTopic: String = "pulseloop",
    val deviceNameOverride: String = "",
    val syncIntervalMinutes: Int = 30,
    val liveStreamingEnabled: Boolean = true,
    val lastPublishAt: Long? = null,
    val lastPublishSummary: String? = null,
    val lastError: String? = null,
    val discoveredSensorKeys: Set<String> = emptySet(),
    val lastDiscoveredDeviceSlug: String? = null,
) {
    /** Discovery prefix with surrounding whitespace/slashes removed, falling back to the default. */
    val normalizedDiscoveryPrefix: String
        get() = discoveryPrefix.trim().trim('/').ifEmpty { "homeassistant" }

    /** Base topic with surrounding whitespace/slashes removed, falling back to the default. */
    val normalizedBaseTopic: String
        get() = baseTopic.trim().trim('/').ifEmpty { "pulseloop" }

    /** WebSocket endpoint path normalized with a leading slash, defaulting to `/mqtt`. */
    val normalizedWsPath: String
        get() = MqttWebSocket.normalizePath(wsPath)

    /** True when the broker connection settings are complete and valid. */
    val isConfigured: Boolean
        get() = enabled && host.trim().isNotEmpty() && port in 1..65535

    /** Sync interval clamped to the supported range [15, 720] minutes. */
    val effectiveSyncIntervalMinutes: Int
        get() = syncIntervalMinutes.coerceIn(15, 720)
}

/**
 * Thread-safe store persisting [MqttPrefs] to plain [SharedPreferences] and the broker
 * password to [EncryptedSharedPreferences].
 *
 * The constructor is [internal] so tests can build instances with fake preferences.
 */
class MqttPrefsStore internal constructor(
    private val prefs: SharedPreferences,
    private val securePrefs: SharedPreferences?,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val writeLock = Any()

    private val _state = MutableStateFlow(loadPrefs())

    /** Current preferences as a [StateFlow]. */
    val state: StateFlow<MqttPrefs> = _state.asStateFlow()

    /** Current preferences snapshot. */
    val current: MqttPrefs
        get() = _state.value

    /**
     * Atomically applies [transform] to the current state, persists the result, and returns it.
     */
    fun update(transform: (MqttPrefs) -> MqttPrefs): MqttPrefs = synchronized(writeLock) {
        val next = transform(_state.value)
        prefs.edit().putString(KEY_PREFS, json.encodeToString(next)).apply()
        _state.value = next
        next
    }

    /**
     * Re-reads preferences from disk, publishes them to [state], and returns them.
     */
    fun reload(): MqttPrefs = synchronized(writeLock) {
        val loaded = loadPrefs()
        _state.value = loaded
        loaded
    }

    /**
     * Reads the broker password from encrypted storage; returns an empty string on any failure.
     */
    fun getPassword(): String = synchronized(writeLock) {
        try {
            securePrefs?.getString(KEY_PASSWORD, "") ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read MQTT password", e)
            ""
        }
    }

    /**
     * Stores the broker password in encrypted storage, or removes it when [password] is empty.
     * Never stores the password in plain preferences; a no-op if secure storage is unavailable.
     */
    fun setPassword(password: String) {
        synchronized(writeLock) {
            val secure = securePrefs ?: return
            try {
                if (password.isEmpty()) {
                    secure.edit().remove(KEY_PASSWORD).apply()
                } else {
                    secure.edit().putString(KEY_PASSWORD, password).apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store MQTT password", e)
            }
        }
    }

    /** True when a broker password is set. */
    fun hasPassword(): Boolean = getPassword().isNotEmpty()

    private fun loadPrefs(): MqttPrefs {
        val raw = prefs.getString(KEY_PREFS, null) ?: return MqttPrefs()
        return try {
            json.decodeFromString<MqttPrefs>(raw)
        } catch (e: SerializationException) {
            Log.w(TAG, "Malformed MQTT prefs JSON; using defaults", e)
            MqttPrefs()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Malformed MQTT prefs JSON; using defaults", e)
            MqttPrefs()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load MQTT prefs; using defaults", e)
            MqttPrefs()
        }
    }

    companion object {
        private const val TAG = "MqttPrefsStore"
        private const val PREF_FILE = "pulseloop_prefs"
        private const val SECURE_PREF_FILE = "pulseloop_secure"
        internal const val KEY_PREFS = "pulseloop.mqtt.v1"
        internal const val KEY_PASSWORD = "mqtt_password"

        @Volatile
        private var instance: MqttPrefsStore? = null

        /**
         * Returns the process-wide singleton [MqttPrefsStore], creating it on first use.
         */
        fun get(context: Context): MqttPrefsStore {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val cached = instance
                if (cached != null) {
                    cached
                } else {
                    val appContext = context.applicationContext
                    val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    val store = MqttPrefsStore(prefs, createSecurePrefs(appContext))
                    instance = store
                    store
                }
            }
        }

        /**
         * Creates [EncryptedSharedPreferences] for credential storage, or null on failure.
         */
        private fun createSecurePrefs(appContext: Context): SharedPreferences? = try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SECURE_PREF_FILE,
                masterKey,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create encrypted preferences; password storage disabled", e)
            null
        }
    }
}
