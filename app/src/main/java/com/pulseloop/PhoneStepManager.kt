package com.pulseloop

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.util.TimeUtil
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * Reads today's step total from Health Connect and publishes it on the bus.
 *
 * Why Health Connect instead of the raw TYPE_STEP_COUNTER hardware sensor?
 *   - The hardware sensor reports cumulative steps since the phone's last reboot, not
 *     steps today. There is no way to recover "how many steps before midnight" from it.
 *   - Health Connect aggregates step records per-day, handles reboots, and always has
 *     the correct "today" total — even for hours when PulseLoop was closed.
 *   - Health Connect is fully on-device (Android system service), so reading from it
 *     does not compromise the app's privacy-first design. No data leaves the phone.
 *
 * This class has no long-lived listeners or timers — it queries on demand, when the
 * app comes to the foreground. Zero battery cost while the app is closed.
 *
 * Only `steps` is published. Distance and calories are not the phone reader's job:
 * calories come from [com.pulseloop.service.DailyCalorieEstimator], and workout
 * distance from [com.pulseloop.service.ActivityRollup]. Baking either into the event
 * would overwrite those paths on write.
 *
 * **Every read subtracts PulseLoop's own origin.** PulseLoop exports its own daily step
 * totals to Health Connect (`ActivityExporter`), so a naive aggregate would include our
 * own earlier exports — the ring's daily total fed back to the phone path, or the
 * phone's own total plus the ring's. Both reads therefore run twice: once with no origin
 * filter (all origins), once filtered to PulseLoop's own package, and the second is
 * subtracted from the first. `ActivityExporter` also skips `source == "phone"` rows,
 * which stops the loop from growing; this read-side subtraction cleans up the exports
 * that already exist.
 */
class PhoneStepManager(private val context: Context) {

    /**
     * PulseLoop's own origin, as Health Connect identifies it. Passed as the
     * `dataOriginFilter` for the "own contribution" aggregate that gets subtracted from
     * the unfiltered one — see the class KDoc.
     */
    private val ownOrigin: Set<DataOrigin> = setOf(DataOrigin(context.packageName))

    /**
     * Query Health Connect for today's step total and publish it as a
     * [PulseEvent.PhoneStepsUpdate]. Returns true when the query succeeded, false when
     * Health Connect is unavailable or the read permission has not been granted yet.
     */
    suspend fun refresh(): Boolean {
        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (_: Exception) {
            return false
        }

        val readStepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            return false
        }
        if (readStepsPermission !in granted) return false

        val now = Instant.now()
        val startOfDayMs = TimeUtil.startOfDayLocal(System.currentTimeMillis())
        val startOfDay = Instant.ofEpochMilli(startOfDayMs)

        // Two aggregates and a subtraction: all origins minus PulseLoop's own contribution.
        // See the class KDoc for why. The result is floored at 0 because a momentarily
        // inconsistent pairing of the two calls could otherwise produce a negative step
        // count — not a value we would ever want to publish.
        val allSteps = aggregateToday(client, startOfDay, now, dataOriginFilter = emptySet())
            ?: return false
        val ownSteps = aggregateToday(client, startOfDay, now, dataOriginFilter = ownOrigin)
            ?: return false
        val steps = (allSteps - ownSteps).coerceAtLeast(0)

