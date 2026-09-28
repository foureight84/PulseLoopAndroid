package com.pulseloop

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.util.TimeUtil
import java.time.Instant

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
 */
class PhoneStepManager(private val context: Context) {

    /**
     * Query Health Connect for today's step total and publish it as a
     * [PulseEvent.PhoneStepsUpdate]. Returns true when a value was published,
     * false when Health Connect is unavailable or the read permission has not
     * been granted yet.
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
        PulseEventBus.publishBlocking(
            PulseEvent.PhoneStepsUpdate(
                timestamp = now,
                steps = steps,
                // Rough estimates: average adult stride ≈ 0.7 m; walking ≈ 0.04 kcal/step.
                distanceMeters = steps * 0.7,
                calories = steps * 0.04,
            )
        )
        return true
    }
}