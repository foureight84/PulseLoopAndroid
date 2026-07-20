package com.pulseloop.ring

/**
 * Ported from LuckRingProtocol.swift (iOS #90).
 *
 * LuckRing / TK18 ("K6 Protocol B") protocol primitives — GATT topology, the fixed 20-byte packet
 * framing, opcodes, the MixInfo TLV, and little-endian byte helpers. This is the wire language the
 * whole 0xFF64 LuckRing family speaks (PID families 618/818/118/518/S2, sold under simsonlab and
 * other brands); what makes a *TK18* a TK18 is only its advertised identity + capability set, which
 * live in [LuckRingCoordinator].
 *
 * Ground truth is the decompiled vendor SDK (`ce.com.cenewbluesdk`, internal family "K6"), decompiled
 * directly into `decompiled-coolring/` at the repo root: `CEBC.java` (opcodes), `queue/b.java`
 * (framing + the app-ACK rule), `K6SendDataManager.java` (`sendAynInfoDetail()` — the connect
 * bundle), `entity/k6/K6_*.java` (struct byte layouts). Cross-checked directly against this decompile
 * (not transcribed from iOS blind): `queue/b.java`'s `getSendData`/`Analysis` confirm the exact 20-byte
 * head/continuation layout and the app-ACK bytes; `CEBC.java` confirms every cmdType/dataType/sleep-
 * status constant used below.
 *
 * **Framing.** Every write/notify is a fixed **20-byte** packet. The head packet is
 *   `[0]=0 [1]=devType [2]=#continuation-pages [3]=rolling-seq [4]=cmdType [5]=dataType`
 *   `[6..7]=CRC16 (always 0x0000 — disabled in vendor code) [8..9]=payloadLen LE [10..19]=payload[0..9]`
 * and each continuation is `[0]=1-based page index [1..19]=next 19 payload bytes`. All integers are
 * little-endian. There is no crypto and no CRC to compute; binding is the MixInfo bundle (dataType 110).
 */
object LuckRingUUIDs {
    /** Primary protocol service. */
    const val SERVICE = "0000f618-0000-1000-8000-00805f9b34fb"
    /** Notify characteristic (CCCD) — the ring streams every reply / data frame here. */
    const val NOTIFY = "0000b001-0000-1000-8000-00805f9b34fb"
    /** Write characteristic — write-without-response only (see RingBLEClient's write-type note). */
    const val WRITE = "0000b002-0000-1000-8000-00805f9b34fb"

    /** Standard BLE Heart Rate service — present but deliberately not subscribed (mirrors the YCBT
     *  rationale: the proprietary `07` stream reflects real finger contact). Documented, not used. */
    const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"

    /** The head packet's `devType` byte. TK18 is a **618-family** unit (`CEBC.PID_TYPE.PID_618 == 1`);
     *  the ring never validates an inbound head's devType, so this only seeds the first outbound
     *  packets before any frame has been received. */
    const val DEVICE_TYPE: UByte = 1u
}

/** The `cmdType` byte (`CEBC.K6.CMD_TYPE_*`). The app ACKs a device-initiated **SEND**; it never ACKs
 *  an ACK or a SEND_NO_ACK (which by definition expects none). */
enum class LuckRingCmdType(val rawValue: UByte) {
    SEND(1u), SEND_NO_ACK(2u), REQUEST(3u), ACK(4u);

    companion object {
        fun fromRaw(value: UByte): LuckRingCmdType = entries.find { it.rawValue == value } ?: SEND
    }
}

/** The `dataType` opcodes (`CEBC.K6.DATA_TYPE_*`). Only the ones PulseLoop drives or decodes are
 *  named; the rest of the vendor table (alarms, watch faces, contacts, OTA, …) has no product
 *  surface here. */
