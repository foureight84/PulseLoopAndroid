package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from YCBTDecoder.swift.
 * Decodes inbound YCBT frames into the shared RingDecodedEvent.
 */

class YCBTDecoder {

    fun decode(frame: YCBTFrame, now: Instant = Instant.now(), startedMode: Int? = null): List<RingDecodedEvent> {
        return when (frame.type) {
            YCBTGroup.REAL -> decodeRealStream(frame, now)
            YCBTGroup.DEV_CONTROL -> decodeDevControlPush(frame.cmd, payload = frame.payload, now = now)
            YCBTGroup.GET -> decodeGetReply(frame)
            YCBTGroup.APP_CONTROL -> if (frame.cmd == YCBTCommand.LIVE_MEASUREMENT) {
                decodeMeasurementStartReply(frame.payload, startedMode = startedMode)
            } else {
                listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
            }
            YCBTGroup.SETTING -> if (frame.cmd == YCBTSettingKey.SET_TIME) {
                listOf(RingDecodedEvent.TimeSyncAck(_timestamp = now))
            } else {
                listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
            }
            else -> listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
        }
    }

    private fun decodeMeasurementStartReply(payload: ByteArray, startedMode: Int?): List<RingDecodedEvent> {
        val ack: List<RingDecodedEvent> = listOf(RingDecodedEvent.CommandAck(commandId = YCBTCommand.LIVE_MEASUREMENT.toUByte()))
        if (payload.size != 1) return ack
        val status = payload[0].toInt() and 0xFF
        if (YCBTMeasurementMode.isAccepted(status) || startedMode == null) return ack
        return listOf(RingDecodedEvent.MeasurementRejected(mode = startedMode))
    }

    private fun decodeRealStream(frame: YCBTFrame, now: Instant): List<RingDecodedEvent> {
        val p = frame.payload
        return when (frame.cmd) {
            YCBTCommand.LIVE_STATUS -> {
                if (p.size < 2) return listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
                listOf(RingDecodedEvent.ActivityUpdate(
                    _timestamp = now,
                    steps = YCBTBytes.u16(p, 0),
                    distanceMeters = YCBTBytes.u16(p, 2),
                    calories = YCBTBytes.u16(p, 4),
                ))
            }
            YCBTCommand.LIVE_HEART_RATE -> {
                val bpm = p.firstOrNull()?.toInt() and 0xFF ?: return listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
                return listOf(RingDecodedEvent.HeartRateSample(bpm = bpm, _timestamp = now))
            }
            YCBTCommand.LIVE_SPO2 -> {
                val spo2 = p.firstOrNull()?.toInt() and 0xFF ?: return listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
                if (spo2 in RingEventBridge.spo2Range) {
                    return listOf(RingDecodedEvent.Spo2Result(value = spo2, _timestamp = now))
                }
                return listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
            }
            YCBTCommand.LIVE_VITALS -> decodeLiveVitals(p, cmd = frame.cmd, now = now)
            YCBTCommand.LIVE_BATTERY -> {
                if (p.size < 2) return listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
                listOf(RingDecodedEvent.Battery(percent = p[1].toInt() and 0xFF))
            }
            YCBTCommand.LIVE_WEARING_STATUS -> {
                if (p.size < 5) return listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
                listOf(RingDecodedEvent.WearingStatus(
                    worn = p[4].toInt() and 0xFF != 0,
                    _timestamp = YCBTBytes.date(YCBTBytes.u32(p, 0))
                ))
            }
            else -> listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
        }
    }

