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
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid")
            .redirectErrorStream(true)
            .start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        val lines = text.lines()
        if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else text
    } catch (e: Exception) {
        "logcat capture failed: ${e.message}"
    }
}
