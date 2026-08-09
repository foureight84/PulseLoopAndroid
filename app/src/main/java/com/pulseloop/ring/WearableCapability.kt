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
    MANUAL_BLOOD_PRESSURE("manualBloodPressure"),
    MANUAL_HRV("manualHrv"),
    REALTIME_HEART_RATE("realtimeHeartRate"),
    REALTIME_STEPS("realtimeSteps"),
    FIND_DEVICE("findDevice"),
    POWER_OFF("powerOff"),
    FACTORY_RESET("factoryReset"),
    SPO2_HISTORY("spo2History"),

    // Configurable all-day measurement: the device exposes a settable HR sampling interval and
    // per-vital monitoring toggles (Colmi `0x16` + prefs). The generic jring has no such control,
    // so it never declares this and the Measurement settings section stays hidden for it.
    MEASUREMENT_INTERVAL("measurementInterval"),

    // YCBT history-only metric.
    VO2MAX("vo2max");

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
    COLMI_R02("Colmi / Yawell ring"),
    // Both speak the Yucheng YCBT protocol (see YCBTProtocol.kt) via the shared YCBTDriver —
    // two families only because they need distinct advertisement-matching + capability sets.
    TK5("TK5"),
    COLMI_SMART_HEALTH("Colmi / Yawell ring (SmartHealth)"),
    // LuckRing / TK18 family (the "K6" vendor SDK, company ID 0xFF64). Sold under simsonlab and
    // other brands; TK18 is the hardware-tested unit. See LuckRingCoordinator.
    LUCK_RING("LuckRing"),
    // CRP ("crrepa"/CRPsmart) family — the proprietary `fdda`-profile rings whose official app is
    // Moyoung "Da Rings" (com.moyoung.ring). Notably the CRP-firmware Colmi R11: it advertises the
    // generic "SMART_RING" name with no service UUID, so it's classified JRING at scan and only
    // reveals its `fdda` service post-connect (issue #29, zaggash's ring). See CRPCoordinator.
    CRP("Colmi / Moyoung ring (CRP)"),
    // Hardware-validated SmartHealth R10M path, kept separate from the broader YCBT families.
    YCBT("YCBT / SmartHealth ring");
}
