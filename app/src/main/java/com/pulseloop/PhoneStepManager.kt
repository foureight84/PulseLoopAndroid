package com.pulseloop

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
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
 * **Every read excludes PulseLoop's own origin.** PulseLoop exports its own daily step
 * totals to Health Connect (`ActivityExporter`), so an aggregate that included our own
 * origin would feed the ring's (or a previous phone-mode run's) exported value back
 * into the phone path — the exported total plus the real one, or the exported total
 * published as "phone steps" on a day the phone has no source. Health Connect's
 * `aggregate` de-duplicates overlapping records by priority rather than summing, so a
 * manual subtraction of our own aggregate (the previous approach) can't work: the
 * unfiltered aggregate picks our own export as the priority winner, and subtracting it
 * then zeroes out the real phone total on any day PulseLoop has exported. Instead we
 * **read the record origins, drop our own package, and pass the remaining origins to
 * `aggregate`'s `dataOriginFilter`** — HC's de-duplication then runs over foreign
 * sources only, which is the intended pool.
 */
class PhoneStepManager(private val context: Context) {

    /**
     * PulseLoop's own origin, as Health Connect identifies it. Excluded from the origins
     * discovered for a read — see the class KDoc.
     */
    private val ownOrigin: Set<DataOrigin> = setOf(DataOrigin(context.packageName))

    /**
     * Query Health Connect for today's step total and publish it as a
     * [PulseEvent.PhoneStepsUpdate]. Returns true when the query succeeded, false when
     * Health Connect is unavailable or the read permission has not been granted yet.
     *
     * **Today always publishes, including 0.** A day's phone total of 0 is a real state —
     * just after midnight, before the phone has counted anything, the correct phone-mode
     * display is 0, not yesterday's ring total still sitting in the `steps` column. Under
     * the separate-column design ([ActivityDailyEntity.phoneSteps]), publishing 0 to
     * `phoneSteps` is how "today's phone value is 0" becomes visible: `displaySteps` picks
     * `phoneSteps` when it is non-null and falls back to the ring only when the phone has
     * never written for the day. Skipping the 0 would leave today's `phoneSteps` null and
     * the display showing the ring's stale value — the exact midnight gap the previous
     * ownership-tracking design produced. `upsertActivityDailyFromPhone` is a no-op when
     * the value is unchanged, so re-publishing the same 0 on every foreground is free.
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
        val range = TimeRangeFilter.between(startOfDay, now)

        // Discover foreign origins writing steps today; aggregate only over them. See the
        // class KDoc for why HC's own subtraction doesn't work.
        val foreignOrigins = try {
            foreignStepOrigins(client, range)
        } catch (_: Exception) {
            return false
        }

        val steps = if (foreignOrigins.isEmpty()) {
            // No foreign origin has written steps today yet — a normal state just after
            // midnight, and also the permanent state on a phone with no step writer. Either
            // way today's phone total is 0. `aggregate` with an empty `dataOriginFilter`
            // means "all origins", not "none", so it must not be called here.
            0
        } else {
            try {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = range,
                        dataOriginFilter = foreignOrigins,
                    )
                )[StepsRecord.COUNT_TOTAL]?.toInt() ?: 0
            } catch (_: Exception) {
                return false
            }
        }

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
     * to the ring's data, which is the only source available for them. Unlike today's read
     * ([refresh], which always publishes including 0), a past day with no phone records is
     * genuinely unknown: the phone may not have been tracking then, so a 0 would wrongly hide
     * a ring day that does have data. Only today's 0 is meaningful.
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
     * 3. Origins are discovered from the raw records first, then the aggregate runs with
     *    `dataOriginFilter` set to the foreign origins only — see the class KDoc for why.
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
        val range = TimeRangeFilter.between(startLocal, endLocal)

        // Discover foreign origins across the whole window; aggregate only over them. See
        // the class KDoc for why HC's own subtraction doesn't work.
        val foreignOrigins = try {
            foreignStepOrigins(client, range)
        } catch (_: Exception) {
            return false
        }
        if (foreignOrigins.isEmpty()) return true

        val buckets = try {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = range,
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = foreignOrigins,
                )
            )
        } catch (_: Exception) {
            return false
        }

        // Each bucket is one day's total over the foreign origins. The bucket's startTime
        // marks the beginning of the period it covers (local midnight). Map each non-empty
        // bucket to a PhoneStepsUpdate so the persistence layer overwrites the corresponding
        // activity_daily row.
        for (bucket in buckets) {
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

    /**
     * The set of Health Connect origins writing `StepsRecord`s in [timeRangeFilter], minus
     * PulseLoop's own. The result is passed as `dataOriginFilter` to the aggregates above so
     * HC's priority-based de-duplication runs over foreign sources only — see the class KDoc.
     */
    private suspend fun foreignStepOrigins(
        client: HealthConnectClient,
        timeRangeFilter: TimeRangeFilter,
    ): Set<DataOrigin> {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRangeFilter,
            )
        ).records
        return records.map { it.metadata.dataOrigin }.toSet() - ownOrigin
    }
}
