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
    /** Decoded kinds whose BLE payload carries a physiological/health value → mask payload. */
    private val HEALTH_KINDS = setOf(
        "activity", "activity_bucket", "hr_sample", "spo2_progress", "spo2_result",
        "sleep_timeline", "history_measurement", "stress_sample", "hrv_sample", "temperature_sample",
    )

    private val MAC = Regex("\\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")

    /**
     * For a health-measurement frame, keep the opcode byte and mask the rest (the values live
     * in the payload). Non-health frames are returned unchanged. [hex] is contiguous lowercase.
     */
    fun maskPacketHex(hex: String, kind: String): String {
        if (kind !in HEALTH_KINDS || hex.length <= 2) return hex
        val byteCount = hex.length / 2
        return hex.substring(0, 2) + "··".repeat(byteCount - 1)
    }

    /** Mask BLE MAC addresses anywhere in free text (logcat, log messages, metadata). */
    fun scrubText(text: String): String = MAC.replace(text, "··:··:··:··:··:··")

    /** Strip the ring's serial suffix, keeping the model (e.g. "COLMI R10_1203" → "COLMI R10").
     *  Delegates to the one shared suffix rule so the UI label and the privacy scrub can't drift. */
    fun maskRingName(name: String): String = com.pulseloop.ring.ringNameWithoutSerial(name)
}
