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

    /** True only when THIS controller opened the stream — never stop one we don't own. */
    private var streaming = false
    private var ticksSinceSpO2 = 0
    private var streamStartedAt = 0L

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
        if (streaming) {
            coordinator.stopWorkoutHeartRate()
            streaming = false
        }
    }

    private suspend fun tick() {
        if (liveWorkout.state.value.isRecording) {
            // A real workout owns the stream now (or is about to). Relinquish our
            // ownership flag rather than assume the underlying stream is still ours —
            // the workout's own finish() will stop it independently of us.
            streaming = false
            return
        }

        val gatesPass = inNightWindow() && coordinator.isConnected && isCharging()

        if (gatesPass && !streaming) {
            coordinator.startWorkoutHeartRate()
            streaming = true
            ticksSinceSpO2 = 0
            streamStartedAt = System.currentTimeMillis()
        } else if (!gatesPass && streaming) {
            coordinator.stopWorkoutHeartRate()
            streaming = false
            // Night ended. If it ended because the phone was UNPLUGGED (not because
            // the clock left the window or the ring dropped), that's the wake signal —
            // screen the span we just streamed, right now.
            val start = streamStartedAt
            if (!isCharging() && start > 0 && System.currentTimeMillis() - start > 30 * 60_000L) {
                scope.launch {
                    try { onNightEnded?.invoke(start, System.currentTimeMillis()) }
                    catch (e: Exception) { android.util.Log.e("SleepStream", "night-end screen failed", e) }
                }
            }
        }

        if (streaming) {
            // Stream watchdog — the missing piece. startWorkoutHeartRate() opens the
            // 0x14 stream once, but the ring silently stops it after hiccups/inactivity;
            // without a re-kick the controller believed it was streaming while the ring
            // sat idle, producing bursty ~5-15min data instead of 1 Hz (observed
            // 2026-07-06). If no HR sample has landed for STREAM_STALE_MS, re-issue the
            // start command — the same self-heal the workout tick already does.
            val sinceHr = System.currentTimeMillis() - coordinator.latestHRAt
            if (coordinator.isConnected && sinceHr > STREAM_STALE_MS) {
                coordinator.startWorkoutHeartRate()
            }

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