    private fun decodeLiveVitals(p: ByteArray, cmd: Int, now: Instant): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        if (p.size >= 2 && p[0].toInt() and 0xFF > 0 && p[1].toInt() and 0xFF > 0) {
            events.add(RingDecodedEvent.BloodPressureSample(systolic = p[0].toInt() and 0xFF, diastolic = p[1].toInt() and 0xFF, _timestamp = now))
        }
        if (p.size >= 3 && p[2].toInt() and 0xFF > 0) {
            events.add(RingDecodedEvent.HeartRateSample(bpm = p[2].toInt() and 0xFF, _timestamp = now))
        }
        if (p.size >= 4 && p[3].toInt() and 0xFF > 0) {
            events.add(RingDecodedEvent.HrvSample(value = p[3].toInt() and 0xFF, _timestamp = now))
        }
        if (p.size >= 5 && (p[4].toInt() and 0xFF) in RingEventBridge.spo2Range) {
            events.add(RingDecodedEvent.Spo2Result(value = p[4].toInt() and 0xFF, _timestamp = now))
        }
        if (p.size >= 7 && p[5].toInt() and 0xFF > 0) {
            events.add(RingDecodedEvent.TemperatureSample(
                celsius = YCBTHealthRecords.composite(p[5].toInt() and 0xFF, p[6].toInt() and 0xFF),
                _timestamp = now
            ))
        }
        return if (events.isEmpty()) listOf(RingDecodedEvent.CommandAck(commandId = cmd.toUByte())) else events
    }

    private fun decodeGetReply(frame: YCBTFrame): List<RingDecodedEvent> {
        return when (frame.cmd) {
            YCBTCommand.GET_DEVICE_INFO -> decodeDeviceInfo(frame.payload)
            YCBTCommand.GET_SUPPORT_FUNCTION -> decodeSupportFunction(frame.payload)
            YCBTCommand.GET_CHIP_SCHEME -> decodeChipScheme(frame.payload)
            else -> listOf(RingDecodedEvent.CommandAck(commandId = frame.cmd.toUByte()))
        }
    }

    private fun decodeDeviceInfo(p: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>(RingDecodedEvent.Status(address = null))
        if (p.size >= 4) {
            events.add(RingDecodedEvent.FirmwareVersion(version = String.format("%d.%02d", p[3].toInt() and 0xFF, p[2].toInt() and 0xFF)))
        }
        if (p.size >= 6) {
            events.add(RingDecodedEvent.Battery(percent = p[5].toInt() and 0xFF))
        }
        return events
    }

    private fun decodeSupportFunction(p: ByteArray): List<RingDecodedEvent> {
        val caps = YCBTSupportFunction.capabilities(p)
        return listOf(RingDecodedEvent.SupportFunctions(caps))
    }

    private fun decodeChipScheme(p: ByteArray): List<RingDecodedEvent> {
        val scheme = YCBTChipScheme.value(p)
        return listOf(RingDecodedEvent.ChipScheme(value = scheme))
    }

    private fun decodeDevControlPush(cmd: Int, payload: ByteArray, now: Instant): List<RingDecodedEvent> {
        return when (cmd) {
            YCBTDevControl.MEASUREMENT_STATUS -> {
                val events = measurementStatusEvents(payload, now)
                if (events.isEmpty()) listOf(RingDecodedEvent.CommandAck(commandId = cmd.toUByte())) else events
            }
            YCBTDevControl.MEASUREMENT_RESULT -> {
                listOf(RingDecodedEvent.CommandAck(commandId = cmd.toUByte()))
            }
            else -> listOf(RingDecodedEvent.CommandAck(commandId = cmd.toUByte()))
        }
    }

    private fun measurementStatusEvents(p: ByteArray, now: Instant): List<RingDecodedEvent> {
        if (p.size < 3) return emptyList()
        val value = p[2].toInt() and 0xFF
        val fraction = if (p.size >= 4) p[3].toInt() and 0xFF else 0
        return when (p[0].toInt() and 0xFF) {
            YCBTMeasurementMode.HEART_RATE -> if (value > 0) listOf(RingDecodedEvent.HeartRateSample(bpm = value, _timestamp = now)) else emptyList()
            YCBTMeasurementMode.BLOOD_PRESSURE -> if (value > 0 && fraction > 0) listOf(
                RingDecodedEvent.BloodPressureSample(systolic = value, diastolic = fraction, _timestamp = now)
            ) else emptyList()
            YCBTMeasurementMode.SPO2 -> if (value in RingEventBridge.spo2Range) listOf(RingDecodedEvent.Spo2Result(value = value, _timestamp = now)) else emptyList()
            YCBTMeasurementMode.TEMPERATURE -> if (value > 0) listOf(
                RingDecodedEvent.TemperatureSample(celsius = YCBTHealthRecords.composite(value, fraction), _timestamp = now)
            ) else emptyList()
            YCBTMeasurementMode.BLOOD_SUGAR -> {
                val tenths = value * 10 + fraction
                if (tenths > 0) listOf(RingDecodedEvent.BloodSugarSample(mgdl = YCBTHealthRecords.bloodSugarMgdl(tenths), _timestamp = now)) else emptyList()
            }
            else -> emptyList()
        }
    }
}
