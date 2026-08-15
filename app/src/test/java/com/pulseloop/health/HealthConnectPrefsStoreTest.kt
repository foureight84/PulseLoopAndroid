package com.pulseloop.health

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 (docs/health-connect-integration.md): the prefs store must survive schema drift in
 * both directions — a blob written before a field existed, and one containing a field we
 * don't know yet — and a corrupt blob must fall back to defaults rather than crash Settings.
 * No mocking framework (repo convention): a hand-rolled in-memory SharedPreferences.
 */
class HealthConnectPrefsStoreTest {

    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (value != null) prefs.map[key!!] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { prefs.map[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { prefs.map[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { prefs.map[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { prefs.map.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { prefs.map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    private fun storeWith(blob: String?): HealthConnectPrefsStore {
        val fake = FakeSharedPreferences()
        if (blob != null) fake.edit().putString("pulseloop.healthconnect.v1", blob).apply()
        return HealthConnectPrefsStore(fake)
    }

    @Test
    fun defaultsWhenNoBlob() {
        val store = storeWith(null)
        assertEquals(HealthConnectPrefs.DEFAULT, store.current)
        assertFalse(store.current.enabled)
        assertTrue(store.current.heartRate)
        assertEquals(HealthConnectPrefs.BackfillChoice.NOT_ASKED, store.current.backfillChoice)
    }

    @Test
    fun unknownFutureKeyDoesNotWipeTheBlob() {
        val store = storeWith("{\"enabled\":true,\"futureField\":123}")
        assertTrue(store.current.enabled)
        // Unknown key ignored; everything else kept its default.
        assertEquals(HealthConnectPrefs.BackfillChoice.NOT_ASKED, store.current.backfillChoice)
        assertTrue(store.current.sleep)
    }

    @Test
    fun blobMissingNewKeysFallsBackToPerFieldDefaults() {
        val store = storeWith("{\"enabled\":true}")
        assertTrue(store.current.enabled)
        assertTrue(store.current.workouts) // field did not exist when the blob was written
        assertNull(store.current.lastSyncAt)
        assertFalse(store.current.isConnected)
    }

    @Test
    fun corruptBlobFallsBackToDefaults() {
        val store = storeWith("not-json{")
        assertEquals(HealthConnectPrefs.DEFAULT, store.current)
        assertEquals(HealthConnectWatermarks.DEFAULT, store.currentWatermarks)
    }

    @Test
    fun watermarkNeverRewinds() {
        val store = storeWith(null)
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 200)
        assertEquals(200L, store.currentWatermarks.vitals)
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 100)
        assertEquals(200L, store.currentWatermarks.vitals)
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 300)
        assertEquals(300L, store.currentWatermarks.vitals)
    }

    @Test
    fun watermarksAreIndependentPerKey() {
        val store = storeWith(null)
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 200)
        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, 50)
        assertEquals(200L, store.currentWatermarks.vitals)
        assertEquals(50L, store.currentWatermarks.sleep)
        assertNull(store.currentWatermarks.activity)
    }

    @Test
    fun clearWatermarksResetsToNull() {
        val store = storeWith(null)
        store.setWatermark(HealthConnectWatermarks.Key.WORKOUTS, 42)
        store.clearWatermarks()
        assertEquals(HealthConnectWatermarks.DEFAULT, store.currentWatermarks)
    }

    @Test
    fun updatePersistsAndSkipsNoOps() {
        val store = storeWith(null)
        store.update { it.copy(enabled = true) }
        assertTrue(store.current.enabled)
        val before = store.current
        store.update { it }
        assertSame(before, store.current)
    }

    @Test
    fun lastGrantedPermissionsTracksPartialGrant() {
        val store = storeWith(null)
        store.update { it.copy(enabled = true, lastGrantedPermissions = listOf("android.permission.health.WRITE_HEART_RATE")) }
        assertTrue(store.current.isConnected)
    }
}
