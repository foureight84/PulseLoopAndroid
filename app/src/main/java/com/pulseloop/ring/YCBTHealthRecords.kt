package com.pulseloop.ring

/**
 * Ported from YCBTHealthRecords.swift (iOS #82).
 * Pure buffer->events decoders for the YCBT health-history record types.
 *
 * **These run over the fully reassembled transfer buffer, never over a single frame.** The ring
 * concatenates fixed-size records and then chops the stream at frame boundaries wherever they
 * happen to fall, so a record routinely straddles two data frames.
 *
 * **Layering:** a decoder here only drops the ring's *"no sample"* fillers (a zero value in a slot
 * the firmware never leaves blank when it has a reading). Plausibility ranges live in exactly one
 * place, [RingEventBridge].
 */
object YCBTHealthRecords {
    /** Decode a completed transfer. The stride comes from the same [YCBTHistoryType] table the
     *  transfer machine drives the queue from, so the two cannot disagree. */
    fun decode(buffer: List<UByte>, type: YCBTHistoryType): List<RingDecodedEvent> = when (type) {
        YCBTHistoryType.SPORT -> sport(buffer)
        YCBTHistoryType.SLEEP -> sleep(buffer)
        YCBTHistoryType.HEART -> heartRate(buffer)
        YCBTHistoryType.BLOOD -> bloodPressure(buffer)
        YCBTHistoryType.ALL -> combinedVitals(buffer)
        YCBTHistoryType.SPO2 -> spo2(buffer)
        YCBTHistoryType.TEMPERATURE -> temperature(buffer)
        YCBTHistoryType.COMPREHENSIVE -> comprehensive(buffer)
        YCBTHistoryType.BODY_DATA -> bodyData(buffer)
        else -> emptyList()
    }

    // MARK: - Sport (query 0x02, 14-byte records)

    /**
     * `[start:u32][end:u32][steps:u16@8][distanceMeters:u16@10][calories:u16@12]`.
     *
     * These are **interval** buckets (each covers start->end), not a running total, so they ride
     * [RingDecodedEvent.ActivityBucket]: upsert by start epoch, day total = sum of distinct
     * buckets. The All record's step field is the opposite (a cumulative daily counter ->
     * [RingDecodedEvent.ActivityUpdate], a per-day max ratchet); the queue asks for sport *before*
     * all, so the cumulative counter always has the last word on a day's total.
     *
     * Calories are deliberately dropped: [RingDecodedEvent.ActivityBucket] has no calorie channel.
     */
    fun sport(buffer: List<UByte>): List<RingDecodedEvent> = records(buffer, 14).mapNotNull { r ->
        val steps = YCBTBytes.u16(r, 8)
        val distance = YCBTBytes.u16(r, 10)
        if (steps <= 0 && distance <= 0) null
        else RingDecodedEvent.ActivityBucket(YCBTBytes.date(YCBTBytes.u32(r, 0)), steps, distance)
    }

    // MARK: - Heart rate (query 0x06, 6-byte records)

    /** `[ts:u32][mode:1][hr:1]`. `hr == 0` is an unworn sample, not a reading. */
    fun heartRate(buffer: List<UByte>): List<RingDecodedEvent> = records(buffer, 6).mapNotNull { r ->
        val hr = r[5]
        if (hr <= 0u) null
        else RingDecodedEvent.HistoryMeasurement(MeasurementKind.HEART_RATE, hr.toDouble(), YCBTBytes.date(YCBTBytes.u32(r, 0)))
    }

    // MARK: - Blood pressure (query 0x08, 8-byte records)

