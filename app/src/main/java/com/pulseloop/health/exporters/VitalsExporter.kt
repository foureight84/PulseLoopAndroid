package com.pulseloop.health.exporters

import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.health.HealthConnectTypeMappings.HrSample
import com.pulseloop.health.HealthConnectTypeMappings.EXCLUDED_SOURCES
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the Phase 1 vitals records (docs/health-connect-integration.md Phase 1):
 *  - heart rate as **series** records, one per local hour bucket, split per Gadgetbridge's rules
 *    (see [HealthConnectTypeMappings.splitHrSegments]) and re-upserted whole whenever the hour
 *    gains a sample — `clientRecordVersion` = max `createdAt` in the bucket, so the fuller, later
 *    version always wins;
 *  - SpO₂ / HRV (RMSSD) / body temperature as instantaneous records, one per row, keyed
 *    `pl-m-<kind>-<epochMs>` so a reading that arrives once live and once via history collapses
 *    onto one Health Connect record.
 *
 * The selection is watermark-driven on `Measurement.createdAt` (never the sample timestamp —
 * late-arriving ring history must still be picked up), and demo/mock rows are excluded (mirrors
 * iOS). Pure DB → records: no client, no inserts — [com.pulseloop.health.HealthConnectExporter]
 * owns the write path.
 */
class VitalsExporter(private val db: PulseLoopDatabase) {

    /**
     * kindKey (the id token, e.g. "hr") -> the `measurements.kindRaw` column value to query.
     * Every write path persists kindRaw = MeasurementKind.<X>.name (EventPersistenceSubscriber,
     * DemoDataSeeder, MetricsService, MeasurementModal) - so the DAO query MUST use the .name,
     * not the id token. (The Phase 1 bug: this queried by the .key, which matched no real or demo
     * data and silently exported nothing for live ring history.)
     */
    private val kindRaw: Map<String, String> = mapOf(
        "hr" to "HEART_RATE",
        "spo2" to "SPO2",
        "hrv" to "HRV",
        "temp" to "TEMPERATURE",
    )

    /**
     * Records to insert for one vitals kind, with the parallel [highWaters] list: entry i is the
     * max source-row `createdAt` that record i represents, so the exporter can advance the
     * watermark only to a value whose rows all reached Health Connect.
     */
    data class PendingKind(
        val kindKey: String,
        val records: List<Record>,
        val highWaters: List<Long>,
    )

    /**
     * Builds the pending records for [kindKey] ("hr" / "spo2" / "hrv" / "temp"). Rows with
     * `createdAt <= [watermark]` were already exported; `null` means export everything.
     */
    suspend fun build(kindKey: String, watermark: Long?, device: Device): PendingKind {
        val wm = watermark ?: 0L
        val dao = db.measurementDao()
        return when (kindKey) {
            "hr" -> buildHr(wm, device)
            else -> buildInstantaneous(kindKey, wm, device)
        }
    }

    // ── heart rate: hourly buckets, whole-hour re-read, series splitting ──

    private suspend fun buildHr(wm: Long, device: Device): PendingKind {
        val dao = db.measurementDao()
        // Watermark selection on createdAt — tells us WHICH local hours are touched…
        val newRows = dao.createdSince(kindRaw.getValue("hr"), wm).filter { it.sourceRaw !in EXCLUDED_SOURCES }
        if (newRows.isEmpty()) return PendingKind("hr", emptyList(), emptyList())
        val zone = ZoneId.systemDefault()

        val touchedHours = newRows
            .map { HealthConnectTypeMappings.hourStartOf(it.timestamp, zone) }
            .distinct()
            .sorted()

        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()

        for (hourStart in touchedHours) {
            // …so we re-read every touched hour IN FULL by sample timestamp and rebuild its
            // records from scratch: an hour that gains a sample must re-upsert the whole hour
            // (plan §3). The end bound is exclusive-of-next-hour by one millisecond (BETWEEN is
            // inclusive).
            val hourRows = dao.rangeReal(kindRaw.getValue("hr"), hourStart, hourStart + HealthConnectTypeMappings.HOUR_MS - 1)
            val samples = hourRows
                .filter { it.sourceRaw !in EXCLUDED_SOURCES }
                .filter { HealthConnectTypeMappings.isPlausibleHr(it.value) }
                .map { HrSample(it.timestamp, it.value.toLong()) }
            if (samples.isEmpty()) continue

            val segments = HealthConnectTypeMappings.splitHrSegments(samples, zone)
            // clientRecordVersion = max createdAt in the bucket — a later, fuller version always
            // wins the upsert.
            val version = hourRows.filter { it.sourceRaw !in EXCLUDED_SOURCES }.maxOf { it.createdAt }

            segments.forEachIndexed { index, segment ->
                val startMs = segment.first().timeMs
                val endMs = HealthConnectTypeMappings.seriesEndMs(startMs, segment.last().timeMs)
                val start = Instant.ofEpochMilli(startMs)
                val end = Instant.ofEpochMilli(endMs)
                val recordId = HealthConnectTypeMappings.hrRecordId(hourStart, if (segments.size > 1) index else null)
                records += HeartRateRecord(
                    start,
                    HealthConnectTypeMappings.zoneOffsetAt(start, zone),
                    end,
                    HealthConnectTypeMappings.zoneOffsetAt(end, zone),
                    segment.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.timeMs), it.bpm) },
                    Metadata.autoRecorded(device, recordId, version),
                )
                highWaters += version
            }
        }
        return PendingKind("hr", records, highWaters)
    }

    // ── instantaneous kinds: one record per row ──

    private suspend fun buildInstantaneous(kindKey: String, wm: Long, device: Device): PendingKind {
        val rows = db.measurementDao().createdSince(kindRaw.getValue(kindKey), wm).filter { it.sourceRaw !in EXCLUDED_SOURCES }
        val zone = ZoneId.systemDefault()
        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()
        for (row in rows) {
            val instant = Instant.ofEpochMilli(row.timestamp)
            val offset = HealthConnectTypeMappings.zoneOffsetAt(instant, zone)
            val record: Record? = when (kindKey) {
                "spo2" -> if (HealthConnectTypeMappings.isPlausibleSpO2(row.value)) {
                    OxygenSaturationRecord(instant, offset, Percentage(row.value), metadata(row, "spo2", device))
                } else null
                "hrv" -> if (HealthConnectTypeMappings.isPlausibleHrvRmssd(row.value)) {
                    HeartRateVariabilityRmssdRecord(instant, offset, row.value, metadata(row, "hrv", device))
                } else null
                "temp" -> if (HealthConnectTypeMappings.isPlausibleBodyTemperature(row.value)) {
                    BodyTemperatureRecord(
                        instant,
                        offset,
                        metadata(row, "temp", device),
                        Temperature.celsius(row.value),
                        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FINGER,
                    )
                } else null
                else -> null
            }
            if (record != null) {
                records += record
                highWaters += row.createdAt
            }
        }
        return PendingKind(kindKey, records, highWaters)
    }

    private fun metadata(row: MeasurementEntity, kindKey: String, device: Device): Metadata =
        // Immutable sample → version 1; the id derives from kind + sample instant (never the row's
        // random live UUID) so live/history duplicates collapse onto one Health Connect record.
        Metadata.autoRecorded(device, HealthConnectTypeMappings.vitalsRecordId(kindKey, row.timestamp), 1L)
}
