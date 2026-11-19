package com.pulseloop.settings

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings

data class ZenWindow(
    val start: Long,
    val end: Long? = null,
)

/**
 * Tracks Android's Bedtime / Do Not Disturb / Zen mode quiet windows (issue #83).
 *
 * Recorded by [ZenModeListener] at each transition, and reconciled with the current Mode on
 * app start.
 */
object ZenModeTracker {
    private const val PREFS_NAME = "zen_mode_history"
    private const val KEY_WINDOWS = "windows"
    private const val MAX_RETENTION_MS = 30L * 86_400_000L // 30 days
    /** Maximum realistic duration of a single quiet window (16 hours). */
    const val MAX_WINDOW_DURATION_MS = 16L * 3600_000L

    /**
     * Is Zen mode (Bedtime / DND / Priority / Alarms) active given an interruption filter?
     * Normal = INTERRUPTION_FILTER_ALL.
     */
    fun isZenModeActive(filter: Int): Boolean =
        filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
            filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS

    fun isZenModeActive(context: Context): Boolean {
        // Reading the filter needs no permission; only changing it needs policy access.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null) return isZenModeActive(nm.currentInterruptionFilter)
        return try {
            val zenMode = Settings.Global.getInt(context.contentResolver, "zen_mode", 0)
            zenMode > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check current interruption filter and record transition if state changed.
     */
    @Synchronized
    fun recordCurrentFilter(context: Context, timestamp: Long = System.currentTimeMillis()) {
        val active = isZenModeActive(context)
        recordState(context, active, timestamp)
    }

    @Synchronized
    fun recordState(context: Context, active: Boolean, timestamp: Long = System.currentTimeMillis()) {
        val windows = loadWindows(context)
        val updated = updateWindows(windows, active, timestamp)
        val pruned = pruneWindows(updated, timestamp)
        saveWindows(context, pruned)
    }

    /**
     * Pure function: transition quiet windows list given active state and timestamp.
     */
    fun updateWindows(windows: List<ZenWindow>, active: Boolean, timestamp: Long): List<ZenWindow> {
        val result = windows.toMutableList()
        val last = result.lastOrNull()
        if (active) {
            if (last != null && last.end == null) {
                // If the open window exceeded max duration, close it and start a fresh one
                if (timestamp - last.start > MAX_WINDOW_DURATION_MS) {
                    result[result.lastIndex] = last.copy(end = last.start + MAX_WINDOW_DURATION_MS)
                    result.add(ZenWindow(start = timestamp, end = null))
                }
                return result
            }
            result.add(ZenWindow(start = timestamp, end = null))
        } else {
            // Close the currently open window if any, capped to max window duration
            if (last != null && last.end == null) {
                val cappedEnd = minOf(maxOf(last.start, timestamp), last.start + MAX_WINDOW_DURATION_MS)
                result[result.lastIndex] = last.copy(end = cappedEnd)
            }
        }
        return result
    }

    /**
     * Get recorded quiet windows since [since].
     */
    @Synchronized
    fun getWindows(context: Context, since: Long = 0L): List<ZenWindow> {
        val windows = loadWindows(context)
        return if (since <= 0L) windows else windows.filter {
            (it.end ?: (it.start + MAX_WINDOW_DURATION_MS)) >= since
        }
    }

    /**
     * Direct setter for testing.
     */
    @Synchronized
    fun setWindowsForTesting(context: Context, windows: List<ZenWindow>) {
        saveWindows(context, windows)
    }

    fun pruneWindows(windows: List<ZenWindow>, now: Long, maxRetentionMs: Long = MAX_RETENTION_MS): List<ZenWindow> {
        val cutoff = now - maxRetentionMs
        return windows.filter { (it.end ?: it.start) >= cutoff }
    }

    fun parseWindows(raw: String?): List<ZenWindow> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.isEmpty()) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = if (parts.size > 1 && parts[1].isNotBlank()) {
                parts[1].toLongOrNull() ?: return@mapNotNull null
            } else null
            ZenWindow(start, end)
        }
    }

    fun serializeWindows(windows: List<ZenWindow>): String =
        windows.joinToString(";") { w -> "${w.start},${w.end ?: ""}" }

    private fun loadWindows(context: Context): List<ZenWindow> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_WINDOWS, null)
        return parseWindows(raw)
    }

    private fun saveWindows(context: Context, windows: List<ZenWindow>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WINDOWS, serializeWindows(windows)).apply()
    }
}
