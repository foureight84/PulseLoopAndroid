package com.pulseloop.ring

import java.time.Instant
import java.time.ZoneId

/**
 * Ported from YCBTProtocol.swift (iOS #82).
 *
 * Yucheng **YCBT** protocol primitives — GATT topology, framing, byte/epoch helpers, opcodes, and
 * the health-record type table. This is the wire language the TK5 speaks and, byte-identically,
 * the SmartHealth-flavoured Colmi rings. Everything here is deliberately device-agnostic: what
 * varies between families is their advertised identity and which capability bits they claim, both
 * of which live in their coordinator ([TK5Coordinator]/[ColmiSmartHealthCoordinator]), so a second
 * family reuses this file verbatim.
 *
 * Ground truth is the decompiled vendor SDK (`com.yucheng.ycbtsdk`, v4.0.10, shipped inside the
 * SmartHealth Android app `com.zhuoting.healthyucheng`): `CMD.java` (opcodes), `Constants.java`
 * (16-bit dataTypes), `DataUnpack.java` (record parsing) and `YCBTClientImpl.java` (framing, queue,
 * history assembly) — decompiled directly into `decompiled-smarthealth/` in this repo, not
 * transcribed from iOS. **BLE connection-lifecycle behavior (GATT open, MTU, bonding, CCCD
 * indicate-vs-notify) is verified separately against the same vendor SDK's `gatt/BleHelper.java`,
 * not ported from iOS's CoreBluetooth calls** — see `RingBLEClient`'s YCBT-specific notes.
 *
 * **Wire format** (both the command channel `be940001` and the async stream `be940003`):
 *   `[type:1][cmd:1][len:2 LE][payload:N][crc16:2 LE]`
 *   where `len` is the *total* frame length (header + payload + crc) and the CRC is
 *   **CRC16/CCITT-FALSE** (poly 0x1021, init 0xFFFF, no reflection) over every byte before it.
 *   A command's 16-bit `dataType` in the SDK is exactly `(type shl 8) or cmd`.
 */
/** Convert a logical `[UByte]` command/payload to the `ByteArray` [RingCommandWriter.enqueue] expects. */
fun List<UByte>.toRawByteArray(): ByteArray = map { it.toByte() }.toByteArray()

object YCBTUUIDs {
    /** Primary protocol service. */
    const val SERVICE = "be940000-7333-be46-b7ae-689e71722bd5"
    /** Command channel — the app writes here AND receives command replies here (write + indicate). */
    const val COMMAND = "be940001-7333-be46-b7ae-689e71722bd5"
    /** Async stream — live HR / steps / SpO2 and downloaded history records (indicate). */
    const val STREAM = "be940003-7333-be46-b7ae-689e71722bd5"
}

/** One validated, unframed YCBT frame. */
data class YCBTFrame(val type: UByte, val cmd: UByte, val payload: List<UByte>) {
    companion object {
        /** Parse and CRC-validate one inbound frame. Returns null on a short frame or CRC mismatch. */
        fun validating(data: ByteArray): YCBTFrame? {
            val bytes = data.map { it.toUByte() }
            if (bytes.size < 6) return null
            val declared = bytes[2].toInt() or (bytes[3].toInt() shl 8)
            if (declared != bytes.size) return null
            val crcGiven = bytes[bytes.size - 2].toInt() or (bytes[bytes.size - 1].toInt() shl 8)
            if (crc16(bytes.subList(0, bytes.size - 2)) != crcGiven) return null
            return YCBTFrame(bytes[0], bytes[1], bytes.subList(4, bytes.size - 2))
        }

        /**
         * Build a framed packet from a logical command `[type, cmd, payload...]`: insert the total
         * length field after the two header bytes and append the little-endian CRC16.
         */
        fun frame(logical: List<UByte>): ByteArray {
            if (logical.size < 2) return logical.map { it.toByte() }.toByteArray()
            val total = logical.size + 4   // + 2-byte length field + 2-byte CRC
            val out = mutableListOf(logical[0], logical[1], (total and 0xff).toUByte(), ((total shr 8) and 0xff).toUByte())
            out.addAll(logical.subList(2, logical.size))
            val crc = crc16(out)
            out.add((crc and 0xff).toUByte())
            out.add(((crc shr 8) and 0xff).toUByte())
            return out.map { it.toByte() }.toByteArray()
        }

        /** CRC16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no input/output reflection, no final xor). */
        fun crc16(bytes: List<UByte>): Int {
            var crc = 0xFFFF
            for (b in bytes) {
                crc = crc xor (b.toInt() shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else (crc shl 1)
                }
                crc = crc and 0xFFFF
            }
            return crc
        }
    }
}

