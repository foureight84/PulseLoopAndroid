package com.pulseloop.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.health.exporters.ActivityExporter
import com.pulseloop.health.exporters.SleepExporter
import com.pulseloop.health.exporters.VitalsExporter
import kotlinx.coroutines.delay

/**
 * Chunk + retry progress for one kind's insert pass (see [healthConnectInsertChunked]).
 */
data class ChunkProgress(
    /** Max source-row createdAt fully inserted; 0 when nothing landed. */
    val lastCompletedHighWater: Long,
    val allCompleted: Boolean,
    val attempts: Int,
    val inserted: Int,
    val lastError: Exception?,
)

/**
 * Inserts [records] in [HealthConnectExporter.CHUNK_SIZE] chunks with retry/backoff
 * (Gadgetbridge's production constants: 200 per call, 5 retries, 1 / 2 / 4 / 8 / 16 s). [highWaters]
 * holds the source-row high water for each record (parallel lists); the watermark may only
 * advance to a value whose rows all reached Health Connect, so each chunk's high water is the max
 * of its records'. A [SecurityException] rethrows immediately (the caller aborts the pass) —
 * never retry a permission failure; any other error is retried and then reported via
 * [ChunkProgress] — never thrown, so one failing kind cannot sink the rest.
 */
internal suspend fun healthConnectInsertChunked(
    records: List<Record>,
    highWaters: List<Long>,
    insert: suspend (List<Record>) -> Unit,
): ChunkProgress {
    require(records.size == highWaters.size) { "records/highWaters must be parallel" }
    if (records.isEmpty()) return ChunkProgress(0L, true, 0, 0, null)
    var lastOk = 0L
    var attempts = 0
    var inserted = 0
    var lastError: Exception? = null
    var i = 0
    while (i < records.size) {
        val end = minOf(i + HealthConnectExporter.CHUNK_SIZE, records.size)
        val chunk = records.subList(i, end)
        val chunkHigh = highWaters.subList(i, end).maxOrNull() ?: 0L
        var success = false
        for (attempt in 0..HealthConnectExporter.MAX_RETRIES) {
            attempts++
            try {
                insert(chunk)
                success = true
                lastError = null
                break
            } catch (e: SecurityException) {
                throw e // permission failure — abort, never retry
            } catch (e: Exception) {
                lastError = e
                if (attempt < HealthConnectExporter.MAX_RETRIES) delay(HealthConnectExporter.RETRY_BASE_MS shl attempt)
            }
        }
        if (!success) {
            // A watermark may only advance past values whose records ALL landed. One source row
            // can produce several records sharing one high water (Phase 3 writes steps + energy +
            // distance from one day), so if the chunk boundary splits such a row, the max of the
            // completed chunks would strand the unlanded sibling below an advanced watermark.
            // Clamp to the largest completed value that is strictly below everything still
            // pending.
            val minRemaining = highWaters.subList(i, highWaters.size).min()
            val safe = highWaters.subList(0, i).filter { it < minRemaining }.maxOrNull() ?: 0L
            return ChunkProgress(minOf(lastOk, safe), false, attempts, inserted, lastError)
        }
        lastOk = maxOf(lastOk, chunkHigh)
        inserted += chunk.size
        i = end
    }
    return ChunkProgress(lastOk, true, attempts, inserted, null)
}

/**
 * The export engine (docs/health-connect-integration.md §3 + Phase 1): a pure DB → Health Connect
 * pass driven by watermarks, never by events.
 *
 * Robustness constants are measured from Gadgetbridge's production code: [CHUNK_SIZE] records per
 * `insertRecords` call; [MAX_RETRIES] retries with exponential backoff from 1 s (1 / 2 / 4 / 8 /
 * 16 s); a [SecurityException] aborts immediately — never retry a permission failure. The
 * watermark advances only to a timestamp that actually reached Health Connect, and never rewinds
 * ([HealthConnectPrefsStore.setWatermark] enforces the monotonic part).
 *
 * Phase 1 wires the vitals group; Phase 2 adds the sleep group (its own [SleepExporter] and its
 * own SLEEP watermark key); Phases 3–5 append the same way (activity / workouts / nutrition).
 */
