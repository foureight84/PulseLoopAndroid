package com.pulseloop.health.exporters

import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.health.HealthConnectTypeMappings.HrSample
import com.pulseloop.health.HealthConnectTypeMappings.EXCLUDED_SOURCES
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the VITALS-group records (docs/health-connect-integration.md Phase 1 + Phase 5):
 *  - heart rate as **series** records, one per local hour bucket, split per Gadgetbridge's rules
 *    (see [HealthConnectTypeMappings.splitHrSegments]) and re-upserted whole whenever the hour
 *    gains a sample — `clientRecordVersion` = max `createdAt` in the bucket, so the fuller, later
 *    version always wins;
 *  - SpO₂ / HRV (RMSSD) / body temperature (Phase 1) and blood glucose / respiratory rate /
 *    VO₂max (Phase 5) as instantaneous records, one per row, keyed `pl-m-<kind>-<epochMs>` so a
 *    reading that arrives once live and once via history collapses onto one Health Connect record;
 *  - blood pressure (Phase 5) as paired instantaneous records: systolic + diastolic rows matched
 *    by exact timestamp into one [BloodPressureRecord], `pl-m-bp-<epochMs>` (see
 *    [HealthConnectTypeMappings.pairBloodPressure]).
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
        // Phase 5 measurement-based kinds (instantaneous; "bp" is paired and handled separately).
        "glucose" to "BLOOD_SUGAR",
        "resp_rate" to "RESPIRATORY_RATE",
        "vo2max" to "VO2MAX",
        // the two source rows a blood-pressure reading is stored as (pair-builder queries both).
        "bp_sys" to "BLOOD_PRESSURE_SYSTOLIC",
        "bp_dia" to "BLOOD_PRESSURE_DIASTOLIC",
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
        /** Readings dropped by this kind's guard (unpaired / out-of-range BP), reported as skipped. */
        val skipped: Int = 0,
    )

    /**
     * Builds the pending records for [kindKey] ("hr" / "spo2" / "hrv" / "temp" / "glucose" /
     * "resp_rate" / "vo2max" / "bp"). Rows with `createdAt <= [watermark]` were already exported;
     * `null` means export everything.
     */
    suspend fun build(kindKey: String, watermark: Long?, device: Device): PendingKind {
        val wm = watermark ?: 0L
        val dao = db.measurementDao()
        return when (kindKey) {
            "hr" -> buildHr(wm, device)
            "bp" -> buildBloodPressure(wm, device)
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

    // ── blood pressure: pair systolic + diastolic by timestamp into one record ──

    /**
     * Pairs the systolic and diastolic [MeasurementEntity] rows that share a sample instant into
     * one [BloodPressureRecord] (plan Phase 5). The app always writes the pair with the same
     * `timestamp` (one [com.pulseloop.service.EventPersistenceSubscriber] transaction for live,
     * one `event.timestamp` for history), so exact-timestamp equality is the correct pairing key -
     * a tolerance would risk cross-pairing two nearby readings. An unpaired row (systolic with no
     * diastolic, or vice versa) is a decode/storage anomaly and is dropped; the empty-kind
     * watermark rule self-resolves it. `clientRecordId` is keyed on the shared timestamp, never on
     * either row's random live UUID (plan identity trap #2), so a reading that arrives once live
     * and once via history still lands on the same record.
     */
    private suspend fun buildBloodPressure(wm: Long, device: Device): PendingKind {
        val dao = db.measurementDao()
        val sysRows = dao.createdSince(kindRaw.getValue("bp_sys"), wm).filter { it.sourceRaw !in EXCLUDED_SOURCES }
        val diaRows = dao.createdSince(kindRaw.getValue("bp_dia"), wm).filter { it.sourceRaw !in EXCLUDED_SOURCES }

        val zone = ZoneId.systemDefault()
        // Pure pairing + plausibility (see HealthConnectTypeMappings.pairBloodPressure): unpaired
        // readings and out-of-range pairs are dropped here, so every result becomes one record.
        val pairing = HealthConnectTypeMappings.pairBloodPressure(
            sysRows.map { HealthConnectTypeMappings.BpSide(it.timestamp, it.value, it.createdAt) },
            diaRows.map { HealthConnectTypeMappings.BpSide(it.timestamp, it.value, it.createdAt) },
        )
        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()
        for (pair in pairing.pairs) {
            val instant = Instant.ofEpochMilli(pair.timestampMs)
            val record = BloodPressureRecord(
                time = instant,
                zoneOffset = HealthConnectTypeMappings.zoneOffsetAt(instant, zone),
                metadata = Metadata.autoRecorded(device, HealthConnectTypeMappings.bloodPressureRecordId(pair.timestampMs), 1L),
                systolic = Pressure.millimetersOfMercury(pair.systolic),
                diastolic = Pressure.millimetersOfMercury(pair.diastolic),
            )
            records += record
            highWaters += pair.highWater
        }
        return PendingKind("bp", records, highWaters, skipped = pairing.dropped)
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
                "glucose" -> if (HealthConnectTypeMappings.isPlausibleBloodGlucose(row.value)) {
                    BloodGlucoseRecord(
                        time = instant,
                        zoneOffset = offset,
                        level = BloodGlucose.milligramsPerDeciliter(row.value),
                        metadata = metadata(row, "glucose", device),
                    )
                } else null
                "resp_rate" -> if (HealthConnectTypeMappings.isPlausibleRespRate(row.value)) {
                    RespiratoryRateRecord(
                        time = instant,
                        zoneOffset = offset,
                        rate = row.value,
                        metadata = metadata(row, "resp_rate", device),
                    )
                } else null
                "vo2max" -> if (HealthConnectTypeMappings.isPlausibleVo2Max(row.value)) {
                    Vo2MaxRecord(
                        time = instant,
                        zoneOffset = offset,
                        vo2MillilitersPerMinuteKilogram = row.value,
                        measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
                        metadata = metadata(row, "vo2max", device),
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