/**
 * Little-endian + epoch helpers. YCBT timestamps are **seconds since 2000-01-01 UTC**, not the
 * Unix epoch (confirmed against the iOS capture's wall-clock time).
 */
object YCBTBytes {
    /** Seconds between 1970-01-01 and 2000-01-01 (the YCBT epoch offset). */
    const val EPOCH_OFFSET_SECONDS = 946_684_800L

    fun u16(b: List<UByte>, i: Int): Int {
        if (b.size < i + 2) return 0
        return b[i].toInt() or (b[i + 1].toInt() shl 8)
    }

    /**
     * 3-byte little-endian. Sleep segment durations are u24 (`DataUnpack` reads bytes 5,6,7 of an
     * 8-byte segment), so a u16 read truncates any segment longer than 18h12m.
     */
    fun u24(b: List<UByte>, i: Int): Int {
        if (b.size < i + 3) return 0
        return b[i].toInt() or (b[i + 1].toInt() shl 8) or (b[i + 2].toInt() shl 16)
    }

    fun u32(b: List<UByte>, i: Int): Long {
        if (b.size < i + 4) return 0
        return b[i].toLong() or (b[i + 1].toLong() shl 8) or (b[i + 2].toLong() shl 16) or (b[i + 3].toLong() shl 24)
    }

    /**
     * Convert a ring timestamp (2000-epoch seconds) to an [Instant]. The ring has no timezone
     * concept — its clock is set from local wall-clock fields (see `YCBTSettingsEncoder.setTime`)
     * and ticks in local time, so decoding must un-apply the device's UTC offset to recover the
     * true absolute instant. Uses the *current* offset as an approximation of the offset in effect
     * when the timestamp was recorded — correct for same-session syncs, only wrong across a DST
     * transition that happens between recording and syncing.
     */
    fun date(ringSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): Instant {
        val offset = zone.rules.getOffset(Instant.now()).totalSeconds
        return Instant.ofEpochSecond(ringSeconds + EPOCH_OFFSET_SECONDS - offset)
    }

    /** Convert an [Instant] to ring seconds (2000-epoch), the inverse of [date]. */
    fun ringSeconds(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Long {
        val offset = zone.rules.getOffset(instant).totalSeconds
        return instant.epochSecond - EPOCH_OFFSET_SECONDS + offset
    }
}

/** Frame `type` byte — the command group. `Constants.DATATYPE` splits every opcode this way. */
object YCBTGroup {
    const val SETTING: UByte = 0x01u      // clock, user info, units, monitor enables
    const val GET: UByte = 0x02u          // device info, support bitmap, name, user config
    const val APP_CONTROL: UByte = 0x03u  // live-measurement start/stop, live-status push
    const val DEV_CONTROL: UByte = 0x04u  // device->app pushes (find phone, SOS, measurement done)
    const val HEALTH: UByte = 0x05u       // history: queries, data frames, terminal block
    const val REAL: UByte = 0x06u         // device->app realtime stream
}

/**
 * The `cmd` bytes we act on, by group ([YCBTGroup]). The Setting-group keys live in
 * [YCBTSettingKey] and the Health-group history keys in [YCBTHistoryType].
 */
object YCBTCommand {
    // Group 0x02 (Get)
    const val GET_DEVICE_INFO: UByte = 0x00u       // battery @payload[5], state @[4], firmware "[3].[2]"
    const val GET_SUPPORT_FUNCTION: UByte = 0x01u  // capability bitmap (see YCBTSupportFunction)
    const val GET_DEVICE_NAME: UByte = 0x03u
    const val GET_USER_CONFIG: UByte = 0x07u
    const val GET_CHIP_SCHEME: UByte = 0x1bu       // JieLi vs Nordic

