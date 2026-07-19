package com.pulseloop.service

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

    /** Identity of a history sample within one sync run — see [isDuplicateHistory]. */
    private data class HistoryKey(val kind: String, val epochSecond: Long)
    /** History samples already seen this sync. Cleared when a sync completes, so it can't grow
     *  unbounded across a long-lived session. */
    private val seenHistoryKeys = mutableSetOf<HistoryKey>()

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
                        // Sessions rebuild from the ring on every connect. Blocks must be wiped
                        // WITH them (no FK cascade at runtime): stale blocks keyed under an id a
                        // rebuilt session reuses — e.g. rows from the pre-waking-day keying, which
                        // filed a night's pre-midnight packets under what is now a different
                        // night's id — would otherwise be merged into that night by
                        // upsertSleepSession and corrupt its span and score.
                        db.sleepStageBlockDao().clear()
                        db.sleepSessionDao().clear()
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
                // History rows are upserted on (kind, timestamp): a ring replays the same log
                // every time we re-request a day, and the epochs it stamps are deterministic, so
                // an exact-timestamp match is a valid identity.
                if (isDuplicateHistory(event.kind.name, event.value, event.timestamp.toEpochMilli())) return
                db.measurementDao().insert(MeasurementEntity(
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
                    sourceRaw = "colmi",
                ))
            }
            is PulseEvent.TemperatureSample -> {
                db.measurementDao().insert(MeasurementEntity(
                    kindRaw = MeasurementKind.TEMPERATURE.name,
                    value = event.celsius, unit = "°C",
                    timestamp = event.timestamp.toEpochMilli(),
                    sourceRaw = "colmi",
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
                upsertSleepSession(event.timestamp.toEpochMilli(), event.stages)
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
                    // The rows are committed by now; the next sync re-checks against the database.
                    seenHistoryKeys.clear()
                }
            }
            is PulseEvent.HeartRateComplete -> {}
            is PulseEvent.Spo2Complete -> {}
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

    /** True when this history sample is already stored (updating the existing row's value in
     *  place). Two tiers: an in-process key set keeps the hot sync path off the database
     *  entirely, and a single indexed fetch catches re-syncs across launches. */
    private suspend fun isDuplicateHistory(kind: String, value: Double, timestampMs: Long): Boolean {
        val key = HistoryKey(kind, timestampMs / 1000)
        if (key in seenHistoryKeys) return true
        seenHistoryKeys += key

        val existing = db.measurementDao().findHistoryAt(kind, timestampMs) ?: return false
        // Same slot, possibly refined value (the ring can revise an averaged block).
        db.measurementDao().updateValue(existing.id, value)
        return true
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

    private suspend fun upsertSleepSession(ts: Long, stages: List<SleepStage>) {
        if (stages.isEmpty()) return
        // Group packets by the waking-day boundary (sleep from 7 PM rolls to the next morning) so
        // a night that starts before midnight lands under the morning of waking instead of being
        // split into two sessions at midnight. Matches the iOS reference
        // (PulseEventBus.persistSleepTimeline + Calendar.wakingDay(forSleepStart:)).
        val dayStart = com.pulseloop.util.TimeUtil.wakingDayLocal(ts)

        // The ring streams sleep as many 15-minute packets (a night in 0x39, daytime naps in 0x3E)
        // that must be STITCHED, not overwritten. Gather every block already stored across the
        // day's sessions, add this packet's, de-dup by absolute start time, then re-split the whole
        // set into distinct sessions (main night vs. naps separated by a >= 60 min gap) via
        // SleepSegmentation. Matches iOS reconcileWakingDay (PR #83).
        val existing = db.sleepSessionDao().ringAllByDay(dayStart)
        val existingBlocks =
            if (existing.isEmpty()) emptyList()
            else db.sleepStageBlockDao().forSessions(existing.map { it.id })

        val byStart = LinkedHashMap<Long, SleepStageBlockEntity>()
        for (b in existingBlocks) byStart[b.startAt] = b
        // buildStageBlocks needs a sessionId, but reconcile re-points every block, so a placeholder
        // is fine — only startAt/durationMinutes/stageRaw survive the re-point.
        for (b in buildStageBlocks("", ts, stages)) byStart.putIfAbsent(b.startAt, b)
        val dayBlocks = byStart.values.sortedBy { it.startAt }

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
     */
    private suspend fun reconcileWakingDay(
        dayStart: Long,
        existing: List<SleepSessionEntity>,
        dayBlocks: List<SleepStageBlockEntity>,
    ) {
        val groups = SleepSegmentation.segment(dayBlocks)

        // No blocks left on this day — drop the empty rows entirely (their blocks cascade).
        if (groups.isEmpty()) {
            existing.forEach { db.sleepSessionDao().deleteById(it.id) }
            return
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
}

// Extension for Set<WearableCapability> CSV from WearableCapability.kt
private fun Set<WearableCapability>.toCsv(): String =
    WearableCapability.entries.filter { it in this }.joinToString(",") { it.key }
