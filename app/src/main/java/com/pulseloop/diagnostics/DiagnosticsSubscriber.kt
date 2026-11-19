package com.pulseloop.diagnostics

import android.util.Log
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.RawPacketEntity
import com.pulseloop.data.entity.WearableLogCategory
import com.pulseloop.data.entity.WearableLogEntity
import com.pulseloop.data.entity.WearableLogLevel
import com.pulseloop.ring.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Ported from DiagnosticsSubscriber.swift.
 * Subscribes to PulseEventBus and records high-level connection/sync/battery/error
 * events into the structured WearableLog store, and every raw BLE packet into `raw_packets`.
 *
 * Started once from `PulseLoopApplication`, so capture does not depend on any screen being open.
 * Both tables are bounded by [DiagnosticsRetention].
 */
class DiagnosticsSubscriber(
    private val db: PulseLoopDatabase,
) {
    private var job: Job? = null
    private var activeDeviceType: RingDeviceType? = null
    private val packetRetention = DiagnosticsRetention(DiagnosticsRetention.RAW_PACKET_LIMIT) {
        db.rawPacketDao().trimTo(it)
    }
    private val logRetention = DiagnosticsRetention(DiagnosticsRetention.WEARABLE_LOG_LIMIT) {
        db.wearableLogDao().trimTo(it)
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            PulseEventBus.events.collect { event ->
                try {
                    record(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Diagnostics", "Could not persist diagnostic event", e)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun record(event: PulseEvent) {
        when (event) {
            is PulseEvent.RawPacket -> {
                db.rawPacketDao().insert(RawPacketEntity(
                    timestamp = event.capturedAt,
                    directionRaw = event.direction.name,
                    commandId = event.data.firstOrNull()?.toInt()?.and(0xFF) ?: 0,
                    hexPayload = event.data.joinToString("") { "%02x".format(it) },
                    decodedKind = event.decoded.kind,
                    decodedJSON = event.transportJSON,
                    deviceTypeRaw = event.deviceType?.name,
                ))
                packetRetention.inserted()
            }
            is PulseEvent.Diagnostic -> log(
                category = when {
                    event.error -> WearableLogCategory.ERROR
                    event.traffic -> WearableLogCategory.SYNC
                    else -> WearableLogCategory.CONNECTION
                },
                level = if (event.error) WearableLogLevel.ERROR else WearableLogLevel.INFO,
                message = event.message,
                deviceType = event.deviceType,
                timestamp = event.timestamp,
            )
            is PulseEvent.DeviceStateChanged -> {
                log(WearableLogCategory.CONNECTION, WearableLogLevel.INFO, "Connection state: ${event.state.name}")
            }
            is PulseEvent.DeviceIdentified -> {
                activeDeviceType = event.deviceType
                // Prefer the exact catalog model over the family label (iOS #49).
                val displayName = com.pulseloop.wearables.WearableModel.model(event.wearableModelID)?.displayName
                    ?: event.deviceType.displayName
                log(WearableLogCategory.CONNECTION, WearableLogLevel.INFO,
                    "Identified $displayName",
                    mapOf("capabilities" to event.capabilities.joinToString(",") { it.key }),
                )
            }
            is PulseEvent.DeviceForgotten -> {
                log(WearableLogCategory.CONNECTION, WearableLogLevel.INFO, "Forgot wearable")
                activeDeviceType = null
            }
            is PulseEvent.BatteryLevel -> {
                log(WearableLogCategory.BATTERY, WearableLogLevel.INFO, "Battery ${event.percent}%")
            }
            is PulseEvent.SyncProgress -> {
                log(WearableLogCategory.SYNC, WearableLogLevel.INFO, "Sync: ${event.stage}")
            }
            is PulseEvent.HeartRateComplete -> {
                log(WearableLogCategory.SYNC, WearableLogLevel.INFO, "Heart-rate measurement complete")
            }
            is PulseEvent.Spo2Result -> {
                log(WearableLogCategory.SYNC, WearableLogLevel.INFO,
                    if (event.spot) "SpO₂ spot measurement complete" else "SpO₂ reading received")
            }
            else -> { /* not logged */ }
        }
    }

    private suspend fun log(
        category: WearableLogCategory,
        level: WearableLogLevel,
        message: String,
        metadata: Map<String, String>? = null,
        deviceType: RingDeviceType? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val json = metadata?.let { map ->
            val sb = StringBuilder("{")
            map.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(",")
                sb.append("\"${k.replace("\"", "\\\"")}\":\"${v.replace("\"", "\\\"")}\"")
            }
            sb.append("}").toString()
        }
        db.wearableLogDao().insert(WearableLogEntity(
            timestamp = timestamp,
            deviceTypeRaw = (deviceType ?: activeDeviceType)?.name ?: "",
            categoryRaw = category.name,
            levelRaw = level.name,
            message = message,
            metadataJSON = json,
        ))
        logRetention.inserted()
    }
}
