package com.pulseloop.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the ring in continuous 1 Hz HR streaming overnight, ONLY when it is both safe
 * and useful: local hour in the night window, ring connected, AND the phone charging.
 * A 1 Hz BLE link left open unattended on battery would drain the ring and the phone
 * for a benefit — denser overnight HR/SpO2 — that only matters while the phone sits
 * charging on a nightstand.
 *
 * The stream is the same 0x14 continuous HR command a workout uses
 * ([RingSyncCoordinator.startWorkoutHeartRate]/[RingSyncCoordinator.stopWorkoutHeartRate]),
 * so this controller must never fight a real workout for it. It tracks its own
 * ownership with [streaming] and only starts/stops when [liveWorkout] reports no
 * active session — an actual workout at 3am is assumed not to happen, but as a hard
 * safety net this refuses to touch the stream at all while one is recording.
 */
class SleepStreamController(
    private val coordinator: RingSyncCoordinator,
    private val liveWorkout: LiveWorkoutManager,
    private val context: Context,
    /** Invoked with (plugInMs, wakeMs) when a streamed night ends on unplug —
     *  the primary trigger for the overnight screening, over the exact span slept. */
    private val onNightEnded: (suspend (Long, Long) -> Unit)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    /** Night mode active: charging + in window. FGS/wakelock/reconnect run whenever
     *  this is true, independent of whether the ring is connected yet. */
    private var active = false
    /** True only when THIS controller opened the stream — never stop one we don't own. */
    private var streaming = false
    private var ticksSinceSpO2 = 0
    private var streamStartedAt = 0L

    /** Held while streaming so Doze can't defer the poll loop's 20s timer. The FGS
     *  keeps the process alive; this keeps the CPU running the timer. Charging is a
     *  gate, so an all-night partial wakelock costs no user battery. */
    private val wakeLock: android.os.PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pulseloop:sleepstream")
            .apply { setReferenceCounted(false) }
    }

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) {
                    android.util.Log.e("SleepStream", "tick failed", e)
                }
                delay(POLL_MS)
            }
        }
    }

    fun destroy() {
        job?.cancel()
        job = null
        if (streaming) coordinator.stopWorkoutHeartRate()
        if (active) {
            SleepForegroundService.stop(context)
            if (wakeLock.isHeld) wakeLock.release()
        }
        streaming = false; active = false
    }

    private suspend fun tick() {
        if (liveWorkout.state.value.isRecording) {
            // A real workout owns the stream now (or is about to). Relinquish our
            // ownership flag rather than assume the underlying stream is still ours —
            // the workout's own finish() will stop it independently of us.
            streaming = false
            return
        }

        // "Night mode" gates on charging + window ONLY — NOT connection. Overnight
        // the ring may be off (on its charger) and put on mid-night; if we gated on
        // isConnected, the FGS/wakelock/reconnect machinery would never run and the
        // phone would sit in Doze, never reconnecting until the user woke and opened
        // the app (observed 2026-07-08: ring on at 4am, no link until 09:58). So we
        // enter night mode on charging+window, hold the process/CPU awake, and
        // ACTIVELY reconnect; streaming begins the moment the ring is connected.
        val nightMode = inNightWindow() && isCharging()

        if (nightMode && !active) {
            SleepForegroundService.start(context)
            if (!wakeLock.isHeld) wakeLock.acquire(11 * 3_600_000L)
            active = true
            streamStartedAt = System.currentTimeMillis()
        } else if (!nightMode && active) {
            if (streaming) coordinator.stopWorkoutHeartRate()
            SleepForegroundService.stop(context)
            if (wakeLock.isHeld) wakeLock.release()
            val wasStreaming = streaming
            active = false; streaming = false
            // Unplug = wake signal: screen the span if we actually streamed >=30min.
            val start = streamStartedAt
            if (wasStreaming && !isCharging() && start > 0 &&
                System.currentTimeMillis() - start > 30 * 60_000L) {
                scope.launch {
                    try { onNightEnded?.invoke(start, System.currentTimeMillis()) }
                    catch (e: Exception) { android.util.Log.e("SleepStream", "night-end screen failed", e) }
                }
            }
        }

        if (active && !coordinator.isConnected) {
            // Awake + charging but no ring: actively pull it back (ring just put on,
            // or Doze dropped the link). isConnected stays false until it lands.
            coordinator.reconnectIfNeeded()
            streaming = false
            return
        }

        if (active && coordinator.isConnected) {
            if (!streaming) {
                coordinator.startWorkoutHeartRate()
                streaming = true
                ticksSinceSpO2 = 0
            }
            // Stream watchdog — the ring silently stops the 0x14 stream after
            // hiccups; re-kick if no sample for STREAM_STALE_MS.
            val sinceHr = System.currentTimeMillis() - coordinator.latestHRAt
            if (sinceHr > STREAM_STALE_MS) coordinator.startWorkoutHeartRate()

            ticksSinceSpO2++
            if (ticksSinceSpO2 >= SPO2_EVERY_TICKS) {
                ticksSinceSpO2 = 0
                // Fire-and-forget: measureSpO2 takes ~40s and shouldn't stall the 60s gate loop.
                scope.launch {
                    try { coordinator.measureSpO2() } catch (e: Exception) {
                        android.util.Log.w("SleepStream", "overnight SpO2 failed", e)
                    }
                }
            }
        }
    }

    private fun inNightWindow(): Boolean {
        // 01:00–11:00. The user plugs in when heading to sleep (~1am) and unplugs
        // on waking, so the charging state is the real start/stop trigger; this
        // window only prevents daytime-charging from streaming.
        val hour = java.time.LocalTime.now().hour
        return hour in 1..10
    }

    /** Sticky-intent read of ACTION_BATTERY_CHANGED — registering with a null receiver
     *  just returns the current battery status without creating a live registration. */
    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged != -1 && plugged != 0
    }

    companion object {
        /** 20s poll so the stream watchdog re-kicks within 20s of the ring going idle —
         *  the phone is charging during streaming, so the extra wakeups are free. */
        private const val POLL_MS = 20_000L
        /** No HR sample for this long while "streaming" ⇒ the ring stopped ⇒ re-kick. */
        private const val STREAM_STALE_MS = 25_000L
        /** ~3 minutes at the 20s poll cadence. */
        private const val SPO2_EVERY_TICKS = 9
    }
}
