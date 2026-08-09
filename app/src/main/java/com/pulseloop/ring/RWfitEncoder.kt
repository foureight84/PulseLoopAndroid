package com.pulseloop.ring

import java.util.Calendar

/**
 * Builds RWfit commands for whichever framing the link turned out to be, ported from the vendor's
 * senders in `p.java` (`CmdHelper`) and `blesdk/service/l.java`.
 *
 * The two framings are **not** interchangeable at the payload level either — `p.java` has a
 * separate builder per framing for the same logical command, and they differ in more than the
 * envelope (see [timeSync]). Anything only one side implements is exposed as nullable rather than
 * faked on the other.
 */
class RWfitEncoder(
    private val legacy: RWfitLegacyCodec,
    private val jieli: RWfitJLCodec,
) {
    var framing: RWfitFraming = RWfitFraming.LEGACY

    /** Device info — the first thing the vendor asks for after connect. */
    fun deviceInfo(): ByteArray = when (framing) {
        RWfitFraming.LEGACY -> legacy.encode(RWfitProtocol.Legacy.DEVICE_INFO).frame
        RWfitFraming.JIELI -> jieli.encode(RWfitProtocol.JieLi.DEVICE_INFO)
    }

    fun battery(): ByteArray = when (framing) {
        RWfitFraming.LEGACY -> legacy.encode(RWfitProtocol.Legacy.BATTERY).frame
        RWfitFraming.JIELI -> jieli.encode(RWfitProtocol.JieLi.BATTERY)
    }

    /**
     * Clock sync. The two framings disagree on the year encoding — legacy sends the full four-digit
     * year as a big-endian u16 (`p.java u(Date)`), JieLi sends `year - 2000` in one byte
     * (`p.java v(Date)`). Getting this wrong sets the ring's clock ~2000 years out and every
     * history timestamp with it.
     */
    fun timeSync(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val year = cal.get(Calendar.YEAR)
        val month = (cal.get(Calendar.MONTH) + 1).toByte()
        val day = cal.get(Calendar.DAY_OF_MONTH).toByte()
        val hour = cal.get(Calendar.HOUR_OF_DAY).toByte()
        val minute = cal.get(Calendar.MINUTE).toByte()
        val second = cal.get(Calendar.SECOND).toByte()

        return when (framing) {
            RWfitFraming.LEGACY -> {
                val y = RWfitProtocol.u16BE(year)
                legacy.encode(
                    RWfitProtocol.Legacy.SET_TIME,
                    byteArrayOf(y[0], y[1], month, day, hour, minute, second),
                ).frame
            }
            RWfitFraming.JIELI -> jieli.encode(
                RWfitProtocol.JieLi.SET_TIME,
                byteArrayOf((year - 2000).toByte(), month, day, hour, minute, second),
            )
        }
    }

    /** The manifest of what history the ring is holding — legacy only (`x5/b.java v0()`). */
    fun syncManifest(): ByteArray? = when (framing) {
        RWfitFraming.LEGACY -> legacy.encode(RWfitProtocol.Legacy.SYNC_MANIFEST).frame
        RWfitFraming.JIELI -> null   // JieLi has no equivalent; each stream is requested directly
    }

    /**
     * One history stream. Legacy requests carry an **empty payload** — the ring replies with
     * everything it holds for that stream (`blesdk/service/l.java`).
     *
     * Returns null when the stream doesn't exist on this framing (HRV/stress/blood sugar are
     * JieLi-only; breathe is legacy-only).
     */
    fun history(type: RWfitProtocol.HistoryType): ByteArray? = when (framing) {
        RWfitFraming.LEGACY -> type.legacyCommand?.let { legacy.encode(it).frame }
        RWfitFraming.JIELI -> type.jlType?.let { jieli.encode(RWfitProtocol.JieLi.historySync(it)) }
    }

    /**
     * Realtime measurement toggle — `06 09 00 <type> 05 <enable>` (`u0.java n()`), **JieLi only**.
     * The vendor app has no legacy on-demand measurement command at all, which is why
     * [RWfitCoordinator] gates the manual/realtime capabilities behind the framing rather than
     * granting them to the whole family.
     */
    fun realtimeMeasure(dataType: Byte, enable: Boolean): ByteArray? = when (framing) {
        RWfitFraming.JIELI -> jieli.encode(
            RWfitProtocol.JieLi.REALTIME_MEASURE,
            byteArrayOf(dataType, 0x05, if (enable) 0x01 else 0x00),
        )
        RWfitFraming.LEGACY -> null
    }

    /** Unbind on Forget. Legacy `0x44` with an empty payload (`h0.java n()`, line 319). */
    fun unbind(): ByteArray? = when (framing) {
        RWfitFraming.LEGACY -> legacy.encode(RWfitProtocol.Legacy.UNBIND).frame
        // iOS records a JieLi unbind triple of {03,01,30}, but the vendor's table (y5/c.java) has
        // no such entry — see RWfitProtocol.JieLi.UNBIND_UNCONFIRMED. Not sent until confirmed:
        // guessing a bind-group triple risks re-binding or factory-resetting someone's ring.
        RWfitFraming.JIELI -> null
    }
}

/** Which wire format the connected ring speaks. Decided post-connect — see [RWfitDriver]. */
enum class RWfitFraming { LEGACY, JIELI }