class HealthConnectExporter(
    private val client: HealthConnectClient,
    private val db: PulseLoopDatabase,
    private val store: HealthConnectPrefsStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * One full pass. Returns a human-readable summary via [PassResult]; never throws for a single
     * failing kind (one failing type must not sink the others), but a [SecurityException] — a
     * mid-pass permission revocation — aborts the whole pass.
     */
    suspend fun run(): PassResult {
        val prefs = store.current
        val wm0 = store.currentWatermarks
        val timestamp = now()

        // First-enable "Only new data from now on": stamp every group's watermark to now exactly
        // once, then export nothing — the choice is made meaningful without a data pass.
        if (prefs.backfillChoice == HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY && wm0.vitals == null) {
            HealthConnectWatermarks.Key.values().forEach { store.setWatermark(it, timestamp) }
            return PassResult(
                inserted = emptyMap(),
                skipped = listOf("all (backfill choice: export new data only — watermarks stamped)"),
                errors = emptyList(),
            )
        }

        // Re-check the granted set live on every pass (plan: partial grants are first-class; the
        // per-kind check below then gates each record class against it). 1.1.0: PermissionController
        // is an interface obtained from the client (no (client) constructor).
        val granted = client.permissionController.getGrantedPermissions()
        val device = deviceForMetadata(db)

        val inserted = LinkedHashMap<String, Int>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // ── Vitals group (Phase 1; watermarked on Measurement.createdAt, one group watermark) ──
        val kindToggles = mapOf(
            "hr" to prefs.heartRate,
            "spo2" to prefs.oxygenSaturation,
            "hrv" to prefs.heartRateVariability,
            "temp" to prefs.bodyTemperature,
        )
        val kindLabels = mapOf(
            "hr" to "heart rate",
            "spo2" to "SpO2",
            "hrv" to "HRV",
            "temp" to "body temperature",
        )
        val vitalsWm = wm0.vitals
        val kindHighs = LinkedHashMap<String, Long>()
        val exporter = VitalsExporter(db)

        for ((kindKey, toggleOn) in kindToggles) {
            if (!toggleOn) {
                skipped += kindLabels.getValue(kindKey) + " (toggle off)"
                continue
            }
            val permission = HealthConnectPermissions.WRITE_PERMISSION_BY_KIND[kindKey]
            if (permission == null || permission !in granted) {
                skipped += kindLabels.getValue(kindKey) + " (permission not granted)"
                continue
            }
            val pending = exporter.build(kindKey, vitalsWm, device)
            val progress = insertChunked(pending.records, pending.highWaters) { chunk ->
                client.insertRecords(chunk)
            }
            val label = kindLabels.getValue(kindKey)
            if (pending.records.isEmpty()) {
                // Nothing new for this kind: its "everything exported" point is now — it must not
                // hold the group watermark hostage at the old value.
                kindHighs[kindKey] = timestamp
            } else {
                kindHighs[kindKey] = progress.lastCompletedHighWater
                inserted[kindKey] = progress.inserted
                if (!progress.allCompleted) {
                    errors += "$label: stopped after ${progress.inserted} records " +
                        "(attempts=${progress.attempts}, last error: ${progress.lastError?.message ?: "unknown"})"
                }
            }
        }

        // Group watermark = the point below which EVERY exported kind is fully done (the min of
        // per-kind highs). A kind that failed partway pins the group at its last success, so the
        // next pass re-reads only what it missed — and re-upserts (same clientRecordIds) the
        // kinds that were already further along. setWatermark() then enforces never-rewind.
        if (kindHighs.isNotEmpty()) {
            val groupHigh = kindHighs.values.minOrNull() ?: 0L
            if (groupHigh > (vitalsWm ?: 0L)) store.setWatermark(HealthConnectWatermarks.Key.VITALS, groupHigh)
        }

        // ── Sleep group (Phase 2; watermarked on SleepSessionEntity.updatedAt — a re-synced
        //    night re-upserts the same pl-sleep-<dayEpochMs> record in place) ──
        if (!prefs.sleep) {
            skipped += "sleep (toggle off)"
        } else {
            val permission = HealthConnectPermissions.sleep.first()
            if (permission !in granted) {
                skipped += "sleep (permission not granted)"
            } else {
                val sleepPending = SleepExporter(db).build(wm0.sleep, device)
                val sleepProgress = insertChunked(sleepPending.records, sleepPending.highWaters) { chunk ->
                    client.insertRecords(chunk)
                }
                if (sleepPending.records.isEmpty()) {
                    // Nothing new for sleep: the group's "everything exported" point is now — it
                    // must not hold its watermark hostage at the old value (same rule as a
                    // vitals kind with no new rows).
                    if (timestamp > (wm0.sleep ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, timestamp)
                    }
                } else {
                    inserted["sleep"] = sleepProgress.inserted
                    // Advance only to what actually landed; setWatermark() enforces never-rewind.
                    if (sleepProgress.lastCompletedHighWater > (wm0.sleep ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.SLEEP, sleepProgress.lastCompletedHighWater)
                    }
                    if (!sleepProgress.allCompleted) {
                        errors += "sleep: stopped after ${sleepProgress.inserted} record(s) " +
                            "(attempts=${sleepProgress.attempts}, last error: ${sleepProgress.lastError?.message ?: "unknown"})"
                    }
                }
                if (sleepPending.skippedSessions > 0) {
                    skipped += "sleep: ${sleepPending.skippedSessions} session(s) without valid stages"
                }
            }
        }

        // ── Activity group (Phase 3; watermarked on ActivityDailyEntity.updatedAt — a day whose
        //    totals grow through the afternoon re-upserts the same three
        //    pl-act-<metric>-<dayEpochMs> records in place) ──
        if (!prefs.stepsAndActivity) {
            skipped += "steps & activity (toggle off)"
        } else {
            val metricPermissions = linkedMapOf(
                HealthConnectTypeMappings.ACT_STEPS to HealthConnectPermissions.steps.first(),
                HealthConnectTypeMappings.ACT_ENERGY to HealthConnectPermissions.activeCalories.first(),
                HealthConnectTypeMappings.ACT_DIST to HealthConnectPermissions.distance.first(),
            )
            val metricLabels = mapOf(
                HealthConnectTypeMappings.ACT_STEPS to "steps",
                HealthConnectTypeMappings.ACT_ENERGY to "active calories",
                HealthConnectTypeMappings.ACT_DIST to "distance",
            )
            // Partial grants are first-class (plan §4): each of the three record types is gated on
            // its own write permission, and the ones that are granted still export.
            metricPermissions.forEach { (metric, permission) ->
                if (permission !in granted) skipped += metricLabels.getValue(metric) + " (permission not granted)"
            }
            val metrics = metricPermissions.filterValues { it in granted }.keys
            if (metrics.isNotEmpty()) {
                val activityPending = ActivityExporter(db).build(
                    watermark = wm0.activity,
                    device = device,
                    metrics = metrics,
                    // Netting is only correct while the workout records it compensates for are
                    // actually being written — see [shouldNetWorkouts].
                    netWorkouts = shouldNetWorkouts(prefs, granted),
                    nowMs = timestamp,
                )
                val activityProgress = insertChunked(activityPending.records, activityPending.highWaters) { chunk ->
                    client.insertRecords(chunk)
                }
                if (activityPending.records.isEmpty()) {
                    // Nothing new (or nothing writable) for activity: its "everything exported"
                    // point is now — same rule as an empty vitals kind or sleep pass. Note this
                    // only fires when the WHOLE pass produced nothing, so a zero-metric day whose
                    // updatedAt sits above every record-producing day is re-read on each pass until
                    // some pass comes back empty. Bounded and idempotent, not a leak.
                    if (timestamp > (wm0.activity ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.ACTIVITY, timestamp)
                    }
                } else {
                    inserted["activity"] = activityProgress.inserted
                    if (activityProgress.lastCompletedHighWater > (wm0.activity ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.ACTIVITY, activityProgress.lastCompletedHighWater)
                    }
                    if (!activityProgress.allCompleted) {
                        errors += "activity: stopped after ${activityProgress.inserted} record(s) " +
                            "(attempts=${activityProgress.attempts}, last error: ${activityProgress.lastError?.message ?: "unknown"})"
                    }
                }
                if (activityPending.skippedDays > 0) {
                    skipped += "activity: ${activityPending.skippedDays} day(s) with nothing to export"
                }
            }
        }

        return PassResult(inserted, skipped, errors)
    }

    /**
     * Ring attribution for record metadata. 1.1.0's [Metadata] requires a non-null device, so
     * when no real ring is paired the export is attributed to the app itself (iOS equivalent:
     * "samples are then attributed to the app only").
     */
    private suspend fun deviceForMetadata(db: PulseLoopDatabase): Device {
        val d = db.deviceDao().currentReal() ?: return Device(
            type = Device.TYPE_PHONE, manufacturer = "PulseLoop", model = "app",
        )
        val modelId = d.wearableModelID // e.g. "colmi-r10"
        val manufacturer: String
        val model: String
        if (modelId != null && modelId.contains('-')) {
            manufacturer = modelId.substringBefore('-')
            model = modelId.substringAfter('-')
        } else {
            manufacturer = "PulseLoop"
            model = d.name.ifBlank { modelId ?: "ring" }
        }
        return Device(type = Device.TYPE_RING, manufacturer = manufacturer, model = model)
    }

    /** Chunk + retry delegate — the implementation is top-level ([healthConnectInsertChunked]) so
     *  it is testable without a client. */
    suspend fun insertChunked(
        records: List<Record>,
        highWaters: List<Long>,
        insert: suspend (List<Record>) -> Unit,
    ): ChunkProgress = healthConnectInsertChunked(records, highWaters, insert)

    companion object {
        /**
         * Whether workout energy/distance may be netted out of the daily aggregates.
         *
         * iOS gates this on its `exportWorkouts` preference alone, because on iOS the workout
         * exporter already exists — netting and the compensating `HKWorkout` ship together. Here
         * they do not: Phase 4 owns the workout records, so netting on the toggle alone would
         * subtract energy and metres that **nothing writes back**, silently under-reporting every
         * day that contains a workout, with no way to repair it (the export is write-only).
         *
         * So netting additionally requires that workouts are genuinely exportable: the Phase 4
         * exporter existing at all ([WORKOUTS_EXPORTED]) and `WRITE_EXERCISE` actually granted —
         * the second half still matters after Phase 4 lands, because the toggle can be on while
         * the permission is denied, which the plan treats as a first-class state.
         *
         * **Phase 4 flips [WORKOUTS_EXPORTED] to true in the same commit that adds
         * `WorkoutExporter`.** Until then the daily totals are written whole, which is the correct
         * reading of the data we actually publish.
         */
        internal fun shouldNetWorkouts(prefs: HealthConnectPrefs, granted: Set<String>): Boolean =
            WORKOUTS_EXPORTED && prefs.workouts && HealthConnectPermissions.exercise.first() in granted

        /** Set to true by Phase 4, together with the `WorkoutExporter` it refers to. */
        internal const val WORKOUTS_EXPORTED = false

        /** Gadgetbridge: records per `insertRecords` call. */
        const val CHUNK_SIZE = 200
        /** Gadgetbridge: retries after the initial attempt (backoff 1 / 2 / 4 / 8 / 16 s). */
        const val MAX_RETRIES = 5
        const val RETRY_BASE_MS = 1_000L
    }

    // ── result ──

    data class PassResult(
        val inserted: Map<String, Int>,
        val skipped: List<String>,
        val errors: List<String>,
    ) {
        fun summary(): String {
            val bits = mutableListOf<String>()
            if (inserted.isNotEmpty()) {
                bits += "exported " + inserted.entries.joinToString(", ") { "${it.key} ${it.value}" }
            }
            if (skipped.isNotEmpty()) bits += "skipped: " + skipped.joinToString("; ")
            if (errors.isNotEmpty()) bits += errors.joinToString("; ")
            return if (bits.isEmpty()) "nothing new to export" else bits.joinToString(" · ")
        }
    }
}
