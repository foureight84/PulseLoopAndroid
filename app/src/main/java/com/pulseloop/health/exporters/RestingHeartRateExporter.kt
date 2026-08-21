package com.pulseloop.health.exporters

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.health.HealthConnectTypeMappings
import java.time.Instant

/**
 * Builds the Phase 5 resting-heart-rate record (docs/health-connect-integration.md Phase 5):
 * one [RestingHeartRateRecord] for the user's learned resting-HR baseline
 * ([com.pulseloop.data.entity.UserProfileEntity.hrRestingBaseline], computed by
 * [com.pulseloop.service.RestingHRBaselineService] from the p10 of the trailing 30 days of HR).
 *
 * Unlike every other group, this is a **single mutable value, not a time series**: there is
 * exactly one baseline, so the record is keyed on a constant [HealthConnectTypeMappings.RESTING_HR_RECORD_ID]
 * and its instant is the baseline's `hrRestingBaselineUpdatedAt` - the
 * moment the value was learned. [HealthConnectExporter] owns the RESTING_HR watermark (that
 * same `hrRestingBaselineUpdatedAt`), so a re-learn advances the version and upserts the SAME
 * record in place, while an unchanged baseline re-reads nothing.
 *
 * `beatsPerMinute` is a whole number; the baseline is stored at 0.5 resolution, so it is rounded
 * to the nearest bpm. Pure DB -> record: no client, no inserts.
 */
class RestingHeartRateExporter(private val db: PulseLoopDatabase) {

    data class PendingRestingHr(
        val records: List<Record>,
        val highWaters: List<Long>,
    )

    /**
     * The single resting-HR record to export, or none. [watermark] is the last exported
     * `hrRestingBaselineUpdatedAt` (`null` = never exported); a baseline whose `updatedAt` has not
     * advanced past it is already current and produces nothing.
     */
    suspend fun build(watermark: Long?, device: Device): PendingRestingHr {
        val profile = db.userProfileDao().get() ?: return PendingRestingHr(emptyList(), emptyList())
        val baseline = profile.hrRestingBaseline ?: return PendingRestingHr(emptyList(), emptyList())
        val updatedAt = profile.hrRestingBaselineUpdatedAt ?: return PendingRestingHr(emptyList(), emptyList())
        if (watermark != null && updatedAt <= watermark) return PendingRestingHr(emptyList(), emptyList())
        if (!HealthConnectTypeMappings.isPlausibleRestingHr(baseline)) return PendingRestingHr(emptyList(), emptyList())

        val instant = Instant.ofEpochMilli(updatedAt)
        val record = RestingHeartRateRecord(
            time = instant,
            zoneOffset = HealthConnectTypeMappings.zoneOffsetAt(instant),
            beatsPerMinute = Math.round(baseline).toLong(),
            metadata = Metadata.autoRecorded(device, HealthConnectTypeMappings.RESTING_HR_RECORD_ID, updatedAt),
        )
        // high water = the baseline's updatedAt (the RESTING_HR watermark the exporter advances).
        return PendingRestingHr(listOf(record), listOf(updatedAt))
    }
}
