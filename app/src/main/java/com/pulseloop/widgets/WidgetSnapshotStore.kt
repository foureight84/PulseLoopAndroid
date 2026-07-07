package com.pulseloop.widgets

import android.content.Context
import java.io.File

/**
 * File storage for the widget snapshot (iOS `PulseWidgetStore`). Unlike iOS there is no app-group
 * container to cross — the Glance widgets run in the app's own process — so the JSON lives in
 * `filesDir`. Writes are atomic (temp file + rename) so a widget render never sees a torn file.
 */
object WidgetSnapshotStore {
    const val FILE_NAME = "widget-snapshot.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): WidgetSnapshot? = try {
        val f = file(context)
        if (f.exists()) WidgetSnapshotCodec.decode(f.readText()) else null
    } catch (_: Exception) {
        null
    }

    /** Atomic write: temp file in the same directory, then rename over the live file. */
    fun write(context: Context, snapshot: WidgetSnapshot): Boolean = try {
        val target = file(context)
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(WidgetSnapshotCodec.encode(snapshot))
        if (!temp.renameTo(target)) {
            // Rename across a stale target can fail on some filesystems — replace explicitly.
            target.delete()
            temp.renameTo(target)
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }
}
