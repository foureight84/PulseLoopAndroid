package com.pulseloop.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Persists uncaught-exception stack traces to filesDir so the next diagnostics export can
 * include them — closing the "user hit a crash but the export has no trace" gap. Writes a
 * plain file synchronously (the DB may be in a bad state during a crash) and chains to the
 * previously-installed handler so the system still shows its crash dialog.
 */
object CrashLogger {
    private const val DIR = "crashes"
    private const val MAX_FILES = 10

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeCrash(appContext, thread, throwable) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = Instant.now().toString().replace(":", "-")
        File(dir, "crash-$stamp.txt").writeText("at: ${Instant.now()}\nthread: ${thread.name}\n\n$sw")
        // Keep only the most recent reports.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** Most-recent crash reports (newest first) as (filename, trace) pairs, for the export. */
    fun recentCrashes(context: Context, max: Int = 5): List<Pair<String, String>> {
        val dir = File(context.filesDir, DIR)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return emptyList()
        return files.take(max).map { it.name to it.readText() }
    }
}
