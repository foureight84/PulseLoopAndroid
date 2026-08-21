package com.pulseloop.health.exporters

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.health.HealthConnectTypeMappings.ACT_DIST
import com.pulseloop.health.HealthConnectTypeMappings.ACT_ENERGY
import com.pulseloop.health.HealthConnectTypeMappings.ACT_STEPS
import com.pulseloop.health.HealthConnectTypeMappings.EXCLUDED_SOURCES
import com.pulseloop.health.HealthConnectTypeMappings.NettableSession
import com.pulseloop.health.HealthConnectTypeMappings.WorkoutNetting
import com.pulseloop.util.TimeUtil
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the Phase 3 daily-activity records (docs/health-connect-integration.md Phase 3): one
 * [StepsRecord], one [ActiveCaloriesBurnedRecord] and one [DistanceRecord] per
 * [ActivityDailyEntity], each spanning the local day — `startOfDay … min(endOfDay, now)`, so
 * today's record never ends in the future.
 *
 * Identity: `clientRecordId = pl-act-<metric>-<dayEpochMs>` with `clientRecordVersion =
 * row.updatedAt`, so a day that gains steps through the afternoon re-upserts the *same* three
 * records rather than accumulating one per sync (plan §3). `ActivityDailyEntity.date` is already
 * the local start-of-day in epoch millis and is uniquely indexed, so — unlike sleep — there is no
 * multi-row-per-day suffix problem here.
 *
 * Workout netting ([HealthConnectTypeMappings.workoutNetting], ported from iOS
 * `HealthSyncService.swift:315-331`): finished-workout energy and GPS distance are subtracted from
 * the day's totals so that a Health Connect consumer adding the daily aggregate to Phase 4's
 * per-workout records does not count the same effort twice. The netting is gated on the workouts
 * toggle by the caller, exactly as iOS gates it on `exportWorkouts` — if workouts are not being
 * exported there is nothing to double-count and the full day total is written.
 *
 * Pure DB → records: no client, no inserts — [com.pulseloop.health.HealthConnectExporter] owns the
 * write path and decides which [metrics] are both toggled on and permission-granted.
 */
class ActivityExporter(private val db: PulseLoopDatabase) {

    /**
     * Pending activity records with the parallel [highWaters] list: entry i is the source row's
     * `updatedAt` that record i represents, so the exporter can advance the activity watermark
     * only to a value whose days all reached Health Connect. [skippedDays] counts days selected by
     * the watermark that produced no record at all (every metric zero, netted to zero, implausible,
     * or the day lies entirely in the future).
     */
    data class PendingActivity(
        val records: List<Record>,
        val highWaters: List<Long>,
        val skippedDays: Int,
        /**
         * Max `updatedAt` among days that were selected but produced no record *and can never
         * produce one from their current contents* — every metric zero, netted away, or
         * implausible. Without it such a day sitting above every exportable one pins the ACTIVITY
         * watermark and each later pass re-selects and re-upserts the whole tail behind it. Safe
         * to advance past: any change to the day bumps `updatedAt`, which re-selects it wherever
         * the watermark stands. A FUTURE-dated day is deliberately excluded — it is not yet
         * exportable rather than never exportable, and advancing past it would lose it once the
         * clock catches up. Applied only on a fully completed pass — see
         * [com.pulseloop.health.watermarkAdvance].
         */
        val droppedHighWater: Long? = null,
    )

