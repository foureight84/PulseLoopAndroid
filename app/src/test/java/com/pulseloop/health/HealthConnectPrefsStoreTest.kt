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

    /** Seeds both blobs, so an on-disk state from an older build can be reconstructed exactly. */
    private fun prefsWith(prefsBlob: String?, watermarksBlob: String?): FakeSharedPreferences {
        val fake = FakeSharedPreferences()
        if (prefsBlob != null) fake.edit().putString("pulseloop.healthconnect.v1", prefsBlob).apply()
        if (watermarksBlob != null) fake.edit().putString("pulseloop.healthconnect.watermarks.v1", watermarksBlob).apply()
        return fake
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
    fun phase5TogglesDefaultTrueAndRoundTrip() {
        // the five Phase 5 per-type toggles default ON under the master toggle (iOS parity)
        val store = storeWith(null)
        assertTrue(store.current.bloodPressure)
        assertTrue(store.current.bloodGlucose)
        assertTrue(store.current.respiratoryRate)
        assertTrue(store.current.vo2Max)
        assertTrue(store.current.restingHeartRate)
        // a pre-Phase-5 blob decodes the new toggles to their default (true), not wiped
        val legacy = storeWith("{\"enabled\":true,\"heartRate\":true,\"backfillChoice\":\"EXPORT_ALL\"}")
        assertTrue(legacy.current.bloodPressure)
        assertTrue(legacy.current.restingHeartRate)
        // toggleFor / withToggleFor route the new rows
        assertTrue(store.current.toggleFor(HealthConnectPermissions.DataTypeRow.BLOOD_PRESSURE))
        assertFalse(store.current.withToggleFor(HealthConnectPermissions.DataTypeRow.RESTING_HEART_RATE, false).restingHeartRate)
    }

    @Test
    fun restingHrWatermarkIsMonotonic() {
        val store = storeWith(null)
        assertNull(store.currentWatermarks.restingHr)
        store.setWatermark(HealthConnectWatermarks.Key.RESTING_HR, 1000L)
        assertEquals(1000L, store.currentWatermarks.restingHr)
        store.setWatermark(HealthConnectWatermarks.Key.RESTING_HR, 500L) // a rewind is a no-op
        assertEquals(1000L, store.currentWatermarks.restingHr)
        store.setWatermark(HealthConnectWatermarks.Key.RESTING_HR, 2000L)
        assertEquals(2000L, store.currentWatermarks.restingHr)
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
        assertFalse(store.current.sleepIdentityV2Done)
        assertNull(store.current.lastSyncAt)
        assertFalse(store.current.isConnected)
    }

    @Test
    fun sleepIdentityV2MarkerDefaultsFalseAndRoundTripsTrue() {
        assertFalse(storeWith(null).current.sleepIdentityV2Done)
        assertFalse(storeWith("{\"enabled\":true}").current.sleepIdentityV2Done)

        val fake = FakeSharedPreferences()
        val store = HealthConnectPrefsStore(fake)
        store.update { it.copy(sleepIdentityV2Done = true) }

        assertTrue(HealthConnectPrefsStore(fake).current.sleepIdentityV2Done)
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
    fun sleepWatermarkNeverRewinds() {
        // Phase 2: the sleep group's watermark (SleepSessionEntity.updatedAt) gets the same
        // monotonic treatment as vitals — an interrupted backfill resumes, never re-exports.
        val store = storeWith(null)
        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, 200)
        assertEquals(200L, store.currentWatermarks.sleep)
        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, 150)
        assertEquals(200L, store.currentWatermarks.sleep)
        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, 300)
        assertEquals(300L, store.currentWatermarks.sleep)
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

    // ── Phase 4: the one-time netting-flip marker + targeted watermark reset ──

    @Test
    fun nettingFlipMarkerDefaultsFalseForPhase3Blobs() {
        // A blob written by the Phase 3 build (no nettingFlipDone key) must decode as
        // "flip not done yet" — that is the state that triggers the reset on first run.
        val store = storeWith("{\"enabled\":true,\"backfillChoice\":\"EXPORT_ALL\"}")
        assertFalse(store.current.nettingFlipDone)
    }

    @Test
    fun nettingFlipMarkerPersistsOnceSet() {
        val store = storeWith(null)
        assertFalse(store.current.nettingFlipDone)
        store.update { it.copy(nettingFlipDone = true) }
        assertTrue(store.current.nettingFlipDone)
    }

    @Test
    fun resetWatermarksNullsOnlyTheNamedKeys() {
        val store = storeWith(null)
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 111)
        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, 222)
        store.setWatermark(HealthConnectWatermarks.Key.ACTIVITY, 333)
        store.setWatermark(HealthConnectWatermarks.Key.WORKOUTS, 444)
        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.ACTIVITY, HealthConnectWatermarks.Key.WORKOUTS))
        val wm = store.currentWatermarks
        assertEquals(111L, wm.vitals)
        assertEquals(222L, wm.sleep)
        assertNull(wm.activity)
        assertNull(wm.workouts)
    }

    @Test
    fun resetWatermarksIsNotRewindProtectionBait() {
        // The monotonic guard belongs to setWatermark only: after an explicit reset the
        // watermark can start again from whatever value the next landed pass reports — even
        // one lower than the pre-reset value, which is the whole point of re-exporting.
        val store = storeWith(null)
        store.setWatermark(HealthConnectWatermarks.Key.ACTIVITY, 500)
        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.ACTIVITY))
        assertNull(store.currentWatermarks.activity)
        store.setWatermark(HealthConnectWatermarks.Key.ACTIVITY, 400)
        assertEquals(400L, store.currentWatermarks.activity)
    }

    @Test
    fun resetWatermarksPersistsToTheBlob() {
        // The process can die between the flip reset and the re-export, so the reset nulls have
        // to be on disk: assert against the backing blob AND a fresh store over the same prefs.
        val fake = FakeSharedPreferences()
        val store = HealthConnectPrefsStore(fake)
        store.setWatermark(HealthConnectWatermarks.Key.ACTIVITY, 333)
        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.ACTIVITY))
        val reloaded = HealthConnectPrefsStore(fake)
        assertNull(reloaded.currentWatermarks.activity)
        assertEquals(
            "{\"vitals\":null,\"sleep\":null,\"activity\":null,\"workouts\":null,\"nutrition\":null,\"restingHr\":null}",
            fake.map["pulseloop.healthconnect.watermarks.v1"] as String,
        )
    }

    // ── Review pass 2 (PR #50): EXPORT_NEW_ONLY consent clamp + upgrade-boundary seed ──

    @Test
    fun resetWatermarksClampsToConsentInstantForNewOnly() {
        // A NEW_ONLY user's grow-reset must NOT null the watermark (null = export-from-epoch =
        // the pre-consent history the user declined leaks). It clamps to the consent instant.
        val store = storeWith(null)
        store.update {
            it.copy(backfillChoice = HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY, newOnlyConsentAt = 1000L)
        }
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 5000) // advanced well past consent
        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, 2000)
        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.VITALS))
        assertEquals(1000L, store.currentWatermarks.vitals) // clamped, not null
        assertEquals(2000L, store.currentWatermarks.sleep) // not named -> untouched
    }

    @Test
    fun resetWatermarksStillNullsForNonNewOnly() {
        // EXPORT_ALL / NOT_ASKED users keep the original null-and-backfill-from-epoch behaviour —
        // the consent clamp applies only to EXPORT_NEW_ONLY.
        val store = storeWith("{\"enabled\":true,\"backfillChoice\":\"EXPORT_ALL\"}")
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 5000)
        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.VITALS))
        assertNull(store.currentWatermarks.vitals)
    }

    @Test
    fun resetWatermarksClampsEveryNamedKeyToConsent() {
        val store = storeWith(null)
        store.update {
            it.copy(backfillChoice = HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY, newOnlyConsentAt = 700L)
        }
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 9000)
        store.setWatermark(HealthConnectWatermarks.Key.NUTRITION, 8000)
        store.setWatermark(HealthConnectWatermarks.Key.ACTIVITY, 9500)
        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.VITALS, HealthConnectWatermarks.Key.NUTRITION))
        assertEquals(700L, store.currentWatermarks.vitals)
        assertEquals(700L, store.currentWatermarks.nutrition)
        assertEquals(9500L, store.currentWatermarks.activity) // not named -> untouched
    }

    @Test
    fun resetWatermarksClampIsIdempotentAndPersists() {
        // The clamp value is on disk (the process can die between reset and re-export) and a
        // fresh store over the same prefs sees it.
        val fake = FakeSharedPreferences()
        val store = HealthConnectPrefsStore(fake)
        store.update {
            it.copy(backfillChoice = HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY, newOnlyConsentAt = 1200L)
        }
        store.setWatermark(HealthConnectWatermarks.Key.VITALS, 4000)
        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.VITALS))
        val reloaded = HealthConnectPrefsStore(fake)
        assertEquals(1200L, reloaded.currentWatermarks.vitals)
    }

    // ── Review pass 4 (PR #50): atomic read-modify-write + legacy consent-instant recovery ──

    @Test
    fun concurrentUpdatesDoNotDropEachOthersFields() {
        // update() is a read-modify-write and there are genuinely concurrent writers (export
        // worker / removal scope / settings on the main thread). Without a lock, two writers that
        // read the same snapshot silently drop each other's field — the settings toggle flipped
        // mid-backfill snaps back on. The transforms below both read, then sleep, so the
        // interleaving is forced rather than hoped for.
        val store = storeWith(null)
        val start = java.util.concurrent.CountDownLatch(1)
        fun writer(body: (HealthConnectPrefs) -> HealthConnectPrefs) = Thread {
            start.await()
            store.update { cur -> Thread.sleep(50); body(cur) }
        }
        val a = writer { it.copy(enabled = true) }
        val b = writer { it.copy(heartRate = false) }
        a.start(); b.start()
        start.countDown()
        a.join(5_000); b.join(5_000)
        assertTrue(store.current.enabled)
        assertFalse(store.current.heartRate)
    }

    @Test
    fun concurrentWatermarkAdvancesAllSurvive() {
        // Same defect class on the watermark blob: two groups advancing at once must not drop
        // each other (a lost advance re-exports; a lost reset leaks pre-consent history).
        val store = storeWith(null)
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = HealthConnectWatermarks.Key.values().mapIndexed { i, key ->
            Thread {
                start.await()
                repeat(50) { n -> store.setWatermark(key, (i + 1) * 1000L + n) }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(5_000) }
        HealthConnectWatermarks.Key.values().forEachIndexed { i, key ->
            assertEquals((i + 1) * 1000L + 49, store.currentWatermarks.get(key))
        }
    }

    @Test
    fun legacyStampedBlobWithoutConsentInstantRecoversFromOldestWatermark() {
        // A dogfood install that ran the build with newOnlyStamped but not newOnlyConsentAt
        // decodes to stamped = true, consentAt = null with every watermark already stamped: the
        // sentinel never re-fires, so nothing would ever populate the consent instant and the
        // read clamp would be a permanent no-op. Recover it from the oldest surviving watermark —
        // the sentinel wrote one instant to all six groups, so the minimum is that instant.
        val fake = prefsWith(
            "{\"enabled\":true,\"backfillChoice\":\"EXPORT_NEW_ONLY\",\"newOnlyStamped\":true}",
            "{\"vitals\":5000,\"sleep\":1000,\"activity\":3000,\"workouts\":null,\"nutrition\":2000,\"restingHr\":null}",
        )
        val store = HealthConnectPrefsStore(fake)
        assertEquals(1000L, store.current.newOnlyConsentAt)
        // and it is on disk, so the recovery runs once rather than on every process start
        assertEquals(1000L, HealthConnectPrefsStore(fake).current.newOnlyConsentAt)
        // the clamp is live again: a revocation-dialog clearWatermarks() no longer means epoch
        store.clearWatermarks()
        assertEquals(1000L, effectiveWatermark(store.currentWatermarks.vitals, store.current.newOnlyConsentAt))
    }

    @Test
    fun consentRecoveryLeavesEveryOtherBlobAlone() {
        // EXPORT_ALL, not-yet-stamped and already-recorded blobs are untouched — the recovery is
        // for exactly one on-disk shape.
        val exportAll = HealthConnectPrefsStore(
            prefsWith("{\"backfillChoice\":\"EXPORT_ALL\",\"newOnlyStamped\":true}", "{\"vitals\":1000}")
        )
        assertNull(exportAll.current.newOnlyConsentAt)

        val notStamped = HealthConnectPrefsStore(
            prefsWith("{\"backfillChoice\":\"EXPORT_NEW_ONLY\"}", "{\"vitals\":1000}")
        )
        assertNull(notStamped.current.newOnlyConsentAt)

        val alreadyRecorded = HealthConnectPrefsStore(
            prefsWith(
                "{\"backfillChoice\":\"EXPORT_NEW_ONLY\",\"newOnlyStamped\":true,\"newOnlyConsentAt\":7000}",
                "{\"vitals\":1000}",
            )
        )
        assertEquals(7000L, alreadyRecorded.current.newOnlyConsentAt) // NOT lowered to the watermark
    }

    @Test
    fun consentRecoveryNoOpsWhenThereIsNothingToRecoverFrom() {
        // All watermarks cleared (post-removal shape): nothing to reconstruct from, so the flag is
        // left alone and the next sentinel pass re-stamps normally.
        val store = HealthConnectPrefsStore(
            prefsWith("{\"backfillChoice\":\"EXPORT_NEW_ONLY\",\"newOnlyStamped\":true}", null)
        )
        assertNull(store.current.newOnlyConsentAt)
        assertTrue(store.current.newOnlyStamped)
    }
}
