package com.pulseloop.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Da Rings retries three seconds after a disconnect (`o3/l.o` in the Android decompile).
 * Keep this separate from the 15-second liveness watchdog: a five-second Android connection
 * timeout otherwise leaves the user waiting another ten seconds before recovery even starts.
 *
 * GATT callbacks and lifecycle actions may arrive on different threads. The epoch and monitor
 * make cancellation invalidate even a timer that has finished its delay and is waiting to run.
 * Callbacks run under that monitor, so cancellation cannot return ahead of a stale retry.
 */
internal class CRPReconnect(
    private val scope: CoroutineScope,
    private val canRetry: () -> Boolean,
    private val onPending: () -> Unit,
    private val onRetry: () -> Unit,
    private val onAbandoned: () -> Unit,
) {
    private val lock = Any()
    private var epoch = 0L
    private var timer: Job? = null
    private var recovering = false

    val isPending: Boolean get() = synchronized(lock) { timer != null }
    /** Remains true during a recovery scan, until success, cancellation, or exhausted retries. */
    val isRecovering: Boolean get() = synchronized(lock) { recovering }

    fun schedule(): Boolean = synchronized(lock) {
        if (timer != null || !canRetry()) return false
        recovering = true
        val generation = ++epoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(3_000L)
            synchronized(lock) {
                if (generation != epoch) return@synchronized
                timer = null
                if (canRetry()) onRetry() else {
                    recovering = false
                    onAbandoned()
                }
            }
        }
        timer = job
        onPending()
        job.start()
        true
    }

    /** A new attempt supersedes the timer but still belongs to the current recovery. */
    fun cancelPending() = synchronized(lock) {
        epoch++
        timer?.cancel()
        timer = null
    }

    fun cancel() = synchronized(lock) {
        cancelPending()
        recovering = false
    }
}
