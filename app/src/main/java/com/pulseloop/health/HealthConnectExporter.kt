package com.pulseloop.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.health.exporters.ActivityExporter
import com.pulseloop.health.exporters.NutritionExporter
import com.pulseloop.health.exporters.RestingHeartRateExporter
import com.pulseloop.health.exporters.SleepExporter
import com.pulseloop.health.exporters.VitalsExporter
import com.pulseloop.health.exporters.WorkoutExporter
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
 * Inserts [chunk], with the 1 MB single-record fallback (plan §3 robustness constants;
 * Gadgetbridge's `insertRecords` + `shrinkOversizedRoute`): the platform rejects an oversized
 * insert with "...single record size limit: 1000000, was: N...". That failure is deterministic,
 * so plain backoff would just burn retries — instead the routes of the [ExerciseSessionRecord]s
 * in the chunk (the only variable-size records we build) are decimated to ~90 % of the limit and
 * the retry is IMMEDIATE, no backoff. Any error the shrink cannot address rethrows for the
 * caller's normal retry loop; a [SecurityException] always rethrows first (never retry a
 * permission failure).
 */
internal suspend fun insertChunkWithRouteShrink(
    chunk: List<Record>,
    insert: suspend (List<Record>) -> Unit,
): Unit {
    var current = chunk
    // Bounded defensively: every successful shrink removes at least one route point, and once a
    // route is at the 2-point floor [shrinkOversizedRoute] reports nothing shrank, so this can
    // never spin — but a cap keeps a malformed platform message from making it so.
    var shrinks = 0
    while (true) {
        try {
            insert(current)
            return
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            val shrunk = shrinkOversizedRoute(current, e) ?: throw e
            if (++shrinks > MAX_ROUTE_SHRINKS) throw e
            current = shrunk
        }
    }
}

/** Defense-in-depth cap on consecutive route shrinks (see [insertChunkWithRouteShrink]). */
internal const val MAX_ROUTE_SHRINKS = 5

/**
 * The workouts watermark advance decision (observer review, Phase 4 stage B — BLOCKER fix):
 * [invalidHighWater] — the max `updatedAt` of the record-less INVALID (zero/negative duration)
 * sessions — may be applied **only when the pass fully completed**. On a partial chunk failure,
 * [healthConnectInsertChunked]'s clamp covers only RECORD high waters; an INVALID row's
 * `updatedAt` sitting above the failed point would otherwise leapfrog the unlanded valid sessions
 * below it (they would never be re-selected, and netting would still subtract their energy from
 * re-exported day records — loss that compounds). On a completed pass every valid session has
 * landed, so advancing past the invalid rows is safe — they can never become exportable.
 */
internal fun workoutsWatermarkAdvance(
    allCompleted: Boolean,
    lastCompletedHighWater: Long,
    invalidHighWater: Long?,
): Long = if (allCompleted) maxOf(lastCompletedHighWater, invalidHighWater ?: 0L) else lastCompletedHighWater

/**
 * If [e] carries the platform's single-record size-limit message and [records] contains an
 * [ExerciseSessionRecord] whose route is long enough to decimate, returns a copy of the list
 * with those routes cut to ~[HealthConnectTypeMappings.ROUTE_SHRINK_MARGIN] of the limit
 * (first and last points preserved, no duplicated timestamps — HC rejects both). Returns null
 * when the error is unrelated or nothing can shrink, so the caller falls back to normal
 * retry/abort. Terminates: once a route is down to 2 points [shrinkOversizedRoute] reports
 * nothing shrank and the original error rethrows.
 */