    /**
     * `[ts:u32][isInflated@4][systolic@5][diastolic@6][heartRate@7]`.
     *
     * `isInflated` flags the ring's own cuff-style sweep; it doesn't gate validity, so it isn't read.
     */
    fun bloodPressure(buffer: List<UByte>): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (r in records(buffer, 8)) {
            val ts = YCBTBytes.date(YCBTBytes.u32(r, 0))
            events.addAll(bloodPressureEvents(r[5], r[6], ts))
            if (r[7] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.HEART_RATE, r[7].toDouble(), ts))
        }
        return events
    }

    // MARK: - Combined vitals (query 0x09, 20-byte records)

    /**
     * The ring's per-interval "All" record:
     * `[ts:u32][steps:u16@4][hr@6][sys@7][dia@8][spo2@9][resp@10][hrv@11][cvrr@12][tempInt@13]`
     * `[tempFrac@14][bodyFatInt@15][bodyFatFrac@16][bloodSugar@17]`.
     *
     * HR at @6 is deliberately not emitted: the paired heart-rate history carries the same samples
     * at the same epochs. Body fat at @15-16 has no [MeasurementKind] and is skipped. cvrr @12
     * likewise.
     *
     * Steps are a **cumulative daily counter**, so they go out as [RingDecodedEvent.ActivityUpdate]
     * (a per-day max ratchet) — distance/calories are zeroed so max() leaves live-status values
     * intact.
     */
    fun combinedVitals(buffer: List<UByte>): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (r in records(buffer, 20)) {
            val ts = YCBTBytes.date(YCBTBytes.u32(r, 0))
            events.add(RingDecodedEvent.ActivityUpdate(ts, YCBTBytes.u16(r, 4), 0, 0))
            events.addAll(bloodPressureEvents(r[7], r[8], ts))
            if (r[9] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.SPO2, r[9].toDouble(), ts))
            if (r[10] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.RESPIRATORY_RATE, r[10].toDouble(), ts))
            if (r[11] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.HRV, r[11].toDouble(), ts))
            events.addAll(temperatureEvents(r[13], r[14], ts))
            if (r[17] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_SUGAR, bloodSugarMgdl(r[17].toInt()), ts))
        }
        return events
    }

    // MARK: - SpO2 (query 0x1A, 6-byte records)

    /** `[ts:u32][type@4][value@5]`. `type` distinguishes automatic all-day sampling from a spot reading. */
    fun spo2(buffer: List<UByte>): List<RingDecodedEvent> = records(buffer, 6).mapNotNull { r ->
        if (r[5] <= 0u) null
        else RingDecodedEvent.HistoryMeasurement(MeasurementKind.SPO2, r[5].toDouble(), YCBTBytes.date(YCBTBytes.u32(r, 0)))
    }

    // MARK: - Temperature (query 0x1E, 7-byte records)

    /** `[ts:u32][type@4][int@5][frac@6]` — advances 7 bytes per record. Value is `int.frac` (°C). */
    fun temperature(buffer: List<UByte>): List<RingDecodedEvent> = records(buffer, 7).flatMap { r ->
        temperatureEvents(r[5], r[6], YCBTBytes.date(YCBTBytes.u32(r, 0)))
    }

    // MARK: - Comprehensive (query 0x2F, 44-byte records)

    /**
     * The ring's "lab panel" sweep. Only blood sugar is decoded —
     * `[ts:u32][bloodSugarModel@4][int@5][frac@6]`. Uric acid, ketones and the lipid fractions have
     * no [MeasurementKind], so they are left on the floor.
     */
    fun comprehensive(buffer: List<UByte>): List<RingDecodedEvent> = records(buffer, 44).mapNotNull { r ->
        val tenths = r[5].toInt() * 10 + r[6].toInt()
        if (tenths <= 0) null
        else RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_SUGAR, bloodSugarMgdl(tenths), YCBTBytes.date(YCBTBytes.u32(r, 0)))
    }

    // MARK: - Body data (query 0x33, 28-byte records)

    /**
     * `[ts:u32][loadIdx i/f@4-5][hrv i/f@6-7][pressure i/f@8-9][body i/f@10-11][sympathetic
     * i/f@12-13][sdnn:u16@14][vo2max@16][pnn50@17][rmssd:u16@18][lf:u16@20][hf:u16@22][lfHf@24]`.
     *
     * The SDK's `pressure` is the **stress** score and `body` the **fatigue** score. Those two
     * scores go through [score] (digit-concatenated, the app's 1..100 scale) while HRV goes through
     * [composite] (milliseconds).
     */
    fun bodyData(buffer: List<UByte>): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (r in records(buffer, 28)) {
            val ts = YCBTBytes.date(YCBTBytes.u32(r, 0))
            if (r[6] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.HRV, composite(r[6], r[7]), ts))
            if (r[8] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.STRESS, score(r[8], r[9]), ts))
            if (r[10] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.FATIGUE, score(r[10], r[11]), ts))
            if (r.size > 16 && r[16] > 0u) events.add(RingDecodedEvent.HistoryMeasurement(MeasurementKind.VO2MAX, r[16].toDouble(), ts))
        }
        return events
    }

    // MARK: - Sleep (query 0x04, variable-length sessions)

    /**
     * Sleep is the one variable-length type. The buffer holds **back-to-back sessions**, each a
     * 20-byte header followed by 8-byte stage segments:
     *
     *   header:  `[flags:2][recordLen:u16@2][start:u32@4][end:u32@8][counts/totals@12..19]`
     *   segment: `[tag:1][segStart:u32 LE][len:u24 LE]`
     *
     * Stage classification is `tag and 0x0F`: 1 deep, 2 light, 3 REM, 4 awake, 5 nap. An unknown
     * tag must be skipped, never terminal — breaking out of the loop on one lets a single nap
     * segment truncate the rest of the night.
     *
     * Segments are also **deduplicated by start time within the session** — some firmware repeats
     * a segment inside one session, and because the timeline is laid out positionally from the
     * session start, a repeat would both inflate that stage's minutes and shift every later block.
     */
    fun sleep(buffer: List<UByte>): List<RingDecodedEvent> {
        val headerLength = 20
        val segmentLength = 8

        val events = mutableListOf<RingDecodedEvent>()
        var cursor = 0
        while (cursor + headerLength <= buffer.size) {
            val recordLength = YCBTBytes.u16(buffer, cursor + 2)
            val segmentsStart = cursor + headerLength
            val declared = maxOf(0, recordLength - headerLength) / segmentLength
            val available = (buffer.size - segmentsStart) / segmentLength
            val segmentCount = minOf(declared, available)

            val stages = mutableListOf<SleepStage>()
            var sessionStart: java.time.Instant? = null
            val seenStarts = mutableSetOf<Int>()
            for (index in 0 until segmentCount) {
                val offset = segmentsStart + index * segmentLength
                val stage = sleepStage(buffer[offset]) ?: continue   // e.g. padding — skip, don't stop
                val segmentStart = YCBTBytes.u32(buffer, offset + 1).toInt()
                if (!seenStarts.add(segmentStart)) continue          // firmware repeat — count once
                val segmentSeconds = YCBTBytes.u24(buffer, offset + 5)
                if (sessionStart == null) sessionStart = YCBTBytes.date(segmentStart.toLong())
                val minutes = Math.round(segmentSeconds / 60.0).toInt()
                repeat(maxOf(1, minutes)) { stages.add(stage) }
            }

            val start = sessionStart
            if (start != null && stages.isNotEmpty()) {
                events.add(RingDecodedEvent.SleepTimeline(start, stages))
            }
            // Advance to the end of the segments consumed — a bogus recordLen still moves the
            // cursor by at least the header, so this can't spin.
            cursor = segmentsStart + segmentCount * segmentLength
        }
        return events
    }

    /** `tag and 0x0F` -> shared stage. 5 = nap/daytime sleep, which has no dedicated bucket. */
    private fun sleepStage(tag: UByte): SleepStage? = when ((tag.toInt() and 0x0f)) {
        1 -> SleepStage.DEEP
        2 -> SleepStage.LIGHT
        3 -> SleepStage.REM
        4 -> SleepStage.AWAKE
        5 -> SleepStage.UNKNOWN
        else -> null
    }

    // MARK: - Shared field decoding

    /** Systolic/diastolic as two upserting history rows. Both bytes are zero on a record the ring
     *  never ran a BP sweep for; the plausible *range* is the bridge's business, not ours. */
    private fun bloodPressureEvents(systolic: UByte, diastolic: UByte, timestamp: java.time.Instant): List<RingDecodedEvent> {
        if (systolic <= 0u || diastolic <= 0u) return emptyList()
        return listOf(
            RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, systolic.toDouble(), timestamp),
            RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, diastolic.toDouble(), timestamp),
        )
    }

    /** The ring's "no temperature sample" fraction marker — a sentinel, not a fraction that happens to be 15. */
    private const val TEMPERATURE_FILLER: Int = 15

    /**
     * Temperature from an int/fraction pair, shared by the dedicated record and the All record.
     * Two fillers, not one: `int = 0, frac = 15` AND `int = 36, frac = 15` are both the "never
     * measured" marker — 36.15 C would otherwise sail through the plausibility gate and be
     * upserted on every future sync (the ring replays its whole log).
     */
    private fun temperatureEvents(integer: UByte, fraction: UByte, timestamp: java.time.Instant): List<RingDecodedEvent> {
        if (integer <= 0u || fraction.toInt() == TEMPERATURE_FILLER) return emptyList()
        return listOf(RingDecodedEvent.HistoryMeasurement(MeasurementKind.TEMPERATURE, composite(integer, fraction), timestamp))
    }

    /**
     * The SDK never adds an integer and its fraction *numerically* — it **string-concatenates**
     * them (`int.frac`). The fraction's scale is therefore implied by its digit count: 5 -> .5,
     * 50 -> .5, 25 -> .25.
     */
    fun composite(integer: UByte, fraction: UByte): Double =
        "${integer}.${fraction}".toDoubleOrNull() ?: integer.toDouble()

    /**
     * Stress / fatigue are the one pair that is **not** the decimal composite. The ring scores
     * them 0-10 with one decimal, and the app displays that x10 on a 1..100 scale: bytes `(5, 3)`
     * are the **53** the app puts on screen, not 5.3.
     *
     * HRV in the same record is deliberately *not* one of these: it is milliseconds, so it keeps
     * the decimal composite (`45, 6` -> 45.6 ms, not 456).
     */
    fun score(integer: UByte, fraction: UByte): Double =
        "$integer$fraction".toDoubleOrNull() ?: integer.toDouble()

    /** mg/dL per mmol/L — the standard glucose molar-mass factor. */
    const val MGDL_PER_MMOL = 18.016

    /**
     * Blood sugar arrives as **tenths of a mmol/L**, not whole mmol/L. PulseLoop persists
     * `.bloodSugar` in mg/dL, hence the conversion. **UNVERIFIED on hardware.**
     */
    fun bloodSugarMgdl(tenthsOfMmol: Int): Double = tenthsOfMmol / 10.0 * MGDL_PER_MMOL

    // MARK: - Helpers

    /** Slice the reassembled buffer into fixed-size records, dropping a short trailing remainder. */
    private fun records(buffer: List<UByte>, size: Int): List<List<UByte>> {
        if (size <= 0) return emptyList()
        val out = mutableListOf<List<UByte>>()
        var i = 0
        while (i + size <= buffer.size) {
            out.add(buffer.subList(i, i + size))
            i += size
        }
        return out
    }
}
