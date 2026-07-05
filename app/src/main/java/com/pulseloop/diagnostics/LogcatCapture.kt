package com.pulseloop.diagnostics

import android.os.Process

/**
 * Captures this process's own logcat. An app may read logcat entries for its OWN pid without
 * the privileged READ_LOGS permission, which covers app logs (e.g. RingBLEClient) plus the
 * in-process BluetoothGatt client callbacks — most of what BLE debugging needs. The separate
 * system Bluetooth process's logs are not readable here (that needs adb / a debug build).
 */
object LogcatCapture {
    fun ownProcessLog(maxLines: Int = 2000): String = try {
        val pid = Process.myPid()
        // -t caps the dump to the newest N lines inside logd itself, so a long session's
        // multi-MB buffer never has to be read into memory just to be trimmed here.
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString(), "--pid=$pid")
            .redirectErrorStream(true)
            .start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        text
    } catch (e: Exception) {
        "logcat capture failed: ${e.message}"
    }
}
