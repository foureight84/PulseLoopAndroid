package com.pulseloop.health

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What PulseLoop exports to Health Connect and how far the last export got — the Android port
 * of iOS `AppleHealthPrefsStore` (docs/health-connect-integration.md §3).
 *
 * Mirrors [com.pulseloop.ui.dashboard.MetricPrefsStore]: a JSON blob behind a StateFlow. A
 * second key holds the watermarks, so their (frequent, mid-backfill) writes don't rewrite the
 * settings blob. Non-sensitive sync state, so the plain shared prefs file — not the encrypted
 * store.
 */
@Serializable
data class HealthConnectPrefs(
    /** Master toggle; defaults OFF (iOS parity). */
    val enabled: Boolean = false,
    // Per-data-type toggles; all default ON under the master toggle (iOS parity).
    val heartRate: Boolean = true,
    val oxygenSaturation: Boolean = true,
    val heartRateVariability: Boolean = true,
    val bodyTemperature: Boolean = true,
    val sleep: Boolean = true,
    val stepsAndActivity: Boolean = true,
    val workouts: Boolean = true,
    val nutrition: Boolean = true,
    // Phase 5 data types (beyond iOS); all default ON under the master toggle (iOS parity).
    val bloodPressure: Boolean = true,
    val bloodGlucose: Boolean = true,
    val respiratoryRate: Boolean = true,
    val vo2Max: Boolean = true,
    val restingHeartRate: Boolean = true,
    /**
     * First-enable backfill choice. Exports are hard-gated on this in Phase 1: nothing runs
     * while it is [BackfillChoice.NOT_ASKED].
     */
    val backfillChoice: BackfillChoice = BackfillChoice.NOT_ASKED,
    val lastSyncAt: Long? = null,
    val lastSyncSummary: String? = null,
    /** Write permissions granted at the last permission-sheet result; Phase 6 diffs this to detect revocation. */
    val lastGrantedPermissions: List<String> = emptyList(),
    /**
     * One-time Phase 4 marker: true after the first pass on a build where netting is live reset
     * the ACTIVITY + WORKOUTS watermarks (see [HealthConnectExporter.run]) so daily records
     * exported under the Phase 3 build — un-netted, because [com.pulseloop.health.HealthConnectExporter.WORKOUTS_EXPORTED]
     * was false — get re-upserted with their netted values now that the workout siblings they
     * compensate for are being written. Old blobs without this key decode to false (tolerant
     * decode), which is exactly the "still needs the flip" state for upgrading users.
     */
    val nettingFlipDone: Boolean = false,
    /**
     * Phase 6: one-shot flag for the full-revocation reset offer (Gadgetbridge pattern). Set when
     * the user declines ("Not now") or confirms the reset; cleared on a later re-grant (a grow) so
     * a future full revocation offers again. Prevents re-offering on every settings open while the
     * revoked state persists.
     */
    val revocationOfferDismissed: Boolean = false,
    /**
     * One-time marker for the first-enable "Only new data from now on" watermark stamp (see
     * [HealthConnectExporter.run]). true after the first pass stamps every group's watermark to
     * now for an EXPORT_NEW_ONLY choice. Deliberately NOT inferred from a null watermark: a
     * Phase 6 grow-reset also nulls the VITALS watermark when a permission is granted out of
     * band, and inferring "first enable" from that would re-stamp every group to now and
     * silently drop the rows pending between the reset and the next pass. "Remove PulseLoop
     * data" resets this to false so a fresh re-enable re-stamps.
     */
    val newOnlyStamped: Boolean = false,
    /**
     * The instant an [BackfillChoice.EXPORT_NEW_ONLY] user's "only new data from now on" consent
     * took effect — the pass that first stamped every group's watermark to now for that choice
     * records its timestamp here (see [HealthConnectExporter.run]). [resetWatermarks] clamps a
     * NEW_ONLY group reset to this instant instead of null: a null group watermark means "export
     * from epoch", which would re-export the pre-consent history the user explicitly declined.
     * The exporter also clamps every SELECT watermark to this instant at the read site
     * ([HealthConnectExporter.effectiveWatermark]), which makes every watermark-nulling path safe
     * by construction. "Remove PulseLoop data" clears it so a fresh re-enable re-stamps.
     */
    val newOnlyConsentAt: Long? = null,
) {
    @Serializable
    enum class BackfillChoice { NOT_ASKED, EXPORT_ALL, EXPORT_NEW_ONLY }

    /** Any granted permission counts as connected — partial grants are first-class. */
    val isConnected: Boolean get() = lastGrantedPermissions.isNotEmpty()

    fun toggleFor(row: HealthConnectPermissions.DataTypeRow): Boolean = when (row) {
        HealthConnectPermissions.DataTypeRow.HEART_RATE -> heartRate
        HealthConnectPermissions.DataTypeRow.OXYGEN_SATURATION -> oxygenSaturation
        HealthConnectPermissions.DataTypeRow.HEART_RATE_VARIABILITY -> heartRateVariability
        HealthConnectPermissions.DataTypeRow.BODY_TEMPERATURE -> bodyTemperature
        HealthConnectPermissions.DataTypeRow.SLEEP -> sleep
        HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY -> stepsAndActivity
        HealthConnectPermissions.DataTypeRow.WORKOUTS -> workouts
        HealthConnectPermissions.DataTypeRow.NUTRITION -> nutrition
        HealthConnectPermissions.DataTypeRow.BLOOD_PRESSURE -> bloodPressure
        HealthConnectPermissions.DataTypeRow.BLOOD_GLUCOSE -> bloodGlucose
        HealthConnectPermissions.DataTypeRow.RESPIRATORY_RATE -> respiratoryRate
        HealthConnectPermissions.DataTypeRow.VO2_MAX -> vo2Max
        HealthConnectPermissions.DataTypeRow.RESTING_HEART_RATE -> restingHeartRate
    }

    fun withToggleFor(row: HealthConnectPermissions.DataTypeRow, value: Boolean): HealthConnectPrefs =
        when (row) {
            HealthConnectPermissions.DataTypeRow.HEART_RATE -> copy(heartRate = value)
            HealthConnectPermissions.DataTypeRow.OXYGEN_SATURATION -> copy(oxygenSaturation = value)
            HealthConnectPermissions.DataTypeRow.HEART_RATE_VARIABILITY -> copy(heartRateVariability = value)
            HealthConnectPermissions.DataTypeRow.BODY_TEMPERATURE -> copy(bodyTemperature = value)
            HealthConnectPermissions.DataTypeRow.SLEEP -> copy(sleep = value)
            HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY -> copy(stepsAndActivity = value)
            HealthConnectPermissions.DataTypeRow.WORKOUTS -> copy(workouts = value)
            HealthConnectPermissions.DataTypeRow.NUTRITION -> copy(nutrition = value)
            HealthConnectPermissions.DataTypeRow.BLOOD_PRESSURE -> copy(bloodPressure = value)
            HealthConnectPermissions.DataTypeRow.BLOOD_GLUCOSE -> copy(bloodGlucose = value)
            HealthConnectPermissions.DataTypeRow.RESPIRATORY_RATE -> copy(respiratoryRate = value)
            HealthConnectPermissions.DataTypeRow.VO2_MAX -> copy(vo2Max = value)
            HealthConnectPermissions.DataTypeRow.RESTING_HEART_RATE -> copy(restingHeartRate = value)
        }

    companion object {
        val DEFAULT = HealthConnectPrefs()
    }
}