        // Just after midnight, or on a phone with no step writer in Health Connect, the
        // aggregate returns null and becomes 0. Publishing 0 would clobber the ring's
        // only valid record for the day and mark the row source = "phone", which then
        // also blocks the ring's bucket-derived total for today. Skip the publish and
        // let the ring's number stand until the phone has actually counted some steps.
        // The query itself succeeded, so still return true.
        if (steps <= 0) return true
        PulseEventBus.publishBlocking(
            PulseEvent.PhoneStepsUpdate(
                timestamp = now,
                steps = steps,
            )
        )
        return true
    }

    /**
     * One aggregate over today's `StepsRecord`s, filtered to [dataOriginFilter] when
     * non-empty. Returns null on any client error so the caller can short-circuit.
     */
    private suspend fun aggregateToday(
        client: HealthConnectClient,
        startOfDay: Instant,
        now: Instant,
        dataOriginFilter: Set<DataOrigin>,
    ): Int? = try {
        client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                dataOriginFilter = dataOriginFilter,
            )
        )[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
    } catch (_: Exception) {
        null
    }

    /**
     * Query Health Connect for step totals per day across a rolling window, and publish each
     * one as a separate [PulseEvent.PhoneStepsUpdate]. Used on app start / toggle change to
     * backfill historical days from the phone's own step counter, so past days in Phone mode
     * show the phone's numbers rather than whatever the ring recorded.
     *
     * Days where Health Connect has no phone-step records are skipped — those days fall back
     * to the ring's data, which is the only source available for them. A day where the phone
     * walked zero steps would publish `0` and clobber the ring's only valid record for that
     * day, so the `steps <= 0` guard skips those buckets entirely.
     *
     * **Three Health Connect quirks handled here:**
     *
     * 1. `aggregateGroupByPeriod` requires the `TimeRangeFilter` to be built with
     *    `LocalDateTime`, not `Instant`. Passing an Instant-based filter throws
     *    `IllegalArgumentException: Either use TimeRangeFilter with LocalDateTime or
     *    AggregateGroupByDurationRequest`. The plain `aggregate` (today's query, above)
     *    accepts Instant, but this one does not.
     *
     * 2. `aggregateGroupByPeriod` aligns buckets to the range **start**, not to calendar
     *    days. If the range starts at `LocalDateTime.now()` (e.g. 22:42), the last bucket
     *    runs 22:42 yesterday → 22:42 today and gets mislabeled as "yesterday", duplicating
     *    today's data. We anchor the range to local midnight so every bucket is exactly one
     *    calendar day.
     *
     * 3. Every emitted total is the **difference** between the unfiltered aggregate and the
     *    PulseLoop-only aggregate — see the class KDoc. The two calls share the range and
     *    slicer, so their buckets line up by `startTime`; a bucket present only in the
     *    unfiltered result is a day where PulseLoop has no records, so the subtraction is
     *    just the unfiltered value.
     *
     * @param daysBack how many days of history to backfill (default 30).
     * @return true if the query succeeded (even if some days were empty), false on failure.
     */
    suspend fun refreshHistoricalDays(daysBack: Long = 30): Boolean {
        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (_: Exception) {
            return false
        }

        val readStepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            return false
        }
        if (readStepsPermission !in granted) return false

        // Anchor the range to local midnight so each bucket is exactly one calendar day.
        // endLocal is tomorrow-midnight (exclusive upper bound); startLocal is the first
        // day of the window. With daysBack = 30 this yields 30 calendar-day buckets, the
        // last of which is today.
        val todayStart: LocalDateTime = LocalDate.now().atStartOfDay()
        val endLocal: LocalDateTime = todayStart.plusDays(1)
        val startLocal: LocalDateTime = todayStart.minusDays(daysBack - 1)

        // Two aggregateGroupByPeriod calls, same range and slicer, differing only in the
        // origin filter. See the class KDoc.
        val allBuckets = try {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startLocal, endLocal),
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = emptySet(),
                )
            )
        } catch (_: Exception) {
            return false
        }
        val ownBuckets = try {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startLocal, endLocal),
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = ownOrigin,
                )
            )
        } catch (_: Exception) {
            return false
        }
        // Index PulseLoop's own per-day totals by bucket start time so the subtraction below
        // is a single map lookup per bucket. A bucket only in `allBuckets` is a day where
        // PulseLoop has no records — the subtraction is just `all - 0`.
        val ownByStart: Map<LocalDateTime, Int> = ownBuckets.associate { bucket ->
            bucket.startTime to (bucket.result[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0)
        }

        // Each bucket is one day's total. The bucket's startTime marks the beginning of the
        // period it covers (local midnight). Map each non-empty bucket to a PhoneStepsUpdate
        // so the persistence layer overwrites the corresponding activity_daily row.
        for (bucket in allBuckets) {
            val all = bucket.result[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
            val own = ownByStart[bucket.startTime] ?: 0
            val steps = (all - own).coerceAtLeast(0)
            // Skip empty days: publishing 0 would clobber the ring's only valid record for
            // that day. Leaving the day untouched lets it fall back to the ring's data.
            if (steps <= 0) continue
            PulseEventBus.publishBlocking(
                PulseEvent.PhoneStepsUpdate(
                    timestamp = bucket.startTime
                        .atZone(ZoneId.systemDefault())
                        .toInstant(),
                    steps = steps,
                )
            )
        }
        return true
    }
}