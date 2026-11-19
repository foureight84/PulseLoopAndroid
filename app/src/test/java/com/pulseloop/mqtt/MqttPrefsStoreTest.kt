package com.pulseloop.mqtt

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPrefsStoreTest {

    // ---------------------------------------------------------------------
    // Minimal fake SharedPreferences (no Mockito/MockK/Robolectric)
    // ---------------------------------------------------------------------

    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        val editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var cleared = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value == null) removals.add(key) else { pending[key] = value; removals.remove(key) }
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values == null) removals.add(key) else { pending[key] = HashSet(values); removals.remove(key) }
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) { pending[key] = value; removals.remove(key) }
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) { pending[key] = value; removals.remove(key) }
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) { pending[key] = value; removals.remove(key) }
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) { pending[key] = value; removals.remove(key) }
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) { removals.add(key); pending.remove(key) }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                cleared = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (cleared) {
                    map.clear()
                    cleared = false
                }
                removals.forEach { map.remove(it) }
                removals.clear()
                pending.forEach { (k, v) -> map[k] = v }
                pending.clear()
            }
        }

        override fun edit(): SharedPreferences.Editor = editor

        override fun getAll(): MutableMap<String, *> = HashMap(map)

        override fun getString(key: String?, defValue: String?): String? =
            if (map.containsKey(key)) map[key] as? String ?: defValue else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (map.containsKey(key)) map[key] as? MutableSet<String> ?: defValues else defValues

        override fun getInt(key: String?, defValue: Int): Int =
            if (map.containsKey(key)) map[key] as Int else defValue

        override fun getLong(key: String?, defValue: Long): Long =
            if (map.containsKey(key)) map[key] as Long else defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            if (map.containsKey(key)) map[key] as Float else defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (map.containsKey(key)) map[key] as Boolean else defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            listeners.remove(listener)
        }
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    @Test
    fun freshEmptyPrefsReturnDefaults() {
        val store = MqttPrefsStore(FakeSharedPreferences(), null)

        val prefs = store.state.value
        assertFalse(prefs.enabled)
        assertEquals("", prefs.host)
        assertEquals(1883, prefs.port)
        assertFalse(prefs.useTls)
        assertEquals("homeassistant", prefs.discoveryPrefix)
        assertEquals("pulseloop", prefs.baseTopic)
        assertEquals(30, prefs.syncIntervalMinutes)
        assertTrue(prefs.liveStreamingEnabled)
        assertFalse(prefs.isConfigured)

        assertEquals(prefs, store.current)
    }

    @Test
    fun updateRoundTripsPersistsAndSurvivesSecondStore() {
        val fake = FakeSharedPreferences()
        val store = MqttPrefsStore(fake, null)

        val updated = store.update {
            it.copy(
                enabled = true,
                host = "mqtt.example.com",
                port = 8883,
                useTls = true,
                discoveryPrefix = "ha",
                baseTopic = "pulseloop/rings",
                syncIntervalMinutes = 60,
                liveStreamingEnabled = false,
            )
        }
        assertTrue(updated.isConfigured)

        assertEquals(updated, store.state.value)
        assertEquals(updated, store.current)
        assertTrue(fake.map.containsKey(MqttPrefsStore.KEY_PREFS))

        val second = MqttPrefsStore(fake, null)
        assertEquals(updated, second.state.value)
        assertEquals(updated, second.current)
    }

    @Test
    fun normalizedHelpersTrimAndFallback() {
        val fake = FakeSharedPreferences()
        val store = MqttPrefsStore(fake, null)

        store.update {
            it.copy(
                discoveryPrefix = " /homeassistant/ ",
                baseTopic = "  pulseloop/rings/  ",
            )
        }
        assertEquals("homeassistant", store.current.normalizedDiscoveryPrefix)
        assertEquals("pulseloop/rings", store.current.normalizedBaseTopic)

        store.update { it.copy(discoveryPrefix = "   ", baseTopic = "") }
        assertEquals("homeassistant", store.current.normalizedDiscoveryPrefix)
        assertEquals("pulseloop", store.current.normalizedBaseTopic)
    }

    @Test
    fun effectiveSyncIntervalCoercesOutOfRangeValues() {
        val fake = FakeSharedPreferences()
        val store = MqttPrefsStore(fake, null)

        store.update { it.copy(syncIntervalMinutes = 5) }
        assertEquals(15, store.current.effectiveSyncIntervalMinutes)

        store.update { it.copy(syncIntervalMinutes = 100_000) }
        assertEquals(720, store.current.effectiveSyncIntervalMinutes)

        store.update { it.copy(syncIntervalMinutes = 30) }
        assertEquals(30, store.current.effectiveSyncIntervalMinutes)
    }

    @Test
    fun forwardCompatibleJsonWithUnknownFieldLoadsCleanly() {
        val fake = FakeSharedPreferences()
        val json = """
            {
              "enabled": true,
              "host": "mqtt.example.com",
              "port": 8883,
              "useTls": true,
              "discoveryPrefix": "ha",
              "baseTopic": "pulseloop/rings",
              "syncIntervalMinutes": 45,
              "liveStreamingEnabled": false,
              "future_flag": true
            }
        """.trimIndent()
        fake.edit().putString(MqttPrefsStore.KEY_PREFS, json).commit()

        val store = MqttPrefsStore(fake, null)
        val prefs = store.state.value
        assertTrue(prefs.enabled)
        assertEquals("mqtt.example.com", prefs.host)
        assertEquals(8883, prefs.port)
        assertTrue(prefs.useTls)
        assertEquals("ha", prefs.discoveryPrefix)
        assertEquals("pulseloop/rings", prefs.baseTopic)
        assertEquals(45, prefs.syncIntervalMinutes)
        assertFalse(prefs.liveStreamingEnabled)
        assertTrue(prefs.isConfigured)
    }

    @Test
    fun corruptJsonFallsBackToDefaults() {
        val fake = FakeSharedPreferences()
        fake.edit().putString(MqttPrefsStore.KEY_PREFS, "{not valid json!!!").commit()

        val store = MqttPrefsStore(fake, null)
        assertEquals(MqttPrefs(), store.state.value)
        assertEquals(MqttPrefs(), store.current)
    }

    @Test
    fun passwordIsolatedToSecurePrefs() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val store = MqttPrefsStore(plain, secure)

        store.setPassword("s3cr3t")

        assertEquals("s3cr3t", secure.map[MqttPrefsStore.KEY_PASSWORD])
        assertFalse(plain.map.containsKey(MqttPrefsStore.KEY_PASSWORD))
        assertEquals("s3cr3t", store.getPassword())
        assertTrue(store.hasPassword())

        store.setPassword("")
        assertFalse(secure.map.containsKey(MqttPrefsStore.KEY_PASSWORD))
        assertEquals("", store.getPassword())
        assertFalse(store.hasPassword())
    }

    @Test
    fun nullSecurePrefsHandlesPasswordCallsWithoutThrowing() {
        val store = MqttPrefsStore(FakeSharedPreferences(), null)

        store.setPassword("whatever")
        assertEquals("", store.getPassword())
        assertFalse(store.hasPassword())
    }

    @Test
    fun legacyJsonWithoutTransportDeserializesToTcpDefaults() {
        val fake = FakeSharedPreferences()
        fake.edit().putString(
            MqttPrefsStore.KEY_PREFS,
            """{"enabled":true,"host":"192.168.1.50","port":1883,"useTls":false}""",
        ).commit()

        val store = MqttPrefsStore(fake, null)
        assertEquals(MqttTransport.TCP, store.current.transport)
        assertEquals("/mqtt", store.current.normalizedWsPath)
    }

    @Test
    fun persistsWebSocketTransportAndPath() {
        val fake = FakeSharedPreferences()
        val store1 = MqttPrefsStore(fake, null)
        store1.update {
            it.copy(
                enabled = true,
                host = "mqtt.example.com",
                port = 443,
                useTls = true,
                transport = MqttTransport.WEBSOCKET,
                wsPath = "ws/ha",
            )
        }

        val store2 = MqttPrefsStore(fake, null)
        assertEquals(MqttTransport.WEBSOCKET, store2.current.transport)
        assertEquals("/ws/ha", store2.current.normalizedWsPath)
        assertEquals(443, store2.current.port)
        assertTrue(store2.current.useTls)
    }
}