/**
 * Export watermarks, one per export group. Vitals watermark on `Measurement.createdAt` —
 * deliberately NOT the sample timestamp, so late-arriving ring history is still picked up;
 * the other groups watermark on the row's `updatedAt` (iOS `AppleHealthSyncState`
 * semantics). null = never exported.
 */
@Serializable
data class HealthConnectWatermarks(
    val vitals: Long? = null,
    val sleep: Long? = null,
    val activity: Long? = null,
    val workouts: Long? = null,
    val nutrition: Long? = null,
    val restingHr: Long? = null,
) {
    enum class Key { VITALS, SLEEP, ACTIVITY, WORKOUTS, NUTRITION, RESTING_HR }

    fun get(key: Key): Long? = when (key) {
        Key.VITALS -> vitals
        Key.SLEEP -> sleep
        Key.ACTIVITY -> activity
        Key.WORKOUTS -> workouts
        Key.NUTRITION -> nutrition
        Key.RESTING_HR -> restingHr
    }

    fun copyWith(key: Key, value: Long): HealthConnectWatermarks = when (key) {
        Key.VITALS -> copy(vitals = value)
        Key.SLEEP -> copy(sleep = value)
        Key.ACTIVITY -> copy(activity = value)
        Key.WORKOUTS -> copy(workouts = value)
        Key.NUTRITION -> copy(nutrition = value)
        Key.RESTING_HR -> copy(restingHr = value)
    }

    companion object {
        val DEFAULT = HealthConnectWatermarks()
    }
}

