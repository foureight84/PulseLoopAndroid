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
    /**
     * First-enable backfill choice. Exports are hard-gated on this in Phase 1: nothing runs
     * while it is [BackfillChoice.NOT_ASKED].
     */
    val backfillChoice: BackfillChoice = BackfillChoice.NOT_ASKED,
    val lastSyncAt: Long? = null,
    val lastSyncSummary: String? = null,
    /** Write permissions granted at the last permission-sheet result; Phase 6 diffs this to detect revocation. */
    val lastGrantedPermissions: List<String> = emptyList(),
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
) {
    enum class Key { VITALS, SLEEP, ACTIVITY, WORKOUTS, NUTRITION }

    fun get(key: Key): Long? = when (key) {
        Key.VITALS -> vitals
        Key.SLEEP -> sleep
        Key.ACTIVITY -> activity
        Key.WORKOUTS -> workouts
        Key.NUTRITION -> nutrition
    }

    fun copyWith(key: Key, value: Long): HealthConnectWatermarks = when (key) {
        Key.VITALS -> copy(vitals = value)
        Key.SLEEP -> copy(sleep = value)
        Key.ACTIVITY -> copy(activity = value)
        Key.WORKOUTS -> copy(workouts = value)
        Key.NUTRITION -> copy(nutrition = value)
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

    fun update(transform: (HealthConnectPrefs) -> HealthConnectPrefs) {
        val next = transform(current)
        if (next == current) return
        _prefs.value = next
        prefsStore.edit().putString(KEY_PREFS, json.encodeToString(HealthConnectPrefs.serializer(), next)).apply()
    }

    /**
     * Advance a watermark. Only ever moves forward: an interrupted backfill resumes, never
     * re-exports, and a crash mid-chunk cannot push the watermark backwards (plan §3:
     * "Advance the watermark only to a timestamp that actually reached Health Connect, and
     * never rewind").
     */
    fun setWatermark(key: HealthConnectWatermarks.Key, value: Long) {
        val cur = currentWatermarks
        val existing = cur.get(key)
        if (existing != null && value <= existing) return
        val next = cur.copyWith(key, value)
        _watermarks.value = next
        prefsStore.edit().putString(KEY_WATERMARKS, json.encodeToString(HealthConnectWatermarks.serializer(), next)).apply()
    }

    /** Clear every watermark (iOS `removeAllExportedData`; Phase 6 revocation reset). */
    fun clearWatermarks() {
        val next = HealthConnectWatermarks.DEFAULT
        _watermarks.value = next
        prefsStore.edit().putString(KEY_WATERMARKS, json.encodeToString(HealthConnectWatermarks.serializer(), next)).apply()
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