    /**
     * Builds the pending records. Rows with `updatedAt <= [watermark]` were already exported;
     * `null` means export everything (first-enable backfill). [metrics] is the subset of
     * [ACT_STEPS] / [ACT_ENERGY] / [ACT_DIST] the caller has cleared for writing; [netWorkouts]
     * mirrors iOS's `exportWorkouts` gate on netting; [nowMs] clamps today's end.
     */
    suspend fun build(
        watermark: Long?,
        device: Device,
        metrics: Set<String>,
        netWorkouts: Boolean,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PendingActivity {
        if (metrics.isEmpty()) return PendingActivity(emptyList(), emptyList(), 0)
        val wm = watermark ?: 0L
        val selected = db.activityDailyDao().updatedSince(wm)
        val rows = selected.filter { it.source !in EXCLUDED_SOURCES }
        // Demo/mock rows are permanently unexportable (a row's source never changes), so they
        // count as drops — otherwise a seeded demo day newer than every real one pins the
        // watermark forever.
        val excludedHigh: Long? = selected.filter { it.source in EXCLUDED_SOURCES }
            .maxOfOrNull { it.updatedAt }
        if (rows.isEmpty()) return PendingActivity(emptyList(), emptyList(), 0, excludedHigh)

        val netting = if (netWorkouts) loadNetting(rows, zone) else WorkoutNetting.EMPTY

        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()
        var skipped = 0
        var droppedHigh: Long? = excludedHigh

        for (row in rows) {
            // Re-normalize rather than trusting the stored value (iOS does the same:
            // `cal.startOfDay(for: row.date)`, HealthSyncService.swift:270). `date` is local
            // midnight *in the zone it was written in*, so after a timezone change a day can be
            // stored at an offset midnight; keying the record on that would emit a second,
            // overlapping record for the same calendar day — and Health Connect sums an app's own
            // overlapping records rather than de-duplicating them. Normalizing also makes these
            // keys align by construction with loadNetting's, which are built from `startedAt`.
            val dayStart = TimeUtil.startOfDayLocal(row.date, zone)
            // Clamp so "today" never ends in the future; a row dated ahead of now yields nothing.
            val endMs = HealthConnectTypeMappings.activityDayEndMs(dayStart, nowMs, zone)
            if (endMs == null) {
                skipped++
                // NOT a drop: a future-dated day becomes exportable once now catches up.
                continue
            }
            val start = Instant.ofEpochMilli(dayStart)
            val end = Instant.ofEpochMilli(endMs)
            val startOffset = HealthConnectTypeMappings.zoneOffsetAt(start, zone)
            val endOffset = HealthConnectTypeMappings.zoneOffsetAt(end, zone)
            val before = records.size

            if (ACT_STEPS in metrics) {
                // Steps are not netted: a workout's steps are the ring's steps — there is no
                // separate step record per workout for them to double against (Phase 4 writes
                // energy and distance siblings only).
                val steps = row.steps.toLong()
                if (HealthConnectTypeMappings.isPlausibleSteps(steps)) {
                    records += StepsRecord(
                        start,
                        startOffset,
                        end,
                        endOffset,
                        steps,
                        metadata(device, ACT_STEPS, dayStart, row.updatedAt),
                    )
                    highWaters += row.updatedAt
                }
            }

            if (ACT_ENERGY in metrics) {
                val leftover = HealthConnectTypeMappings.activityLeftover(row.calories, netting.kcal(dayStart))
                if (HealthConnectTypeMappings.isPlausibleActiveCalories(leftover)) {
                    records += ActiveCaloriesBurnedRecord(
                        start,
                        startOffset,
                        end,
                        endOffset,
                        Energy.kilocalories(leftover),
                        metadata(device, ACT_ENERGY, dayStart, row.updatedAt),
                    )
                    highWaters += row.updatedAt
                }
            }

            if (ACT_DIST in metrics) {
                val leftover = HealthConnectTypeMappings.activityLeftover(row.distanceMeters, netting.meters(dayStart))
                if (HealthConnectTypeMappings.isPlausibleDistanceMeters(leftover)) {
                    records += DistanceRecord(
                        start,
                        startOffset,
                        end,
                        endOffset,
                        Length.meters(leftover),
                        metadata(device, ACT_DIST, dayStart, row.updatedAt),
                    )
                    highWaters += row.updatedAt
                }
            }

            if (records.size == before) {
                skipped++
                droppedHigh = maxOf(droppedHigh ?: Long.MIN_VALUE, row.updatedAt)
            }
        }
        return PendingActivity(records, highWaters, skipped, droppedHigh)
    }

    private fun metadata(device: Device, metric: String, dayStart: Long, updatedAt: Long): Metadata =
        Metadata.autoRecorded(
            device,
            HealthConnectTypeMappings.activityRecordId(metric, dayStart),
            updatedAt,
        )

    /**
     * The finished workouts overlapping the pending days, reduced to netting inputs. One query
     * spans the whole pending range rather than one per day — a first-enable backfill can select
     * years of rows.
     */
    private suspend fun loadNetting(rows: List<ActivityDailyEntity>, zone: ZoneId): WorkoutNetting {
        // Bound the query on the NORMALIZED day starts (startOfDayLocal), not the raw stored date:
        // after a timezone change startOfDayLocal(date) can be up to a day earlier than date, and a
        // session starting in that [normalizedDayStart, date) window maps to the same netting day
        // key as the daily row — so a raw `from` would exclude it and its energy/distance would not
        // net out, letting a consumer double-count the workout against the daily aggregate.
        val from = rows.minOf { TimeUtil.startOfDayLocal(it.date, zone) }
        val to = Instant.ofEpochMilli(rows.maxOf { TimeUtil.startOfDayLocal(it.date, zone) })
            .atZone(zone).plusDays(1).toInstant().toEpochMilli()
        val sessions = db.activitySessionDao().finishedStartedBetween(from, to)
        if (sessions.isEmpty()) return WorkoutNetting.EMPTY
        return HealthConnectTypeMappings.workoutNetting(
            sessions.map {
                NettableSession(
                    dayStartMs = TimeUtil.startOfDayLocal(it.startedAt, zone),
                    calories = it.calories,
                    distanceMeters = it.distanceMeters,
                    useGps = it.useGps,
                    // Duration inputs for the credit-eligibility check (workoutNetting skips the
                    // same sub-minute sessions ActivityRollup.credit never credited).
                    startedAtMs = it.startedAt,
                    endedAtMs = it.endedAt,
                    totalPauseSeconds = it.totalPauseSeconds,
                )
            },
        )
    }
}