class HealthConnectPrefsStore internal constructor(private val prefsStore: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<HealthConnectPrefs> = _prefs.asStateFlow()
    val current: HealthConnectPrefs get() = _prefs.value

    private val _watermarks = MutableStateFlow(loadWatermarks())
    val watermarks: StateFlow<HealthConnectWatermarks> = _watermarks.asStateFlow()
    val currentWatermarks: HealthConnectWatermarks get() = _watermarks.value

    /**
     * Serializes every read-modify-write below (review pass 4 MINOR). All the mutators are
     * read-modify-write over a single JSON blob, and there are genuinely concurrent writers:
     * [HealthConnectExportWorker] stamps `lastSyncAt`/`lastSyncSummary` and advances watermarks
     * on the worker dispatcher, [HealthConnectRemoval] writes from its own scope, and the
     * settings screen writes toggles / `enabled` / `backfillChoice` from the main thread. A plain
     * `_prefs.value = transform(current)` has no compare-and-set, so two writers that read the
     * same snapshot silently drop each other's fields — e.g. a data-type Switch flipped off just
     * as a long `EXPORT_ALL` backfill pass finishes snaps back on and re-exports that type.
     *
     * The lock spans the persist as well as the in-memory assignment, so the on-disk blob can
     * never end up ordered differently from the StateFlow (a lost disk write would resurrect the
     * dropped field on the next process start).
     */
    private val writeLock = Any()

    init {
        repairMissingConsentInstant()
    }

    fun update(transform: (HealthConnectPrefs) -> HealthConnectPrefs) {
        synchronized(writeLock) {
            val prev = _prefs.value
            val next = transform(prev)
            if (next == prev) return
            _prefs.value = next
            prefsStore.edit().putString(KEY_PREFS, json.encodeToString(HealthConnectPrefs.serializer(), next)).apply()
        }
    }

    /**
     * Back-fills [HealthConnectPrefs.newOnlyConsentAt] for an install that stamped its NEW_ONLY
     * watermarks under a build that had [HealthConnectPrefs.newOnlyStamped] but not yet
     * `newOnlyConsentAt` (review pass 4 NIT; branch/dogfood installs only — `origin/main` has no
     * Health Connect code). Such a blob tolerantly decodes to `stamped = true, consentAt = null`
     * with every watermark already stamped, so the sentinel never re-fires and nothing would ever
     * populate the consent instant: [HealthConnectExporter.effectiveWatermark] would degrade to
     * the unclamped `stored ?: 0` permanently, and the first watermark-nulling path (the
     * revocation dialog's "Reset export", or a grow-reset) would export the full pre-consent
     * history the user declined.
     *
     * The recovery is the inverse of the stamp: the sentinel wrote the same instant to all six
     * groups, so the oldest surviving watermark is that instant (or later, if a group has since
     * advanced) — taking the minimum reconstructs the consent boundary without ever placing it
     * earlier than the real one, which is the direction that would leak history. Runs only when
     * there is something to reconstruct from; a fully-cleared blob leaves the flag alone, and the
     * next sentinel pass re-stamps normally.
     */
    private fun repairMissingConsentInstant() {
        val p = _prefs.value
        if (p.backfillChoice != HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY) return
        if (!p.newOnlyStamped || p.newOnlyConsentAt != null) return
        val marks = _watermarks.value
        val oldest = HealthConnectWatermarks.Key.values().mapNotNull { marks.get(it) }.minOrNull() ?: return
        update { it.copy(newOnlyConsentAt = oldest) }
    }

    /**
     * Advance a watermark. Only ever moves forward: an interrupted backfill resumes, never
     * re-exports, and a crash mid-chunk cannot push the watermark backwards (plan §3:
     * "Advance the watermark only to a timestamp that actually reached Health Connect, and
     * never rewind").
     */
    fun setWatermark(key: HealthConnectWatermarks.Key, value: Long) {
        synchronized(writeLock) {
            val cur = _watermarks.value
            val existing = cur.get(key)
            if (existing != null && value <= existing) return
            val next = cur.copyWith(key, value)
            _watermarks.value = next
            prefsStore.edit().putString(KEY_WATERMARKS, json.encodeToString(HealthConnectWatermarks.serializer(), next)).apply()
        }
    }

    /**
     * Reset specific groups' watermarks to null ("never exported"). The monotonic
     * [setWatermark] can never rewind, so a deliberate re-export — the Phase 4 netting-flip
     * reset and Phase 6's permission/revocation resets — goes through here. Records re-exported
     * after a reset upsert under the same clientRecordIds, so the pass stays idempotent.
     *
     * **EXPORT_NEW_ONLY consent clamp (review MAJOR):** a null group watermark means "export from
     * epoch" (the exporters select on `createdSince(kind, watermark ?: 0)`). For a user who
     * chose "only new data from now on", a grow-reset that nulled their watermark would re-export
     * the pre-consent history they explicitly declined — the Phase 4 netting flip is deliberately
     * gated on `EXPORT_ALL` for exactly this reason. So when the backfill choice is
     * [HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY], the named groups are reset to the
     * consent instant ([HealthConnectPrefs.newOnlyConsentAt], the pass that first stamped them)
     * instead of null: the re-granted / re-enabled types backfill their post-consent rows and
     * nothing older. `consent` is null for an `EXPORT_ALL`/`NOT_ASKED` choice — and for a
     * NEW_ONLY user whose sentinel has not stamped yet (all watermarks null, nothing to clamp) —
     * preserving the original null-and-backfill-from-epoch behaviour there.
     */
    fun resetWatermarks(keys: Set<HealthConnectWatermarks.Key>) {
        if (keys.isEmpty()) return
        synchronized(writeLock) {
            val prefs = _prefs.value
            val consent: Long? =
                if (prefs.backfillChoice == HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY) prefs.newOnlyConsentAt else null
            val cur = _watermarks.value
            // copyWith can't null a key; rebuild the blob with the reset keys nulled (or clamped to the
            // consent instant for EXPORT_NEW_ONLY).
            val next = HealthConnectWatermarks(
                vitals = if (HealthConnectWatermarks.Key.VITALS in keys) consent else cur.vitals,
                sleep = if (HealthConnectWatermarks.Key.SLEEP in keys) consent else cur.sleep,
                activity = if (HealthConnectWatermarks.Key.ACTIVITY in keys) consent else cur.activity,
                workouts = if (HealthConnectWatermarks.Key.WORKOUTS in keys) consent else cur.workouts,
                nutrition = if (HealthConnectWatermarks.Key.NUTRITION in keys) consent else cur.nutrition,
                restingHr = if (HealthConnectWatermarks.Key.RESTING_HR in keys) consent else cur.restingHr,
            )
            _watermarks.value = next
            prefsStore.edit().putString(KEY_WATERMARKS, json.encodeToString(HealthConnectWatermarks.serializer(), next)).apply()
        }
    }


    /** Clear every watermark (iOS `removeAllExportedData`; Phase 6 revocation reset). */
    fun clearWatermarks() {
        synchronized(writeLock) {
            val next = HealthConnectWatermarks.DEFAULT
            _watermarks.value = next
            prefsStore.edit().putString(KEY_WATERMARKS, json.encodeToString(HealthConnectWatermarks.serializer(), next)).apply()
        }
    }

    private fun load(): HealthConnectPrefs {
        val raw = prefsStore.getString(KEY_PREFS, null) ?: return HealthConnectPrefs.DEFAULT
        // Tolerant decode: a blob written before a field existed falls back to per-field
        // defaults, and a blob containing a key we don't know yet decodes without wiping
        // the known fields (ignoreUnknownKeys) — a new field must never reset the user's
        // existing choices.
        return try {
            json.decodeFromString(HealthConnectPrefs.serializer(), raw)
        } catch (_: Exception) {
            HealthConnectPrefs.DEFAULT
        }
    }

    private fun loadWatermarks(): HealthConnectWatermarks {
        val raw = prefsStore.getString(KEY_WATERMARKS, null) ?: return HealthConnectWatermarks.DEFAULT
        return try {
            json.decodeFromString(HealthConnectWatermarks.serializer(), raw)
        } catch (_: Exception) {
            HealthConnectWatermarks.DEFAULT
        }
    }

    companion object {
        private const val KEY_PREFS = "pulseloop.healthconnect.v1"
        private const val KEY_WATERMARKS = "pulseloop.healthconnect.watermarks.v1"
        // Same shared file as MetricPrefsStore — the app's plain (non-encrypted) UI prefs.
        private const val FILE = "pulseloop_prefs"

        @Volatile
        private var instance: HealthConnectPrefsStore? = null

        /** Process-wide shared instance so Settings and the (Phase 1) exporter mutate the same flows. */
        fun get(context: Context): HealthConnectPrefsStore =
            instance ?: synchronized(this) {
                instance ?: HealthConnectPrefsStore(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
