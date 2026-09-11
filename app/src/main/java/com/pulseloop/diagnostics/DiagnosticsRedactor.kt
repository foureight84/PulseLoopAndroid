package com.pulseloop.diagnostics

/**
 * Scrubs PHI/PII from a diagnostics report while keeping it useful for debugging.
 *
 * What's removed: physiological values (HR, SpO₂, BP, glucose, temperature, stress, HRV,
 * sleep, activity), the ring's serial suffix, and BLE MAC addresses.
 * What's kept: device + ring model, Android version, firmware, capabilities, command
 * opcodes, decoded kinds, error/status frames, service UUIDs, timestamps — i.e. everything
 * needed to follow the protocol flow and spot the error, just not the measured numbers.
 *
 * Health-measurement BLE frames carry the values in their payload, so those are reduced to
 * the opcode byte. Control/protocol frames (acks, status, time-sync, battery, firmware, bind)
 * carry no vitals and are kept whole — that's the data most connection/pairing bugs need.
 */
object DiagnosticsRedactor {
    /** `RingDecodedEvent.FramePending.kind` for the chunk that opens a frame — it carries the
     *  routing header, so it masks like any other health frame of its family. */
    const val FRAME_START = "frame_start"
    /** `RingDecodedEvent.FramePending.kind` for a chunk in the middle of a frame: no header, so
     *  nothing beyond byte 0 may survive. Both are asserted against the decoder in
     *  `DiagnosticsRedactorTest`. */
    const val FRAME_CONTINUATION = "frame_chunk"

    /** Decoded kinds whose BLE payload carries a physiological/health value → mask payload. */
    private val HEALTH_KINDS = setOf(
        "activity", "activity_bucket", "hr_sample", "spo2_progress", "spo2_result",
        "sleep_timeline", "history_measurement", "stress_sample", "hrv_sample", "temperature_sample",
        "sport_telemetry",
        // Half of a frame still being reassembled (RingDecodedEvent.FramePending). Masked because
        // what it will decode to is not yet known, and a chunk of a multi-frame health reply holds
        // samples in the same shape as the assembled one. `unknown` stays unmasked deliberately —
        // control and pairing frames are what most connection reports are taken for — so a chunk
        // has to be named something other than that, or it leaves by the wrong door.
        FRAME_START, FRAME_CONTINUATION,
    )

    private val MAC = Regex("\\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")

    /**
     * How many leading bytes survive masking for [kind] on [deviceType].
     *
     * A continuation chunk is the exception to the header rule: it is the *middle* of a frame, so it
     * has no routing header at all and byte 0 onwards is payload. Keeping its family's header length
     * would keep up to six bytes of samples, which is the opposite of the intent.
     */
    private fun keepBytes(kind: String, deviceType: String): Int =
        if (kind == FRAME_CONTINUATION) 1 else headerBytes(deviceType)

    /**
     * How many leading bytes of a frame are routing, not measurement, per protocol family.
     *
     * **An unrecognised family keeps one byte, and that matters for more than tidiness.** The
     * header length has to come from the family that *sent the packet*, not from whichever ring
     * happens to be connected when the report is exported: masking a Colmi frame with CRP's
     * six-byte header would export five bytes of someone's samples. Packets captured before the
     * family was recorded alongside them have no answer here, so they fall to this branch and are
     * masked from byte 1 — less useful, never wrong in the direction that leaks.
     *
     * Masking from byte 1 keeps a health frame's values out of a report but also throws away the
     * bytes that say *which* value it was: on CRP the group and command live at offsets 4 and 5, so
     * a masked frame could not be told apart from any other health frame, and issue #58 could not
     * establish from the attached report whether an all-day SpO₂ reply carried samples or not.
     * These headers carry no physiological data — they are the same bytes the app writes when it
     * *asks* for the record, and outbound queries are already exported unmasked — so keeping them
     * costs no privacy and is most of what a protocol report is for.
     */
    private fun headerBytes(deviceType: String): Int = when (deviceType) {
        // `FD DA 10 <len> <group> <cmd>` — CRPProtocol.HEADER_SIZE.
        "CRP" -> 6
        // `<group> <cmd> <len-lo> <len-hi>` — YCBTFrame.frame(). Every family that drives a
        // YCBTDriver, including the hardware-validated R10M path (`RingDeviceType.YCBT`).
        "YCBT", "TK5", "COLMI_SMART_HEALTH" -> 4
        // Everything else (Colmi/QRing, jring, LuckRing, RWfit) puts its opcode in byte 0.
        else -> 1
    }

    /**
     * For a health-measurement frame, keep the routing header and mask the rest (the values live
     * in the payload). Non-health frames are returned unchanged. [hex] is contiguous lowercase;
     * [deviceType] is the report's `RingDeviceType` name, which decides how long that header is.
     */
    fun maskPacketHex(hex: String, kind: String, deviceType: String = ""): String {
        if (kind !in HEALTH_KINDS || hex.length <= 2) return hex
        val byteCount = hex.length / 2
        val keep = keepBytes(kind, deviceType).coerceAtMost(byteCount - 1)
        return hex.substring(0, keep * 2) + "··".repeat(byteCount - keep)
    }

    /** Mask BLE MAC addresses anywhere in free text (logcat, log messages, metadata). */
    fun scrubText(text: String): String = MAC.replace(text, "··:··:··:··:··:··")

    /** Strip the ring's serial suffix, keeping the model (e.g. "COLMI R10_1203" → "COLMI R10").
     *  Delegates to the one shared suffix rule so the UI label and the privacy scrub can't drift. */
    fun maskRingName(name: String): String = com.pulseloop.ring.ringNameWithoutSerial(name)
}