object LuckRingDataType {
    const val DEV_INFO: UByte = 2u          // 6B -> firmware "customer.hardware.code.picture.font"
    const val BATTERY: UByte = 3u           // [percent][charging]
    const val REAL_SPORT: UByte = 4u        // live step buckets
    const val HISTORY_SPORT: UByte = 5u     // stored step buckets
    const val SLEEP: UByte = 6u             // paged sleep timeline
    const val REAL_HEART: UByte = 7u        // live HR (envelope + 5B records)
    const val HISTORY_HEART: UByte = 8u     // stored HR
    const val DEV_SYNC: UByte = 9u          // settings sync -- reply is a MixInfo TLV
    const val MIX_SPORT: UByte = 10u        // workout records (skipped in v1)
    const val FIND_DEVICE: UByte = 11u      // buzz the ring
    const val FUNCTION_CONTROL: UByte = 22u // capability bitmap (obfuscated -- not mapped)
    const val EXERCISE_HEART: UByte = 17u   // workout HR (envelope + 5B records)
    const val REAL_BP: UByte = 18u          // live blood pressure
    const val REAL_O2: UByte = 20u          // live SpO2
    const val REAL_HR: UByte = 24u          // real-HR toggle (write side)
    const val HISTORY_O2: UByte = 40u       // stored SpO2
    const val HISTORY_BP: UByte = 41u       // stored blood pressure
    const val HISTORY_HRV: UByte = 42u      // stored HRV
    const val REAL_HRV: UByte = 45u         // live HRV toggle + stream
    const val REAL_TEMP: UByte = 46u        // live temperature toggle + stream
    const val HISTORY_TEMP: UByte = 47u     // stored temperature
    const val STRESS: UByte = 52u           // body-recovery / stress (live)
    const val STRESS_HISTORY: UByte = 53u   // body-recovery / stress (stored)
    const val USER_INFO: UByte = 102u       // 9B profile
    const val LANGUAGE: UByte = 103u
    const val TIME: UByte = 104u            // 9B clock
    const val DATA_SWITCH: UByte = 109u     // 1 enables the real-time pushes
    const val MIX_INFO: UByte = 110u        // the binding / startup TLV bundle
    const val GOALS: UByte = 111u           // 16B goals
    const val RESET: UByte = 118u
    const val PAIR_FINISH: UByte = 120u     // ring -> app: pairing complete
    const val HEART_AUTO_SWITCH: UByte = 128u // 8B auto-monitoring config (autoHR/interval/autoO2)
    const val CALL_ALARM: UByte = 124u
    const val UNBIND: UByte = 159u
}

/** Little-endian helpers. Every K6 integer is LE (`ByteUtil.int2bytes2` / `intToByte4` / `byte4ToInt`). */
object LuckRingBytes {
    fun u16(b: List<UByte>, i: Int): Int {
        if (b.size < i + 2) return 0
        return b[i].toInt() or (b[i + 1].toInt() shl 8)
    }

    fun u24(b: List<UByte>, i: Int): Int {
        if (b.size < i + 3) return 0
        return b[i].toInt() or (b[i + 1].toInt() shl 8) or (b[i + 2].toInt() shl 16)
    }

    fun u32(b: List<UByte>, i: Int): Long {
        if (b.size < i + 4) return 0
        return b[i].toLong() or (b[i + 1].toLong() shl 8) or (b[i + 2].toLong() shl 16) or (b[i + 3].toLong() shl 24)
    }

    fun le16(value: Int): List<UByte> = listOf((value and 0xff).toUByte(), ((value shr 8) and 0xff).toUByte())

    fun le32(value: Long): List<UByte> = listOf(
        (value and 0xff).toUByte(),
        ((value shr 8) and 0xff).toUByte(),
        ((value shr 16) and 0xff).toUByte(),
        ((value shr 24) and 0xff).toUByte(),
    )
}

/**
 * A *logical* K6 frame -- one command or one reassembled data frame, independent of how many
 * 20-byte packets carry it. `cmdType`/`dataType`/`payload` are the meaning; `seq`/`devType` are the
 * framing identifiers an ACK must echo back (`queue/b.java` copies `bArr[3]`/`bArr[1]` into its reply).
 */
data class LuckRingFrame(
    val cmdType: LuckRingCmdType,
    val dataType: UByte,
    val payload: List<UByte>,
    val seq: UByte = 0u,
    val devType: UByte = LuckRingUUIDs.DEVICE_TYPE,
)

