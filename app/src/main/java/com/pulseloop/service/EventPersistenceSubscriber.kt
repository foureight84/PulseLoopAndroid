package com.pulseloop.service

import android.content.Context
import androidx.room.withTransaction
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import com.pulseloop.health.HealthConnectExportWorker
import com.pulseloop.ring.*
import kotlinx.coroutines.*

/**
 * Ported from [EventPersistenceSubscriber] in PulseEventBus.swift.
 * Subscribes to PulseEventBus and persists ring data to Room.
 */
class EventPersistenceSubscriber(
    private val context: Context,
    private val db: PulseLoopDatabase,
    /**
     * Fired after a data-bearing event lands in Room (measurements, activity, sleep) — the
     * Android analog of iOS `PulseDataChange`. The widget snapshot publisher hooks this
     * (debounced) so home-screen widgets refresh after every ring-sync batch.
     */
    private val onDataPersisted: (() -> Unit)? = null,
) {
    /**
     * The kinds whose live samples are currently *not* stored, because a spot measurement is
     * settling them (issue #60). Driven by [PulseEvent.LiveSampleGate], which the coordinator
     * sends through the same bus as the samples, so this collector sees the gate close before the
     * samples it must drop and reopen before the settled reading it must keep — whatever its lag
     * behind the ring. Only touched from the collector, so it needs no lock.
     */
    private val closedGates = mutableSetOf<MeasurementKind>()

    /**
     * Timestamps of the spot readings this app stored itself, per kind — the rows a ring that
     * logs its own spot measurements will later re-supply from history (issue #60, RC-1: two HR
     * rows per measurement, same minute, values a few bpm apart). Loaded lazily from the table
     * and kept current here so the history path can answer "is this the ring's copy of one of
     * ours?" without a query per history sample. See [adoptRingsCopy].
     */
    private val spotReadings = mutableMapOf<MeasurementKind, MutableList<Long>>()

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

    /**
     * Write a measurement the ring can re-supply — unless the user deleted it (issue #60).
     *
     * Every caller here uses a deterministic `history:<kind>:<timestamp>` id so that re-syncing a
     * day the ring still holds updates the same row instead of duplicating it. That idempotence is
     * exactly what would undo a deletion, so the tombstone is checked on the way in. A reading with
     * no tombstone is written as before.
     */
    private suspend fun upsertUnlessDeleted(measurement: MeasurementEntity) {
        if (db.measurementDeletionDao().isDeleted(measurement.id)) return
        db.measurementDao().upsert(measurement)
    }

    /**
     * A live reading of [kind]: the ring's stream, or a spot measurement's settled value.
     *
     * [awaitingRingsCopy] marks the second case *on a ring that logs its own spot measurements*,
     * so a later history sync can recognise the ring's copy and replace ours ([adoptRingsCopy]).
     * A spot reading from a ring that does not log one is stored exactly as before — a plain live
     * row, with no pending reconciliation and nothing that could delete it.
     */
    private suspend fun storeLiveReading(
        kind: MeasurementKind,
        value: Double,
        unit: String,
        at: Long,
        awaitingRingsCopy: Boolean,
    ) {
        db.measurementDao().insert(MeasurementEntity(
            kindRaw = kind.name, value = value, unit = unit, timestamp = at,
            sourceRaw = if (awaitingRingsCopy) SOURCE_SPOT else "live",
        ))
        if (awaitingRingsCopy) spotReadingsOf(kind).add(at)
    }

    private suspend fun spotReadingsOf(kind: MeasurementKind): MutableList<Long> =
        spotReadings.getOrPut(kind) {
            db.measurementDao().timestampsBySource(kind.name, SOURCE_SPOT, System.currentTimeMillis() - SPOT_LOOKBACK_MS)
                .toMutableList()
        }

    /**
     * The ring's copy of a spot reading replaces ours (issue #60, confirmed on RC-2).
     *
     * The YCBT ring logs every spot measurement into its own history — verified by reading five
     * SpO₂ runs back out of the ring's memory at the exact times they were taken — and the vendor
     * app's whole reaction to a `04 0e` success is to re-sync that history: the ring decides the
     * number, the app re-reads it. So when a history sample of the same kind lands within
     * [SPOT_MATCH_MS] of a reading we stored for our own settled value, it is that measurement
     * seen from the ring's side, and keeping both is the doubled row the tester saw. The ring's
     * row wins because it is the one that regenerates on every sync (and so the one a tombstone
     * can hold down); ours is deleted outright.
     *
     * **Only rings that actually log spot readings take part.** A row is marked [SOURCE_SPOT] at
     * write time only when the ring reported its own completion, so this can never fire on a CRP
     * or Colmi ring — where the nearest history sample is an unrelated point on the five-minute
     * all-day grid and would land inside [SPOT_MATCH_MS] of most measurements.
     */
    private suspend fun adoptRingsCopy(kind: MeasurementKind, historyAt: Long) {
        if (kind != MeasurementKind.HEART_RATE && kind != MeasurementKind.SPO2) return
        val ours = spotReadingsOf(kind)
        if (ours.isEmpty()) return
        val matched = spotReadingsMatching(ours, historyAt)
        if (matched.isEmpty()) return
        db.measurementDao().deleteBySourceBetween(kind.name, SOURCE_SPOT, historyAt - SPOT_MATCH_MS, historyAt + SPOT_MATCH_MS)
        ours.removeAll(matched)
    }

    private suspend fun persistUnsafe(event: PulseEvent) {
        when (event) {
            // A start/end verdict for one spot measurement (issue #59) — a control signal for the
            // coordinator's poll loop, carrying no value of its own. Nothing to store.
            is PulseEvent.MeasurementComplete -> Unit
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
                        // Connecting deletes NOTHING — not stored history, not demo rows. Demo
                        // data is retired only from Settings → Privacy & Data, matching iOS, which
                        // has no connect-time purge at all and instead *detects* seeded rows
                        // (`isDemo`, `source == "mock"`, `DataFreshness.demo`) and adapts the UI.
                        // Demo data coexisting with a paired ring is an expected state, not a mess
                        // to clean up.
                        //
                        // The `when` below is the guard, and it is exhaustive on purpose — see
                        // [ConnectPurge]. It reads as dead code and is not: it is what makes
                        // re-introducing a connect-time delete a compile error rather than a
                        // one-line edit.
                        //
                        // History, because this keeps getting re-added: connect used to clear *all*
                        // sleep for every family except YCBT and rebuild it from the ring. No ring
                        // re-supplies more than its own buffer, and the two smallest re-supply a
                        // single day — CRP sends `queryHistorySleep(daysAgo=0)`, jring
                        // `makeHistoryQueryCommand()` with its 1-day default (JringDriver.kt:105,
                        // NOT `syncWindowDays`, which only sizes the progress bar) — so every
                        // connect destroyed each night older than that, and a new night replaced
                        // the last one instead of joining it (issue #43, zaggash's R11). The
                        // rebuild was never load-bearing: [upsertSleepSessionAtomic] reconciles one
                        // waking day at a time, idempotently, and re-points legacy mis-keyed blocks
                        // itself — which was the blanket clear's stated reason for existing. The
                        // demo purge was the last surviving fragment of that same model.
                        when (connectPurge(event.deviceType)) {
                            ConnectPurge.NOTHING -> {}
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
            is PulseEvent.LiveSampleGate -> {
                if (event.closed) closedGates.add(event.kind) else closedGates.remove(event.kind)
            }
            is PulseEvent.HeartRateSample -> {
                if (!event.spot && MeasurementKind.HEART_RATE in closedGates) return
                storeLiveReading(
                    MeasurementKind.HEART_RATE, event.bpm.toDouble(), "bpm",
                    event.timestamp.toEpochMilli(), awaitsRingsCopy(event.spot, event.ringWillLogIt),
                )
            }
            is PulseEvent.Spo2Result -> {
                if (!event.spot && MeasurementKind.SPO2 in closedGates) return
                storeLiveReading(
                    MeasurementKind.SPO2, event.value.toDouble(), "%",
                    event.timestamp.toEpochMilli(), awaitsRingsCopy(event.spot, event.ringWillLogIt),
                )
            }
            is PulseEvent.HistoryMeasurement -> {
                val at = event.timestamp.toEpochMilli()
                upsertUnlessDeleted(MeasurementEntity(
                    id = historyMeasurementId(event.kind, at),
                    kindRaw = event.kind.name,
                    value = event.value, unit = event.kind.unit,
                    timestamp = at,
                    sourceRaw = "history",
                ))
                adoptRingsCopy(event.kind, at)
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
                if (event.isHistory) upsertUnlessDeleted(measurement)
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
                        upsertUnlessDeleted(systolic)
                        upsertUnlessDeleted(diastolic)
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
                if (event.isHistory) upsertUnlessDeleted(measurement)
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
                if (event.stage == "done") {
                    val device = db.deviceDao().currentReal()
                    if (device != null) {
                        db.deviceDao().upsert(device.copy(lastFullSyncAt = System.currentTimeMillis()))
                    }
                    reconcileRecentlyFinishedWorkouts()
                    recomputeCalorieEstimates()
                    // iOS #95: re-learn the resting-HR baseline that drives the "auto" HR zone
                    // mode. Self-throttled to every 6h, so calling it on every completed sync is
                    // cheap.
                    RestingHRBaselineService.refreshIfStale(db)
                    // Health Connect export trigger (Phase 1): a completed history sync is the
                    // moment new measurements can have landed. Debounced 15 s + REPLACE in the
                    // worker, so this is cheap to fire on every done.
                    HealthConnectExportWorker.enqueue(context)
                }
            }
            is PulseEvent.HeartRateComplete -> {}
            is PulseEvent.Spo2Complete -> {}
            is PulseEvent.MeasurementRejected -> {} // Product orchestration only; no persistence.
            is PulseEvent.WearState -> {} // Product orchestration only (fast-fail a measure); not persisted.
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
            is PulseEvent.FirmwareRevision -> {
                // Stamp the version onto an existing row only — never create one, so a late reply
                // can't resurrect a forgotten ring (same rule as DeviceStateChanged above).
                //
                // The firmware string also does NOT travel as DeviceStateChanged: that event means
                // the *connection state* changed, which a device-info reply doesn't, and
                // CRPSyncEngine.runStartup re-queries firmware on every pass — including the
                // ~30-minute background sync — so it would restate CONNECTED all session long.
                val device = db.deviceDao().currentReal() ?: return
                if (event.version == device.firmwareVersion) return  // blank is gated in the bridge
                db.deviceDao().upsert(device.copy(
                    firmwareVersion = event.version,
                    updatedAt = System.currentTimeMillis(),
                ))
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

        // YCBT complete records are authoritative for the ring session they describe, including
        // shortened revisions. Packet-based families replace only the packet interval. In both
        // cases the unaffected blocks remain available for SleepSegmentation to preserve
        // separate naps — and, for a complete record, the *other* ring sessions of the same night
        // (issue #63): see [completeSessionSurvivors] for why "the session it describes" is a
        // contiguous run of blocks and not every block of every row the packet touches.
        val replacements = buildStageBlocks("", ts, stages)
        val dayBlocks = replaceOverlappingSleepBlocks(
            existing = if (completeSession) {
                completeSessionSurvivors(existingBlocks, ts, packetEnd)
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
     * the UI on any sleep-table write.
     *
     * This function's idempotence is now what protects stored history. Connecting no longer clears
     * the sleep tables (issue #43), so a re-synced day arrives on top of rows that are already
     * there and has to reconcile with them rather than repopulate an emptied table.
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

    // ── Calorie estimation recompute (iOS #98) ───────────────────────────────────

    private suspend fun recomputeCalorieEstimates() {
        val profile = db.userProfileDao().get() ?: return
        val estimator = DailyCalorieEstimator.Profile(
            sex = profile.sex, age = profile.age,
            weightKg = profile.weightKg, heightCm = profile.heightCm,
        )
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        for (d in 0 until 7) {
            val dayStart = com.pulseloop.util.TimeUtil.startOfDayLocal(now - d * dayMs)
            DailyCalorieEstimator.recompute(dayStart, db, estimator)
        }
    }

    companion object {
        /** `sourceRaw` of a spot measurement's settled reading **on a ring that will also log it
         *  itself** (issue #60) — i.e. a row still awaiting reconciliation with the ring's copy.
         *  Reads alongside `"live"` everywhere a source is filtered; nothing treats the two apart
         *  except [adoptRingsCopy]. */
        const val SOURCE_SPOT = "spot"
        /** How far a ring-history sample may sit from one of our spot readings and still be the
         *  ring's copy of it. The measurement itself runs 35–63 s and the ring stamps its log to
         *  the minute, so 90 s covers a stamp at either end of the run without reaching the ring's
         *  own all-day samples five minutes apart. */
        const val SPOT_MATCH_MS = 90_000L
        /** How far back [spotReadings] is primed from the table on first use. */
        const val SPOT_LOOKBACK_MS = 7L * 24 * 60 * 60_000
        private const val MAX_SLEEP_TIMELINE_MINUTES = 24 * 60
    }
}

/**
 * Is this reading one the ring will hand back from its own history, so that the two copies have to
 * be reconciled later ([EventPersistenceSubscriber.adoptRingsCopy])? Both halves are required:
 * a streamed sample is not a spot reading, and a spot reading from a ring that keeps no log of it
 * has no second copy coming. Getting the second half wrong is not cosmetic — on a CRP or Colmi
 * ring the nearest history sample is an unrelated point on the five-minute all-day grid, so the
 * reconciliation would delete readings the user deliberately took (issue #60).
 */
internal fun awaitsRingsCopy(spot: Boolean, ringWillLogIt: Boolean): Boolean = spot && ringWillLogIt

/**
 * Which of our spot readings ([ours], epoch millis) a ring-history sample stamped [historyAt] is
 * the ring's own copy of — the pure half of [EventPersistenceSubscriber.adoptRingsCopy]
 * (issue #60): within [window] either side, and nothing else.
 */
internal fun spotReadingsMatching(
    ours: List<Long>,
    historyAt: Long,
    window: Long = EventPersistenceSubscriber.SPOT_MATCH_MS,
): List<Long> = ours.filter { kotlin.math.abs(it - historyAt) <= window }

internal fun historyMeasurementId(kind: MeasurementKind, timestamp: Long): String =
    "history:${kind.key}:$timestamp"

/**
 * True when a `DeviceStateChanged(CONNECTED, …)` is a real connection transition rather than a
 * mid-session re-assertion.
 *
 * **No production caller.** It gated the connect-time demo purge; with that purge gone a connect
 * does nothing worth gating, so this is currently exercised only by `EventPersistenceIdentityTest`.
 * It is kept because the distinction it draws is real, non-obvious, and re-derived wrongly every
 * time someone needs it — any future connect-time action wants exactly this gate.
 *
 * Exactly two things publish a CONNECTED event, and `deviceType` separates them cleanly:
 *  - `RingBLEClient`'s own connect always passes `deviceType = activeCoordinator.deviceType`, which
 *    is non-null by then (`installDriver` ran before the CCCD write that gates CONNECTED).
 *  - `RingEventBridge` maps every decoder's `RingDecodedEvent.Status` to CONNECTED and never sets
 *    `deviceType`. Those are ordinary device-info replies — jring `0x0C`, LuckRing dev-info, YCBT
 *    status packets — re-sent by `runStartup` on every sync pass, not connection events.
 *
 * `preservesSleepOnConnect` used to live beside this, carving YCBT out of a clear-and-rebuild that
 * every other family ran on connect. Both the carve-out and the rebuild are gone (issue #43): no
 * ring re-supplies more sleep than its own buffer holds, so "delete everything and ask again"
 * capped stored history at whatever the ring still had — one day, on CRP and jring.
 */
internal fun isConnectTransition(eventDeviceType: RingDeviceType?): Boolean = eventDeviceType != null

/**
 * What a CONNECTED event is allowed to remove: **nothing**.
 *
 * The enum has exactly one member, and that is the point. Connecting must never delete stored
 * history (issue #43) and — since the iOS-parity fix — must not delete demo rows either. Encoding
 * that as a *type* rather than a comment means widening what a connect may delete cannot be done
 * by editing one line: it needs a new member here, which fails to compile against the exhaustive
 * `when`s in [EventPersistenceSubscriber] and in `EventPersistenceIdentityTest`. That is the
 * tripwire the deleted `preservesSleepOnConnect` boolean never had — it made destructive clearing
 * a per-family *option*, and every family took it but one.
 *
 * Its limit, stated plainly so nobody trusts it further than it goes: it forces a compile error
 * only for an author who routes the new deletion through this enum. A bare
 * `db.measurementDao().clearDemo()` dropped straight into the CONNECTED arm still compiles. The
 * type documents and channels the invariant; the tests below it are what actually enforce it.
 *
 * A `DEMO_ROWS` member used to live here. It was the last fragment of the old "connect = clear
 * everything and rebuild from the ring" design, and it fired on every reconnect — measured at
 * roughly one every five minutes on a COLMI R10 — so seeded demo data could not survive alongside
 * a paired ring for more than a few minutes. iOS never had a connect-time purge at all, so this
 * now matches it.
 */
internal enum class ConnectPurge { NOTHING }

/**
 * Always [ConnectPurge.NOTHING].
 *
 * Kept as a function rather than inlined so the guard has one named place to sit, and so the
 * exhaustive `when`s that enforce it have something to switch on. The result does not depend on
 * [eventDeviceType] — nothing is deleted for any family — but the parameter is retained so that a
 * future connect-time action has the device in hand and reaches for [isConnectTransition] rather
 * than firing on decoder status echoes as well as real connects.
 */
@Suppress("UNUSED_PARAMETER")
internal fun connectPurge(eventDeviceType: RingDeviceType?): ConnectPurge = ConnectPurge.NOTHING

internal fun shouldReplaceCompleteSleep(
    existingStart: Long,
    existingMinutes: Int,
    incomingStart: Long,
    incomingMinutes: Int,
): Boolean = existingStart == incomingStart || incomingMinutes > existingMinutes

/**
 * The stored stage blocks a *complete* ring session leaves standing (issue #63).
 *
 * A YCBT ring closes a sleep session when it sees the wearer get up and opens a new one when they
 * settle again, so one night can be two `af fa` records three minutes apart. SleepSegmentation
 * (60-minute gap) rightly merges those into one stored row. The old rule then treated a complete
 * record as authoritative for **every block of every row it overlapped** — so on the next sync
 * pass, when the second record arrived on top of the merged row, it wiped the first record's
 * three hours along with its own stale copy, and the night read 4 h 06 instead of 6 h 08. The
 * vendor app keeps every record as its own session and never lets one displace another.
 *
 * What a complete record *is* authoritative for is the ring session it describes, which in the
 * stored blocks is the contiguous run the packet's interval sits in: blocks that overlap the
 * interval, and any block that abuts that run end-to-start without a gap, in either direction.
 * That still lets a shortened revision retire its own stale head or tail (those abut the new
 * interval), while a neighbouring session on the far side of even a one-minute gap is untouched.
 * Blocks overlapping the interval itself are dropped here as well; the caller's interval replace
 * would only trim them, and a complete record has no use for what it trimmed off.
 */
internal fun completeSessionSurvivors(
    existing: List<SleepStageBlockEntity>,
    replacementStart: Long,
    replacementEnd: Long,
): List<SleepStageBlockEntity> {
    fun end(block: SleepStageBlockEntity) = block.startAt + block.durationMinutes * 60_000L
    var runStart = replacementStart
    var runEnd = replacementEnd
    val retired = mutableSetOf<String>()
    var grew = true
    while (grew) {
        grew = false
        for (block in existing) {
            if (block.id in retired) continue
            val overlaps = block.startAt < runEnd && end(block) > runStart
            val abuts = block.startAt == runEnd || end(block) == runStart
            if (overlaps || abuts) {
                retired.add(block.id)
                runStart = minOf(runStart, block.startAt)
                runEnd = maxOf(runEnd, end(block))
                grew = true
            }
        }
    }
    return existing.filterNot { it.id in retired }
}

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
