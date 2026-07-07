package com.pulseloop.ring

/**
 * Ported from [WearableCapability] in WearableCapability.swift.
 * What a connected wearable can actually do. The active device's set is the single
 * source the product UI consults to decide which metric cards/actions to render.
 *
 * Raw values are persisted; append cases, never rename/reorder.
 */
enum class WearableCapability(val key: String) {
    // Shared by jring + Colmi
    HEART_RATE("heartRate"),
    SPO2("spo2"),
    STEPS("steps"),
    SLEEP("sleep"),
    BATTERY("battery"),
    BLOOD_PRESSURE("bloodPressure"),
    BLOOD_SUGAR("bloodSugar"),

    // Colmi R02: richer metrics jring lacks
    REM_SLEEP("remSleep"),
    STRESS("stress"),
    FATIGUE("fatigue"),
    HRV("hrv"),
    TEMPERATURE("temperature"),

    // Interaction capabilities
    MANUAL_HEART_RATE("manualHeartRate"),
    MANUAL_SPO2("manualSpo2"),
    REALTIME_HEART_RATE("realtimeHeartRate"),
    REALTIME_STEPS("realtimeSteps"),
    FIND_DEVICE("findDevice"),
    POWER_OFF("powerOff"),
    FACTORY_RESET("factoryReset"),

    // Configurable all-day measurement: the device exposes a settable HR sampling interval and
    // per-vital monitoring toggles (Colmi `0x16` + prefs). The generic jring has no such control,
    // so it never declares this and the Measurement settings section stays hidden for it.
    MEASUREMENT_INTERVAL("measurementInterval");

    companion object {
        fun fromCsv(csv: String): Set<WearableCapability> =
            csv.split(",").mapNotNull { key -> entries.find { it.key == key.trim() } }.toSet()

        fun Set<WearableCapability>.toCsv(): String =
            entries.filter { it in this }.joinToString(",") { it.key }
    }
}

/**
 * Ported from [RingDeviceType] in WearableCoordinator.swift.
 * Stable identifier for a wearable family. Append cases, never rename/reorder.
 */
enum class RingDeviceType(val displayName: String) {
    JRING("SMART_RING"),
    // One protocol family covering the whole Colmi/Yawell line — the exact model comes from
    // WearableModel (iOS #49), so the family label stays honest about the ambiguity.
    COLMI_R02("Colmi / Yawell ring");
}
