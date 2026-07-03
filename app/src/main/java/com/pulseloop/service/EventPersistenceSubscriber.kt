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
        } catch (e: Exception) {
            // Swallow individual persistence failures so one bad event
            // (malformed ring packet, DB constraint, etc.) never crashes the app.
            android.util.Log.e("EventPersistence", "Failed to persist event", e)
        }
    }

    private suspend fun persistUnsafe(event: PulseEvent) {
        when (event) {
            is PulseEvent.DeviceStateChanged -> {
                val device = db.deviceDao().current() ?: DeviceEntity()
                val state = when (event.state) {
                    RingConnectionState.CONNECTED -> {
                        db.measurementDao().clearDemo()
                        db.activityDailyDao().clearDemo()
                        // Demo rows only. This fires on every 0x0C status packet
                        // (~every 30-min background sync) — an unconditional clear()
                        // here was erasing ALL sleep history, of which only the last
                        // day ever got re-synced. Stage blocks cascade with sessions.
                        db.sleepSessionDao().clearDemo()
                        // New sync pass: the ring will replay the full 0x10 history
                        // stream, so restart the per-day bucket accumulators.
                        bucketDaySums.clear()
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
                    bleAddressHint = event.address ?: device.bleAddressHint,
                    // 0x0C device-info already gives the complete "<CID><DID>V<version>" string.
                    firmwareVersion = event.firmware ?: device.firmwareVersion,
                    lastConnectedAt = if (state == "CONNECTED") System.currentTimeMillis() else device.lastConnectedAt,
                    updatedAt = System.currentTimeMillis(),
                ))
            }
            is PulseEvent.DeviceIdentified -> {
                val device = db.deviceDao().current() ?: DeviceEntity()
                db.deviceDao().upsert(device.copy(
                    deviceTypeRaw = event.deviceType.name,
                    capabilitiesRaw = event.capabilities.toCsv(),
                    updatedAt = System.currentTimeMillis(),
                ))
            }
            is PulseEvent.BatteryLevel -> {
                val device = db.deviceDao().current() ?: DeviceEntity()
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
                // 0x10 history buckets are per-minute DELTAS, not cumulative totals.
                // Sum them per local day across this sync pass, and let
                // upsertActivityDaily's maxOf compare the running day-sum against the
                // stored total. A re-synced stream reproduces the same sum, so this is
                // idempotent — no double counting — while a bucket-only day (yesterday,
                // synced today) now gets its true total instead of its largest single
                // minute, which is what maxOf-per-bucket used to store.
                val ts = event.timestamp.toEpochMilli()
                val day = com.pulseloop.util.TimeUtil.startOfDayLocal(ts)
                val sums = bucketDaySums.getOrPut(day) { BucketSums() }
                sums.steps += event.steps
                sums.distanceM += event.distanceMeters
                upsertActivityDaily(ts, sums.steps, 0.0, sums.distanceM)
            }
            is PulseEvent.SleepTimeline -> {
                upsertSleepSession(event.timestamp.toEpochMilli(), event.stages)
            }
            is PulseEvent.SyncProgress -> {} // UI feedback, no persistence needed
            is PulseEvent.HeartRateComplete -> {}
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

    /** Running per-day sums for 0x10 history buckets within one sync pass. */
    private data class BucketSums(var steps: Int = 0, var distanceM: Double = 0.0)
    private val bucketDaySums = mutableMapOf<Long, BucketSums>()

    private suspend fun upsertActivityDaily(ts: Long, steps: Int, calories: Double, distanceM: Double) {
        // Key the daily row by local midnight so it matches the Today dashboard and
        // MetricsService/notifications, which all read per-day rows by local-day boundary.
        val dayStart = com.pulseloop.util.TimeUtil.startOfDayLocal(ts)
        val existing = db.activityDailyDao().byDay(dayStart)
        if (existing != null) {
            db.activityDailyDao().upsert(existing.copy(
                steps = maxOf(existing.steps, steps),
                calories = maxOf(existing.calories, calories),
                distanceMeters = maxOf(existing.distanceMeters, distanceM),
                updatedAt = System.currentTimeMillis(),
            ))
        } else {
            db.activityDailyDao().upsert(ActivityDailyEntity(
                date = dayStart, steps = steps, calories = calories,
                distanceMeters = distanceM, source = "ring",
            ))
        }
    }

    private suspend fun upsertSleepSession(ts: Long, stages: List<SleepStage>) {
        if (stages.isEmpty()) return
        // Local-day key so a night is attributed to the correct local date (and stitches
        // with same-night packets) rather than flipping at UTC midnight.
        val dayStart = com.pulseloop.util.TimeUtil.startOfDayLocal(ts)
        val sessionId = "sleep-$dayStart"

        // The ring streams a night as many 15-minute 0x11 packets that must be STITCHED,
        // not overwritten. Accumulate this packet's blocks with those already stored for the
        // night, de-duplicating by absolute start time so re-syncs don't double-count.
        // Matches the iOS reference (PulseEventBus.persistSleepTimeline).
        val byStart = LinkedHashMap<Long, SleepStageBlockEntity>()
        for (b in db.sleepStageBlockDao().forSession(sessionId)) byStart[b.startAt] = b
        for (b in buildStageBlocks(sessionId, ts, stages)) byStart.putIfAbsent(b.startAt, b)

        val merged = byStart.values.sortedBy { it.startAt }
        val sessionStart = merged.first().startAt
        val sessionEnd = merged.maxOf { it.startAt + it.durationMinutes * 60_000L }
        val totalMin = ((sessionEnd - sessionStart) / 60_000L).toInt().coerceAtLeast(0)
        val deepMin = merged.filter { it.stageRaw == SleepStage.DEEP.name }.sumOf { it.durationMinutes }
        val score = computeSleepScore(deepMin, totalMin)

        // Rewrite the full accumulated set with start minutes relative to the night start.
        db.sleepStageBlockDao().deleteBySession(sessionId)
        merged.forEach {
            db.sleepStageBlockDao().insert(it.copy(startMinute = ((it.startAt - sessionStart) / 60_000L).toInt()))
        }

        val existing = db.sleepSessionDao().byDay(dayStart)
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
