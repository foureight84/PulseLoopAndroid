package com.pulseloop

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
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
 */
class PhoneStepManager(private val context: Context) {

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

        val response = try {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                )
            )
        } catch (_: Exception) {
            return false
        }

        val steps = response[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
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
     * **Two Health Connect quirks handled here:**
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

        val response = try {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startLocal, endLocal),
                    timeRangeSlicer = Period.ofDays(1),
                )
            )
        } catch (_: Exception) {
            return false
        }

        // Each bucket is one day's total. The bucket's startTime marks the beginning of the
        // period it covers (local midnight). Map each non-empty bucket to a PhoneStepsUpdate
        // so the persistence layer overwrites the corresponding activity_daily row.
        for (bucket in response) {
            val steps = bucket.result[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
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