package com.pulseloop.ring

/**
 * Shared vocabulary for the RWfit ring family (vendor app `com.rw.revivalfit`).
 *
 * **Every constant here was read out of `decompiled-rwfit-official/sources/` and carries the file
 * it came from.** Paths below are relative to that directory. This is the rule in the root
 * `AGENTS.md`: match the vendor app, not iOS, and never guess a wire constant. The first attempt at
 * this driver (PulseLoopAndroid PR #45) invented the whole table — wrong characteristics, wrong
 * frame layout, wrong command ids — and had to be reverted; see `docs/ios-sync.md`
 * § "RWfit (#130) — backed out".
 *
 * One GATT service, two wire framings:
 *  - **Legacy `0x7E`** ("Realtek" in the vendor's own logging): 8-byte header, XOR checksum over
 *    the payload, per-frame serials, mandatory `0xFE`/`0xFF` ACK handshake (`x5/d.java`).
 *  - **JieLi `0xAB`**: 6-byte header, CRC-16/ARC over the body, `{cmd, key, keyFlag}` triple as the
 *    first three body bytes, flag-`0x11` ACKs (`x5/c.java` encode, `r5/b.java:386-476` decode).
 *
 * Which one a ring speaks is **not** in the advertisement — it is decided after connect from the
 * sibling services the ring exposes (`r5/b.java onServicesDiscovered`, lines 700-727). That is why
 * the whole family is one [RingDeviceType] and [RWfitDriver] owns the decision.
 */
object RWfitProtocol {

    // ── GATT (`y5/a.java`, static initialiser) ───────────────────────────────────

    /** Primary data service, both framings (`y5/a.java f19994a`). */
    const val SERVICE_UUID = "0000a00a-0000-1000-8000-00805f9b34fb"

    /** Command write characteristic (`y5/a.java f19995b`). */
    const val WRITE_UUID = "0000b002-0000-1000-8000-00805f9b34fb"

    /** Notify characteristic — command replies and device-initiated pushes (`y5/a.java f19996c`). */
    const val NOTIFY_UUID = "0000b003-0000-1000-8000-00805f9b34fb"

    /**
     * Framing discriminators. Never subscribed — only *seen* at service discovery. Any one of them
     * present ⇒ JieLi framing; none present ⇒ legacy (`r5/b.java:700-727`, where the vendor logs
     * "获取杰里蓝牙服务" / "获取瑞昱蓝牙服务" — got JieLi / got Realtek).
     */
    const val JIELI_SERVICE_UUID = "0000ae00-0000-1000-8000-00805f9b34fb"
    const val PIXART_OTA_SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
    const val TELINK_OTA_SERVICE_UUID = "00010203-0405-0607-0809-0a0b0c0d1912"

    val FRAMING_DISCRIMINATOR_UUIDS = listOf(
        JIELI_SERVICE_UUID, TELINK_OTA_SERVICE_UUID, PIXART_OTA_SERVICE_UUID,
    )

    // ── Advertisement recognition (`r5/d.java:76`) ───────────────────────────────

    /**
     * The vendor's scanner matches on the **raw advertising bytes**, hex-formatted with spaces, and
     * accepts a device when any of these four substrings appears (`r5/d.java c()`):
     *
     *  - `02 01 06 03 03 0a a0` — Flags + a complete 16-bit service list holding `0xA00A`
     *    (little-endian on the wire), the vendor's `pidType 1`.
     *  - `d6 05 02 00` — manufacturer data, company `0x05D6`, `pidType 2`.
     *  - `15 ff d6 05 41 54` — a 0x15-long manufacturer AD, company `0x05D6`, then ASCII "AT"
     *    (`pidType 239`).
     *  - `d6 06 02 00` — company `0x06D6`, the "T-Ring" line (`pidType 4`).
     *
     * It requires a non-empty device name but **never matches on the name's content** — these rings
     * are rebranded constantly (the unit iOS tested was sold as a "Colmi"), so the name is the one
     * field that carries no family signal.
     */
    val ADVERTISEMENT_HEX_PATTERNS = listOf(
        "02010603030aa0",   // 02 01 06 03 03 0a a0 — Flags + 16-bit service list with 0xA00A
        "d6050200",
        "15ffd6054154",
        "d6060200",
    )

    /** Manufacturer-data prefixes, for the parsed-`manufacturerData` path (same source). */
    val MANUFACTURER_HEX_PREFIXES = listOf("d6050200", "d6054154", "d6060200")

    // ── Legacy `0x7E` commands (`x5/b.java a()` dispatch + the senders cited per line) ──

    object Legacy {
        /** Reply decoded by `x5/b.java o()` (`a()` case `b3 == 0`). */
        const val DEVICE_INFO: Byte = 0x00

        /**
         * `PowerBean`: payload `[lowPowerFlag, powerStatus, percent]` — the battery percentage is
         * **byte 2, not byte 0** (`x5/b.java a()`, case `b3 == 1 || b3 == 96`). `0x60` is an alias
         * the firmware also uses.
         */
        const val BATTERY: Byte = 0x01
        const val BATTERY_ALT: Byte = 0x60

