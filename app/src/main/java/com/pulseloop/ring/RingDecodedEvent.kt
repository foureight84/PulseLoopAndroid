package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from [MeasurementKind] in PulseModels.swift.
 */
enum class MeasurementKind(val key: String, val unit: String) {
    HEART_RATE("hr", "bpm"),
    SPO2("spo2", "%"),
    STRESS("stress", ""),
    FATIGUE("fatigue", ""),
    HRV("hrv", "ms"),
    TEMPERATURE("temp", "°C"),
    BLOOD_PRESSURE_SYSTOLIC("bp_sys", "mmHg"),
    BLOOD_PRESSURE_DIASTOLIC("bp_dia", "mmHg"),
    BLOOD_SUGAR("glucose", "mg/dL"),
    // YCBT/TK5 history metrics: respiratory rate rides the "All" record, VO2max the body-data
    // record. Append only — raw values persisted.
    RESPIRATORY_RATE("resp_rate", "breaths/min"),
    VO2MAX("vo2max", "mL/kg/min");
}

/**
 * Ported from [SleepStage] in PulseModels.swift.
 */
enum class SleepStage {
    LIGHT, DEEP, AWAKE, UNKNOWN, REM;

    companion object {
        /**
         * Sleep stage from ring byte. Uses threshold-based mapping matching
         * the official app (Gadgetbridge KeepFitDeviceSupport):
         *   >= 80  → DEEP sleep
         *   >= 1   → LIGHT sleep
         *   0      → AWAKE
         * The values 0x28 (40) and 0x63 (99) previously used as exact
         * matches are just examples — the ring can send any value in range.
         */
        fun fromByte(byte: UByte): SleepStage = when {
            byte >= 0x50u.toUByte() -> DEEP   // >= 80
            byte >= 0x01u.toUByte() -> LIGHT  // >= 1
            byte == 0x00u.toUByte() -> AWAKE
            else -> UNKNOWN
        }
    }
}

/**
 * Ported from [DecodeConfidence] in PulseModels.swift.
 */
enum class DecodeConfidence(val debugLabel: String) {
    KNOWN("high"),
    PARTIAL("medium"),
    UNKNOWN("unknown");
}

/**
 * Ported from [RingDecodedEvent] in RingProtocol.swift.
 * A typed event decoded from a raw ring notification.
 */
sealed class RingDecodedEvent {
    abstract val kind: String
    abstract val confidence: DecodeConfidence
    abstract val debugJSON: String

    val timestamp: Instant get() = when (this) {
        is ActivityUpdate -> this._timestamp
        is ActivityBucket -> this._timestamp
        is HeartRateSample -> this._timestamp
        is HeartRateComplete -> this._timestamp
        is Spo2Progress -> this._timestamp
        is Spo2Result -> this._timestamp
        is Spo2Complete -> this._timestamp
        is SleepTimeline -> this._timestamp
        is HistoryMeasurement -> this._timestamp
        is StressSample -> this._timestamp
        is HrvSample -> this._timestamp
        is TemperatureSample -> this._timestamp
        is HistorySyncProgress -> Instant.EPOCH
        is HistorySyncFinished -> Instant.EPOCH
        is Battery -> Instant.EPOCH
        is Status -> Instant.EPOCH
        is TimeSyncAck -> this._timestamp
        is CommandAck -> Instant.EPOCH
        is FirmwareVersion -> Instant.EPOCH
        is BindNotify -> Instant.EPOCH
        is BandFunction -> Instant.EPOCH
        is SupportFunctions -> Instant.EPOCH
        is ChipScheme -> Instant.EPOCH
        is WearingStatus -> this._timestamp
        is MeasurementRejected -> Instant.EPOCH
        is BloodPressureSample -> this._timestamp
        is BloodSugarSample -> this._timestamp
        is Unknown -> Instant.EPOCH
    }