/**
 * Splits a logical frame into exact 20-byte packets, and builds the mandatory app-ACK.
 *
 * Page math mirrors `queue/b.java`'s `a(length)`: the head carries the first 10 payload bytes, each
 * continuation the next 19, so `pages = ceil((len-10)/19)` (0 when `len <= 10`). Every packet is
 * zero-padded to 20 bytes; `[6..7]` is always `0x0000` because the vendor sends `figureCrc16()==0`.
 */
object LuckRingPacketizer {
    const val PACKET_SIZE = 20
    private const val HEAD_PAYLOAD = 10
    private const val CONTINUATION_PAYLOAD = 19

    /** The number of continuation pages a payload of `length` bytes needs. */
    fun continuationPages(payloadLength: Int): Int {
        if (payloadLength <= HEAD_PAYLOAD) return 0
        val remainder = payloadLength - HEAD_PAYLOAD
        return remainder / CONTINUATION_PAYLOAD + (if (remainder % CONTINUATION_PAYLOAD > 0) 1 else 0)
    }

    fun packets(frame: LuckRingFrame): List<ByteArray> {
        val payload = frame.payload
        val pages = continuationPages(payload.size)

        val head = ByteArray(PACKET_SIZE)
        head[0] = 0
        head[1] = frame.devType.toByte()
        head[2] = minOf(pages, 255).toByte()
        head[3] = frame.seq.toByte()
        head[4] = frame.cmdType.rawValue.toByte()
        head[5] = frame.dataType.toByte()
        // [6..7] CRC16 disabled (0x0000); [8..9] payload length LE.
        head[8] = (payload.size and 0xff).toByte()
        head[9] = ((payload.size shr 8) and 0xff).toByte()
        for (i in 0 until minOf(HEAD_PAYLOAD, payload.size)) {
            head[HEAD_PAYLOAD + i] = payload[i].toByte()
        }
        val out = mutableListOf(head)

        if (pages > 0) {
            for (page in 1..pages) {
                val packet = ByteArray(PACKET_SIZE)
                packet[0] = minOf(page, 255).toByte()
                val start = HEAD_PAYLOAD + (page - 1) * CONTINUATION_PAYLOAD
                for (i in 0 until CONTINUATION_PAYLOAD) {
                    if (start + i < payload.size) packet[1 + i] = payload[start + i].toByte()
                }
                out.add(packet)
            }
        }
        return out
    }

    /**
     * The app-ACK for a device-initiated SEND: `[4]=ack, [5]=dataType, len=1, payload[0]=1`. Echoes
     * the ring's own `seq`/`devType` so the ring can pair the ACK to the frame it just sent, and stops
     * it retransmitting. (`queue/b.java` writes `bArr[10]=1` because CRC is disabled and always "matches".)
     */
    fun ack(dataType: UByte, seq: UByte, devType: UByte): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        packet[1] = devType.toByte()
        packet[3] = seq.toByte()
        packet[4] = LuckRingCmdType.ACK.rawValue.toByte()
        packet[5] = dataType.toByte()
        packet[8] = 1                 // payload length = 1
        packet[10] = 1                // status 1 = accepted (CRC disabled => always accepted)
        return packet
    }
}

/**
 * Reassembles 20-byte notify packets into whole logical frames.
 *
 * A head packet (`[0]==0`) starts a frame and declares its payload length at `[8..9]` and its
 * continuation count at `[2]`; a device ACK head (`[4]==4`) is self-contained. Continuations
 * (`[0]!=0`) must arrive in strict 1-based order. A fresh head mid-assembly abandons the partial one
 * (`queue/b.java` overwrites its single buffer), and a continuation with no head is dropped -- so one
 * truncated frame after a disconnect can't poison the next.
 */
class LuckRingFrameAssembler {
    private data class Partial(
        val cmdType: LuckRingCmdType,
        val dataType: UByte,
        val seq: UByte,
        val devType: UByte,
        val totalPages: Int,
        val declaredLength: Int,
        val payload: MutableList<UByte>,
        var receivedPages: Int,
    )

    private var partial: Partial? = null

    /** Drop any half-assembled frame. A fresh driver is built per connection, so this is for
     *  reconnects that reuse one (and for tests). */
    fun reset() {
        partial = null
    }

