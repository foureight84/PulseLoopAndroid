package com.pulseloop.ring

import java.time.Instant

class RWfitDecoder {
    private var useAbProtocol = false
    private val buffer = mutableListOf<Byte>()

    fun setProtocol(ab: Boolean) { useAbProtocol = ab }

    fun feed(data: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        buffer.addAll(data.toList())
        while (buffer.size >= 2) {
            val header = buffer[0].toInt() and 0xFF
            if (useAbProtocol && header == 0xAB) {
                if (buffer.size < 6) break
                val cmd = buffer[1].toInt() and 0xFF
                val payloadLen = buffer.size - 6
                // CRC16 validation deferred — needs hardware captures to confirm the
                // checksum is actually placed at the expected offset in real frames.
                val payload = if (payloadLen > 0) buffer.subList(4, 4 + payloadLen).toByteArray() else ByteArray(0)
                events.addAll(decodeRaw(cmd, payload))
                buffer.clear()
            } else if (header == 0x7E) {
                if (buffer.size < 4) break
                val len = buffer[1].toInt() and 0xFF
                if (buffer.size < len + 2) break
                val cmd = buffer[2].toInt() and 0xFF
                // XOR checksum validation deferred — see 0xAB note above.
                val payload = if (len > 2) buffer.subList(3, 1 + len).toByteArray() else ByteArray(0)
                events.addAll(decodeRaw(cmd, payload))
                repeat(len + 2) { buffer.removeAt(0) }
            } else {
                buffer.removeAt(0)
            }
        }
        return events
    }

