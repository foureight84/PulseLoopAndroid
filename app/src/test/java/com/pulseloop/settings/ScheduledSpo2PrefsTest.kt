package com.pulseloop.settings

import android.content.SharedPreferences
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interval picker behind issue #103's request to space the app's own spot SpO₂ past an hour.
 */
class ScheduledSpo2PrefsTest {

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
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (value != null) prefs.map[key!!] = value; return this }
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

    @Test
    fun `hideEstimated defaults to true and round trips`() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = ScheduledSpo2Prefs(fakePrefs)
        assertTrue(prefs.hideEstimated)
        prefs.hideEstimated = false
        assertFalse(prefs.hideEstimated)
        prefs.hideEstimated = true
        assertTrue(prefs.hideEstimated)
    }

    @Test
    fun `enabled defaults to false and interval defaults to 60`() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = ScheduledSpo2Prefs(fakePrefs)
        assertFalse(prefs.enabled)
        assertEquals(60, prefs.intervalMinutes)
        prefs.intervalMinutes = 120
        assertEquals(120, prefs.intervalMinutes)
    }

    @Test
    fun `the offered options pass through unchanged`() {
        for (minutes in listOf(60, 120, 180)) {
            assertEquals(minutes, ScheduledSpo2Prefs.sanitizeIntervalMinutes(minutes))
        }
    }

    @Test
    fun `anything else snaps to the nearest option and stays inside the range`() {
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(0))
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(59))
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(89))
        assertEquals(120, ScheduledSpo2Prefs.sanitizeIntervalMinutes(90))
        assertEquals(180, ScheduledSpo2Prefs.sanitizeIntervalMinutes(150))
        assertEquals(180, ScheduledSpo2Prefs.sanitizeIntervalMinutes(151))
        assertEquals(180, ScheduledSpo2Prefs.sanitizeIntervalMinutes(300))
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(-30))
    }

    /** Albabit's ring (issue #103, 2026-10-23): `colmi-smarthealth`, SmartHealth/YCBT protocol,
     *  `manualSpo2` in its capabilities — both #103 controls must reach it, not just `YCBT`. */
    @Test
    fun `COLMI_SMART_HEALTH with MANUAL_SPO2 is supported`() {
        val device = DeviceEntity(
            deviceTypeRaw = RingDeviceType.COLMI_SMART_HEALTH.name,
            capabilitiesRaw = WearableCapability.MANUAL_SPO2.key,
        )
        assertTrue(ScheduledSpo2Prefs.isSupported(device))
    }

    @Test
    fun `YCBT with MANUAL_SPO2 is still supported`() {
        val device = DeviceEntity(
            deviceTypeRaw = RingDeviceType.YCBT.name,
            capabilitiesRaw = WearableCapability.MANUAL_SPO2.key,
        )
        assertTrue(ScheduledSpo2Prefs.isSupported(device))
    }

    @Test
    fun `the MANUAL_SPO2 capability is still required`() {
        val device = DeviceEntity(
            deviceTypeRaw = RingDeviceType.COLMI_SMART_HEALTH.name,
            capabilitiesRaw = WearableCapability.SPO2.key,
        )
        assertFalse(ScheduledSpo2Prefs.isSupported(device))
    }
}
