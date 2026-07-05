package com.pulseloop.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.pulseloop.data.PulseLoopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     *
     * [mask] (default true) scrubs PHI/PII: physiological values, the ring serial suffix, and
     * MAC addresses are removed while opcodes / decoded kinds / control frames / UUIDs / errors
     * are kept (see [DiagnosticsRedactor]). Pass false only to capture full unmasked BLE frames
     * for deep protocol debugging.
     */
    suspend fun exportJSON(
        context: Context, db: PulseLoopDatabase, mask: Boolean = true, maxLogs: Int = 500,
    ): String = withContext(Dispatchers.IO) {
        // Everything below is blocking work (logcat exec, crash-file reads, JSON encode of
        // hundreds of entries) — keep it off the caller's thread, which is Main when the
        // Debug screen's Export button triggers this.
        val device = db.deviceDao().current()
        val logs = db.wearableLogDao().recent(maxLogs)
        val root = buildJsonObject {
            put("generatedAt", Instant.now().toString())
            put("redacted", mask)

            putJsonObject("app") {
                put("version", com.pulseloop.BuildConfig.VERSION_NAME)
                put("versionCode", com.pulseloop.BuildConfig.VERSION_CODE)
                put("buildType", if (com.pulseloop.BuildConfig.DEBUG) "debug" else "release")
                put("applicationId", com.pulseloop.BuildConfig.APPLICATION_ID)
                put("platform", "Android")
                put("sdkVersion", Build.VERSION.SDK_INT)
            }

            putJsonObject("device") {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("osVersion", Build.VERSION.RELEASE)
                if (device != null) {
                    put("wearableType", device.deviceTypeRaw)
                    put("wearableName", if (mask) DiagnosticsRedactor.maskRingName(device.name) else device.name)
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
                        put("message", if (mask) DiagnosticsRedactor.scrubText(log.message) else log.message)
                        log.metadataJSON?.let {
                            if (it != "null") put("metadata", if (mask) DiagnosticsRedactor.scrubText(it) else it)
                        }
                        if (log.deviceTypeRaw.isNotEmpty()) put("deviceType", log.deviceTypeRaw)
                    }
                }
            }

            // Raw BLE packets for protocol debugging. When masking, health-measurement frames
            // are reduced to their opcode (the values live in the payload); control frames stay
            // whole so connection/pairing flow is still fully visible.
            val packets = db.rawPacketDao().recent(200)
            putJsonArray("rawPackets") {
                packets.forEach { pkt ->
                    val kind = pkt.decodedKind ?: ""
                    addJsonObject {
                        put("at", Instant.ofEpochMilli(pkt.timestamp).toString())
                        put("direction", pkt.directionRaw)
                        put("hex", if (mask) DiagnosticsRedactor.maskPacketHex(pkt.hexPayload, kind) else pkt.hexPayload)
                        put("decoded", kind)
                    }
                }
            }

            // Recent crash stack traces (uncaught exceptions persisted by CrashLogger).
            putJsonArray("crashes") {
                CrashLogger.recentCrashes(context).forEach { (name, trace) ->
                    addJsonObject {
                        put("file", name)
                        put("trace", trace)
                    }
                }
            }

            // This process's own logcat — app logs + in-process BluetoothGatt callbacks.
            // MAC addresses are scrubbed when masking.
            val logcat = LogcatCapture.ownProcessLog()
            put("logcat", if (mask) DiagnosticsRedactor.scrubText(logcat) else logcat)
        }
        json.encodeToString(JsonObject.serializer(), root)
    }

    suspend fun exportFile(context: Context, db: PulseLoopDatabase, mask: Boolean = true): File {
        val report = exportJSON(context, db, mask)
        return withContext(Dispatchers.IO) {
            val stamp = Instant.now().toString().replace(":", "-")
            val suffix = if (mask) "" else "-full"
            val file = File(context.cacheDir, "pulseloop-diagnostics-$stamp$suffix.json")
            file.writeText(report)
            file
        }
    }

    suspend fun shareIntent(context: Context, db: PulseLoopDatabase, mask: Boolean = true): Intent {
        val file = exportFile(context, db, mask)
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