    // Group 0x03 (AppControl)
    const val FIND_DEVICE: UByte = 0x00u          // make the ring buzz
    const val LIVE_MEASUREMENT: UByte = 0x2fu     // [enable, mode] — mode picks the sensor/LED
    const val LIVE_STATUS_PUSH: UByte = 0x09u     // enable the ring's continuous 06 00 status stream

    // Group 0x06 (Real — async stream on be940003)
    const val LIVE_STATUS: UByte = 0x00u          // steps + distance/calories, repeated
    const val LIVE_HEART_RATE: UByte = 0x01u      // 1-byte bpm
    const val LIVE_SPO2: UByte = 0x02u            // 1-byte SpO2 %
    const val LIVE_VITALS: UByte = 0x03u          // SBP/DBP/hr/hrv/spo2/temp
    const val LIVE_WEARING_STATUS: UByte = 0x13u  // [ts:u32][worn]
    const val LIVE_BATTERY: UByte = 0x15u         // [chargingStatus][percent]
}

/**
 * One health-history record type: the query key we write, the ack key its data frames carry back,
 * and the fixed record stride the reassembled buffer is sliced at. This table is the single source
 * of truth for both [YCBTHistoryTransfer] (which types to ask for, which frames belong to which
 * type) and [YCBTHealthRecords] (how to cut the buffer) — the two must never disagree.
 */
data class YCBTHistoryType(
    /** `05 <queryKey>` with an empty payload asks the ring for every stored record of this type. */
    val queryKey: UByte,
    /** The `cmd` the ring's data frames carry (a *different* key from the query). */
    val ackKey: UByte,
    /** Fixed record size in the reassembled buffer. Null => variable-length (sleep). */
    val recordStride: Int?,
    /** Human label for the sync-progress UI ("Syncing sleep..."). */
    val label: String,
) {
    companion object {
        val SPORT = YCBTHistoryType(0x02u, 0x11u, 14, "activity")
        val SLEEP = YCBTHistoryType(0x04u, 0x13u, null, "sleep")
        val HEART = YCBTHistoryType(0x06u, 0x15u, 6, "heart rate")
        val BLOOD = YCBTHistoryType(0x08u, 0x17u, 8, "blood pressure")
        val ALL = YCBTHistoryType(0x09u, 0x18u, 20, "vitals")
        val SPO2 = YCBTHistoryType(0x1au, 0x22u, 6, "blood oxygen")
        val TEMPERATURE = YCBTHistoryType(0x1eu, 0x26u, 7, "temperature")
        val COMPREHENSIVE = YCBTHistoryType(0x2fu, 0x30u, 44, "metabolic")
        val BODY_DATA = YCBTHistoryType(0x33u, 0x34u, 28, "body data")

        /**
         * Every type the SDK's `DataSyncUtils` can request, in its own ascending-key sync order —
         * and every type [YCBTHealthRecords] decodes. Both YCBT families query the whole catalog,
         * whatever their capability set says: a type the ring doesn't implement answers with a
         * no-data header or a `0xFC` (unsupported key), which [YCBTHistoryTransfer] skips —
         * permanently, for `0xFC`.
         */
        val CATALOG: List<YCBTHistoryType> = listOf(
            SPORT, SLEEP, HEART, BLOOD, ALL, SPO2, TEMPERATURE, COMPREHENSIVE, BODY_DATA,
        )
    }
}

/**
 * The measurement-mode byte — one table shared by the two commands that must agree on it: the
 * **`03 2f` start/stop** payload we write (`{enable, mode}`) and the **`04 13` status/result push**
 * the ring answers with (`[type][state]...`).
 */
object YCBTMeasurementMode {
    const val HEART_RATE: UByte = 0x00u
    const val BLOOD_PRESSURE: UByte = 0x01u
    const val SPO2: UByte = 0x02u
    const val RESPIRATORY_RATE: UByte = 0x03u
    const val TEMPERATURE: UByte = 0x04u
    const val BLOOD_SUGAR: UByte = 0x05u
    const val URIC_ACID: UByte = 0x06u
    const val BLOOD_KETONE: UByte = 0x07u
    const val BLOOD_FAT: UByte = 0x09u
    const val HRV: UByte = 0x0au
    const val STRESS: UByte = 0x0cu