        /** Bind state (`x5/b.java c()`). */
        const val BIND_STATUS: Byte = 0x02

        /** Supported-features bitmap → `SupportMenuBean` (`x5/b.java i()`). Capability discovery. */
        const val FEATURES: Byte = 0x03

        /** `[bindType, userId as UTF-16LE]` (`p.java s(BindInfoBean)`, line 763). */
        const val BIND: Byte = 0x20

        /**
         * `[yearHi, yearLo, month, day, hour, min, sec]` — the legacy year is the **full four-digit
         * year** as a big-endian u16 (`p.java u(Date)`, line 815). The JieLi variant sends
         * `year - 2000` in one byte instead (`p.java v(Date)`); do not share the encoder.
         */
        const val SET_TIME: Byte = 0x21

        /** `[lang, measureUnit, tempUnit, timeFont]` (`p.java P(UnitBean)`, line 316). */
        const val UNITS: Byte = 0x24

        /**
         * `[gender, age, heightBE u16, (weight×10) BE u16, goalBE u16, nickname UTF-16LE…]`
         * (`p.java x(PersonBean)`, line 876).
         */
        const val PROFILE: Byte = 0x2E

        /** Unbind on Forget — empty payload (`h0.java n()`, line 319). */
        const val UNBIND: Byte = 0x44

        /**
         * Health-sync manifest: which history streams the ring currently holds, decoded into
         * `HealthSyncBean` (`u1.java g()` sends it, `x5/b.java v0()` decodes). Every per-stream
         * request below is gated on the matching `isHasXData()` flag from this reply
         * (`blesdk/service/l.java`).
         */
        const val SYNC_MANIFEST: Byte = 0xA0.toByte()

        /** Per-stream history requests — all sent with an **empty payload** (`blesdk/service/l.java`). */
        const val STEPS_HISTORY: Byte = 0xA1.toByte()          // l.java, via f.java e()
        const val SLEEP_HISTORY: Byte = 0xA2.toByte()          // l.java i(), line 332
        const val HEART_RATE_HISTORY: Byte = 0xA3.toByte()     // l.java h(), line 307
        const val BLOOD_PRESSURE_HISTORY: Byte = 0xA4.toByte() // l.java e(), line 232
        const val SPO2_HISTORY: Byte = 0xA5.toByte()           // l.java d(), line 207
        const val TEMPERATURE_HISTORY: Byte = 0xA6.toByte()    // l.java g(), line 282
        const val BREATHE_HISTORY: Byte = 0xA7.toByte()        // l.java f(), line 257

        /**
         * Device→app ACK of one of our commands: payload `[serHi, serLo, cmd, status]`
         * (`x5/d.java i()`). Releases the command gate for the matching serial.
         */
        const val DEVICE_ACK: Byte = 0xFE.toByte()

        /**
         * App→device ACK of a device frame, sent as its own framed command with the *inbound*
         * frame's serial in the payload (`x5/d.java b()` → `j((byte) -1, …)`).
         */
        const val APP_ACK: Byte = 0xFF.toByte()

        /**
         * File-transfer command ids, excluded from the ACK handshake by `x5/d.java d()`:
         * `{0x84, 0x80, 0x82, 0x86, 0x85}`. PulseLoop never sends these — listed so
         * [isFileTransfer] can mirror the vendor's gate rather than re-deriving it.
         */
        private val FILE_TRANSFER_CMDS = setOf(
            0x84.toByte(), 0x80.toByte(), 0x82.toByte(), 0x86.toByte(), 0x85.toByte(),
        )

        fun isFileTransfer(cmd: Byte): Boolean = cmd in FILE_TRANSFER_CMDS
    }

    // ── JieLi `0xAB` addressing (`y5/c.java a()`, the full triple→msgId table) ───

    /**
     * A JieLi `{cmd, key, keyFlag}` triple — the first three bytes of every `0xAB` frame body, in
     * both directions. `keyFlag` convention as used by the table: `0x00` set, `0x10` get/sync,
     * `0x20` bind variants, `0x30` a second sync variant.
     */
    data class JLTriple(val cmd: Byte, val key: Byte, val keyFlag: Byte) {
        val bytes: ByteArray get() = byteArrayOf(cmd, key, keyFlag)
    }