internal fun shrinkOversizedRoute(records: List<Record>, e: Exception): List<Record>? {
    val (limit, was) = HealthConnectTypeMappings.parseRecordSizeLimit(e.message) ?: return null
    var shrankAny = false
    val result = records.map { record ->
        val route = ((record as? ExerciseSessionRecord)?.exerciseRouteResult as? ExerciseRouteResult.Data)
            ?.exerciseRoute
            ?: return@map record
        val points = route.route
        if (points.size < 2) return@map record
        val target = (points.size * (limit.toDouble() / was.toDouble()) * HealthConnectTypeMappings.ROUTE_SHRINK_MARGIN).toInt()
        if (target >= points.size) return@map record
        val decimated = HealthConnectTypeMappings.decimateToSize(points, target)
        // A route already at the 2-point floor "shrinks" to itself — count it only when points
        // were actually removed, or the caller would retry the same oversized chunk forever.
        if (decimated.size == points.size) return@map record
        shrankAny = true
        ExerciseSessionRecord(
            startTime = record.startTime,
            startZoneOffset = record.startZoneOffset,
            endTime = record.endTime,
            endZoneOffset = record.endZoneOffset,
            metadata = record.metadata,
            exerciseType = record.exerciseType,
            title = record.title,
            notes = record.notes,
            exerciseRoute = ExerciseRoute(decimated),
        )
    }
    return if (shrankAny) result else null
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
 * own SLEEP watermark key); Phases 3–4 append the same way (activity / workouts); Phase 5
 * (nutrition) does the rest. The workouts group is the only one that needs the
 * 1 MB per-record fallback ([insertChunkWithRouteShrink]), because the embedded GPS route is
 * the only variable-size record we build.
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
        val timestamp = now()
        var wm0 = store.currentWatermarks

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

        // One-time Phase 4 netting flip ([HealthConnectPrefs.nettingFlipDone]): on the first
        // pass of a build where netting is live, reset the ACTIVITY and WORKOUTS watermarks so
        // the daily records exported under the Phase 3 build — UN-netted, because
        // WORKOUTS_EXPORTED was false then — are re-selected and re-upserted with their netted
        // values, in the same pass that writes the workout siblings they compensate for.
        // Without this, every pre-flip day containing a workout would over-count by the
        // workout's own energy/distance for as long as the day stays un-updated (write-only:
        // the stale un-netted record cannot be deleted). The re-export is idempotent — same
        // clientRecordIds, higher-or-equal versions (an unchanged row re-upserts at the SAME
        // version — the platform accepts equal-version upserts, verified live in the Phase 4
        // flip pass) — and bounded: each group's full history once.
        //
        // Gated on EXPORT_ALL (observer review, Phase 4 stage B): a user who chose "Only new
        // data from now on" consented to no history — a full-watermark reset would re-export
        // pre-consent days, violating the Phase 1 backfill boundary. Their narrower residual
        // (pre-flip un-netted daily records for days that were already inside the consented
        // window) is accepted: it is bounded by the days touched between Phase 3 enable and the
        // Phase 4 update, and the accepted-stale-window rule on
        // [HealthConnectTypeMappings.activityLeftover] covers it.
        if (WORKOUTS_EXPORTED && prefs.workouts && !prefs.nettingFlipDone &&
            prefs.backfillChoice == HealthConnectPrefs.BackfillChoice.EXPORT_ALL &&
            HealthConnectPermissions.exercise.first() in granted) {
            store.resetWatermarks(
                setOf(HealthConnectWatermarks.Key.ACTIVITY, HealthConnectWatermarks.Key.WORKOUTS),
            )
            store.update { it.copy(nettingFlipDone = true) }
            // The reset just invalidated the snapshot above: re-read before the groups use it.
            wm0 = store.currentWatermarks
        }

        val inserted = LinkedHashMap<String, Int>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // ── Vitals group (Phase 1; watermarked on Measurement.createdAt, one group watermark) ──
        val kindToggles = mapOf(
            "hr" to prefs.heartRate,
            "spo2" to prefs.oxygenSaturation,
            "hrv" to prefs.heartRateVariability,
            "temp" to prefs.bodyTemperature,
            // Phase 5 measurement-based kinds.
            "glucose" to prefs.bloodGlucose,
            "resp_rate" to prefs.respiratoryRate,
            "vo2max" to prefs.vo2Max,
            "bp" to prefs.bloodPressure,
        )
        val kindLabels = mapOf(
            "hr" to "heart rate",
            "spo2" to "SpO2",
            "hrv" to "HRV",
            "temp" to "body temperature",
            "glucose" to "blood glucose",
            "resp_rate" to "respiratory rate",
            "vo2max" to "VO2max",
            "bp" to "blood pressure",
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
            if (pending.skipped > 0) {
                skipped += "$label: ${pending.skipped} reading(s) dropped (unpaired or out of range)"
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

        // ── Workouts group (Phase 4; watermarked on ActivitySessionEntity.updatedAt — a
        //    post-finish edit or vitals backfill re-upserts the same pl-wk-<sessionId> records
        //    in place) ──
        if (!prefs.workouts) {
            skipped += "workouts (toggle off)"
        } else {
            val exercisePermission = HealthConnectPermissions.exercise.first()
            if (exercisePermission !in granted) {
                skipped += "workouts (permission not granted)"
            } else {
                // The route is an embedded field with its own, independently grantable write
                // permission: without it the session still writes, just without a route. The
                // siblings are standalone records, each gated on its OWN permission — the same
                // three that the activity group gates its per-metric records on (plan Phase 4 —
                // partial grants are first-class).
                val withRoute = HealthConnectPermissions.exerciseRoute.first() in granted
                val withEnergy = HealthConnectPermissions.activeCalories.first() in granted
                val withDistance = HealthConnectPermissions.distance.first() in granted
                val workoutsPending = WorkoutExporter(db).build(
                    wm0.workouts, device, withRoute, withEnergy, withDistance, timestamp,
                )
                val workoutsProgress = insertChunked(workoutsPending.records, workoutsPending.highWaters) { chunk ->
                    insertChunkWithRouteShrink(chunk) { client.insertRecords(it) }
                }
                if (workoutsPending.records.isEmpty()) {
                    // Nothing new (or nothing exportable) for workouts: its "everything exported"
                    // point is now — same rule as an empty vitals kind, sleep or activity pass.
                    // EXCEPT when the pass stopped at a future-dated session: that session IS new
                    // data, just not yet exportable, and stamping to now would leapfrog it (iOS
                    // `guard end <= now else break`).
                    if (!workoutsPending.blockedFuture && timestamp > (wm0.workouts ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.WORKOUTS, timestamp)
                    }
                } else {
                    inserted["workouts"] = workoutsProgress.inserted
                    // Advance to what landed — or past the never-exportable rows (invalidHighWater)
                    // ONLY when the pass completed: a zero-duration session produces no record, so
                    // it would otherwise be re-selected forever (iOS advances its workout watermark
                    // past such sessions in the same step) — but on a partial failure that value
                    // could leapfrog unlanded valid sessions (see [workoutsWatermarkAdvance]).
                    val advanceTo = workoutsWatermarkAdvance(
                        workoutsProgress.allCompleted,
                        workoutsProgress.lastCompletedHighWater,
                        workoutsPending.invalidHighWater,
                    )
                    if (advanceTo > (wm0.workouts ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.WORKOUTS, advanceTo)
                    }
                    if (!workoutsProgress.allCompleted) {
                        errors += "workouts: stopped after ${workoutsProgress.inserted} record(s) " +
                            "(attempts=${workoutsProgress.attempts}, last error: ${workoutsProgress.lastError?.message ?: "unknown"})"
                    }
                }
                if (workoutsPending.blockedFuture) {
                    skipped += "workouts: pass stopped at a future-dated session (retries next run)"
                }
                if (workoutsPending.skippedSessions > 0) {
                    skipped += "workouts: ${workoutsPending.skippedSessions} session(s) with zero or negative duration"
                }
            }
        }

        // ── Resting HR group (Phase 5; a single mutable baseline, watermarked on
        //    UserProfileEntity.hrRestingBaselineUpdatedAt - a re-learn re-upserts the same
        //    pl-resting-hr record in place at a higher version) ──
        if (!prefs.restingHeartRate) {
            skipped += "resting heart rate (toggle off)"
        } else {
            val restingPermission = HealthConnectPermissions.restingHeartRate.first()
            if (restingPermission !in granted) {
                skipped += "resting heart rate (permission not granted)"
            } else {
                val restingPending = RestingHeartRateExporter(db).build(wm0.restingHr, device)
                if (restingPending.records.isEmpty()) {
                    // Nothing to export (no baseline yet, already current, or implausible): the
                    // group's "everything exported" point is now - same rule as the other groups.
                    if (timestamp > (wm0.restingHr ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.RESTING_HR, timestamp)
                    }
                } else {
                    val restingProgress = insertChunked(restingPending.records, restingPending.highWaters) { chunk ->
                        client.insertRecords(chunk)
                    }
                    inserted["resting_hr"] = restingProgress.inserted
                    if (restingProgress.lastCompletedHighWater > (wm0.restingHr ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.RESTING_HR, restingProgress.lastCompletedHighWater)
                    }
                    if (!restingProgress.allCompleted) {
                        errors += "resting heart rate: stopped after ${restingProgress.inserted} record(s) " +
                            "(attempts=${restingProgress.attempts}, last error: ${restingProgress.lastError?.message ?: "unknown"})"
                    }
                }
            }
        }

        // ── Nutrition group (Phase 5; watermarked on MealEntryEntity.createdAt - a logged meal
        //    is insert-once, so createdAt is the stable high water; version = createdAt) ──
        // iOS gates nutrition export on the nutrition FEATURE's master toggle as well as the
        // Health Connect per-type toggle (+Nutrition.swift:19) - off-feature meals must not leak to
        // Health Connect, so both must be on.
        val nutritionFeatureOn = db.userGoalDao().get()?.nutritionEnabled ?: false
        if (!prefs.nutrition) {
            skipped += "nutrition (toggle off)"
        } else if (!nutritionFeatureOn) {
            skipped += "nutrition (nutrition feature off)"
        } else {
            val nutritionPermission = HealthConnectPermissions.nutrition.first()
            if (nutritionPermission !in granted) {
                skipped += "nutrition (permission not granted)"
            } else {
                val nutritionPending = NutritionExporter(db).build(wm0.nutrition, device)
                if (nutritionPending.records.isEmpty()) {
                    // Nothing new (or nothing exportable) for nutrition: its "everything exported"
                    // point is now - same rule as the other groups.
                    if (timestamp > (wm0.nutrition ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.NUTRITION, timestamp)
                    }
                } else {
                    val nutritionProgress = insertChunked(nutritionPending.records, nutritionPending.highWaters) { chunk ->
                        client.insertRecords(chunk)
                    }
                    inserted["nutrition"] = nutritionProgress.inserted
                    if (nutritionProgress.lastCompletedHighWater > (wm0.nutrition ?: 0L)) {
                        store.setWatermark(HealthConnectWatermarks.Key.NUTRITION, nutritionProgress.lastCompletedHighWater)
                    }
                    if (!nutritionProgress.allCompleted) {
                        errors += "nutrition: stopped after ${nutritionProgress.inserted} record(s) " +
                            "(attempts=${nutritionProgress.attempts}, last error: ${nutritionProgress.lastError?.message ?: "unknown"})"
                    }
                }
                if (nutritionPending.skippedMeals > 0) {
                    skipped += "nutrition: ${nutritionPending.skippedMeals} meal(s) outside the platform's range"
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
         * the two shipped separately (Phase 3, then Phase 4), so netting additionally requires
         * that workouts are genuinely exportable in this very build: the workout exporter
         * existing at all ([WORKOUTS_EXPORTED]) and `WRITE_EXERCISE` actually granted — the
         * second half still matters after Phase 4 lands, because the toggle can be on while the
         * permission is denied, which the plan treats as a first-class state (and then no
         * workout record is written for the daily netting to compensate against).
         *
         * **Phase 4 flips [WORKOUTS_EXPORTED] to true in the same commit that adds
         * [WorkoutExporter]** (plan Phase 4 "Inherited from Phase 3") — netting is live from
         * this commit on. A day whose netted leftover falls to ≤ 0 has its record DROPPED, not
         * floored: the stale-record decision lives on
         * [HealthConnectTypeMappings.activityLeftover].
         */
        internal fun shouldNetWorkouts(prefs: HealthConnectPrefs, granted: Set<String>): Boolean =
            WORKOUTS_EXPORTED && prefs.workouts && HealthConnectPermissions.exercise.first() in granted

        /**
         * True since Phase 4, which shipped the [WorkoutExporter] this flag refers to — in the
         * same commit, as the plan requires — so netting and the compensating workout records
         * turn on together and no day is ever netted against records nothing writes.
         */
        internal const val WORKOUTS_EXPORTED = true

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
