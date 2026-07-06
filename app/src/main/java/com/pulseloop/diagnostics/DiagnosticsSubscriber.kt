package com.pulseloop.diagnostics

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.WearableLogCategory
import com.pulseloop.data.entity.WearableLogEntity
import com.pulseloop.data.entity.WearableLogLevel
import com.pulseloop.ring.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Ported from DiagnosticsSubscriber.swift.
 * Subscribes to PulseEventBus and records high-level connection/sync/battery/error
 * events into the structured WearableLog store.
 */
class DiagnosticsSubscriber(
    private val db: PulseLoopDatabase,
) {
    private var job: Job? = null
    private var activeDeviceType: RingDeviceType? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            PulseEventBus.events.collect { event ->
                record(event)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun record(event: PulseEvent) {
        when (event) {
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
                log(WearableLogCategory.SYNC, WearableLogLevel.INFO, "SpO₂ measurement complete")
            }
            else -> { /* not logged */ }
        }
    }

    private suspend fun log(
        category: WearableLogCategory,
        level: WearableLogLevel,
        message: String,
        metadata: Map<String, String>? = null,
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
            deviceTypeRaw = activeDeviceType?.name ?: "",
            categoryRaw = category.name,
            levelRaw = level.name,
            message = message,
            metadataJSON = json,
        ))
    }
}