    object JieLi {
        // Every triple below appears verbatim in `y5/c.java a()`'s map.
        val SET_TIME = JLTriple(0x02, 0x01, 0x00)          // y5/c.java:17
        val BATTERY = JLTriple(0x02, 0x03, 0x10)           // y5/c.java:19
        val DEVICE_INFO = JLTriple(0x02, 0x04, 0x10)       // y5/c.java:20
        val PROFILE = JLTriple(0x02, 0x06, 0x00)           // y5/c.java:26
        val GOAL = JLTriple(0x02, 0x07, 0x00)              // y5/c.java:25
        val UNITS = JLTriple(0x02, 0x11, 0x00)             // y5/c.java:23
        val BIND_STATUS = JLTriple(0x03, 0x01, 0x00)       // y5/c.java:14
        val BIND = JLTriple(0x03, 0x01, 0x20)              // y5/c.java:15
        /**
         * NOTE — iOS `RWfitProtocol.swift` records unbind as `{0x03, 0x01, 0x30}`. The vendor's
         * table has no such entry; the only other group-3 `0x20` triple is `{0x03, 0x02, 0x20}`
         * (`y5/c.java:16`). Left unresolved rather than guessed: [RWfitEncoder] does not send a
         * JieLi unbind until this is confirmed from the sender, and Forget falls back to dropping
         * the link. See the open question in `docs/ios-sync.md`.
         */
        val UNBIND_UNCONFIRMED = JLTriple(0x03, 0x02, 0x20)

        /**
         * `06 09 00` — the unified realtime-measurement toggle. Its inbound ACK is the one special
         * case in the vendor's app→device ACK builder: for `cmd == 6 && key == 9` the ACK body is
         * four bytes `[cmd, key, keyFlag, 0x00]` rather than the usual three
         * (`r5/b.java:436-443`).
         */
        val REALTIME_MEASURE = JLTriple(0x06, 0x09, 0x00)

        /** History sync for one stream: `05 <type> 10` (`y5/c.java:74-91`). */
        fun historySync(type: Byte) = JLTriple(0x05, type, 0x10)
    }

    /**
     * JieLi `05`-group data-type bytes — used both as the `<type>` in [JieLi.historySync] and as the
     * type byte of the realtime-measure command (`y5/c.java:74-91`).
     */
    object JLDataType {
        const val STEPS: Byte = 0x02
        const val HEART_RATE: Byte = 0x03
        const val BLOOD_PRESSURE: Byte = 0x04
        const val SLEEP: Byte = 0x05
        const val TEMPERATURE: Byte = 0x08
        const val SPO2: Byte = 0x09
        const val HRV: Byte = 0x0A
        const val STRESS: Byte = 0x0D
        const val BLOOD_SUGAR: Byte = 0x10
    }

    /**
     * One history stream, unified across the two framings so the sync engine doesn't care which
     * wire it rides. `legacyCommand == null` marks a stream the legacy protocol has no request for
     * — the vendor's legacy sync cascade (`blesdk/service/l.java`) covers exactly seven streams, so
     * HRV, stress and blood sugar are JieLi-only. `jlType == null` is the mirror case: breathe has
     * no `05`-group type.
     */
    enum class HistoryType(val legacyCommand: Byte?, val jlType: Byte?, val label: String) {
        STEPS(Legacy.STEPS_HISTORY, JLDataType.STEPS, "activity"),
        SLEEP(Legacy.SLEEP_HISTORY, JLDataType.SLEEP, "sleep"),
        HEART_RATE(Legacy.HEART_RATE_HISTORY, JLDataType.HEART_RATE, "heart rate"),
        BLOOD_PRESSURE(Legacy.BLOOD_PRESSURE_HISTORY, JLDataType.BLOOD_PRESSURE, "blood pressure"),
        SPO2(Legacy.SPO2_HISTORY, JLDataType.SPO2, "blood oxygen"),
        TEMPERATURE(Legacy.TEMPERATURE_HISTORY, JLDataType.TEMPERATURE, "temperature"),
        BREATHE(Legacy.BREATHE_HISTORY, null, "breathing"),
        HRV(null, JLDataType.HRV, "HRV"),
        STRESS(null, JLDataType.STRESS, "stress"),
        BLOOD_SUGAR(null, JLDataType.BLOOD_SUGAR, "blood sugar"),
    }

    // ── Checksums ────────────────────────────────────────────────────────────────

    /**
     * XOR fold over the whole array, legacy framing's payload checksum (`y5/b.java o()`).
     * Undefined for an empty array in the vendor (it indexes `[0]`); the encoder writes `0x00`
     * for an empty payload instead of calling this (`x5/d.java j()`).
     */
    fun xorChecksum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Byte {
        var cs = 0
        for (i in offset until offset + length) cs = cs xor (data[i].toInt() and 0xFF)
        return cs.toByte()
    }

    /**
     * CRC-16/ARC (init `0x0000`, reflected poly `0xA001`, no final xor) — the JieLi body checksum.
     * The vendor ships it as a 256-entry table (`y5/d.java f20005a`, whose `[1] = 0xC0C1`,
     * `[2] = 0xC181`, `[3] = 0x0140` identify it as ARC) and formats the result `"%04x"`, so the
     * two frame bytes are **big-endian**: high byte first (`x5/c.java g()`, lines 213-225).
     */
    fun crc16Arc(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0x0000
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    // ── Small byte helpers, mirroring `y5/b.java` ────────────────────────────────

    /** `y5/b.java a(value, 2)` — big-endian u16. */
    fun u16BE(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    /** `y5/b.java j(byte[])` over two bytes — big-endian u16. */
    fun readU16BE(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