    data class ActivityUpdate(
        val _timestamp: Instant,
        val steps: Int,
        val distanceMeters: Int,
        val calories: Int
    ) : RingDecodedEvent() {
        override val kind = "activity"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"steps":$steps,"distance_m":$distanceMeters,"calories":$calories}"""
    }

    data class ActivityBucket(
        val _timestamp: Instant,
        val steps: Int,
        val distanceMeters: Int
    ) : RingDecodedEvent() {
        override val kind = "activity_bucket"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"steps":$steps,"distance_m":$distanceMeters}"""
    }

    data class HeartRateSample(
        val bpm: Int,
        val _timestamp: Instant,
        val sleepStatus: Int = 0,
        val isError: Boolean = false
    ) : RingDecodedEvent() {
        override val kind = "hr_sample"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"bpm":$bpm,"error":$isError}"""
    }

    data class HeartRateComplete(
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "hr_complete"
        override val confidence = DecodeConfidence.PARTIAL
        override val debugJSON = "{}"
    }

    data class Spo2Progress(
        val percent: Int?,
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "spo2_progress"
        override val confidence = DecodeConfidence.PARTIAL
        override val debugJSON = "{}"
    }

    data class Spo2Result(
        val value: Int,
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "spo2_result"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"spo2":$value}"""
    }

    data class Spo2Complete(
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "spo2_complete"
        override val confidence = DecodeConfidence.PARTIAL
        override val debugJSON = "{}"
    }

    data class SleepTimeline(
        val _timestamp: Instant,
        val stages: List<SleepStage>,
        /** True when this event is the ring's complete authoritative session, not one packet. */
        val completeSession: Boolean = false,
    ) : RingDecodedEvent() {
        override val kind = "sleep_timeline"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = "{}"
    }

    data class HistoryMeasurement(
        val kind_field: MeasurementKind,
        val value: Double,
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "history_measurement"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = "{}"
    }

    /**
     * Ring-side bind/unbind notification (0x4B). Mirrors the official SDK's
     * onNotifyBindedInfo(action, state). action: 0=INIT, 1=APP_START, 2=ACK,
     * 3=ACK_CANCEL, 4=SUCCESS, 5=UNBOND, 6=UNBOND_ACK. The ring drives a small
     * handshake on connect and acks an app-initiated unbind on forget.
     */
    data class BindNotify(
        val action: Int,
        val state: Int,
    ) : RingDecodedEvent() {
        override val kind = "bind_notify"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"action":$action,"state":$state}"""
    }

    /** Capability bitmask reply (0x20). Consumed by `JringSyncEngine`; produces no `PulseEvent`. */
    data class BandFunction(
        val capabilities: JringBandCapabilities
    ) : RingDecodedEvent() {
        override val kind = "band_function"
        override val confidence = DecodeConfidence.PARTIAL   // bit ordering unverified against hardware
        override val debugJSON = """{"temp":${capabilities.hasTemperature},"spo2_separate":${capabilities.separateBloodOxygenMode},"spo2_offline":${capabilities.hasOxygenOfflineHistory},"pressure":${capabilities.hasPressureHistory}}"""
    }

    /**
     * The device's own capability bitmap, already mapped onto [WearableCapability] (YCBT `02 01`;
     * see `YCBTSupportFunction`). Consumed by `RingBLEClient` to refine the active capability set —
     * the coordinator's baseline is what the *family* can do, this is what *this unit* claims.
     * Produces no `PulseEvent`.
     */
    data class SupportFunctions(
        val capabilities: Set<WearableCapability>
    ) : RingDecodedEvent() {
        override val kind = "support_functions"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"capabilities":"${capabilities.joinToString(",") { it.key }}"}"""
    }

    /**
     * The chipset/OTA family (YCBT `02 1b`). Diagnostic only — produces no `PulseEvent`.
     */
    data class ChipScheme(
        val value: Int
    ) : RingDecodedEvent() {
        override val kind = "chip_scheme"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"value":$value}"""
    }

    /**
     * The ring reports it went on/off the finger (YCBT `06 13`). Debug-feed only — produces no
     * `PulseEvent`, since nothing in the app gates on wear state yet.
     */
    data class WearingStatus(
        val worn: Boolean,
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "wearing_status"
        override val confidence = DecodeConfidence.PARTIAL   // polarity unverified against hardware
        override val debugJSON = """{"worn":$worn}"""
    }

    /**
     * The ring **refused** to start the spot measurement we asked for (YCBT `03 2f` answered with a
     * non-zero status). `mode` is the measurement mode we started — the reply itself carries only a
     * status byte, so the mode comes from the start `YCBTDriver` remembers sending. `RingEventBridge`
     * maps it to `PulseEvent.MeasurementRejected`, which `RingSyncCoordinator` consumes to abort the
     * matching in-flight measurement.
     */
    data class MeasurementRejected(
        val mode: Int
    ) : RingDecodedEvent() {
        override val kind = "measurement_rejected"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"mode":$mode}"""
    }

    data class StressSample(
        val value: Int,
        val _timestamp: Instant,
        val isHistory: Boolean = false,
    ) : RingDecodedEvent() {
        override val kind = "stress_sample"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"stress":$value}"""
    }

    data class HrvSample(
        val value: Int,
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "hrv_sample"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"hrv_ms":$value}"""
    }

    data class TemperatureSample(
        val celsius: Double,
        val _timestamp: Instant,
        val isHistory: Boolean = false,
    ) : RingDecodedEvent() {
        override val kind = "temperature_sample"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"temp_c":$celsius}"""
    }

    data class HistorySyncProgress(
        val stage: String
    ) : RingDecodedEvent() {
        override val kind = "history_sync_progress"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"stage":"$stage"}"""
    }

    object HistorySyncFinished : RingDecodedEvent() {
        override val kind = "history_sync_finished"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = "{}"
    }

    data class Battery(
        val percent: Int,
        val charging: Boolean = false
    ) : RingDecodedEvent() {
        override val kind = "battery"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"percent":$percent,"charging":$charging}"""
    }

    data class Status(
        val address: String?,
        val firmware: String? = null,
    ) : RingDecodedEvent() {
        override val kind = "status"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"address":"${address ?: ""}","firmware":"${firmware ?: ""}"}"""
    }

    data class FirmwareVersion(
        val version: Int? = null,
    ) : RingDecodedEvent() {
        override val kind = "firmware_version"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"version":${version ?: 0}}"""
    }

    data class TimeSyncAck(
        val _timestamp: Instant
    ) : RingDecodedEvent() {
        override val kind = "time_sync_ack"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = "{}"
    }

    data class CommandAck(
        val commandId: UByte
    ) : RingDecodedEvent() {
        override val kind = "command_ack"
        override val confidence = DecodeConfidence.PARTIAL
        override val debugJSON = "{}"
    }

    data class BloodPressureSample(
        val systolic: Int,
        val diastolic: Int,
        val _timestamp: Instant,
        val isHistory: Boolean = false,
    ) : RingDecodedEvent() {
        override val kind = "blood_pressure_sample"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"sys":$systolic,"dia":$diastolic}"""
    }

    data class BloodSugarSample(
        val mgdl: Double,
        val _timestamp: Instant,
    ) : RingDecodedEvent() {
        override val kind = "blood_sugar_sample"
        override val confidence = DecodeConfidence.KNOWN
        override val debugJSON = """{"mgdl":$mgdl}"""
    }

    data class Unknown(
        val commandId: UByte,
        val raw: ByteArray
    ) : RingDecodedEvent() {
        override val kind = "unknown"
        override val confidence = DecodeConfidence.UNKNOWN
        override val debugJSON = "{}"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return commandId == other.commandId && raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int = 31 * commandId.hashCode() + raw.contentHashCode()
    }
}