    private fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())
    private fun ts(epochMs: Long): Instant = Instant.ofEpochMilli(epochMs)

    private fun decodeRaw(cmd: Int, payload: ByteArray): List<RingDecodedEvent> = when (cmd) {
        RWfitProtocol.CMD_DEVICE_INFO -> decodeDeviceInfo(payload)
        RWfitProtocol.CMD_BATTERY -> decodeBattery(payload)
        RWfitProtocol.CMD_HEART_RATE -> decodeHR(payload)
        RWfitProtocol.CMD_SPO2 -> decodeSpO2(payload)
        RWfitProtocol.CMD_STEPS -> decodeSteps(payload)
        RWfitProtocol.CMD_SLEEP -> decodeSleep(payload)
        RWfitProtocol.CMD_HRV -> decodeHRV(payload)
        RWfitProtocol.CMD_TEMPERATURE -> decodeTemp(payload)
        RWfitProtocol.CMD_STRESS -> decodeStress(payload)
        RWfitProtocol.CMD_BLOOD_PRESSURE -> decodeBP(payload)
        RWfitProtocol.CMD_BLOOD_SUGAR -> decodeGlucose(payload)
        RWfitProtocol.CMD_HISTORY_DATA -> decodeHistory(payload)
        else -> listOf(RingDecodedEvent.Status(address = null))
    }

    private fun decodeBattery(p: ByteArray): List<RingDecodedEvent> {
        if (p.isEmpty()) return emptyList()
        return listOf(RingDecodedEvent.Battery(percent = p[0].toInt() and 0xFF))
    }

    private fun decodeHR(p: ByteArray): List<RingDecodedEvent> {
        if (p.isEmpty()) return emptyList()
        return listOf(RingDecodedEvent.HeartRateSample(_timestamp = now(), bpm = p[0].toInt() and 0xFF))
    }

    private fun decodeSpO2(p: ByteArray): List<RingDecodedEvent> {
        if (p.isEmpty()) return emptyList()
        return listOf(RingDecodedEvent.Spo2Result(_timestamp = now(), value = p[0].toInt() and 0xFF))
    }

    private fun decodeSteps(p: ByteArray): List<RingDecodedEvent> {
        if (p.size < 4) return emptyList()
        val steps = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8) or ((p[2].toInt() and 0xFF) shl 16) or ((p[3].toInt() and 0xFF) shl 24)
        val dist = if (p.size >= 8) (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8) or ((p[6].toInt() and 0xFF) shl 16) or ((p[7].toInt() and 0xFF) shl 24) else 0
        return listOf(RingDecodedEvent.ActivityUpdate(_timestamp = now(), steps = steps, distanceMeters = dist, calories = 0))
    }

    private fun decodeHRV(p: ByteArray): List<RingDecodedEvent> {
        if (p.isEmpty()) return emptyList()
        return listOf(RingDecodedEvent.HrvSample(_timestamp = now(), value = p[0].toInt() and 0xFF))
    }

    private fun decodeTemp(p: ByteArray): List<RingDecodedEvent> {
        if (p.size < 2) return emptyList()
        val temp = ((p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)) / 10.0
        return listOf(RingDecodedEvent.TemperatureSample(_timestamp = now(), celsius = temp))
    }

    private fun decodeStress(p: ByteArray): List<RingDecodedEvent> {
        if (p.isEmpty()) return emptyList()
        return listOf(RingDecodedEvent.StressSample(_timestamp = now(), value = p[0].toInt() and 0xFF.coerceIn(0, 100)))
    }

    private fun decodeBP(p: ByteArray): List<RingDecodedEvent> {
        if (p.size < 2) return emptyList()
        return listOf(RingDecodedEvent.BloodPressureSample(_timestamp = now(), systolic = p[0].toInt() and 0xFF, diastolic = p[1].toInt() and 0xFF))
    }

    private fun decodeGlucose(p: ByteArray): List<RingDecodedEvent> {
        if (p.size < 2) return emptyList()
        val value = ((p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)) / 10.0
        return listOf(RingDecodedEvent.BloodSugarSample(_timestamp = now(), mgdl = value))
    }

    private fun decodeSleep(p: ByteArray): List<RingDecodedEvent> {
        if (p.size < 4) return emptyList()
        val totalMin = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
        val deep = (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8)
        return listOf(RingDecodedEvent.SleepTimeline(_timestamp = now(), stages = emptyList(), completeSession = false))
    }

    private fun decodeDeviceInfo(p: ByteArray): List<RingDecodedEvent> {
        val fw = if (p.size >= 2) "V${p[0].toInt() and 0xFF}.${p[1].toInt() and 0xFF}" else null
        return listOf(RingDecodedEvent.Status(address = null, firmware = fw))
    }

    private fun decodeHistory(p: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        if (p.size < 2) return events
        val count = p[0].toInt() and 0xFF
        var pos = 1
        for (i in 0 until count) {
            if (pos + 4 > p.size) break
            val epochMs = ((p[pos].toLong() and 0xFF) or ((p[pos + 1].toLong() and 0xFF) shl 8) or ((p[pos + 2].toLong() and 0xFF) shl 16) or ((p[pos + 3].toLong() and 0xFF) shl 24)) * 1000L
            pos += 4
            if (pos >= p.size) break
            val kind = p[pos].toInt() and 0xFF
            pos++
            when (kind) {
                1 -> { if (pos < p.size) { events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.HEART_RATE, value = (p[pos].toInt() and 0xFF).toDouble(), _timestamp = ts(epochMs))); pos++ } }
                2 -> { if (pos < p.size) { events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.SPO2, value = (p[pos].toInt() and 0xFF).toDouble(), _timestamp = ts(epochMs))); pos++ } }
                3 -> { if (pos < p.size) { events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.STRESS, value = (p[pos].toInt() and 0xFF).toDouble(), _timestamp = ts(epochMs))); pos++ } }
                4 -> { if (pos < p.size) { events.add(RingDecodedEvent.HistoryMeasurement(kind_field = MeasurementKind.HRV, value = (p[pos].toInt() and 0xFF).toDouble(), _timestamp = ts(epochMs))); pos++ } }
                else -> pos++
            }
        }
        return events
    }
}
