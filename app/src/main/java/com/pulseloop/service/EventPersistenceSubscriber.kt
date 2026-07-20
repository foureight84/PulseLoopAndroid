package com.pulseloop.service

import androidx.room.withTransaction
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import com.pulseloop.ring.*
import kotlinx.coroutines.*

/**
 * Ported from [EventPersistenceSubscriber] in PulseEventBus.swift.
 * Subscribes to PulseEventBus and persists ring data to Room.
 */
class EventPersistenceSubscriber(
    private val db: PulseLoopDatabase,
    /**
     * Fired after a data-bearing event lands in Room (measurements, activity, sleep) — the
     * Android analog of iOS `PulseDataChange`. The widget snapshot publisher hooks this
     * (debounced) so home-screen widgets refresh after every ring-sync batch.
     */
    private val onDataPersisted: (() -> Unit)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            PulseEventBus.events.collect { event -> persist(event) }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun persist(event: PulseEvent) {
        try {
            persistUnsafe(event)
            if (isDataEvent(event)) onDataPersisted?.invoke()
        } catch (e: Exception) {
            // Swallow individual persistence failures so one bad event
            // (malformed ring packet, DB constraint, etc.) never crashes the app.
            android.util.Log.e("EventPersistence", "Failed to persist event", e)
        }
    }

    /** Events that change what the Today tiles (and therefore the widgets) show. */
    private fun isDataEvent(event: PulseEvent): Boolean = when (event) {
        is PulseEvent.HeartRateSample,
        is PulseEvent.Spo2Result,
        is PulseEvent.HistoryMeasurement,
        is PulseEvent.StressSample,
        is PulseEvent.HrvSample,
        is PulseEvent.BloodPressureSample,
        is PulseEvent.BloodSugarSample,
        is PulseEvent.TemperatureSample,
        is PulseEvent.ActivityUpdate,
        is PulseEvent.ActivityBucket,
        is PulseEvent.SleepTimeline -> true
        else -> false
    }

    private suspend fun persistUnsafe(event: PulseEvent) {
        when (event) {
            is PulseEvent.DeviceStateChanged -> {
                // Never resurrect a forgotten ring: after Forget / Factory Reset clears the
                // device row, the ring's own teardown still emits a late DISCONNECTED — only
                // a connection-establishing event may create a fresh row.
                // currentReal() (not current()) so a seeded demo-device row can never absorb a
                // real ring's identity — the ring gets its own row instead.
                val existing = db.deviceDao().currentReal()
                if (existing == null &&
                    event.state != RingConnectionState.CONNECTED &&
                    event.state != RingConnectionState.CONNECTING) return
                val device = existing ?: DeviceEntity()
                val state = when (event.state) {
                    RingConnectionState.CONNECTED -> {
                        db.measurementDao().clearDemo()
                        db.activityDailyDao().clearDemo()
                        // Never clear real sleep on CONNECTED. YCBT emits Status/CONNECTED more
                        // than once during startup, and history arrives asynchronously; clearing
                        // here made a complete night flash, partially rebuild, then disappear.
                        // Sleep upserts are timestamp-idempotent, so preserve the last good local
                        // copy until replacement packets arrive.
                        db.sleepStageBlockDao().clearDemo()
                        db.sleepSessionDao().clearDemo()
                        "CONNECTED"
                    }
                    RingConnectionState.DISCONNECTED -> "DISCONNECTED"
                    RingConnectionState.CONNECTING -> "CONNECTING"
                    RingConnectionState.SCANNING -> "SCANNING"
                    RingConnectionState.RECONNECTING -> "RECONNECTING"
                    RingConnectionState.FAILED -> "FAILED"
                    else -> "IDLE"
                }
                db.deviceDao().upsert(device.copy(
                    stateRaw = state,
                    // Capture the connected ring's advertised name so the UI reflects the actual
                    // device instead of the DeviceEntity default. Falls back to the existing name
                    // for events that don't carry one (e.g. disconnect).
                    name = event.name?.takeIf { it.isNotBlank() } ?: device.name,
                    advertisedName = event.name?.takeIf { it.isNotBlank() } ?: device.advertisedName,
                    bleAddressHint = event.address ?: device.bleAddressHint,
                    // 0x0C device-info already gives the complete "<CID><DID>V<version>" string.
                    firmwareVersion = event.firmware ?: device.firmwareVersion,
                    lastConnectedAt = if (state == "CONNECTED") System.currentTimeMillis() else device.lastConnectedAt,
                    updatedAt = System.currentTimeMillis(),
                ))
            }
            is PulseEvent.DeviceIdentified -> {
                val device = db.deviceDao().currentReal() ?: DeviceEntity()
                db.deviceDao().upsert(device.copy(
                    deviceTypeRaw = event.deviceType.name,
                    // A connect that can't resolve the exact model (e.g. re-pair with the
                    // carousel on the wrong family) must not erase a previously stamped one.
                    wearableModelID = event.wearableModelID ?: device.wearableModelID,
                    advertisedName = event.advertisedName ?: device.advertisedName,
                    capabilitiesRaw = event.capabilities.toCsv(),
                    updatedAt = System.currentTimeMillis(),
                ))
            }
            is PulseEvent.DeviceForgotten -> {
                // Mirror iOS: forgetting clears the stored model identity (the row itself is
                // cleared by the Forget flow; this covers a row that survives, e.g. offline forget).
                db.deviceDao().currentReal()?.let { device ->
                    db.deviceDao().upsert(device.copy(
                        wearableModelID = null,
                        advertisedName = null,
                        updatedAt = System.currentTimeMillis(),
                    ))
                }
            }
            is PulseEvent.BatteryLevel -> {
                val device = db.deviceDao().currentReal() ?: DeviceEntity()
                db.deviceDao().upsert(device.copy(
                    batteryPercent = event.percent,
                    updatedAt = System.currentTimeMillis(),
                ))
            }
            is PulseEvent.HeartRateSample -> {
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.HEART_RATE.name,
                    value = event.bpm.toDouble(), unit = "bpm",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "live",
                ))
            }
            is PulseEvent.Spo2Result -> {
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.SPO2.name,
                    value = event.value.toDouble(), unit = "%",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "live",
                ))
            }
            is PulseEvent.HistoryMeasurement -> {
                db.measurementDao().upsertByIdentity(MeasurementEntity(
                    id = historyMeasurementId(event.kind, event.timestamp.toEpochMilli()),
                    kindRaw = event.kind.name,
                    value = event.value, unit = event.kind.unit,
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "history",
                ))
            }
            is PulseEvent.StressSample -> {
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.STRESS.name,
                    value = event.value.toDouble(), unit = "",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "colmi",
                ))
            }
            is PulseEvent.HrvSample -> {
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.HRV.name,
                    value = event.value.toDouble(), unit = "ms",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "live",
                ))
            }
            is PulseEvent.BloodPressureSample -> {
                db.withTransaction {
                    val systolic = MeasurementEntity(
                        id = if (event.isHistory) {
                            historyMeasurementId(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, event.timestamp.toEpochMilli())
                        } else {
                            java.util.UUID.randomUUID().toString()
                        },
                        kindRaw = MeasurementKind.BLOOD_PRESSURE_SYSTOLIC.name,
                        value = event.systolic.toDouble(), unit = "mmHg",
                        timestamp = event.timestamp.toEpochMilli(),
                        sourceRaw = if (event.isHistory) "history" else "live",
                    )
                    val diastolic = MeasurementEntity(
                        id = if (event.isHistory) {
                            historyMeasurementId(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, event.timestamp.toEpochMilli())
                        } else {
                            java.util.UUID.randomUUID().toString()
                        },
                        kindRaw = MeasurementKind.BLOOD_PRESSURE_DIASTOLIC.name,
                        value = event.diastolic.toDouble(), unit = "mmHg",
                        timestamp = event.timestamp.toEpochMilli(),
                        sourceRaw = if (event.isHistory) "history" else "live",
                    )
                    db.measurementDao().upsertByIdentity(systolic)
                    db.measurementDao().upsertByIdentity(diastolic)
                }
            }
            is PulseEvent.BloodSugarSample -> {
                db.measurementDao().upsertByIdentity(MeasurementEntity(
                    kindRaw = MeasurementKind.BLOOD_SUGAR.name,
                    value = event.mgdl,
                    unit = MeasurementKind.BLOOD_SUGAR.unit,
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "live",
                ))
            }
            is PulseEvent.TemperatureSample -> {
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.TEMPERATURE.name,
                    value = event.celsius, unit = "°C",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "live",
                ))
            }
            is PulseEvent.ActivityUpdate -> {
                upsertActivityDaily(event.timestamp.toEpochMilli(), event.steps, event.calories, event.distanceMeters)
            }
            is PulseEvent.ActivityBucket -> {
                // Per-slice ring history: upserted by timestamp + the day total recomputed as the
                // sum of distinct buckets, so re-syncs are idempotent (no drift). Routing these
                // through upsertActivityDaily's max() ratchet collapsed a history day's total to
                // its single largest bucket. Calories omitted (unverified ring field).
                applyActivityBucket(event.timestamp.toEpochMilli(), event.steps, event.distanceMeters)
            }
            is PulseEvent.SleepTimeline -> {
                upsertSleepSession(event.timestamp.toEpochMilli(), event.stages, event.completeSession)
            }
            is PulseEvent.SyncProgress -> {} // UI feedback, no persistence needed
            is PulseEvent.HeartRateComplete -> {}
            is PulseEvent.Spo2Complete -> {}
            is PulseEvent.MeasurementRejected -> {} // Product orchestration only; no persistence.

            is PulseEvent.RawPacket -> {
                db.rawPacketDao().insert(RawPacketEntity(
                    directionRaw = event.direction.name,
                    commandId = event.data.getOrNull(0)?.toInt()?.and(0xFF) ?: 0,
                    hexPayload = event.data.joinToString("") { "%02x".format(it) },
                    decodedKind = event.decoded.kind,
                ))
            }
            is PulseEvent.ActivitySyncReset -> {}
            is PulseEvent.FirmwareVersion -> {
                // 0xF6 must NOT write the firmware version. The ring streams two distinct 0xF6
                // sub-records — one (sub-byte 0x00) carries the real version (e.g. 138), the
                // other (sub-byte 0x41) carries an unrelated value that decodes to 2704. Because
                // the decoder can't reliably tell them apart and both stream constantly, letting
                // 0xF6 populate a blank field is exactly what produced the bogus "V2704".
                // The authoritative version comes from 0x0C device-info (see decodeStatus) and is
                // requested on every connect via runStartup(), with DIS 0x2A26 as a fallback, so
                // 0xF6 is never needed here. Kept as a decoded event purely for diagnostics.
            }
        }
    }

    private suspend fun upsertActivityDaily(ts: Long, steps: Int, calories: Double, distanceM: Double) {
        // Key the daily row by local midnight so it matches the Today dashboard and
        // MetricsService/notifications, which all read per-day rows by local-day boundary.
        val dayStart = com.pulseloop.util.TimeUtil.startOfDayLocal(ts)
        val existing = db.activityDailyDao().byDay(dayStart)
        if (existing != null) {
            // Normally keep the running daily max (steps only climb through the day). But if the
            // stored total is implausibly high — e.g. a value locked in by the old little-endian
            // live-activity decode before this fix — overwrite it so the day self-heals instead
            // of staying stuck at a garbage number until midnight.
            val stale = existing.steps > 200_000
            db.activityDailyDao().upsert(existing.copy(
                steps = if (stale) steps else maxOf(existing.steps, steps),
                calories = if (stale) calories else maxOf(existing.calories, calories),
                distanceMeters = if (stale) distanceM else maxOf(existing.distanceMeters, distanceM),
                updatedAt = System.currentTimeMillis(),
            ))
        } else {
            db.activityDailyDao().upsert(ActivityDailyEntity(
                date = dayStart, steps = steps, calories = calories,
                distanceMeters = distanceM, source = "ring",
            ))
        }
    }

    /**
     * Persist one intraday activity bucket from ring history (a Colmi `0x43` sample) and recompute
     * its day's total. The bucket is **upserted by its start time**, so re-syncing the same bucket
     * *replaces* it (never accumulates), and the day's `activity_daily` row is recomputed as the
     * **sum of distinct buckets** for that day. Mirrors `ActivityService.applyActivityBucket` on
     * iOS (the GadgetBridge model). Calories intentionally untouched (unverified ring field).
     */
    private suspend fun applyActivityBucket(ts: Long, steps: Int, distanceM: Double) {
        db.withTransaction { applyActivityBucketAtomic(ts, steps, distanceM) }
    }

    private suspend fun applyActivityBucketAtomic(ts: Long, steps: Int, distanceM: Double) {
        val dayStart = com.pulseloop.util.TimeUtil.startOfDayLocal(ts)
        db.activityBucketDao().upsert(ActivityBucketEntity(
            startEpoch = ts,
            date = dayStart,
            steps = steps,
            distanceMeters = distanceM,
        ))

        val buckets = db.activityBucketDao().byDay(dayStart)
        val totalSteps = buckets.sumOf { it.steps }
        val totalDistance = buckets.sumOf { it.distanceMeters }

        val existing = db.activityDailyDao().byDay(dayStart)
        // A past day's truth is the sum of its distinct buckets (now true 15-minute slices —
        // see ColmiDecoder.decodeActivityHistory). For TODAY the live 0x0C cumulative total
        // leads the bucket history (the in-progress slice isn't served yet), so ratchet
        // against the existing row instead of overwriting — otherwise every reconnect visibly
        // drops today's count until the next live update. Keep the stale-garbage escape from
        // the live path.
        val isToday = dayStart == com.pulseloop.util.TimeUtil.startOfTodayLocal()
        val stale = existing != null && existing.steps > 200_000
        val ratchet = isToday && existing != null && !stale
        db.activityDailyDao().upsert(
            (existing ?: ActivityDailyEntity(date = dayStart, source = "ring_history")).copy(
                steps = if (ratchet) maxOf(existing!!.steps, totalSteps) else totalSteps,
                distanceMeters = if (ratchet) maxOf(existing!!.distanceMeters, totalDistance) else totalDistance,
                source = if (ratchet) existing!!.source else "ring_history",
                syncedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun upsertSleepSession(ts: Long, stages: List<SleepStage>, completeSession: Boolean) {
        if (stages.isEmpty() || stages.size > MAX_SLEEP_TIMELINE_MINUTES) return
        db.withTransaction { upsertSleepSessionAtomic(ts, stages, completeSession) }
    }

    private suspend fun upsertSleepSessionAtomic(ts: Long, stages: List<SleepStage>, completeSession: Boolean) {
        // Group packets by the waking-day boundary (sleep from 7 PM rolls to the next morning) so
        // a night that starts before midnight lands under the morning of waking instead of being
        // split into two sessions at midnight. Matches the iOS reference
        // (PulseEventBus.persistSleepTimeline + Calendar.wakingDay(forSleepStart:)).
        val dayStart = com.pulseloop.util.TimeUtil.wakingDayLocal(ts)
        val sessionId = "sleep-$dayStart"
        val existingSession = db.sleepSessionDao().ringByDay(dayStart)
        if (completeSession && existingSession != null && !shouldReplaceCompleteSleep(
                existingStart = existingSession.startAt,
                existingMinutes = existingSession.totalMinutes,
                incomingStart = ts,
                incomingMinutes = stages.size,
            )) return

        val packetEnd = ts + stages.size * 60_000L
        val replacements = buildStageBlocks(sessionId, ts, stages)
        val merged = if (completeSession) {
            // YCBT sends an authoritative whole-session record. Remove legacy midnight-split
            // parents that overlap it, then replace every block so shortened revisions cannot
            // retain a stale tail.
            for (legacy in db.sleepSessionDao().ringOverlapping(ts, packetEnd)) {
                if (legacy.id != sessionId) {
                    db.sleepStageBlockDao().deleteBySession(legacy.id)
                    db.sleepSessionDao().deleteById(legacy.id)
                }
            }
            replacements
        } else {
            // Other families stream a night as packets. Replace only the packet interval while
            // preserving and splitting unaffected portions of existing stage runs.
            replaceOverlappingSleepBlocks(
                existing = db.sleepStageBlockDao().forSession(sessionId),
                replacements = replacements,
                replacementStart = ts,
                replacementEnd = packetEnd,
            )
        }
        val sessionStart = merged.first().startAt
        val sessionEnd = merged.maxOf { it.startAt + it.durationMinutes * 60_000L }
        if (sessionEnd - sessionStart > MAX_SLEEP_TIMELINE_MINUTES * 60_000L) return
        val totalMin = ((sessionEnd - sessionStart) / 60_000L).toInt().coerceAtLeast(0)
        val deepMin = merged.filter { it.stageRaw == SleepStage.DEEP.name }.sumOf { it.durationMinutes }
        val score = computeSleepScore(deepMin, totalMin)

        // Upsert the parent session BEFORE its stage blocks. sleep_stage_blocks has a foreign key
        // to sleep_sessions(id), so a newly discovered night needs its parent first.
        val existing = existingSession
        db.sleepSessionDao().upsert(
            (existing ?: SleepSessionEntity(id = sessionId, date = dayStart, startAt = sessionStart, endAt = sessionEnd, totalMinutes = totalMin, score = score)).copy(
                startAt = sessionStart,
                endAt = sessionEnd,
                totalMinutes = totalMin,
                score = score,
                syncedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )

        // Rewrite the full accumulated set with start minutes relative to the night start.
        db.sleepStageBlockDao().deleteBySession(sessionId)
        merged.forEach {
            db.sleepStageBlockDao().insert(it.copy(startMinute = ((it.startAt - sessionStart) / 60_000L).toInt()))
        }
    }

    /**
     * Build SleepStageBlockEntity entries with run-length encoding.
     * Consecutive minutes of the same stage are merged into one block.
     */
    private fun buildStageBlocks(sessionId: String, startTs: Long, stages: List<SleepStage>): List<SleepStageBlockEntity> {
        if (stages.isEmpty()) return emptyList()
        val blocks = mutableListOf<SleepStageBlockEntity>()
        var currentStage = stages[0]
        var blockStart = startTs
        var blockMinute = 0
        var duration = 1

        for (i in 1 until stages.size) {
            val stage = stages[i]
            if (stage == currentStage) {
                duration++
            } else {
                blocks.add(SleepStageBlockEntity(
                    sessionId = sessionId,
                    startAt = blockStart,
                    startMinute = blockMinute,
                    durationMinutes = duration,
                    stageRaw = currentStage.name,
                ))
                currentStage = stage
                blockStart = startTs + i * 60_000L
                blockMinute = i
                duration = 1
            }
        }
        // Final block
        blocks.add(SleepStageBlockEntity(
            sessionId = sessionId,
            startAt = blockStart,
            startMinute = blockMinute,
            durationMinutes = duration,
            stageRaw = currentStage.name,
        ))
        return blocks
    }

    /**
     * Sleep quality score (0-100) based on deep sleep ratio.
     * Medical research: optimal deep sleep = 15-25% of total.
     * Matches the official app's scoring.
     */
    private fun computeSleepScore(deepMin: Int, totalMin: Int): Int? {
        if (totalMin == 0) return null
        val deepPct = (deepMin.toFloat() / totalMin * 100).toInt()
        return when {
            deepPct >= 20 -> 90
            deepPct >= 15 -> 75
            deepPct >= 10 -> 60
            else -> 40
        }
    }

    private companion object {
        const val MAX_SLEEP_TIMELINE_MINUTES = 24 * 60
    }
}

internal fun historyMeasurementId(kind: MeasurementKind, timestamp: Long): String =
    "history:${kind.key}:$timestamp"

internal fun shouldReplaceCompleteSleep(
    existingStart: Long,
    existingMinutes: Int,
    incomingStart: Long,
    incomingMinutes: Int,
): Boolean = existingStart == incomingStart || incomingMinutes > existingMinutes

internal fun replaceOverlappingSleepBlocks(
    existing: List<SleepStageBlockEntity>,
    replacements: List<SleepStageBlockEntity>,
    replacementStart: Long,
    replacementEnd: Long,
): List<SleepStageBlockEntity> {
    val byStart = LinkedHashMap<Long, SleepStageBlockEntity>()
    for (block in existing) {
        val blockEnd = block.startAt + block.durationMinutes * 60_000L
        if (blockEnd <= replacementStart || block.startAt >= replacementEnd) {
            byStart[block.startAt] = block
            continue
        }
        if (block.startAt < replacementStart) {
            byStart[block.startAt] = block.copy(
                durationMinutes = ((replacementStart - block.startAt) / 60_000L).toInt(),
            )
        }
        if (blockEnd > replacementEnd) {
            byStart[replacementEnd] = block.copy(
                id = "${block.id}:$replacementEnd",
                startAt = replacementEnd,
                durationMinutes = ((blockEnd - replacementEnd) / 60_000L).toInt(),
            )
        }
    }
    for (block in replacements) byStart[block.startAt] = block
    return byStart.values.sortedBy { it.startAt }
}

// Extension for Set<WearableCapability> CSV from WearableCapability.kt
private fun Set<WearableCapability>.toCsv(): String =
    WearableCapability.entries.filter { it in this }.joinToString(",") { it.key }