    /**
     * The ring's **verdict** on a `03 2f` start. The reply is a single status byte — `0x00` =
     * accepted, non-zero = "I will not run that" — and it does not echo the mode.
     */
    fun isAccepted(status: UByte): Boolean = status == 0x00u.toUByte()
}

/**
 * Group 4 (**DevControl**) — the ring->app push channel: measurement progress/results, SOS,
 * find-phone, sedentary reminders. The app never *initiates* a `04 xx`; the only `04` frame it
 * writes is the ACK below.
 */
object YCBTDevControl {
    const val FIND_PHONE: UByte = 0x00u
    const val SOS: UByte = 0x05u
    const val MEASUREMENT_RESULT: UByte = 0x0eu  // [measureType][result]
    const val MEASUREMENT_STATUS: UByte = 0x13u  // [type][state] + the value for that type
    const val SEDENTARY_REMINDER: UByte = 0x16u
    const val SOS_CALL: UByte = 0x17u

    /**
     * `04 <key> {00}` — the mandatory push ACK. **The ring retransmits a push until it arrives**,
     * so we send it before we even parse the payload.
     */
    fun ack(key: UByte): List<UByte> = listOf(YCBTGroup.DEV_CONTROL, key, 0x00u)

    /** `result` byte of a `04 0e` MeasurementResult push: 1 = success, else failed/cancelled. */
    const val RESULT_SUCCESS: UByte = 0x01u
}

/** The Health group's two control keys and the ACK status bytes. */
object YCBTHealth {
    /**
     * `05 80` — inbound it terminates a transfer (`[totalPackets:u16][totalBytes:u16][crc16:u16]`);
     * outbound it is the mandatory block ACK.
     */
    const val TERMINAL_BLOCK: UByte = 0x80u
    /** Reassembled buffer matched the terminal frame's CRC16. */
    const val ACK_ACCEPTED: UByte = 0x00u
    /** CRC mismatch — the ring may re-send. */
    const val ACK_CRC_FAILURE: UByte = 0x04u
    /** A header frame carries `[recordCount:u16][totalPackets:u32][totalBytes:u32]`. */
    const val HEADER_PAYLOAD_LENGTH = 10
    /** The terminal block's payload length. */
    const val TERMINAL_PAYLOAD_LENGTH = 6
}

/**
 * Logical (unframed) Health-group commands. Shared so the transfer machine and a family's encoder
 * can never drift apart on the exact bytes.
 */
object YCBTHealthCommand {
    /** Ask for one history type: `05 <queryKey>` with an **empty** payload. */
    fun historyRequest(type: YCBTHistoryType): List<UByte> = listOf(YCBTGroup.HEALTH, type.queryKey)

