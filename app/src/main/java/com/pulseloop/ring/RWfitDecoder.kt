package com.pulseloop.ring

import java.time.Instant
import java.util.TimeZone

/**
 * Payload decoders for the RWfit legacy (`0x7E`) replies, ported from `x5/b.java` in
 * `decompiled-rwfit-official/sources/`. Framing is handled upstream by [RWfitLegacyCodec]; this
 * turns one deframed `(cmd, payload)` into PulseLoop events.
 *
 * Each decoder cites the vendor method it came from. The dispatch itself is `x5/b.java a()`.
 */
object RWfitDecoder {

    /**
     * The vendor stamps history timestamps as **local wall-clock seconds pretending to be UTC**, and
     * corrects them on the way in by subtracting the zone's raw offset (plus a flat hour when the
     * zone observes DST at all). Replicated exactly, quirk included — `useDaylightTime()` asks
     * whether the zone *ever* uses DST, not whether the timestamp falls inside it, so the vendor is
     * an hour out for half the year in DST zones. Matching that keeps our decode aligned with what
     * the ring and the vendor app agree on; "fixing" it here would put us an hour off theirs.
     */
    private fun tzCorrectionSeconds(): Long {
        val tz = TimeZone.getDefault()
        return (tz.rawOffset + if (tz.useDaylightTime()) 3_600_000L else 0L) / 1000
    }

    private fun u16(p: ByteArray, i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)

