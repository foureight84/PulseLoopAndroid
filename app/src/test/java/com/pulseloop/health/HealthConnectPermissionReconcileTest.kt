package com.pulseloop.health

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (docs/health-connect-integration.md §4): the permission → export-group mapping and the
 * grant/revocation reconciliation. Pure logic + an in-memory prefs store (repo convention: no
 * mocking framework) — no client, no database.
 */
class HealthConnectPermissionReconcileTest {

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

    private fun store(): HealthConnectPrefsStore = HealthConnectPrefsStore(FakeSharedPreferences())

    private val V = HealthConnectWatermarks.Key.VITALS
    private val S = HealthConnectWatermarks.Key.SLEEP
    private val A = HealthConnectWatermarks.Key.ACTIVITY
    private val W = HealthConnectWatermarks.Key.WORKOUTS
    private val N = HealthConnectWatermarks.Key.NUTRITION
    private val R = HealthConnectWatermarks.Key.RESTING_HR

    // ── mapping ──

    @Test
    fun allSixteenPermissionsAreMappedToExactlyOneGroup() {
        val all = HealthConnectPermissions.all
        assertEquals(16, all.size)
        // Every requested permission has a group, and the map has no stray keys.
        assertTrue(all.all { HealthConnectPermissionReconcile.PERMISSION_GROUP.containsKey(it) })
        assertEquals(all, HealthConnectPermissionReconcile.PERMISSION_GROUP.keys)
    }

    @Test
    fun phase5KindsAndLegacyVitalsAllMapToVitals() {
        // The four Phase 5 kinds share the advanced VITALS watermark (plan §4 Phase 6 + Phase 5 log).
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.bloodGlucose.first()])
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.respiratoryRate.first()])
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.vo2Max.first()])
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.bloodPressure.first()])
        // ...and the four legacy vitals kinds the .name fix backfills on the same reset.
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.heartRate.first()])
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.oxygenSaturation.first()])
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.heartRateVariability.first()])
        assertEquals(V, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.bodyTemperature.first()])
    }

    @Test
    fun activityWorkoutsSleepNutritionRestingMapCorrectly() {
        assertEquals(A, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.steps.first()])
        assertEquals(A, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.activeCalories.first()])
        assertEquals(A, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.distance.first()])
        assertEquals(W, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.exercise.first()])
        assertEquals(W, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.exerciseRoute.first()])
        assertEquals(S, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.sleep.first()])
        assertEquals(N, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.nutrition.first()])
        assertEquals(R, HealthConnectPermissionReconcile.PERMISSION_GROUP[HealthConnectPermissions.restingHeartRate.first()])
    }

    @Test
    fun groupsForDeduplicatesToDistinctKeys() {
        val groups = HealthConnectPermissionReconcile.groupsFor(setOf(
            HealthConnectPermissions.heartRate.first(),
            HealthConnectPermissions.bloodGlucose.first(), // same VITALS group as heartRate
            HealthConnectPermissions.sleep.first(),
        ))
        assertEquals(setOf(V, S), groups)
    }

    // ── reconcile ──

    @Test
    fun growResetsOnlyTheGroupsOfTheNewPermissions() {
        val store = store()
        store.setWatermark(V, 1000L)
        store.setWatermark(S, 2000L)
        val previous = setOf(HealthConnectPermissions.heartRate.first())
        // Granting glucose (a VITALS kind) is a grow that must reset VITALS, not SLEEP.
        val current = previous + HealthConnectPermissions.bloodGlucose.first()
        val outcome = HealthConnectPermissionReconcile.reconcile(previous, current, store)
        assertTrue(V in outcome.grewGroups)
        assertNull(store.currentWatermarks.get(V))
        assertEquals(2000L, store.currentWatermarks.get(S))
        assertFalse(outcome.allRevoked)
        assertEquals(setOf(HealthConnectPermissions.bloodGlucose.first()), outcome.grew)
    }

    @Test
    fun fullRevokeFlagsAllRevokedButDoesNotReset() {
        val store = store()
        store.setWatermark(V, 1000L)
        val previous = setOf(HealthConnectPermissions.heartRate.first(), HealthConnectPermissions.bloodGlucose.first())
        val outcome = HealthConnectPermissionReconcile.reconcile(previous, emptySet(), store)
        assertTrue(outcome.allRevoked)
        assertTrue(outcome.grewGroups.isEmpty())
        // A shrink never auto-resets: the watermark stays so the settings screen can OFFER the
        // reset (a forced reset on mere revocation would discard the users data choice).
        assertEquals(1000L, store.currentWatermarks.get(V))
    }

    @Test
    fun unchangedSetIsANoOp() {
        val store = store()
        store.setWatermark(V, 1000L)
        val same = setOf(HealthConnectPermissions.heartRate.first())
        val outcome = HealthConnectPermissionReconcile.reconcile(same, same, store)
        assertTrue(outcome.grew.isEmpty())
        assertTrue(outcome.revoked.isEmpty())
        assertFalse(outcome.allRevoked)
        assertEquals(1000L, store.currentWatermarks.get(V))
    }

    @Test
    fun regrantAfterFullRevokeIsAGrowFromEmptyThatResets() {
        val store = store()
        store.setWatermark(S, 5000L)
        // After a detected full revoke the stored set is empty; re-granting sleep is a grow from
        // empty, so the automatic reset re-exports the sleep history (the plans backstop).
        val outcome = HealthConnectPermissionReconcile.reconcile(emptySet(), setOf(HealthConnectPermissions.sleep.first()), store)
        assertTrue(S in outcome.grewGroups)
        assertNull(store.currentWatermarks.get(S))
    }

    // ── review pass 5: one definition of the stored granted set ──

    @Test
    fun storedSetOfKeepsOnlyRequestedPermissionsSorted() {
        val live = listOf(
            HealthConnectPermissions.sleep.first(),
            "android.permission.health.READ_HEART_RATE",       // never requested (write-only app)
            "android.permission.health.WRITE_BODY_FAT",        // a health perm we don't declare
            HealthConnectPermissions.heartRate.first(),
        )
        val stored = HealthConnectPermissionReconcile.storedSetOf(live)
        assertEquals(
            listOf(HealthConnectPermissions.heartRate.first(), HealthConnectPermissions.sleep.first()).sorted(),
            stored,
        )
    }

    @Test
    fun storedSetOfIsStableSoAnUnrequestedGrantIsNotSeenAsAGrowOrShrink() {
        // The bug this closes: the permission-sheet callback filtered its result while the
        // app-start / settings reconcile stored the live set verbatim, so a health permission
        // granted outside `all` made the two disagree on every pass.
        val live = HealthConnectPermissions.all + "android.permission.health.WRITE_BODY_FAT"
        val first = HealthConnectPermissionReconcile.storedSetOf(live)
        val second = HealthConnectPermissionReconcile.storedSetOf(first)
        assertEquals(first, second)
        assertEquals(HealthConnectPermissions.all.size, first.size)
    }
}