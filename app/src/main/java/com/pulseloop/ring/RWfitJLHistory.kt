package com.pulseloop.ring

import java.time.Duration
import java.time.Instant
import java.util.TimeZone

/**
 * Payload decoders for the RWfit **JieLi (`0xAB`) `05`-group history bodies**, ported from the
 * per-type parsers in `x5/b.java` (`decompiled-rwfit-official/sources/`). Framing is handled
 * upstream by [RWfitJLCodec]; this turns one reassembled `{5, key, 0x10}` reply body into
 * PulseLoop events. Multi-packet reassembly already happens in the codec, so each decoder here
 * sees the ring's complete body for its stream in one array.
 *
 * **Every layout below was re-derived from the decompile** (the anti-fabrication rule in the root
 * `AGENTS.md` — this family's first port, PR #45, invented its constants and was backed out). The
 * reply body still carries the `{cmd, key, keyFlag}` triple in bytes 0..2; the vendor parsers
 * start at byte 3, which is exactly what [RWfitJLInbound.Frame.payload] hands over, so every
 * `records` argument below is vendor index 3-based. Dispatch: the reply's triple is looked up in
 * the `y5/c.java` table (`x5/b.java z()`, lines 3678-3684) and the 05-group ids map onto the
 * parsers at `x5/b.java:3852-3899` — `-60`→`a0` steps, `-62`→`V` HR, `-64`→`T` BP, `-66`→`Z`
 * sleep, `-68`→`U` temp, `-70`→`S` SpO2, `-72`→`W` HRV, `-74`→`Y` stress, `-112`→`R` blood sugar
 * (table: `y5/c.java:74-91`).
 *
 * **Not ported, on purpose:**
 *  - The `05 xx 30` (keyFlag `0x30`) variants — `y5/c.java:75,77,79,81,83,85,87,89,91` maps them
 *    to ids `-61..-75/-111`, but **no parser for any of those ids exists in `x5/b.java`** (the
 *    dispatch at 3852-3932 has no arm for them; verified by search). The vendor only *sends* them
 *    as post-sync delete-acks that erase the ring's records (`t.java:63`, `o.java:63`, …). We never
 *    request them and decode nothing for them.
 *  - The remaining `05`-group streams with no PulseLoop metric: sport `{5,14,16}` (`Q` @1011),
 *    Muslim count `{5,23,16}` (`X` @1405), and the contact-file/vaper types. [decode] returns null
 *    for them so the driver logs them as unported.
 *
 * **Timestamps.** Every JieLi parser stamps a record as local-wall-clock seconds counted from
 * **2000-01-01T00:00:00Z** — the `+ 946684800` that appears in all of them (e.g. `a0` @1562,
 * `Z` @1533, `V` @1306) — and corrects on the way in by subtracting
 * `com.example.baselibrary.utils.b.i() / 1000`, which is `TimeZone.getDefault().getOffset(now)`
 * (`utils/b.java:250-252`). `Z` inlines that same expression verbatim (`x5/b.java:1533`), so all
 * nine streams share one correction — there is no per-parser split.
 *
 * Note the JieLi correction is a **different function from the legacy one**: the `0x7E` parsers
 * subtract `rawOffset + (useDaylightTime() ? 1h : 0)` (the quirk [RWfitDecoder.tzCorrectionSeconds]
 * replicates; vendor side `utils/b.java:268-270`, e.g. `x5/b.java:406`), which is an hour off for
 * half the year in DST zones. Do not "unify" the two helpers — each matches its own framing.
 */
object RWfitJLHistory {

    /** Seconds from the Unix epoch to 2000-01-01T00:00:00Z — the JieLi record-epoch base. */
    const val JIELI_EPOCH_SECONDS = 946_684_800L

    /** mg/dL per mmol/L of glucose — the unit the app's BLOOD_SUGAR kind speaks everywhere. */
    private const val MGDL_PER_MMOL = 18.016

    /**
     * The JieLi record-time correction, `utils.b.i() / 1000` = `getOffset(now)` in seconds
     * (`utils/b.java:250-252`, inlined at `x5/b.java:1533` in `Z`). Read at decode time, exactly
     * as the vendor does — not the legacy `rawOffset + DST?1h:0` quirk. (The iOS port instead
     * latches the offset at timeSync; a deliberate documented divergence there, see
     * `RWfitClock` in `RWfitProtocol.swift`.)
     */
    private fun tzCorrectionSeconds(): Long =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L

