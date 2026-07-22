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

    // Battery-history throttle (iOS #61b) — in-memory, so the first reading after each (re)launch
    // always records; a change or a 30-min floor logs a fresh row otherwise, keeping the table to a
    // few dozen rows/day instead of one per BLE battery read.
    private var lastBatteryPercent: Int? = null
    private var lastBatteryLogAt: Long? = null
    private val batteryMinIntervalMs = 30 * 60_000L

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
                        if (preservesSleepOnConnect(event.deviceType, existing?.deviceType)) {
                            // YCBT status packets re-emit CONNECTED while history is still arriving.
                            db.sleepStageBlockDao().clearDemo()
                            db.sleepSessionDao().clearDemo()
                        } else {
                            // Packet-based families rebuild sleep on connect. Clear blocks with
                            // sessions so legacy midnight-keyed blocks cannot contaminate a rebuild.
                            db.sleepStageBlockDao().clear()
                            db.sleepSessionDao().clear()
                        }
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
                    lastSyncAt = if (state == "CONNECTED") System.currentTimeMillis() else device.lastSyncAt,
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
                // This event is queued after all earlier ring events, so deleting here prevents a
                // pre-forget CONNECTED event still in the bus from recreating the cleared row.
                db.deviceDao().currentReal()?.let { db.deviceDao().deleteById(it.id) }
            }
            is PulseEvent.BatteryLevel -> {
                val device = db.deviceDao().currentReal() ?: DeviceEntity()
                val now = System.currentTimeMillis()
                db.deviceDao().upsert(device.copy(
                    batteryPercent = event.percent,
                    updatedAt = now,
                ))
                recordBatterySample(event.percent, now)
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
                db.measurementDao().upsert(MeasurementEntity(
                    id = historyMeasurementId(event.kind, event.timestamp.toEpochMilli()),
                    kindRaw = event.kind.name,
                    value = event.value, unit = event.kind.unit,
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "history",
                ))
            }
            is PulseEvent.StressSample -> {
                val measurement = MeasurementEntity(
                    id = if (event.isHistory) {
                        historyMeasurementId(MeasurementKind.STRESS, event.timestamp.toEpochMilli())
                    } else {
                        java.util.UUID.randomUUID().toString()
                    },
                    kindRaw = MeasurementKind.STRESS.name,
                    value = event.value.toDouble(), unit = "",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "colmi",
                )
                if (event.isHistory) db.measurementDao().upsert(measurement)
                else db.measurementDao().insert(measurement)
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
                    if (event.isHistory) {
                        db.measurementDao().upsert(systolic)
                        db.measurementDao().upsert(diastolic)
                    } else {
                        db.measurementDao().insert(systolic)
                        db.measurementDao().insert(diastolic)
                    }
                }
            }
            is PulseEvent.BloodSugarSample -> {
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.BLOOD_SUGAR.name,
                    value = event.mgdl,
                    unit = MeasurementKind.BLOOD_SUGAR.unit,
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "live",
                ))
            }
            is PulseEvent.TemperatureSample -> {
                val measurement = MeasurementEntity(
                    id = if (event.isHistory) {
                        historyMeasurementId(MeasurementKind.TEMPERATURE, event.timestamp.toEpochMilli())
                    } else {
                        java.util.UUID.randomUUID().toString()
                    },
                    kindRaw = MeasurementKind.TEMPERATURE.name,
                    value = event.celsius, unit = "°C",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "live",
                )
                if (event.isHistory) db.measurementDao().upsert(measurement)
                else db.measurementDao().insert(measurement)
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
            is PulseEvent.SyncProgress -> {
                // Only "done" (a history sync actually completed) stamps lastFullSyncAt — the
                // coach-notification freshness gate (iOS #61c). Bare CONNECT already re-stamps
                // the looser lastSyncAt elsewhere and must not touch this one.
                if (event.stage == "done") {
                    val device = db.deviceDao().currentReal()
                    if (device != null) {
                        db.deviceDao().upsert(device.copy(lastFullSyncAt = System.currentTimeMillis()))
                    }
                    reconcileRecentlyFinishedWorkouts()
                }
            }
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

    /**
     * Post-workout vitals backfill (iOS #57e): a ring's own HR/SpO2 log often only reaches the
     * phone on the *next* sync — a reconnect right after finishing, or a delayed history flush —
     * so a session can finish before its window's ring-log samples have landed. Every completed
     * sync re-derives aggregates for sessions that finished within [ActivityAggregates.backfillWindowMillis],
     * picking up whatever now sits in `[startedAt, endedAt]`. [ActivityAggregates.recompute] never
     * touches [ActivityRollup] (minutes/distance credited once at finish), so re-running it here is
     * idempotent and safe to call on every sync, not just the first one after finish.
     */
    private suspend fun reconcileRecentlyFinishedWorkouts() {
        val cutoff = System.currentTimeMillis() - ActivityAggregates.backfillWindowMillis
        for (session in db.activitySessionDao().finishedSince(cutoff)) {
            db.activitySessionDao().upsert(ActivityAggregates.recompute(db, session))
        }
    }

    /** Throttled battery-history write (iOS #61b): only on change or a 30-min floor. */
    private suspend fun recordBatterySample(percent: Int, now: Long) {
        if (percent !in 0..100) return
        val changed = percent != lastBatteryPercent
        val elapsed = lastBatteryLogAt?.let { now - it } ?: Long.MAX_VALUE
        if (!changed && elapsed < batteryMinIntervalMs) return
        lastBatteryPercent = percent
        lastBatteryLogAt = now
        db.batterySampleDao().insert(BatterySampleEntity(percent = percent, timestamp = now))
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
        val packetEnd = ts + stages.size * 60_000L
        // Include legacy rows keyed to the wrong day if they overlap this packet. Reconciliation
        // re-points their surviving blocks to the correct waking day.
        val overlapping = db.sleepSessionDao().ringOverlapping(ts, packetEnd)
        val existing = (db.sleepSessionDao().ringAllByDay(dayStart) + overlapping).distinctBy { it.id }
        val existingBlocks =
            if (existing.isEmpty()) emptyList()
            else db.sleepStageBlockDao().forSessions(existing.map { it.id })

        // YCBT complete records are authoritative for their interval, including shortened
        // revisions. Packet-based families replace only the packet interval. In both cases the
        // unaffected blocks remain available for SleepSegmentation to preserve separate naps.
        val replacements = buildStageBlocks("", ts, stages)
        val dayBlocks = replaceOverlappingSleepBlocks(
            existing = if (completeSession) {
                val replacedSessionIds = overlapping.mapTo(mutableSetOf()) { it.id }
                existingBlocks.filterNot { it.sessionId in replacedSessionIds }
            } else {
                existingBlocks
            },
            replacements = replacements,
            replacementStart = ts,
            replacementEnd = packetEnd,
        )

        reconcileWakingDay(dayStart, existing, dayBlocks)
    }

    /**
     * Re-derive one waking day's sessions from its raw stage blocks: split by [SleepSegmentation],
     * then reconcile the Room rows — match each segment to the existing session whose prior
     * time-range overlaps it (so identity is stable even when a nap syncs before the night it
     * precedes), insert rows for unmatched segments, delete leftover empty rows, re-point every
     * block to its segment, and recompute bounds/score. Idempotent.
     *
     * Ported from iOS `SleepService.reconcileWakingDay`. Unlike iOS this emits no DerivedUpdateRow
     * change signal: Room's reactive Flows (SleepViewModel observes `recentFlow`) already refresh
     * the UI on any sleep-table write, and sleep is cleared + rebuilt from the ring on every connect
     * anyway — there is no unchanged-day re-sync to optimize away.
     *
     * Wrapped in one transaction: the body below deletes every existing row's blocks up front, then
     * re-upserts sessions and re-inserts blocks per segment one write at a time. Without a
     * transaction, each per-segment session upsert fires `recentFlow` mid-reconcile, and
     * SleepViewModel re-reads blocks for a session that's only partially rewritten — the sleep
     * architecture chart transiently renders with just the first few (chronologically-earliest)
     * blocks before the rest land. `db.withTransaction` defers the Flow's invalidation until commit,
     * so observers only ever see the fully-reconciled state (matches DemoDataSeeder's usage).
     */
    private suspend fun reconcileWakingDay(
        dayStart: Long,
        existing: List<SleepSessionEntity>,
        dayBlocks: List<SleepStageBlockEntity>,
    ) = db.withTransaction {
        val groups = SleepSegmentation.segment(dayBlocks)

        // No blocks left on this day — drop the empty rows entirely (their blocks cascade).
        if (groups.isEmpty()) {
            existing.forEach { db.sleepSessionDao().deleteById(it.id) }
            return@withTransaction
        }

        data class Segment(val blocks: List<SleepStageBlockEntity>, val start: Long, val end: Long)
        val segments = groups.map { g ->
            val sorted = g.sortedBy { it.startAt }
            val start = sorted.first().startAt
            val end = sorted.maxOf { it.startAt + it.durationMinutes * 60_000L }
            Segment(sorted, start, end)
        }

        fun overlap(a0: Long, a1: Long, b0: Long, b1: Long): Long =
            maxOf(0L, minOf(a1, b1) - maxOf(a0, b0))

        // Greedily match each segment to the best-overlapping unused row; a row also matches when it
        // contains the segment's start (covers a freshly-created zero-length container row). The
        // rows' pre-mutation bounds are the match key — `existing` is read before any write below.
        val available = existing.toMutableList()
        val matched: List<Pair<Segment, SleepSessionEntity?>> = segments.map { seg ->
            val best = available.maxByOrNull { overlap(seg.start, seg.end, it.startAt, it.endAt) }
            if (best != null && (overlap(seg.start, seg.end, best.startAt, best.endAt) > 0L ||
                    best.startAt in seg.start..seg.end)) {
                available.remove(best)
                seg to best
            } else {
                seg to null
            }
        }

        // Clear every existing row's blocks up front so re-pointing a block between sessions can't
        // leave a transient duplicate keyed to two sessions at once.
        existing.forEach { db.sleepStageBlockDao().deleteBySession(it.id) }

        val now = System.currentTimeMillis()
        for ((seg, row) in matched) {
            val id = row?.id ?: "sleep-$dayStart-${seg.start}"
            val totalMin = ((seg.end - seg.start) / 60_000L).toInt().coerceAtLeast(0)
            val deepMin = seg.blocks
                .filter { it.stageRaw == SleepStage.DEEP.name }
                .sumOf { it.durationMinutes }
            val score = computeSleepScore(deepMin, totalMin)

            // Parent session BEFORE its blocks (FK sleep_stage_blocks -> sleep_sessions.id).
            db.sleepSessionDao().upsert(
                (row ?: SleepSessionEntity(
                    id = id, date = dayStart, startAt = seg.start, endAt = seg.end,
                    totalMinutes = totalMin, score = score,
                )).copy(
                    date = dayStart,
                    startAt = seg.start,
                    endAt = seg.end,
                    totalMinutes = totalMin,
                    score = score,
                    syncedAt = now,
                    updatedAt = now,
                )
            )
            seg.blocks.forEach {
                db.sleepStageBlockDao().insert(
                    it.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        sessionId = id,
                        startMinute = ((it.startAt - seg.start) / 60_000L).toInt().coerceAtLeast(0),
                    )
                )
            }
        }

        // Rows not matched to any segment had all their blocks re-pointed away — delete them.
        available.forEach { db.sleepSessionDao().deleteById(it.id) }
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

internal fun preservesSleepOnConnect(
    eventDeviceType: RingDeviceType?,
    persistedDeviceType: RingDeviceType? = null,
): Boolean = when (eventDeviceType ?: persistedDeviceType) {
    // All three identifiers use YCBTDriver and share its repeated status packets plus async
    // history transfer. Packet-based Colmi/Jring/CRP families still clear and rebuild on connect.
    RingDeviceType.YCBT, RingDeviceType.TK5, RingDeviceType.COLMI_SMART_HEALTH -> true
    else -> false
}

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