    /** Feed one 20-byte notify packet; returns the frame it completed, or null while still assembling. */
    fun append(data: ByteArray): LuckRingFrame? {
        if (data.size < LuckRingPacketizer.PACKET_SIZE) return null
        val bytes = data.map { it.toUByte() }
        return if (bytes[0] == 0u.toUByte()) handleHead(bytes) else handleContinuation(bytes)
    }

    private fun handleHead(bytes: List<UByte>): LuckRingFrame? {
        val devType = bytes[1]
        val seq = bytes[3]
        val rawCmd = (bytes[4].toInt() and 0x0f).toUByte()
        val dataType = bytes[5]
        val cmdType = LuckRingCmdType.fromRaw(rawCmd)

        // A device ACK is a complete, single-packet frame -- its status byte is at [10].
        if (cmdType == LuckRingCmdType.ACK) {
            partial = null
            return LuckRingFrame(LuckRingCmdType.ACK, dataType, listOf(bytes[10]), seq, devType)
        }

        val declaredLen = LuckRingBytes.u16(bytes, 8)
        val totalPages = bytes[2].toInt()
        val firstChunkEnd = minOf(20, 10 + maxOf(0, declaredLen))
        val firstChunk = bytes.subList(10, firstChunkEnd)

        if (totalPages == 0) {
            partial = null
            return LuckRingFrame(cmdType, dataType, firstChunk.take(declaredLen), seq, devType)
        }

        // Multi-packet: seed the buffer with the head's first 10 payload bytes and wait for continuations.
        partial = Partial(cmdType, dataType, seq, devType, totalPages, declaredLen, firstChunk.toMutableList(), 0)
        return null
    }

    private fun handleContinuation(bytes: List<UByte>): LuckRingFrame? {
        val current = partial ?: return null   // continuation with no head -- drop it
        val pageIndex = bytes[0].toInt()
        if (pageIndex != current.receivedPages + 1) {
            // A jumped/duplicated page can't be recovered into this frame; abandon it.
            partial = null
            return null
        }
        current.payload.addAll(bytes.subList(1, 20))
        current.receivedPages = pageIndex

        if (current.receivedPages < current.totalPages) return null
        partial = null
        // Trim to the head's declared length -- the last continuation is zero-padded to 20 bytes, and
        // the vendor codec sizes its buffer from the declared length. Without the cut, decoders that
        // derive a record stride from the payload size (temperature's 5B/8B variants) would misparse.
        return LuckRingFrame(current.cmdType, current.dataType,
            current.payload.take(current.declaredLength), current.seq, current.devType)
    }
}

/**
 * The MixInfo TLV (`K6_MixInfoStruct`): the container the K6 protocol uses for its binding bundle
 * (dataType 110) and its settings-sync reply (dataType 9). Layout:
 *   `[totalLen u16 LE][itemCount u8]` then, per property, `[propLen u16 LE = dataLen+3][propType u8][data]`.
 * `totalLen` is `(sum propBytes) + 1` -- the size of everything after the length field itself -- and is
 * ignored on decode, which walks `itemCount` properties from offset 3.
 */
object LuckRingMixInfoTLV {
    data class Property(val type: UByte, val data: List<UByte>)

    fun encode(properties: List<Property>): List<UByte> {
        val propBytes = mutableListOf<UByte>()
        for (property in properties) {
            val propLen = property.data.size + 3
            propBytes.addAll(LuckRingBytes.le16(propLen))
            propBytes.add(property.type)
            propBytes.addAll(property.data)
        }
        val out = mutableListOf<UByte>()
        out.addAll(LuckRingBytes.le16(propBytes.size + 1))
        out.add(properties.size.and(0xff).toUByte())
        out.addAll(propBytes)
        return out
    }

    fun decode(bytes: List<UByte>): List<Property> {
        if (bytes.size < 3) return emptyList()
        val itemCount = bytes[2].toInt()
        val properties = mutableListOf<Property>()
        var i = 3
        for (n in 0 until itemCount) {
            if (i + 3 > bytes.size) break
            val propLen = LuckRingBytes.u16(bytes, i)
            val dataLen = propLen - 3
            if (dataLen < 0 || i + 3 + dataLen > bytes.size) break
            val type = bytes[i + 2]
            val data = bytes.subList(i + 3, i + 3 + dataLen)
            properties.add(Property(type, data))
            i += propLen
        }
        return properties
    }
}
