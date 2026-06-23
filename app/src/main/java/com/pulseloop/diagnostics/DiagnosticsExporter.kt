package com.pulseloop.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.pulseloop.data.PulseLoopDatabase
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

/**
 * Ported from DiagnosticsExporter.swift.
 * Builds a shareable diagnostics bundle (JSON): app/OS/device info + recent WearableLog timeline.
 */
object DiagnosticsExporter {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Serialize a diagnostics report to pretty-printed JSON.
     */
    suspend fun exportJSON(db: PulseLoopDatabase, maxLogs: Int = 500): String {
        val device = db.deviceDao().current()
        val logs = db.wearableLogDao().recent(maxLogs)
        val root = buildJsonObject {
            put("generatedAt", Instant.now().toString())

            putJsonObject("app") {
                put("version", "1.0.0")
                put("platform", "Android")
                put("sdkVersion", Build.VERSION.SDK_INT)
            }

            putJsonObject("device") {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("osVersion", Build.VERSION.RELEASE)
                if (device != null) {
                    put("wearableType", device.deviceTypeRaw)
                    put("wearableName", device.name)
                    put("capabilities", device.capabilitiesRaw)
                    put("firmware", device.firmwareVersion ?: "?")
                    put("lastSyncAt", device.lastSyncAt?.let { Instant.ofEpochMilli(it).toString() } ?: "")
                }
            }

            putJsonArray("logs") {
                logs.forEach { log ->
                    addJsonObject {
                        put("at", Instant.ofEpochMilli(log.timestamp).toString())
                        put("category", log.categoryRaw)
                        put("level", log.levelRaw)
                        put("message", log.message)
                        log.metadataJSON?.let { if (it != "null") put("metadata", it) }
                        if (log.deviceTypeRaw.isNotEmpty()) put("deviceType", log.deviceTypeRaw)
                    }
                }
            }

            // Raw BLE packets for protocol debugging
            val packets = db.rawPacketDao().recent(200)
            putJsonArray("rawPackets") {
                packets.forEach { pkt ->
                    addJsonObject {
                        put("at", Instant.ofEpochMilli(pkt.timestamp).toString())
                        put("direction", pkt.directionRaw)
                        put("hex", pkt.hexPayload)
                        put("decoded", pkt.decodedKind ?: "")
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    suspend fun exportFile(context: Context, db: PulseLoopDatabase): File {
        val report = exportJSON(db)
        val stamp = Instant.now().toString().replace(":", "-")
        val file = File(context.cacheDir, "pulseloop-diagnostics-$stamp.json")
        file.writeText(report)
        return file
    }

    suspend fun shareIntent(context: Context, db: PulseLoopDatabase): Intent {
        val file = exportFile(context, db)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PulseLoop Diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