    /** The mandatory end-of-transfer ACK: `05 80 {status}`. */
    fun historyBlockAck(status: UByte): List<UByte> = listOf(YCBTGroup.HEALTH, YCBTHealth.TERMINAL_BLOCK, status)
}

/**
 * Device-side rejection of a command. The SDK's `isError` treats **any** 1-byte response payload
 * in `0xFB..0xFF` as an error status rather than data — for *every* group.
 */
enum class YCBTFrameError(val rawValue: UByte) {
    UNSUPPORTED_COMMAND(0xfbu),   // the group byte isn't implemented
    UNSUPPORTED_KEY(0xfcu),       // the cmd byte isn't implemented on this firmware
    LENGTH(0xfdu),
    DATA(0xfeu),
    CRC(0xffu);

    /**
     * True when the ring is telling us it will *never* answer this type on this firmware, so the
     * transfer machine can stop asking for the rest of the session.
     */
    val isPermanent: Boolean get() = this == UNSUPPORTED_COMMAND || this == UNSUPPORTED_KEY

    companion object {
        /**
         * Detect an error frame. Must be checked *before* interpreting a payload as a
         * header/record/push, because a 1-byte error payload is otherwise indistinguishable from a
         * short header.
         */
        fun detect(payload: List<UByte>): YCBTFrameError? {
            if (payload.size != 1) return null
            return entries.find { it.rawValue == payload[0] }
        }
    }
}

/**
 * Reassembles GATT notifications into whole logical frames.
 *
 * A logical frame longer than `MTU-3` is split across notifications (and, symmetrically, several
 * short frames can land in a single notification). Validation keys off the declared total length
 * at bytes [2..3], exactly as `YCBTClientImpl`'s receive parser does. Garbage (a truncated tail
 * after a disconnect, a stray notification) is resynced by dropping one byte at a time until a
 * plausible header appears, so one bad byte can't poison the rest of a session.
 */
class YCBTFrameAssembler {
    /** Header + CRC with an empty payload — the shortest frame that can exist. */
    private val minFrameLength = 6

    /** No YCBT frame comes close to this; resync rather than wait forever for bytes that never arrive. */
    private val maxFrameLength = 1024

    /** Partial frames, per characteristic UUID: the command channel and the async stream interleave. */
    private val pending = mutableMapOf<String, MutableList<UByte>>()

    /** Drop every partial frame. A fresh driver is built per connection, so this exists for reconnects. */
    fun reset() {
        pending.clear()
    }

    /** Feed one notification; returns the complete logical frames it completed (0, 1, or several). */
    fun append(data: ByteArray, from: String): List<ByteArray> {
        val buffer = pending.getOrPut(from) { mutableListOf() }
        buffer.addAll(data.map { it.toUByte() })

        val frames = mutableListOf<ByteArray>()
        while (buffer.size >= 4) {
            val declared = buffer[2].toInt() or (buffer[3].toInt() shl 8)
            if (!isPlausibleGroup(buffer[0]) || declared < minFrameLength || declared > maxFrameLength) {
                buffer.removeAt(0)   // resync: this can't be a frame start
                continue
            }
            if (buffer.size < declared) break   // still waiting on the rest of this frame
            frames.add(buffer.subList(0, declared).map { it.toByte() }.toByteArray())
            repeat(declared) { buffer.removeAt(0) }
        }

        return frames
    }

    /** Only the six groups the ring ever sends us can legitimately start a frame. */
    private fun isPlausibleGroup(byte: UByte): Boolean =
        byte in YCBTGroup.SETTING..YCBTGroup.REAL
}

/**
 * Parser for the `02 01` **SupportFunction** reply: a variable-length bit array (bit 7 of each
 * byte first) in which the firmware declares what it actually implements. Mirrors
 * `DataUnpack.saveDeviceSupportFunctionData`.
 *
 * This is what lets one driver serve a whole *family* of rings whose SKUs disagree: a family
 * declares a capability as `bitmapGatedCapabilities` (see [WearableCoordinator]) and the connected
 * unit's own bitmap decides whether it is really there.
 */
object YCBTSupportFunction {
    /**
     * One capability bit: its byte, its bit index (7 = MSB, matching `(b shr n) and 1`), and the
     * payload length the SDK demands before it will read that byte at all.
     */
    private data class Bit(val byte: Int, val bit: Int, val minLength: Int, val capability: WearableCapability)