    private fun u24(p: ByteArray, i: Int) =
        ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)

    private fun u32(p: ByteArray, i: Int) =
        ((p[i].toLong() and 0xFF) shl 24) or ((p[i + 1].toLong() and 0xFF) shl 16) or
            ((p[i + 2].toLong() and 0xFF) shl 8) or (p[i + 3].toLong() and 0xFF)

    private fun instantAt(p: ByteArray, i: Int): Instant =
        Instant.ofEpochSecond(u32(p, i) - tzCorrectionSeconds())

    /** What the ring says it currently holds (`x5/b.java v0()`, cmd `0xA0`). */
    data class SyncManifest(
        val totalDataCount: Int,
        val hasSteps: Boolean,
        val hasSleep: Boolean,
        val hasHeartRate: Boolean,
        val hasBloodPressure: Boolean,
        val hasSpo2: Boolean,
        val hasTemperature: Boolean,
        val hasBreathe: Boolean,
        val hasEcg: Boolean,
        val hasSport: Boolean,
    ) {
        /** The streams worth requesting, in the vendor's own cascade order. */
        fun pendingStreams(): List<RWfitProtocol.HistoryType> = buildList {
            if (hasSteps) add(RWfitProtocol.HistoryType.STEPS)
            if (hasSleep) add(RWfitProtocol.HistoryType.SLEEP)
            if (hasHeartRate) add(RWfitProtocol.HistoryType.HEART_RATE)
            if (hasBloodPressure) add(RWfitProtocol.HistoryType.BLOOD_PRESSURE)
            if (hasSpo2) add(RWfitProtocol.HistoryType.SPO2)
            if (hasTemperature) add(RWfitProtocol.HistoryType.TEMPERATURE)
            if (hasBreathe) add(RWfitProtocol.HistoryType.BREATHE)
        }
    }

    /** Decoded manifest, or null when the frame is too short. `x5/b.java v0()`. */
    fun decodeSyncManifest(p: ByteArray): SyncManifest? {
        if (p.size < 4) return null
        fun bit(b: Byte, n: Int) = ((b.toInt() shr n) and 1) == 1
        return SyncManifest(
            totalDataCount = u16(p, 0),
            hasSteps = bit(p[2], 0),
            hasSleep = bit(p[2], 1),
            hasHeartRate = bit(p[2], 2),
            hasBloodPressure = bit(p[2], 3),
            hasSpo2 = bit(p[2], 4),
            hasTemperature = bit(p[2], 5),
            hasBreathe = bit(p[2], 6),
            hasEcg = bit(p[2], 7),
            hasSport = bit(p[3], 0),
        )
    }

    /**
     * `PowerBean` (`x5/b.java a()`, case `b3 == 1 || b3 == 96`):
     * `[lowPowerFlag, powerStatus, percent]`. The percentage is **byte 2** — reading byte 0 gets a
     * boolean flag, which is how the reverted driver reported every ring as 0 % or 1 %.
     * `powerStatus` 1 = charging, per the vendor's charge UI.
     */
    fun decodeBattery(p: ByteArray): List<RingDecodedEvent> {
        if (p.size < 3) return emptyList()
        return listOf(
            RingDecodedEvent.Battery(
                percent = (p[2].toInt() and 0xFF).coerceIn(0, 100),
                charging = (p[1].toInt() and 0xFF) == 1,
            )
        )
    }

    /**
     * Heart-rate history (`x5/b.java w0()`, cmd `0xA3`). Repeating day records:
     * `[dayTs u32][itemCount u16]` then `itemCount × [sampleTs u32][bpm u8]`.
     */
    fun decodeHeartRateHistory(p: ByteArray) =
        decodeDayRecords(p, itemSize = 5) { payload, at ->
            val bpm = payload[at + 4].toInt() and 0xFF
            if (bpm in 25..250) {
                listOf(
                    RingDecodedEvent.HistoryMeasurement(
                        kind_field = MeasurementKind.HEART_RATE,
                        value = bpm.toDouble(),
                        _timestamp = instantAt(payload, at),
                    )
                )
            } else emptyList()
        }

    /** SpO2 history (`x5/b.java r0()`, cmd `0xA5`). Same shape as HR. */
    fun decodeSpo2History(p: ByteArray) =
        decodeDayRecords(p, itemSize = 5) { payload, at ->
            val spo2 = payload[at + 4].toInt() and 0xFF
            if (spo2 in 50..100) {
                listOf(
                    RingDecodedEvent.HistoryMeasurement(
                        kind_field = MeasurementKind.SPO2,
                        value = spo2.toDouble(),
                        _timestamp = instantAt(payload, at),
                    )
                )
            } else emptyList()
        }

    /**
     * Blood-pressure history (`x5/b.java s0()`, cmd `0xA4`). Item is 6 bytes:
     * `[ts u32][systolic u8][diastolic u8]`.
     */
    fun decodeBloodPressureHistory(p: ByteArray) =
        decodeDayRecords(p, itemSize = 6) { payload, at ->
            val sys = payload[at + 4].toInt() and 0xFF
            val dia = payload[at + 5].toInt() and 0xFF
            if (sys in 60..250 && dia in 30..200) {
                val ts = instantAt(payload, at)
                listOf(
                    RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, sys.toDouble(), ts),
                    RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, dia.toDouble(), ts),
                )
            } else emptyList()
        }

    /**
     * Body-temperature history (`x5/b.java u0()`, cmd `0xA6`). Item is 5 bytes, and the value is
     * offset-encoded: **°C = (raw + 200) / 10**, i.e. raw 160 → 36.0 °C.
     */
    fun decodeTemperatureHistory(p: ByteArray) =
        decodeDayRecords(p, itemSize = 5) { payload, at ->
            val celsius = ((payload[at + 4].toInt() and 0xFF) + 200) / 10.0
            if (celsius in 30.0..45.0) {
                listOf(
                    RingDecodedEvent.HistoryMeasurement(
                        kind_field = MeasurementKind.TEMPERATURE,
                        value = celsius,
                        _timestamp = instantAt(payload, at),
                    )
                )
            } else emptyList()
        }

    /**
     * Step history (`x5/b.java C0()`, cmd `0xA1`). Day record is 15 bytes —
     * `[dayTs u32][totalSteps u24][totalCalorie u24][totalDistance u24][itemCount u16]` — followed
     * by `itemCount × 8`: `[index u8][steps u16][calorie u24][distance u16]`.
     *
     * Only the day totals are emitted: PulseLoop's intraday buckets are keyed by wall-clock start,
     * and the per-item `index` alone doesn't pin one down without knowing the ring's bucket width,
     * which the vendor never states. Left for a hardware capture rather than assumed.
     */
    fun decodeStepHistory(p: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        var i = 0
        while (i + 15 <= p.size) {
            val dayTs = instantAt(p, i)
            val totalSteps = u24(p, i + 4)
            val totalCalorie = u24(p, i + 7)
            val totalDistance = u24(p, i + 10)
            val itemCount = u16(p, i + 13)
            i += 15
            val itemsEnd = i + itemCount * 8
            if (itemsEnd > p.size) break     // truncated record — stop rather than read past it
            i = itemsEnd

            events.add(
                RingDecodedEvent.ActivityUpdate(
                    _timestamp = dayTs,
                    steps = totalSteps,
                    distanceMeters = totalDistance,
                    calories = totalCalorie,
                )
            )
        }
        return events
    }

    /**
     * Sleep history (`x5/b.java A0()`, cmd `0xA2`). Day record is 16 bytes —
     * `[dayTs u32][totalSleepMinutes u16][asleepTs u32][awakeTs u32][itemCount u16]` — followed by
     * `itemCount × 2`: `[lengthMinutes u8][stageType u8]`.
     *
     * Stage types are confirmed from the vendor's own aggregation (`s1.java:1636-1645`, which sums
     * them into wakeupCount / light / deep / REM): `0` awake, `1` light, `2` deep, `3` REM.
     */
    fun decodeSleepHistory(p: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        var i = 0
        while (i + 16 <= p.size) {
            val asleepAt = instantAt(p, i + 6)
            val itemCount = u16(p, i + 14)
            i += 16
            val itemsEnd = i + itemCount * 2
            if (itemsEnd > p.size) break

            val stages = mutableListOf<SleepStage>()
            for (n in 0 until itemCount) {
                val at = i + n * 2
                val minutes = p[at].toInt() and 0xFF
                val stage = when (p[at + 1].toInt() and 0xFF) {
                    0 -> SleepStage.AWAKE
                    1 -> SleepStage.LIGHT
                    2 -> SleepStage.DEEP
                    3 -> SleepStage.REM
                    else -> SleepStage.UNKNOWN
                }
                repeat(minutes) { stages.add(stage) }
            }
            i = itemsEnd

            // An all-awake record carries no sleep; the persistence layer treats it as a session.
            if (stages.any { it != SleepStage.AWAKE }) {
                events.add(
                    RingDecodedEvent.SleepTimeline(
                        _timestamp = asleepAt,
                        stages = stages,
                        completeSession = true,
                    )
                )
            }
        }
        return events
    }

    /**
     * The five history streams that share `[dayTs u32][itemCount u16]` + fixed-size items
     * (`w0`/`r0`/`s0`/`u0` are byte-for-byte the same loop in the vendor, differing only in the
     * item body).
     */
    private inline fun decodeDayRecords(
        p: ByteArray,
        itemSize: Int,
        decodeItem: (ByteArray, Int) -> List<RingDecodedEvent>,
    ): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        var i = 0
        while (i + 6 <= p.size) {
            val itemCount = u16(p, i + 4)
            i += 6
            val itemsEnd = i + itemCount * itemSize
            if (itemsEnd > p.size) break     // truncated record
            for (n in 0 until itemCount) events.addAll(decodeItem(p, i + n * itemSize))
            i = itemsEnd
        }
        return events
    }
}