    private fun u16(p: ByteArray, i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)

    private fun u24(p: ByteArray, i: Int) =
        ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)

    private fun u32(p: ByteArray, i: Int) =
        ((p[i].toLong() and 0xFF) shl 24) or ((p[i + 1].toLong() and 0xFF) shl 16) or
            ((p[i + 2].toLong() and 0xFF) shl 8) or (p[i + 3].toLong() and 0xFF)

    /** Vendor index `3 + i` in the parser's `bArr` → a true instant (epoch-2000 + correction). */
    private fun instantAt(p: ByteArray, i: Int): Instant =
        Instant.ofEpochSecond(u32(p, i) + JIELI_EPOCH_SECONDS - tzCorrectionSeconds())

    /**
     * One `05`-group reply body. `key` is the triple's key byte; `records` is the body after the
     * triple (vendor parser offset 3). Returns null for keys this port does not decode, so the
     * driver can keep its "not yet ported" log for them.
     */
    fun decode(key: Byte, records: ByteArray): List<RingDecodedEvent>? = when (key) {
        RWfitProtocol.JLDataType.STEPS -> decodeSteps(records)
        RWfitProtocol.JLDataType.HEART_RATE -> decodeHeartRate(records)
        RWfitProtocol.JLDataType.BLOOD_PRESSURE -> decodeBloodPressure(records)
        RWfitProtocol.JLDataType.SLEEP -> decodeSleep(records)
        RWfitProtocol.JLDataType.TEMPERATURE -> decodeTemperature(records)
        RWfitProtocol.JLDataType.SPO2 -> decodeSpo2(records)
        RWfitProtocol.JLDataType.HRV -> decodeHrv(records)
        RWfitProtocol.JLDataType.STRESS -> decodeStress(records)
        RWfitProtocol.JLDataType.BLOOD_SUGAR -> decodeBloodSugar(records)
        else -> null
    }

    // ── The shared record loop ───────────────────────────────────────────────────
    //
    // All nine `05`-group parsers are the same `while (i10 >= bArr.length) break` loop from
    // offset 3 with a fixed stride (`x5/b.java:1552-1557` for `a0`, 1296-1300 for `V`, …). The
    // vendor's condition only tests the record *start*; a partial tail would AIOOBE in the vendor
    // (the field reads sit outside its try/catch). The boundary guard `i + stride <= size` below
    // is byte-for-byte identical to the vendor on well-formed bodies and drops a torn tail here.

    private inline fun series6(
        records: ByteArray,
        decodeItem: (Instant, Int) -> List<RingDecodedEvent>,
    ): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        var i = 0
        while (i + 6 <= records.size) {
            events.addAll(decodeItem(instantAt(records, i), i))
            i += 6
        }
        return events
    }

    // ── Steps (`a0()`, id -60) ───────────────────────────────────────────────────

    /**
     * 16-byte records from offset 3 (`x5/b.java a0()` @1549-1575):
     * `[ts2000 u32 @i][pad @i+4][steps u24 @i+5..7][calorie×10 u32 @i+8..11][distance u32 @i+12..15]`
     * — `d(i+5,i+7)` @1570 (the 3-byte read is INCLUSIVE, `y5/b.java:61-66`), `d(i+8,i+11)/10`
     * @1571, `d(i+12,i+15)/10000` @1572, stride `i10 += 16` @1573.
     *
     * The vendor's `d()` parses 4-byte slices as *signed* ints (`y5/b.java j()` @149-164); the
     * unsigned reads here agree for every realistic value (steps ≤ 16.7M, distance raw well under
     * 2³¹ decimetres).
     *
     * Records are **per-interval deltas, not daily totals**: the vendor sums them per date when it
     * stores the sync (`m.java:147-157` — `stepByDate.setTotalStep(getSteps() + getTotalStep())`),
     * so each record is published as one [RingDecodedEvent.ActivityBucket]; persistence upserts the
     * bucket by its start time and recomputes the day as the sum of distinct buckets, which is the
     * same per-date sum. (An [RingDecodedEvent.ActivityUpdate] would be wrong: its persistence
     * path keeps a running daily *max*, which only makes sense for cumulative daily totals like
     * the legacy stream's.)
     *
     * Distance: the vendor renders `raw / 10000` as kilometres, so the raw unit is decimetres and
     * `raw / 10` is metres. Calories (÷10 kcal) have no field in [RingDecodedEvent.ActivityBucket]
     * and persistence leaves bucket calories untouched — dropped, as on the legacy steps path.
     */
    fun decodeSteps(records: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        var i = 0
        while (i + 16 <= records.size) {
            val steps = u24(records, i + 5)
            if (steps > 0) {
                events.add(
                    RingDecodedEvent.ActivityBucket(
                        _timestamp = instantAt(records, i),
                        steps = steps,
                        distanceMeters = (u32(records, i + 12) / 10).toInt(),
                    )
                )
            }
            i += 16
        }
        return events
    }

    // ── Heart rate (`V()`, id -62) ───────────────────────────────────────────────

    /**
     * 6-byte records from offset 3 (`x5/b.java V()` @1291-1321): `[ts2000 u32 @i][hr @i+4][pad]`.
     * `hr` is `bArr[i+4] & 255` @1314-1316, stride `i10 += 6` @1317, and items with `hr == 0` are
     * **dropped by the vendor itself** (`if (getHr() > 0)` @1318-1320) — replicated, not clamped.
     */
    fun decodeHeartRate(records: ByteArray): List<RingDecodedEvent> =
        series6(records) { ts, i ->
            val hr = records[i + 4].toInt() and 0xFF
            if (hr > 0) {
                listOf(RingDecodedEvent.HistoryMeasurement(MeasurementKind.HEART_RATE, hr.toDouble(), ts))
            } else emptyList()
        }

    // ── Blood pressure (`T()`, id -64) ──────────────────────────────────────────

    /**
     * 6-byte records from offset 3 (`x5/b.java T()` @1182-1210): `[ts2000 u32 @i][systolic @i+4]
     * [diastolic @i+5]` — `sp = bArr[i+4] & 255` @1205-1207, `dp = bArr[i+5] & 255` @1208, stride
     * `i10 += 6` @1209. The vendor adds every record unconditionally; a zero in either field is a
     * "no sample" slot, so both must be non-zero (the bridge's plausibility window does the rest).
     * Emits one [RingDecodedEvent.HistoryMeasurement] per field, same as the legacy BP decoder.
     */
    fun decodeBloodPressure(records: ByteArray): List<RingDecodedEvent> =
        series6(records) { ts, i ->
            val sys = records[i + 4].toInt() and 0xFF
            val dia = records[i + 5].toInt() and 0xFF
            if (sys > 0 && dia > 0) {
                listOf(
                    RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, sys.toDouble(), ts),
                    RingDecodedEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, dia.toDouble(), ts),
                )
            } else emptyList()
        }

    // ── Sleep (`Z()`, id -66) ────────────────────────────────────────────────────

    /**
     * Session-start marker byte. The vendor's sleep *consumer* (not the `Z` parser — which just
     * decodes `{time, model}` pairs) treats model `17` as the moment the subject fell asleep:
     * `s1.java:1004-1006` (resets the per-session buffer) and `s1.java:1093-1098,1114`
     * (`asleepTime = first 17 record's timestamp`).
     */
    private const val SLEEP_SESSION_START = 0x11

    /**
     * Session-end (wakeup) marker byte: `s1.java:1008-1018` (date fix-up between the markers) and
     * `s1.java:1101-1116` (`wakeupTime = first 34 record's timestamp`).
     */
    private const val SLEEP_SESSION_END = 0x22

    /**
     * 7-byte records from offset 3 (`x5/b.java Z()` @1520-1540): `[ts2000 u32 @i][model @i+4]
     * [2 unused bytes]` — `setSleepModel(bArr[i+4])` @1537, stride `i10 += 7` @1538. NOT grouped:
     * the parser emits one flat list of `{time, model}` pairs, and the ring sends a
     * **stage-transition stream** — one record per stage *change* (plus the 17/34 session
     * markers), the way the vendor reconstructs sessions in `s1.java:1127-1177`: segment N runs
     * from record N's timestamp to record N+1's, `duration = (t[n+1] − t[n]) / 60` minutes
     * (`s1.java:1134-1135`).
     *
     * Stage values, from that same reconstruction (`s1.java:1139-1157`):
     *  - `1` → **deep** (`sleepType 2`, deepTime @1139-1141)
     *  - `2` → **light** (`sleepType 1`, lightTime @1142-1144)
     *  - `3` or `0` → **awake** (`sleepType 0`, wakeup count @1145-1147)
     *  - `4` → **REM** (`sleepType 3`, rapidTime @1153-1156)
     *  - `17` (the start marker) counts as the first **light** segment (@1149-1151)
     *
     * NOTE: this is **not** the legacy 0/1/2/3 = awake/light/deep/REM map (that one belongs to the
     * `0x7E` sleep items, `s1.java:1636-1645`). The JieLi model bytes were verified independently
     * from the JieLi consumer above.
     *
     * A session is emitted when its `34` marker arrives with at least one minute of stages
     * ([RingDecodedEvent.SleepTimeline], `completeSession = true`, timestamped at the `17` marker
     * — the same asleep-anchor the legacy decoder uses). A stream that ends without its `34`,
     * or a back-to-back `17`/`34` pair with no minutes between them, produces nothing — the
     * vendor likewise stores no `DataSleep` without both markers (`s1.java:1113`). Note the
     * marker's own segment always counts as light (`s1.java:1149-1151`), so a genuine session
     * always carries at least a light minute — there is no all-awake session to filter out here,
     * unlike the legacy stream.
     */
    fun decodeSleep(records: ByteArray): List<RingDecodedEvent> {
        data class Record(val time: Instant, val model: Int)

        val recs = mutableListOf<Record>()
        var i = 0
        while (i + 7 <= records.size) {
            recs.add(Record(instantAt(records, i), records[i + 4].toInt() and 0xFF))
            i += 7
        }

        val events = mutableListOf<RingDecodedEvent>()
        var sessionStart: Instant? = null
        val stages = mutableListOf<SleepStage>()

        for (index in recs.indices) {
            val rec = recs[index]
            if (rec.model == SLEEP_SESSION_START) {
                sessionStart = rec.time
                stages.clear()
            }
            val start = sessionStart ?: continue
            if (rec.model == SLEEP_SESSION_END) {
                if (stages.isNotEmpty()) {
                    // toList(): the event must outlive the loop's buffer, which the next session
                    // reuses (the vendor builds a fresh list per session, s1.java:1119).
                    events.add(RingDecodedEvent.SleepTimeline(start, stages.toList(), completeSession = true))
                }
                sessionStart = null
                stages.clear()
                continue
            }
            // Segment length = gap to the next record (`s1.java:1134-1135`); the final record has
            // no following boundary and contributes nothing, exactly like the vendor's
            // `for (i22 < size4)` loop that skips the last element.
            if (index + 1 >= recs.size) continue
            val minutes = Duration.between(rec.time, recs[index + 1].time).seconds / 60
            if (minutes in 1..(24 * 60 - 1)) {
                repeat(minutes.toInt()) { stages.add(sleepStage(rec.model)) }
            }
        }
        return events
    }

    private fun sleepStage(model: Int): SleepStage = when (model) {
        1 -> SleepStage.DEEP
        2 -> SleepStage.LIGHT
        0, 3 -> SleepStage.AWAKE
        4 -> SleepStage.REM
        SLEEP_SESSION_START -> SleepStage.LIGHT   // start marker doubles as the first light segment
        else -> SleepStage.UNKNOWN
    }

    // ── Body temperature (`U()`, id -68) ────────────────────────────────────────

    /**
     * 6-byte records from offset 3 (`x5/b.java U()` @1238-1263): `[ts2000 u32 @i][temp u16 BE
     * @i+4..5][pad]` — `setTemp(d(i+4, i+5) / 10.0f)` @1261, stride `i10 += 6` @1262. The value is
     * already °C×10 — **no legacy `+200` offset** (that belongs to the `0x7E` stream's
     * `u0()`); raw 0 is a "no sample" slot and is dropped.
     */
    fun decodeTemperature(records: ByteArray): List<RingDecodedEvent> =
        series6(records) { ts, i ->
            val raw = u16(records, i + 4)
            if (raw > 0) {
                listOf(RingDecodedEvent.HistoryMeasurement(MeasurementKind.TEMPERATURE, raw / 10.0, ts))
            } else emptyList()
        }

    // ── SpO2 (`S()`, id -70) ────────────────────────────────────────────────────

    /**
     * 6-byte records from offset 3 (`x5/b.java S()` @1127-1155): `[ts2000 u32 @i][spo2 @i+4][pad]`
     * — `setBloodOxy(bArr[i+4] & 255)` @1150-1152, stride `i10 += 6` @1153. The vendor adds every
     * record; a zero byte is "no sample", so it is dropped (the bridge's 70..100 window does the
     * rest).
     */
    fun decodeSpo2(records: ByteArray): List<RingDecodedEvent> =
        series6(records) { ts, i ->
            val spo2 = records[i + 4].toInt() and 0xFF
            if (spo2 > 0) {
                listOf(RingDecodedEvent.HistoryMeasurement(MeasurementKind.SPO2, spo2.toDouble(), ts))
            } else emptyList()
        }

    // ── HRV (`W()`, id -72) ─────────────────────────────────────────────────────

    /**
     * 6-byte records from offset 3 (`x5/b.java W()` @1348-1375): `[ts2000 u32 @i][hrv ms @i+4]
     * [pad]` — `setHrv(bArr[i+4] & 255)` @1371-1373, stride `i10 += 6` @1374. Zero = "no sample",
     * dropped; the vendor itself drops nothing here, the bridge's 1..300 ms window is the guard.
     */
    fun decodeHrv(records: ByteArray): List<RingDecodedEvent> =
        series6(records) { ts, i ->
            val hrv = records[i + 4].toInt() and 0xFF
            if (hrv > 0) {
                listOf(RingDecodedEvent.HistoryMeasurement(MeasurementKind.HRV, hrv.toDouble(), ts))
            } else emptyList()
        }

    // ── Stress (`Y()`, id -74) ──────────────────────────────────────────────────

    /**
     * 6-byte records from offset 3 (`x5/b.java Y()` @1462-1492): `[ts2000 u32 @i][stress @i+4]
     * [pad]` — `setPressure(bArr[i+4] & 255)` @1486-1488, stride `i10 += 6` @1489, and items with
     * value `0` are **dropped by the vendor itself** (`if (getPressure() > 0)` @1490-1492).
     */
    fun decodeStress(records: ByteArray): List<RingDecodedEvent> =
        series6(records) { ts, i ->
            val stress = records[i + 4].toInt() and 0xFF
            if (stress > 0) {
                listOf(RingDecodedEvent.HistoryMeasurement(MeasurementKind.STRESS, stress.toDouble(), ts))
            } else emptyList()
        }

    // ── Blood sugar (`R()`, id -112) ────────────────────────────────────────────

    /**
     * 6-byte records from offset 3 (`x5/b.java R()` @1073-1100): `[ts2000 u32 @i][sugar u16 BE
     * @i+4..5][pad]` — `setSugar(d(i+4, i+5) / 10.0f)` @1097, stride `i10 += 6` @1098. The vendor
     * displays the value in **mmol/L** (`SugarStatisticsFragment.java:439-442` — `"-- mmol/L"`,
     * reference range 3.9-6.1 in `b0.java:445`), so raw ÷ 10 is mmol/L; the app's BLOOD_SUGAR kind
     * speaks mg/dL everywhere (unit string, demo data, bridge window), so convert at the boundary
     * with the standard glucose factor (the same convention the YCBT history uses via
     * [com.pulseloop.ring.YCBTHealthRecords.bloodSugarMgdl]). Raw 0 = "no sample", dropped.
     */
    fun decodeBloodSugar(records: ByteArray): List<RingDecodedEvent> =
        series6(records) { ts, i ->
            val raw = u16(records, i + 4)
            if (raw > 0) {
                listOf(
                    RingDecodedEvent.HistoryMeasurement(
                        MeasurementKind.BLOOD_SUGAR,
                        (raw / 10.0) * MGDL_PER_MMOL,
                        ts,
                    )
                )
            } else emptyList()
        }
}