    /** Bit -> capability, each named with the `Constants.FunctionConstant` the SDK stores it under. */
    private val bits: List<Bit> = listOf(
        Bit(0, 7, 14, WearableCapability.STEPS),           // ISHASSTEPCOUNT
        Bit(0, 6, 14, WearableCapability.SLEEP),           // ISHASSLEEP
        Bit(0, 3, 14, WearableCapability.HEART_RATE),      // ISHASHEARTRATE
        Bit(0, 0, 14, WearableCapability.BLOOD_PRESSURE),  // ISHASBLOOD
        Bit(1, 3, 14, WearableCapability.SPO2),            // ISHASBLOODOXYGEN
        Bit(1, 1, 14, WearableCapability.HRV),             // ISHASHRV
        Bit(8, 0, 14, WearableCapability.TEMPERATURE),     // ISHASTEMP
        Bit(17, 3, 18, WearableCapability.BLOOD_SUGAR),    // ISHASBLOODSUGAR
        Bit(22, 6, 23, WearableCapability.STRESS),         // IS_HAS_PRESSURE
        // Fatigue rides the stress bit — the ring gives them one switch (see YCBTHealthRecords /
        // TK5Coordinator doc comments for the full rationale).
        Bit(22, 6, 23, WearableCapability.FATIGUE),         // IS_HAS_PRESSURE (same record)
        Bit(6, 4, 14, WearableCapability.FIND_DEVICE),      // ISHASFINDDEVICE
        Bit(15, 1, 18, WearableCapability.MANUAL_HEART_RATE),      // ISHATESTHEART
        Bit(15, 2, 18, WearableCapability.MANUAL_BLOOD_PRESSURE),  // ISHASTESTBLOOD
        Bit(15, 3, 18, WearableCapability.MANUAL_SPO2),            // ISHASTESTSPO2
        Bit(23, 0, 24, WearableCapability.MANUAL_HRV),             // IS_HAS_HRV_MEASUREMENT
    )

    /**
     * The capabilities this unit claims. A payload too short to clear even the SDK's own `>= 14`
     * gate yields the empty set — every bit's `minLength` is at least that — which under the
     * additive-only refinement means "no opinion": the family's baseline stands.
     */
    fun capabilities(payload: List<UByte>): Set<WearableCapability> =
        bits.filter { isSet(payload, it) }.map { it.capability }.toSet()

    /** Raw bit array (MSB first within each byte) for the debug feed / diagnostics. */
    fun rawBits(payload: List<UByte>): List<Boolean> =
        payload.flatMap { byte -> (0..7).map { ((byte.toInt() shr (7 - it)) and 1) == 1 } }

    private fun isSet(payload: List<UByte>, bit: Bit): Boolean {
        if (payload.size < bit.minLength || bit.byte >= payload.size) return false
        return ((payload[bit.byte].toInt() shr bit.bit) and 1) == 1
    }
}

/**
 * The `02 1b` **chipScheme** reply (`DataUnpack.unpackGetChipScheme`): one byte naming the
 * chipset/OTA family. Diagnostic only — PulseLoop does no firmware updates.
 */
object YCBTChipScheme {
    /** `bArr[0] and 0xFF`, except a value >= 240 is an error status, folded to 0 = "unknown/other". */
    fun value(payload: List<UByte>): Int {
        val first = payload.firstOrNull() ?: return 0
        if (first.toInt() >= 240) return 0
        return first.toInt()
    }

    /** `InnerUtils.isJieLiChipScheme`: 3, 4 and 5 are the JieLi families. */
    fun isJieLi(value: Int): Boolean = value in 3..5
}
